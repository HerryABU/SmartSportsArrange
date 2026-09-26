package com.sports.schedule.core.math;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 兼项冲突纯函数核心 {@link ConflictMath} 的单元测试。
 *
 * <p>该模块「不读库、不落库、无状态」，是编排消解端与检测端共用的唯一冲突真理源，
 * 因此必须能脱离 Spring 独立稳定复现。所有断言都对着<b>手算期望值</b>，而非实现输出，
 * 避免实现算错、测试跟着错。</p>
 */
@DisplayName("兼项冲突纯函数核心")
class ConflictMathTest {

    /** 构造一条赛程条目（绝对分钟，day 从 1 起） */
    private static ConflictMath.SchedEntry e(long schedId, long eventId, int day, int s, int en, String venue) {
        return new ConflictMath.SchedEntry(schedId, eventId, day, s, en, venue);
    }

    /** 构造「运动员 -> 项目集合」 */
    private static Map<Long, Set<Long>> athEvs(Object... pairs) {
        Map<Long, Set<Long>> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Long aid = (Long) pairs[i];
            @SuppressWarnings("unchecked")
            Set<Long> evs = (Set<Long>) pairs[i + 1];
            m.put(aid, evs);
        }
        return m;
    }

    // ===== gapOf：单对判定 =====

    @Test
    @DisplayName("同一天时间窗重叠 -> 严重(OVERLAP)")
    void gapOf_overlapIsSevere() {
        ConflictMath.SchedEntry x = e(1, 10, 1, 60, 120, "田赛A");  // 10:00-11:00
        ConflictMath.SchedEntry y = e(2, 20, 1, 90, 150, "田赛B");  // 10:30-11:30
        ConflictMath.Gap g = ConflictMath.gapOf(x, y, 15);
        assertEquals(ConflictMath.Kind.OVERLAP, g.kind());
    }

    @Test
    @DisplayName("不同天 -> 一定无冲突(NONE)")
    void gapOf_differentDayIsNone() {
        ConflictMath.SchedEntry x = e(1, 10, 1, 60, 120, "田赛A");
        ConflictMath.SchedEntry y = e(2, 20, 2, 60, 120, "田赛A");  // 仅差在 day=2
        assertEquals(ConflictMath.Kind.NONE, ConflictMath.gapOf(x, y, 15).kind());
    }

    @Test
    @DisplayName("同场地同时开赛 -> 严重(SAME_VENUE_START)")
    void gapOf_sameVenueSameStartIsSevere() {
        ConflictMath.SchedEntry x = e(1, 10, 1, 60, 120, "田赛A");
        ConflictMath.SchedEntry y = e(2, 20, 1, 60, 130, "田赛A");  // 同场地、同起点
        ConflictMath.Gap g = ConflictMath.gapOf(x, y, 15);
        assertEquals(ConflictMath.Kind.SAME_VENUE_START, g.kind());
    }

    @Test
    @DisplayName("间隔小于缓冲 -> 一般(TIGHT)")
    void gapOf_tightGapIsWarn() {
        ConflictMath.SchedEntry x = e(1, 10, 1, 60, 120, "田赛A");  // 结束 11:00
        ConflictMath.SchedEntry y = e(2, 20, 1, 125, 180, "田赛B"); // 开始 11:05，间隔 5<15
        ConflictMath.Gap g = ConflictMath.gapOf(x, y, 15);
        assertEquals(ConflictMath.Kind.TIGHT, g.kind());
        assertEquals(5, g.gapMinutes());
    }

    @Test
    @DisplayName("间隔 >= 缓冲 -> 无冲突(NONE)")
    void gapOf_enoughGapIsNone() {
        ConflictMath.SchedEntry x = e(1, 10, 1, 60, 120, "田赛A");
        ConflictMath.SchedEntry y = e(2, 20, 1, 140, 180, "田赛B"); // 间隔 20>=15
        assertEquals(ConflictMath.Kind.NONE, ConflictMath.gapOf(x, y, 15).kind());
    }

    @Test
    @DisplayName("对称间隔：交换 x/y 次序结果一致（修旧实现漏报 bug）")
    void gapOf_symmetricRegardlessOfOrder() {
        ConflictMath.SchedEntry x = e(1, 10, 1, 60, 120, "田赛A");
        ConflictMath.SchedEntry y = e(2, 20, 1, 130, 180, "田赛B"); // 间隔 10
        ConflictMath.Gap g1 = ConflictMath.gapOf(x, y, 15);
        ConflictMath.Gap g2 = ConflictMath.gapOf(y, x, 15);
        assertEquals(g1.kind(), g2.kind(), "对称间隔口径下顺序不应影响判定");
        assertEquals(g1.gapMinutes(), g2.gapMinutes());
        assertEquals(ConflictMath.Kind.TIGHT, g1.kind()); // 间隔 10 < 15
    }

    // ===== countConflicts：总量统计 =====

    @Test
    @DisplayName("一名运动员两项目重叠 -> 总1/严重1")
    void count_oneAthleteOverlap() {
        List<ConflictMath.SchedEntry> entries = new ArrayList<>(List.of(
                e(1, 10, 1, 60, 120, "田赛A"),
                e(2, 20, 1, 90, 150, "田赛B")));
        Map<Long, Set<Long>> ae = athEvs(1L, Set.of(10L, 20L));
        int[] r = ConflictMath.countConflicts(entries, ae, 15);
        assertEquals(1, r[0], "total");
        assertEquals(1, r[1], "severe");
    }

    @Test
    @DisplayName("同项目内的多赛次(预赛+决赛)重叠不算兼项冲突")
    void count_sameEventOverlapSkipped() {
        // 运动员只报了项目 10，但同一项目有两条赛程（预赛+决赛）且时间重叠
        List<ConflictMath.SchedEntry> entries = new ArrayList<>(List.of(
                e(1, 10, 1, 60, 120, "田赛A"),
                e(2, 10, 1, 90, 150, "田赛A")));
        Map<Long, Set<Long>> ae = athEvs(1L, Set.of(10L));
        int[] r = ConflictMath.countConflicts(entries, ae, 15);
        assertEquals(0, r[0], "同一项目内重叠不计兼项冲突");
        assertEquals(0, r[1]);
    }

    @Test
    @DisplayName("多名运动员：只有真正冲突者被计入")
    void count_multipleAthletesOnlyConflictingCounted() {
        List<ConflictMath.SchedEntry> entries = new ArrayList<>(List.of(
                e(1, 10, 1, 60, 120, "田赛A"),
                e(2, 20, 1, 90, 150, "田赛B"),   // 与(1,10)重叠 -> 运动员1冲突
                e(3, 30, 1, 300, 360, "田赛C"),
                e(4, 40, 1, 400, 460, "田赛D"))); // 运动员2两项间隔充足 -> 无冲突
        Map<Long, Set<Long>> ae = athEvs(
                1L, Set.of(10L, 20L),
                2L, Set.of(30L, 40L));
        int[] r = ConflictMath.countConflicts(entries, ae, 15);
        assertEquals(1, r[0], "total");
        assertEquals(1, r[1], "severe");
    }

    @Test
    @DisplayName("全部零冲突 -> [0,0]")
    void count_zeroWhenNoConflict() {
        List<ConflictMath.SchedEntry> entries = new ArrayList<>(List.of(
                e(1, 10, 1, 60, 120, "田赛A"),
                e(2, 20, 1, 200, 260, "田赛B")));
        Map<Long, Set<Long>> ae = athEvs(1L, Set.of(10L, 20L));
        int[] r = ConflictMath.countConflicts(entries, ae, 15);
        assertEquals(0, r[0]);
        assertEquals(0, r[1]);
    }

    @Test
    @DisplayName("严重与一般混合计数准确（重叠1 + 紧间隔1）")
    void count_mixedSevereAndWarn() {
        List<ConflictMath.SchedEntry> entries = new ArrayList<>(List.of(
                e(1, 10, 1, 60, 120, "田赛A"),
                e(2, 20, 1, 90, 150, "田赛B"),   // 重叠 -> 严重
                e(3, 30, 1, 125, 180, "田赛C"))); // 与(1,10)间隔5 -> 一般；(2,20)与(3,30)重叠 -> 严重
        // 运动员1: (10,20)重叠=severe; (10,30)紧间隔=warn; (20,30)重叠=severe
        Map<Long, Set<Long>> ae = athEvs(1L, Set.of(10L, 20L, 30L));
        int[] r = ConflictMath.countConflicts(entries, ae, 15);
        assertEquals(3, r[0], "total=严重2 + 一般1");
        assertEquals(2, r[1], "severe=2");
    }

    // ===== 纪律：纯函数不持有状态、可重复调用 =====

    @Test
    @DisplayName("纯函数无状态：两次调用结果一致，且内部不累积")
    void pure_noStateAcrossCalls() {
        List<ConflictMath.SchedEntry> entries = new ArrayList<>(List.of(
                e(1, 10, 1, 60, 120, "田赛A"),
                e(2, 20, 1, 90, 150, "田赛B")));
        Map<Long, Set<Long>> ae = athEvs(1L, Set.of(10L, 20L));
        int[] r1 = ConflictMath.countConflicts(entries, ae, 15);
        int[] r2 = ConflictMath.countConflicts(entries, ae, 15);
        assertNotSame(r1, r2, "每次返回新数组");
        assertEquals(r1[0], r2[0]);
        assertEquals(r1[1], r2[1]);
    }

    @Test
    @DisplayName("缓冲阈值生效：放宽到 10 分钟则紧间隔升级为冲突")
    void bufferThresholdDrivesTightVsNone() {
        ConflictMath.SchedEntry x = e(1, 10, 1, 60, 120, "田赛A");
        ConflictMath.SchedEntry y = e(2, 20, 1, 130, 180, "田赛B"); // 间隔 10
        assertSame(ConflictMath.Kind.NONE, ConflictMath.gapOf(x, y, 5).kind());  // 5<10 -> 不冲突
        assertSame(ConflictMath.Kind.TIGHT, ConflictMath.gapOf(x, y, 15).kind()); // 10<15 -> 冲突
    }
}
