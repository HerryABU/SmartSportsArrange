package com.sports.schedule.opt.solver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 项目块完整性约束 + 「尽可能减少工期」开关的回归测试。
 *
 * <p>背景：用户诉求里反复强调「项目必须是块状，不能见缝插针乱排」，但改动前全仓库
 * 只有 {@code groupMustStartTogether}（同组<b>同时刻开赛</b>），那并不等于「连续成块」——
 * 同一个项目的 6 个组次可以分散在第 1/3/5 天且完全不违规。</p>
 *
 * <p>另外主田径编排原来把 {@code days=0}（不限）与 {@code days<0}（尽可能减少）混为一谈，
 * 后者根本没有表达方式。本测试把两条都钉住。</p>
 */
@DisplayName("项目块完整性 + 工期三态")
class ProjectBlockContiguityTest {

    @AfterEach
    void resetStaticBridge() {
        // 静态桥会跨用例存活，必须复位，否则污染后续测试（这条本身也是回归点）
        ScheduleConstraintProvider.setMinimizeDaysMode(false);
    }

    private ScheduleUnit unit(String key, long eventId, int day) {
        long[] athletes = {1L, 2L};
        Placement p = new Placement("TRACK", 0, 0, day, "2026-09-20", "上午", "田径场", 480, 480, 300);
        ScheduleUnit u = new ScheduleUnit(key, eventId, "100米", "高一年级", true, "TRACK", null,
                10, 60, 10, athletes, List.of(60), List.of(p));
        u.setPlacement(p);
        u.setDuration(60);
        return u;
    }

    @Test
    @DisplayName("同一项目的组次被拆到不同天 → 判定为块被打散")
    void sameEventAcrossDaysBreaksBlock() {
        ScheduleUnit a = unit("a", 100L, 1);
        ScheduleUnit b = unit("b", 100L, 2);
        assertTrue(ScheduleConstraintProvider.breaksBlock(a, b),
                "同一项目跨天说明中间被别的项目插进去了，必须判定为块被打散");
    }

    @Test
    @DisplayName("同一项目同一天 → 不算块被打散")
    void sameEventSameDayKeepsBlock() {
        ScheduleUnit a = unit("a", 100L, 1);
        ScheduleUnit b = unit("b", 100L, 1);
        assertFalse(ScheduleConstraintProvider.breaksBlock(a, b));
    }

    @Test
    @DisplayName("不同项目跨天 → 与块完整性无关，不罚")
    void differentEventsNeverBreakBlock() {
        ScheduleUnit a = unit("a", 100L, 1);
        ScheduleUnit b = unit("b", 200L, 3);
        assertFalse(ScheduleConstraintProvider.breaksBlock(a, b),
                "块完整性只约束同一项目内部，跨项目分散是正常的");
    }

    @Test
    @DisplayName("未排单元不参与判定（否则求解中间态会刷出大量假违规）")
    void unplacedUnitsAreIgnored() {
        ScheduleUnit placed = unit("a", 100L, 1);
        ScheduleUnit unplaced = unit("b", 100L, 2);
        unplaced.setPlacement(null);
        assertFalse(ScheduleConstraintProvider.breaksBlock(placed, unplaced));
        assertFalse(ScheduleConstraintProvider.breaksBlock(unplaced, placed));
        assertFalse(ScheduleConstraintProvider.breaksBlock(null, placed));
    }

    @Test
    @DisplayName("块状惩罚量级：显著大于压缩 1 分钟，且不高于跨一天的代价")
    void blockPenaltyMagnitudeIsSane() {
        int penalty = ScheduleConstraintProvider.blockBreakPenaltyForTest();
        assertTrue(penalty >= 50, "惩罚太小时求解器没有动机避免项目被打散，实际=" + penalty);
        assertTrue(penalty <= 200, "惩罚高于跨天代价会喧宾夺主，实际=" + penalty);
    }

    @Test
    @DisplayName("days==-1（尽可能减少）：跨天惩罚被放大，逼求解器压紧工期")
    void minimizeDaysModeScalesDayPenalty() {
        ScheduleConstraintProvider.setMinimizeDaysMode(false);
        int base = ScheduleConstraintProvider.dayPenaltyForTest();

        ScheduleConstraintProvider.setMinimizeDaysMode(true);
        int boosted = ScheduleConstraintProvider.dayPenaltyForTest();

        assertTrue(boosted > base,
                "「尽可能减少」必须真的改变求解目标，否则只是一个没有行为差异的枚举值");
        assertEquals(base * 5, boosted, "放大倍数应固定为 5，便于回归比对");
    }

    @Test
    @DisplayName("days!=−1 时必须回到基准惩罚（静态桥不能跨请求泄漏）")
    void modeResetsToBaseline() {
        int base = ScheduleConstraintProvider.dayPenaltyForTest();
        ScheduleConstraintProvider.setMinimizeDaysMode(true);
        ScheduleConstraintProvider.setMinimizeDaysMode(false);
        assertEquals(base, ScheduleConstraintProvider.dayPenaltyForTest(),
                "静态桥忘了复位会污染后续所有编排，这是最隐蔽的一类 bug");
    }
}
