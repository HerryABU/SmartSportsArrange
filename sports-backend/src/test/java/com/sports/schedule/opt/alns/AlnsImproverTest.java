package com.sports.schedule.opt.alns;

import com.sports.schedule.opt.solver.ScheduleOptimizer;
import com.sports.schedule.opt.solver.SchedulePlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ALNS 主循环的验证：输入守护、绝不返回更差的解、结果仍合法、同种子可复现、统计可解释。
 */
@DisplayName("自适应大邻域搜索 ALNS")
class AlnsImproverTest {

    private ScheduleOptimizer optimizer;
    private AlnsImprover alns;

    @BeforeEach
    void setUp() {
        optimizer = new ScheduleOptimizer(1);
        alns = new AlnsImprover(optimizer);
    }

    @Test
    @DisplayName("输入不可用（无解 / 无评分 / 轮数为 0）时安全返回空")
    void unusableInputReturnsEmpty() {
        assertTrue(alns.improve(null, 4).isEmpty(), "null 输入");
        assertTrue(alns.improve(new SchedulePlan(java.util.List.of(), java.util.List.of()), 4).isEmpty(),
                "空问题");

        SchedulePlan solved = optimizer.solve(MnsaFixture.problem(), Duration.ofMillis(400)).orElseThrow();
        assertTrue(alns.improve(solved, 0).isEmpty(), "轮数为 0 = 关闭");
    }

    @Test
    @DisplayName("绝不返回更差的解，且结果仍满足同并发位不重叠")
    void neverReturnsWorseAndStaysLegal() {
        SchedulePlan base = optimizer.solve(MnsaFixture.problem(), Duration.ofMillis(400)).orElseThrow();

        Optional<SchedulePlan> result = alns.improve(base, 6, 21L, null);

        result.ifPresent(p -> {
            assertTrue(p.getScore().compareTo(base.getScore()) >= 0,
                    "ALNS 返回了更差的解：" + base.getScore() + " → " + p.getScore());
            assertTrue(p.getScore().isFeasible(), "结果必须仍然可行：" + p.getScore());
        });
    }

    @Test
    @DisplayName("同种子两次运行结果完全一致（可复现性）")
    void deterministicWithSameSeed() {
        SchedulePlan base = optimizer.solve(MnsaFixture.problem(), Duration.ofMillis(400)).orElseThrow();

        Optional<SchedulePlan> first = alns.improve(base, 5, 33L, null);
        Optional<SchedulePlan> second = alns.improve(base, 5, 33L, null);

        assertEquals(first.isPresent(), second.isPresent());
        first.ifPresent(p -> assertEquals(p.getScore(), second.get().getScore(), "同种子必须同结果"));
    }

    @Test
    @DisplayName("过程统计写入 info（破坏/修复算子的使用分布可解释）")
    void reportsStats() {
        SchedulePlan base = optimizer.solve(MnsaFixture.problem(), Duration.ofMillis(400)).orElseThrow();
        Map<String, Object> info = new HashMap<>();
        alns.improve(base, 4, 77L, info);
        if (!info.isEmpty()) {
            assertEquals(4, info.get("rounds"));
            assertNotNull(info.get("destroyStats"));
            assertNotNull(info.get("repairStats"));
        }
        assertEquals(List.of("随机破坏", "冲突簇破坏", "最忙运动员破坏", "时段窗口破坏"),
                AlnsImprover.destroyNames());
    }

    /** 与 mnsa 测试共用的夹具（含兼项运动员，冲突链：b-e 共享运动员 3） */
    static final class MnsaFixture {
        static SchedulePlan problem() {
            java.util.List<com.sports.schedule.opt.solver.Placement> placements = new java.util.ArrayList<>();
            for (int slot = 0; slot < 2; slot++) {
                for (int offset = 0; offset + 10 <= 210; offset += 10) {
                    placements.add(new com.sports.schedule.opt.solver.Placement(
                            "径赛", slot, 0, 1, "2026-01-01", "上午", "场地" + slot,
                            480 + offset, 480, 210));
                }
            }
            java.util.List<com.sports.schedule.opt.solver.ScheduleUnit> units = java.util.List.of(
                    unit("a", 60, placements, new long[]{1, 2}),
                    unit("b", 45, placements, new long[]{2, 3}),
                    unit("c", 30, placements, new long[]{4}),
                    unit("d", 25, placements, new long[]{}),
                    unit("e", 20, placements, new long[]{3}));
            return new SchedulePlan(placements, units);
        }

        private static com.sports.schedule.opt.solver.ScheduleUnit unit(
                String key, int rawDuration, java.util.List<com.sports.schedule.opt.solver.Placement> candidates,
                long[] athletes) {
            java.util.List<Integer> choices = new java.util.ArrayList<>();
            for (int v = rawDuration; v >= 10; v -= 5) choices.add(v);
            if (!choices.contains(10)) choices.add(10);
            return new com.sports.schedule.opt.solver.ScheduleUnit(key, 1L, "项目" + key, "高一", true,
                    "径赛", null, 5, rawDuration, 10, athletes, choices, candidates);
        }
    }
}
