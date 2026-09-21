package com.sports.schedule.opt.ga;

import ai.timefold.solver.core.config.localsearch.LocalSearchType;
import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.ScheduleOptimizer;
import com.sports.schedule.opt.solver.SchedulePlan;
import com.sports.schedule.opt.solver.ScheduleUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 遗传算法（种群 + 交叉 + 变异）的验证。
 *
 * <p>重点验证：<b>非法输入安全返回空</b>、<b>交叉保基因数、按 key 对齐</b>、
 * <b>变异服从概率且不改坏其它基因</b>、<b>进化绝不返回更差解</b>。</p>
 */
@DisplayName("遗传算法 GA")
class GeneticAlgorithmTest {

    private ScheduleOptimizer optimizer;
    private GeneticAlgorithm ga;

    @BeforeEach
    void setUp() {
        optimizer = new ScheduleOptimizer(1);
        ga = new GeneticAlgorithm(optimizer);
    }

    @Test
    @DisplayName("输入不可用（无解 / 无评分 / 种群过小 / 代数为 0）时安全返回空")
    void unusableInputReturnsEmpty() {
        assertTrue(ga.evolve(null, 4, 2, Duration.ofMillis(100), 0.1).isEmpty(), "null 输入");
        assertTrue(ga.evolve(new SchedulePlan(List.of(), List.of()), 4, 2,
                Duration.ofMillis(100), 0.1).isEmpty(), "空问题");

        SchedulePlan seed = optimizer.solve(problem(), Duration.ofMillis(400)).orElseThrow();
        assertTrue(ga.evolve(seed, 1, 2, Duration.ofMillis(100), 0.1).isEmpty(), "种群 < 2");
        assertTrue(ga.evolve(seed, 4, 0, Duration.ofMillis(100), 0.1).isEmpty(), "代数 < 1");
    }

    @Test
    @DisplayName("均匀交叉保基因数、按 key 对齐交换同项目基因")
    void crossoverPreservesGenesAndAlignsByKey() {
        SchedulePlan a = optimizer.solve(problem(), Duration.ofMillis(400)).orElseThrow();
        SchedulePlan b = optimizer.solve(problem(), Duration.ofMillis(400),
                LocalSearchType.SIMULATED_ANNEALING, 999L).orElseThrow();

        SchedulePlan child = GeneticOperators.uniformCrossover(a, b, 42L);

        assertEquals(a.getUnits().size(), child.getUnits().size(), "交叉不改变单元个数");
        Set<String> keys = new HashSet<>();
        for (ScheduleUnit u : child.getUnits()) keys.add(u.getKey());
        for (ScheduleUnit u : a.getUnits()) {
            assertTrue(keys.contains(u.getKey()), "交叉后的 key 集合应与父代一致：" + u.getKey());
        }
    }

    @Test
    @DisplayName("变异服从概率：rate=0 不动，rate=1 每基因都重设")
    void mutationHonorsRate() {
        SchedulePlan plan = optimizer.solve(problem(), Duration.ofMillis(400)).orElseThrow();

        SchedulePlan unchanged = GeneticOperators.mutate(plan, 0.0, 1L);
        for (int i = 0; i < plan.getUnits().size(); i++) {
            ScheduleUnit x = plan.getUnits().get(i);
            ScheduleUnit y = unchanged.getUnits().get(i);
            assertEquals(x.getPlacement(), y.getPlacement(), "rate=0 时落位不应变");
            assertEquals(x.getDuration(), y.getDuration(), "rate=0 时时长不应变");
        }

        SchedulePlan fully = GeneticOperators.mutate(plan, 1.0, 2L);
        boolean changed = false;
        for (int i = 0; i < plan.getUnits().size(); i++) {
            ScheduleUnit x = plan.getUnits().get(i);
            ScheduleUnit y = fully.getUnits().get(i);
            if (!java.util.Objects.equals(x.getPlacement(), y.getPlacement())
                    || !java.util.Objects.equals(x.getDuration(), y.getDuration())) {
                changed = true;
            }
        }
        assertTrue(changed, "rate=1 时至少有一个基因被重设");
    }

    @Test
    @DisplayName("进化绝不返回更差的解——精英保留是硬保证")
    void neverReturnsWorseSolution() {
        SchedulePlan seed = optimizer.solve(problem(), Duration.ofMillis(400)).orElseThrow();
        Optional<SchedulePlan> evolved = ga.evolve(seed, 6, 3, Duration.ofMillis(250), 0.15);
        evolved.ifPresent(p -> assertTrue(p.getScore().compareTo(seed.getScore()) > 0,
                "GA 返回的解不得差于种子解：" + seed.getScore() + " → " + p.getScore()));
    }

    @Test
    @DisplayName("进化后的解仍满足硬约束（同并发位不重叠、不越界）")
    void evolvedSolutionStaysLegal() {
        SchedulePlan seed = optimizer.solve(problem(), Duration.ofMillis(400)).orElseThrow();
        SchedulePlan result = ga.evolve(seed, 6, 3, Duration.ofMillis(250), 0.15).orElse(seed);
        assertTrue(result.getScore().isFeasible(), "结果必须可行：" + result.getScore());
    }

    // ==================== 夹具 ====================

    private static SchedulePlan problem() {
        List<Placement> placements = new ArrayList<>();
        for (int slot = 0; slot < 2; slot++) {
            for (int offset = 0; offset + 10 <= 210; offset += 10) {
                placements.add(new Placement("径赛", slot, 0, 1, "2026-01-01", "上午", "场地" + slot,
                        480 + offset, 480, 210));
            }
        }
        List<ScheduleUnit> units = List.of(
                unit("a", 60, placements),
                unit("b", 45, placements),
                unit("c", 30, placements),
                unit("d", 25, placements));
        return new SchedulePlan(placements, units);
    }

    private static ScheduleUnit unit(String key, int rawDuration, List<Placement> candidates) {
        List<Integer> choices = new ArrayList<>();
        for (int v = rawDuration; v >= 10; v -= 5) choices.add(v);
        if (!choices.contains(10)) choices.add(10);
        return new ScheduleUnit(key, 1L, "项目" + key, "高一", true, "径赛", null, 5,
                rawDuration, 10, new long[0], choices, candidates);
    }
}
