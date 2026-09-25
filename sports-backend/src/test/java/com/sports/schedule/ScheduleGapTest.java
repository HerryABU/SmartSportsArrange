package com.sports.schedule;

import com.sports.schedule.core.placement.conflict.ClashCounter;
import com.sports.schedule.core.placement.slot.SlotSearch;
import com.sports.schedule.core.primitive.Cand;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 候选排序「间隔」偏好与最小间隔计算的纯单元测试（U45/B41：分批间隙大间隔放置）。
 *
 * <p>这些逻辑无 Spring / 无数据库依赖，可在进程内稳定复现，专门锁定新增的
 * 「冲突数、时段相同后优先间隔更大者」这一维，以及 {@link ClashCounter#minGapToBusy} 的度量。</p>
 */
class ScheduleGapTest {

    // ===== SlotSearch.beats：间隔是「冲突数 → 时段」之后的次级偏好 =====

    @Test
    void beats_prefersLargerGapWhenConflictAndWindowEqual() {
        // 冲突数、时段都相同，间隔更大者更优（运动员休息更足）
        Cand largerGap = new Cand(0, 0, 100, 0, 50);
        Cand smallerGap = new Cand(0, 0, 90, 0, 30);
        assertTrue(SlotSearch.beats(largerGap, smallerGap), "间隔更大者应胜出");
        assertFalse(SlotSearch.beats(smallerGap, largerGap), "间隔更小者不应胜出");
    }

    @Test
    void beats_conflictStillDominatesGap() {
        // 冲突数更小者胜出，即便对方间隔大得多
        Cand zeroConflict = new Cand(0, 0, 100, 0, 50);
        Cand oneConflict = new Cand(0, 0, 100, 1, 9999);
        assertTrue(SlotSearch.beats(zeroConflict, oneConflict));
        assertFalse(SlotSearch.beats(oneConflict, zeroConflict));
    }

    @Test
    void beats_windowStillDominatesGap() {
        // 更早时段胜出，即便对方间隔大得多
        Cand earlierWindow = new Cand(0, 0, 500, 0, 50);
        Cand laterWindowLargerGap = new Cand(0, 1, 50, 0, 9999);
        assertTrue(SlotSearch.beats(earlierWindow, laterWindowLargerGap));
        assertFalse(SlotSearch.beats(laterWindowLargerGap, earlierWindow));
    }

    @Test
    void beats_tiesBreakByEarliestStart() {
        // 冲突、时段、间隔全相同 → 起点更早者胜出
        Cand earlier = new Cand(0, 0, 100, 0, 50);
        Cand later = new Cand(0, 0, 200, 0, 50);
        assertTrue(SlotSearch.beats(earlier, later));
        assertFalse(SlotSearch.beats(later, earlier));
    }

    // ===== ClashCounter.minGapToBusy：到最近同天已排占用的最小间隔 =====

    @Test
    void minGapToBusy_noSpan_returnsLarge() {
        Map<Long, List<int[]>> busy = new HashMap<>();
        busy.put(1L, List.of(new int[]{0, 100}));
        // 运动员 2 当天没有任何已排占用 → 视为整段空闲，返回极大值
        int gap = ClashCounter.minGapToBusy(Set.of(2L), 0, 200, 30, busy);
        assertTrue(gap > 10_000, "无占用时应返回极大值，不去无谓后移");
    }

    @Test
    void minGapToBusy_returnsSymmetricGap() {
        Map<Long, List<int[]>> busy = new HashMap<>();
        busy.put(1L, List.of(new int[]{0, 100}));
        // 起点 200（绝对 200），结束 230；距已排占用 [0,100] 的间隔 = 200 - 100 = 100
        assertEquals(100, ClashCounter.minGapToBusy(Set.of(1L), 0, 200, 30, busy));
        // 起点 130 → 间隔 = 130 - 100 = 30
        assertEquals(30, ClashCounter.minGapToBusy(Set.of(1L), 0, 130, 30, busy));
    }

    @Test
    void minGapToBusy_overlap_returnsNegativeOrZero() {
        Map<Long, List<int[]>> busy = new HashMap<>();
        busy.put(1L, List.of(new int[]{0, 100}));
        // 起点 50，结束 80，与 [0,100] 重叠 → 对称间隔为负
        int gap = ClashCounter.minGapToBusy(Set.of(1L), 0, 50, 30, busy);
        assertTrue(gap < 0, "与已排占用重叠时间隔应为负（即冲突）");
    }

    @Test
    void minGapToBusy_countsOnlySameDay() {
        Map<Long, List<int[]>> busy = new HashMap<>();
        // 占用在 day 0；放到 day 1 应视为无相邻占用（跨天不算冲突，间隔为正且很大）
        busy.put(1L, List.of(new int[]{0, 100}));
        int gapDay1 = ClashCounter.minGapToBusy(Set.of(1L), 1, 0, 30, busy);
        assertTrue(gapDay1 > 0, "跨天不应计为冲突（间隔应为正）");
    }
}
