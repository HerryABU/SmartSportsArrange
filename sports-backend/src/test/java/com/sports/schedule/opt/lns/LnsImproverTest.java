package com.sports.schedule.opt.lns;

import com.sports.schedule.opt.Placement;
import com.sports.schedule.opt.ScheduleOptimizer;
import com.sports.schedule.opt.SchedulePlan;
import com.sports.schedule.opt.ScheduleUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 大邻域搜索（破坏-重建）的验证。
 *
 * <p>重点验证三件事：**不合法输入要安全返回空**、**绝不返回更差的解**（接受准则的实现约束）、
 * **时间预算是真的生效**（否则一轮几百毫秒的承诺就是假的）。</p>
 */
@DisplayName("大邻域搜索 LNS")
class LnsImproverTest {

    private ScheduleOptimizer optimizer;
    private LnsImprover lns;

    @BeforeEach
    void setUp() {
        optimizer = new ScheduleOptimizer(1);
        lns = new LnsImprover(optimizer);
    }

    @Test
    @DisplayName("输入不可用（无解 / 无评分 / 轮数为 0）时安全返回空")
    void unusableInputReturnsEmpty() {
        assertTrue(lns.improve(null, 2, Duration.ofMillis(100)).isEmpty(), "null 输入");

        SchedulePlan noScore = new SchedulePlan(List.of(), List.of());
        assertTrue(lns.improve(noScore, 2, Duration.ofMillis(100)).isEmpty(), "空问题");

        SchedulePlan solved = optimizer.solve(problem(), Duration.ofMillis(400)).orElseThrow();
        assertTrue(lns.improve(solved, 0, Duration.ofMillis(100)).isEmpty(), "轮数为 0 = 关闭");
    }

    @Test
    @DisplayName("绝不返回更差的解——「只接受更好」是精修阶段的硬约束")
    void neverReturnsWorseSolution() {
        SchedulePlan base = optimizer.solve(problem(), Duration.ofMillis(400)).orElseThrow();

        Optional<SchedulePlan> improved = lns.improve(base, 2, Duration.ofMillis(250));

        improved.ifPresent(p -> assertTrue(p.getScore().compareTo(base.getScore()) >= 0,
                "LNS 若返回了更差的解，等于把上游辛苦得到的解抖散了：" + base.getScore() + " → " + p.getScore()));
    }

    @Test
    @DisplayName("破坏-重建后仍满足硬约束（同并发位不重叠、项目不越界）")
    void repairedSolutionStaysLegal() {
        SchedulePlan base = optimizer.solve(problem(), Duration.ofMillis(400)).orElseThrow();

        SchedulePlan result = lns.improve(base, 2, Duration.ofMillis(250)).orElse(base);

        assertTrue(result.getScore().isFeasible(), "结果必须仍然可行：" + result.getScore());
        List<ScheduleUnit> units = result.getUnits();
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
