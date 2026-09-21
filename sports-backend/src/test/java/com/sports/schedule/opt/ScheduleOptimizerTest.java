package com.sports.schedule.opt;

import com.sports.service.arrange.ConflictService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.ScheduleConstraintProvider;
import com.sports.schedule.opt.solver.ScheduleOptimizer;
import com.sports.schedule.opt.solver.SchedulePlan;
import com.sports.schedule.opt.solver.ScheduleUnit;

/**
 * 约束求解器（Timefold）的集成测试。
 *
 * <p>与 {@code ScheduleServiceTest} 的分工：那边验证的是<b>求解失败后回退贪心</b>的兜底路径，
 * 这里验证的是<b>求解器真正跑起来</b>时的行为——即「用了库里之后方案是否还正确」。
 * 两者缺一不可：只测兜底会掩盖求解器的问题，只测求解器又无法保证降级路径可用。</p>
 *
 * <p>用例刻意用 1 秒预算，避免拖慢构建；这里要验证的是<b>语义正确性</b>而不是解的最优程度。</p>
 */
@DisplayName("赛程约束求解器")
class ScheduleOptimizerTest {

    /** 每个用例都用 1 秒预算：CI 里要快，正确性与最优性分开验证 */
    private ScheduleOptimizer optimizer;

    @BeforeEach
    void setUp() {
        optimizer = new ScheduleOptimizer(1);
    }

    @Test
    @DisplayName("容量充足时：方案合法、项目全部排入、同一并发位不重叠")
    void solverProducesLegalAndCompleteSchedule() {
        List<Placement> candidates = grid("径赛", 2, 480, 210, 10);
        List<ScheduleUnit> units = List.of(
                unit("a", "径赛", 60, 30, new long[0], candidates, null),
                unit("b", "径赛", 40, 20, new long[0], candidates, null),
                unit("c", "径赛", 30, 15, new long[0], candidates, null));

        SchedulePlan solved = solve(units, candidates);

        assertTrue(solved.getScore().isFeasible(), "硬约束必须全部满足，实际评分=" + solved.getScore());
        assertTrue(solved.getUnits().stream().allMatch(ScheduleUnit::isPlaced),
                "容量充足时不应有项目被丢弃");
        assertNoOverlap(solved);
    }

    @Test
    @DisplayName("兼报同一运动员的两个项目会被错开（含 15 分钟赶场缓冲）")
    void solverAvoidsAthleteClash() {
        // 只给一个并发位：两个项目无法同时进行，求解器必须把它们在时间上错开
        List<Placement> candidates = grid("径赛", 1, 480, 210, 10);
        long[] sharedAthlete = {1L};
        List<ScheduleUnit> units = List.of(
                unit("a", "径赛", 30, 30, sharedAthlete, candidates, null),
                unit("b", "径赛", 30, 30, sharedAthlete, candidates, null));

        SchedulePlan solved = solve(units, candidates);

        assertTrue(solved.getScore().isFeasible(), "硬约束必须全部满足，实际评分=" + solved.getScore());
        assertFalse(ScheduleConstraintProvider.athleteClash(solved.getUnits().get(0), solved.getUnits().get(1)),
                "兼报同一运动员的两个项目不得排在同一时刻（含缓冲）");
    }

    @Test
    @DisplayName("容量客观不足时：如实报「排不下」，而不是重叠硬塞")
    void solverReportsUnassignedInsteadOfOverlapping() {
        // 窗口只有 60 分钟，却要放 5 个 30 分钟的项目——客观上放不下
        List<Placement> candidates = grid("田赛", 1, 480, 60, 10);
        List<ScheduleUnit> units = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            units.add(unit("u" + i, "田赛", 30, 30, new long[0], candidates, null));
        }

        SchedulePlan solved = solve(units, candidates);

        long unassigned = solved.getUnits().stream().filter(u -> !u.isPlaced()).count();
        assertTrue(unassigned >= 3,
                "60 分钟容量放不下 5 个 30 分钟项目，应有项目被如实标记为未排入；实际未排入=" + unassigned);
        assertNoOverlap(solved);
    }

    @Test
    @DisplayName("编排端与检测端的兼项缓冲口径一致（否则会出现「排时不冲突、检出又冲突」）")
    void conflictBufferMatchesConflictService() {
        assertEquals(ConflictService.CONFLICT_BUFFER_MIN, ScheduleConstraintProvider.CONFLICT_BUFFER_MIN,
                "约束求解器与 ConflictService 必须共用同一个兼项缓冲值");
    }

    // ==================== 测试夹具 ====================

    private SchedulePlan solve(List<ScheduleUnit> units, List<Placement> candidates) {
        return optimizer.solve(new SchedulePlan(candidates, units), Duration.ofSeconds(1))
                .orElseThrow(() -> new AssertionError("约束求解未返回结果"));
    }

    /** 生成一个并发池的候选位置网格（单窗口、多槽位、按 step 分钟步进） */
    private static List<Placement> grid(String pool, int slots, int windowStart, int capacity, int step) {
        List<Placement> out = new ArrayList<>();
        for (int slot = 0; slot < slots; slot++) {
            for (int offset = 0; offset + 10 <= capacity; offset += step) {
                out.add(new Placement(pool, slot, 0, 1, "2026-01-01", "上午", "场地" + slot,
                        windowStart + offset, windowStart, capacity));
            }
        }
        return out;
    }

    private static ScheduleUnit unit(String key, String pool, int rawDuration, int minDuration,
                                     long[] athletes, List<Placement> candidates, String groupKey) {
        List<Integer> choices = new ArrayList<>();
        for (int v = rawDuration; v >= minDuration; v -= 5) choices.add(v);
        if (!choices.contains(minDuration)) choices.add(minDuration);
        return new ScheduleUnit(key, 1L, "项目" + key, "高一", true, pool, groupKey, 5,
                rawDuration, minDuration, athletes, choices, candidates);
    }

    /** 断言：落在同一并发位的任意两个项目时间区间不重叠 */
    private static void assertNoOverlap(SchedulePlan plan) {
        List<ScheduleUnit> units = plan.getUnits();
        for (int i = 0; i < units.size(); i++) {
            for (int j = i + 1; j < units.size(); j++) {
                ScheduleUnit a = units.get(i);
                ScheduleUnit b = units.get(j);
                if (!a.isPlaced() || !b.isPlaced()) continue;
                if (!a.getPlacement().getBinKey().equals(b.getPlacement().getBinKey())) continue;
                int aStart = a.getPlacement().getStartMinute();
                int aEnd = aStart + a.getDuration();
                int bStart = b.getPlacement().getStartMinute();
                int bEnd = bStart + b.getDuration();
                assertTrue(aStart >= bEnd || bStart >= aEnd,
                        "同一并发位内不得重叠：" + a + " 与 " + b);
            }
        }
    }
}
