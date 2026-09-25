package com.sports.schedule.core.placement.slot;

import com.sports.schedule.core.primitive.Cand;
import com.sports.schedule.core.primitive.Cursor;
import com.sports.schedule.core.primitive.Pool;
import com.sports.schedule.core.primitive.Unit;
import com.sports.schedule.core.primitive.Window;
import com.sports.schedule.core.placement.conflict.ClashCounter;

import java.util.List;
import java.util.Map;

/**
 * 放置候选搜索（调度层：最优起点搜索）。
 *
 * <p>在并发池各槽位内扫描候选起点，按「冲突最少 → 窗口靠前 → 起点靠前 → 槽位号小」挑最优者；
 * 同槽位内按 interval 步进枚举，可为主动避让兼项冲突而后移若干分钟。另含候选排序与游标拾取。</p>
 */
public final class SlotSearch {

    private SlotSearch() {
    }

    /**
     * 在池的各槽位内扫描候选起点，返回排序最优者：
     * 冲突数最少 → 日期/时段最早 → 起点最早 → 槽位号最小。
     *
     * <p>同槽位内的候选按 interval 步进枚举（而非只取「最早可用」这一个点），
     * 这样才能为了避让兼项冲突而主动后移若干分钟；容量边界与 {@link Cursor#place} 保持一致。</p>
     *
     * @return 最优候选；该池在剩余时段内完全放不下时返回 null
     */
    public static Cand findBestSlot(Unit u, Pool pool, List<Window> windows, int interval,
                                    Map<Long, List<int[]>> busy) {
        Cand best = null;
        for (int si = 0; si < pool.cursors.size(); si++) {
            Cursor c = pool.cursors.get(si);
            for (int wi = 0; wi < windows.size(); wi++) {
                Window w = windows.get(wi);
                int base = c.usedAt(wi);
                int lead = base == 0 ? 0 : interval;             // 与 Cursor.place 的段前间隔口径一致
                if (base + lead + u.duration > w.capacity) continue;
                int start = w.startMinute + base + lead;
                int n = ClashCounter.countConflicts(u.athleteIds, w.day, start, u.duration, busy);
                int bestGap = ClashCounter.minGapToBusy(u.athleteIds, w.day, start, u.duration, busy);
                for (int off = base + lead + interval; off + u.duration <= w.capacity; off += interval) {
                    int s2 = w.startMinute + off;
                    int n2 = ClashCounter.countConflicts(u.athleteIds, w.day, s2, u.duration, busy);
                    if (n2 < n) {
                        // 冲突更少：无条件更优
                        n = n2;
                        start = s2;
                        bestGap = ClashCounter.minGapToBusy(u.athleteIds, w.day, s2, u.duration, busy);
                    } else if (n2 == n) {
                        // 冲突数相同：在当天空档里尽量拉开（间隔更大 → 运动员休息更足）
                        int g2 = ClashCounter.minGapToBusy(u.athleteIds, w.day, s2, u.duration, busy);
                        if (g2 > bestGap) {
                            start = s2;
                            bestGap = g2;
                        }
                    }
                }
                Cand cand = new Cand(si, wi, start, n, bestGap);
                if (beats(cand, best)) best = cand;
            }
        }
        return best;
    }

    /**
     * 候选排序：冲突少者优先（尽量避开兼项）→ 窗口靠前 → 起点靠前 → 槽位号小。
     *
     * <p>U26/B23：不再有「是否缩短」这一维——项目时长是固定的编排单位，候选里全是能整块放下的位置。</p>
     */
    public static boolean beats(Cand a, Cand b) {
        if (b == null) return true;
        if (a.conflicts != b.conflicts) return a.conflicts < b.conflicts;
        if (a.windowIdx != b.windowIdx) return a.windowIdx < b.windowIdx;
        // U45/B41：冲突数、时段相同后，优先间隔更大者（运动员休息更足），只在有空档时生效
        if (a.gap != b.gap) return a.gap > b.gap;
        if (a.startMinute != b.startMinute) return a.startMinute < b.startMinute;
        return a.slotIdx < b.slotIdx;
    }

    /** 单元在所属池内的游标：多单元共用同池时依次占用不同槽位 */
    public static Cursor cursorOf(Pool pool, int unitIdx) {
        return pool.cursors.get(unitIdx % pool.cursors.size());
    }
}
