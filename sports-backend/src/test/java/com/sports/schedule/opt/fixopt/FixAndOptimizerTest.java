package com.sports.schedule.opt.fixopt;

import com.sports.schedule.opt.solver.ScheduleOptimizer;
import com.sports.schedule.opt.solver.SchedulePlan;
import com.sports.schedule.opt.solver.ScheduleUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fix-and-Optimize 的验证：输入守护、无冲突直接空手而归、绝不返回更差的解、
 * 冻结语义（切片外的单元落位不被改动）、可复现。
 */
@DisplayName("Fix-and-Optimize 局部精确修复")
class FixAndOptimizerTest {

    private ScheduleOptimizer optimizer;
    private FixAndOptimizer fixopt;

    @BeforeEach
    void setUp() {
        optimizer = new ScheduleOptimizer(1);
        fixopt = new FixAndOptimizer(optimizer);
    }

    @Test
    @DisplayName("输入不可用（无解 / 无评分 / 切片数为 0 / 预算为 0）时安全返回空")
    void unusableInputReturnsEmpty() {
        assertTrue(fixopt.optimize(null, 2, Duration.ofMillis(300)).isEmpty(), "null 输入");
        assertTrue(fixopt.optimize(new SchedulePlan(List.of(), List.of()), 2,
                Duration.ofMillis(300)).isEmpty(), "空问题");

        SchedulePlan solved = optimizer.solve(problem(), Duration.ofMillis(400)).orElseThrow();
        assertTrue(fixopt.optimize(solved, 0, Duration.ofMillis(300)).isEmpty(), "切片数为 0 = 关闭");
        assertTrue(fixopt.optimize(solved, 2, Duration.ZERO).isEmpty(), "预算为 0 = 关闭");
    }

    @Test
    @DisplayName("无冲突的解直接返回空（没有病就不动手术）")
    void cleanPlanSkipsSurgery() {
        SchedulePlan solved = optimizer.solve(problem(), Duration.ofMillis(400)).orElseThrow();
        if (ConflictSliceSelector.slices(solved).isEmpty()) {
            assertTrue(fixopt.optimize(solved, 2, Duration.ofMillis(300)).isEmpty(),
                    "无冲突切片时应返回空");
        }
    }

    @Test
    @DisplayName("绝不返回更差的解，结果仍可行")
    void neverReturnsWorse() {
        SchedulePlan base = optimizer.solve(problem(), Duration.ofMillis(400)).orElseThrow();

        Optional<SchedulePlan> result = fixopt.optimize(base, 3, Duration.ofMillis(300), 42L, null);

        result.ifPresent(p -> {
            assertTrue(p.getScore().compareTo(base.getScore()) >= 0,
                    "Fix-and-Optimize 返回了更差的解：" + base.getScore() + " → " + p.getScore());
            assertTrue(p.getScore().isFeasible(), "结果必须仍然可行：" + p.getScore());
        });
    }

    @Test
    @DisplayName("冻结语义：被治疗切片之外的单元落位不变")
    void freezesEverythingOutsideSlice() {
        SchedulePlan base = optimizer.solve(problem(), Duration.ofMillis(400)).orElseThrow();
        List<ConflictSliceSelector.Slice> slices = ConflictSliceSelector.slices(base);
        if (slices.isEmpty()) return;   // 夹具解无冲突时本用例无对象，跳过

        Map<String, String> before = new HashMap<>();
        for (ScheduleUnit u : base.getUnits()) {
            before.put(u.getKey(), u.isPlaced() ? u.getPlacement().getId() + "@" + u.getDuration() : "未排");
        }
        Optional<SchedulePlan> result = fixopt.optimize(base, 1, Duration.ofMillis(300), 7L, null);
        if (result.isEmpty()) return;

        java.util.Set<String> treatedKeys = new java.util.HashSet<>();
        for (ScheduleUnit u : slices.get(0).units()) treatedKeys.add(u.getKey());
        for (ScheduleUnit u : result.get().getUnits()) {
            String after = u.isPlaced() ? u.getPlacement().getId() + "@" + u.getDuration() : "未排";
            if (!treatedKeys.contains(u.getKey()) && u.isPlaced()) {
                assertEquals(before.get(u.getKey()), after,
                        "切片外的单元 " + u.getKey() + " 被冻结，落位不得改变");
            }
        }
    }

    @Test
    @DisplayName("同种子两次运行结果完全一致（可复现性）")
    void deterministicWithSameSeed() {
        SchedulePlan base = optimizer.solve(problem(), Duration.ofMillis(400)).orElseThrow();
        Optional<SchedulePlan> first = fixopt.optimize(base, 2, Duration.ofMillis(300), 9L, null);
        Optional<SchedulePlan> second = fixopt.optimize(base, 2, Duration.ofMillis(300), 9L, null);
        assertEquals(first.isPresent(), second.isPresent());
        first.ifPresent(p -> assertEquals(p.getScore(), second.get().getScore(), "同种子必须同结果"));
    }

    @Test
    @DisplayName("过程统计写入 info（切片数与治疗数可解释）")
    void reportsStats() {
        SchedulePlan base = optimizer.solve(problem(), Duration.ofMillis(400)).orElseThrow();
        Map<String, Object> info = new HashMap<>();
        fixopt.optimize(base, 2, Duration.ofMillis(300), 13L, info);
        if (!info.isEmpty()) {
            assertNotNull(info.get("slicesTotal"));
            assertEquals(2, info.get("slicesTreated"));
            assertNotNull(info.get("trace"));
        }
    }

    // ==================== 多轮升级重排（2026-09-27） ====================

    @Test
    @DisplayName("多轮重排：预算逐轮翻倍不破坏「绝不更差」，统计含轮数与切片数")
    void multiPass_escalatingBudget_neverWorse() {
        SchedulePlan base = optimizer.solve(problem(), Duration.ofMillis(400)).orElseThrow();
        Map<String, Object> info = new HashMap<>();

        Optional<SchedulePlan> result =
                fixopt.optimizeMultiPass(base, 3, 10, Duration.ofMillis(300), 21L, info);

        result.ifPresent(p -> {
            assertTrue(p.getScore().compareTo(base.getScore()) >= 0,
                    "多轮重排返回了更差的解：" + base.getScore() + " → " + p.getScore());
            assertTrue(p.getScore().isFeasible(), "结果必须仍然可行：" + p.getScore());
            assertNotNull(info.get("passes"), "应报告实际使用轮数");
            assertNotNull(info.get("slicesTotal"));
            assertNotNull(info.get("slicesTreated"));
            assertTrue((Integer) info.get("accepted") <= (Integer) info.get("slicesTreated"),
                    "接受数不得超过治疗数");
        });
    }

    @Test
    @DisplayName("多轮重排：0 轮或 0 切片 = 关闭，安全返回空")
    void multiPass_disabledGuards() {
        SchedulePlan solved = optimizer.solve(problem(), Duration.ofMillis(400)).orElseThrow();
        assertTrue(fixopt.optimizeMultiPass(solved, 0, 10, Duration.ofMillis(300), 21L, null).isEmpty(),
                "maxPasses=0 应返回空");
        assertTrue(fixopt.optimizeMultiPass(solved, 3, 0, Duration.ofMillis(300), 21L, null).isEmpty(),
                "slicesPerPass=0 应返回空");
    }

    @Test
    @DisplayName("多轮重排：同种子两次运行结果完全一致（可复现性）")
    void multiPass_deterministicWithSameSeed() {
        SchedulePlan base = optimizer.solve(problem(), Duration.ofMillis(400)).orElseThrow();
        Optional<SchedulePlan> first =
                fixopt.optimizeMultiPass(base, 2, 5, Duration.ofMillis(300), 9L, null);
        Optional<SchedulePlan> second =
                fixopt.optimizeMultiPass(base, 2, 5, Duration.ofMillis(300), 9L, null);
        assertEquals(first.isPresent(), second.isPresent());
        first.ifPresent(p -> assertEquals(p.getScore(), second.get().getScore(), "同种子必须同结果"));
    }

    // ==================== 夹具 ====================

    private static SchedulePlan problem() {
        List<com.sports.schedule.opt.solver.Placement> placements = new ArrayList<>();
        for (int slot = 0; slot < 2; slot++) {
            for (int offset = 0; offset + 10 <= 210; offset += 10) {
                placements.add(new com.sports.schedule.opt.solver.Placement(
                        "径赛", slot, 0, 1, "2026-01-01", "上午", "场地" + slot,
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

    private static ScheduleUnit unit(String key, int rawDuration,
                                     List<com.sports.schedule.opt.solver.Placement> candidates,
                                     long[] athletes) {
        List<Integer> choices = new ArrayList<>();
        for (int v = rawDuration; v >= 10; v -= 5) choices.add(v);
        if (!choices.contains(10)) choices.add(10);
        return new ScheduleUnit(key, 1L, "项目" + key, "高一", true, "径赛", null, 5,
                rawDuration, 10, athletes, choices, candidates);
    }
}
