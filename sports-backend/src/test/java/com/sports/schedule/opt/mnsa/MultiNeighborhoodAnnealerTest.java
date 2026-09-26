package com.sports.schedule.opt.mnsa;

import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.ScheduleOptimizer;
import com.sports.schedule.opt.solver.SchedulePlan;
import com.sports.schedule.opt.solver.ScheduleUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多邻域模拟退火的验证：输入守护、绝不返回更差的解、结果仍合法、同种子可复现。
 */
@DisplayName("多邻域模拟退火 MNSA")
class MultiNeighborhoodAnnealerTest {

    private ScheduleOptimizer optimizer;
    private MultiNeighborhoodAnnealer annealer;

    @BeforeEach
    void setUp() {
        optimizer = new ScheduleOptimizer(1);
        annealer = new MultiNeighborhoodAnnealer(optimizer);
    }

    @Test
    @DisplayName("输入不可用（无解 / 无评分 / 步数为 0）时安全返回空")
    void unusableInputReturnsEmpty() {
        assertTrue(annealer.anneal(null, 10).isEmpty(), "null 输入");
        assertTrue(annealer.anneal(new SchedulePlan(List.of(), List.of()), 10).isEmpty(), "空问题");

        SchedulePlan solved = optimizer.solve(problem(), java.time.Duration.ofMillis(400)).orElseThrow();
        assertTrue(annealer.anneal(solved, 0).isEmpty(), "步数为 0 = 关闭");
    }

    @Test
    @DisplayName("绝不返回更差的解，且结果仍满足同并发位不重叠")
    void neverReturnsWorseAndStaysLegal() {
        SchedulePlan base = optimizer.solve(problem(), java.time.Duration.ofMillis(400)).orElseThrow();

        Optional<SchedulePlan> result = annealer.anneal(base, 60, 42L, null);

        result.ifPresent(p -> {
            assertTrue(p.getScore().compareTo(base.getScore()) >= 0,
                    "MNSA 返回了更差的解：" + base.getScore() + " → " + p.getScore());
            assertTrue(p.getScore().isFeasible(), "结果必须仍然可行：" + p.getScore());
            assertNoBinOverlap(p);
        });
    }

    @Test
    @DisplayName("同种子两次退火结果完全一致（可复现性）")
    void deterministicWithSameSeed() {
        SchedulePlan base = optimizer.solve(problem(), java.time.Duration.ofMillis(400)).orElseThrow();

        Optional<SchedulePlan> first = annealer.anneal(base, 40, 7L, null);
        Optional<SchedulePlan> second = annealer.anneal(base, 40, 7L, null);

        assertEquals(first.isPresent(), second.isPresent());
        if (first.isPresent()) {
            assertEquals(first.get().getScore(), second.get().getScore(), "同种子必须同结果");
            assertSameAssignments(first.get(), second.get());
        }
    }

    @Test
    @DisplayName("过程统计写入 info（移动维度可解释）")
    void reportsStats() {
        SchedulePlan base = optimizer.solve(problem(), java.time.Duration.ofMillis(400)).orElseThrow();
        Map<String, Object> info = new HashMap<>();
        annealer.anneal(base, 30, 9L, info);
        if (!info.isEmpty()) {
            assertEquals(30, info.get("iterations"));
            assertNotNull(info.get("moveStats"));
            assertNotNull(info.get("score"));
        }
    }

    @Test
    @DisplayName("结构类移动在已求解解上可施加；拔除/补排在被破坏的解上可施加")
    void movesApplyOnRightScenarios() {
        SchedulePlan base = optimizer.solve(problem(), java.time.Duration.ofMillis(400)).orElseThrow();
        java.util.Random rnd = new java.util.Random(11);

        // 已求解的好解：无冲突、无空缺 → 「拔除冲突/补排空缺」本就无处施力（返回 false 是正确行为），
        // 但四个结构类移动（换位/迁移/换时长/压缩）应至少三种可用
        int structural = 0;
        List<MnsaMove> all = MnsaMoves.standard();
        for (int i = 0; i < 4; i++) {
            if (all.get(i).apply(base.deepCopy(), rnd)) structural++;
        }
        assertTrue(structural >= 3, "四个结构类移动应至少三种可施加，实际 " + structural);

        // 人工破坏解：制造兼项撞车（b 与 e 共享运动员 3 且同一位置重叠）+ 一个未排单元
        SchedulePlan damaged = base.deepCopy();
        List<ScheduleUnit> units = damaged.getUnits();
        Placement target = units.get(0).getCandidatePlacements().get(0);
        units.get(1).setPlacement(target);   // b（运动员 2,3）
        units.get(1).setDuration(45);
        units.get(4).setPlacement(target);   // e（运动员 3）→ 与 b 同位重叠 = 兼项撞车
        units.get(4).setDuration(20);
        units.get(2).setPlacement(null);     // c 未排入 → 补排空缺有了用武之地
        units.get(2).setDuration(null);
        assertTrue(all.get(4).apply(damaged.deepCopy(), rnd), "被破坏的解上「拔除冲突」应可施加");
        assertTrue(all.get(5).apply(damaged.deepCopy(), rnd), "被破坏的解上「补排空缺」应可施加");
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
                unit("a", 60, placements, new long[]{1, 2}),
                unit("b", 45, placements, new long[]{2, 3}),
                unit("c", 30, placements, new long[]{4}),
                unit("d", 25, placements, new long[]{}),
                unit("e", 20, placements, new long[]{3}));
        return new SchedulePlan(placements, units);
    }

    private static ScheduleUnit unit(String key, int rawDuration, List<Placement> candidates, long[] athletes) {
        List<Integer> choices = new ArrayList<>();
        for (int v = rawDuration; v >= 10; v -= 5) choices.add(v);
        if (!choices.contains(10)) choices.add(10);
        return new ScheduleUnit(key, 1L, "项目" + key, "高一", true, "径赛", null, 5,
                rawDuration, 10, athletes, choices, candidates);
    }

    private static void assertNoBinOverlap(SchedulePlan plan) {
        List<ScheduleUnit> units = plan.getUnits();
        for (int i = 0; i < units.size(); i++) {
            for (int j = i + 1; j < units.size(); j++) {
                ScheduleUnit a = units.get(i);
                ScheduleUnit b = units.get(j);
                if (!a.isPlaced() || !b.isPlaced()) continue;
                if (!a.getPlacement().getBinKey().equals(b.getPlacement().getBinKey())) continue;
                int aEnd = a.getPlacement().getStartMinute() + a.getDuration();
                int bEnd = b.getPlacement().getStartMinute() + b.getDuration();
                assertTrue(a.getPlacement().getStartMinute() >= bEnd
                                || b.getPlacement().getStartMinute() >= aEnd,
                        "同一并发位内不得重叠：" + a + " 与 " + b);
            }
        }
    }

    private static void assertSameAssignments(SchedulePlan a, SchedulePlan b) {
        assertEquals(a.getUnits().size(), b.getUnits().size());
        for (int i = 0; i < a.getUnits().size(); i++) {
            ScheduleUnit ua = a.getUnits().get(i);
            ScheduleUnit ub = b.getUnits().get(i);
            assertEquals(ua.isPlaced(), ub.isPlaced(), "第 " + i + " 个单元的排入状态不同");
            if (ua.isPlaced()) {
                assertEquals(ua.getPlacement(), ub.getPlacement(), "第 " + i + " 个单元的位置不同");
                assertEquals(ua.getDuration(), ub.getDuration(), "第 " + i + " 个单元的时长不同");
            }
        }
    }
}
