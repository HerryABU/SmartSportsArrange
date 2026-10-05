package com.sports.schedule.core.placement.split;

import com.sports.schedule.core.primitive.Cursor;
import com.sports.schedule.core.primitive.Pool;
import com.sports.schedule.core.primitive.Unit;
import com.sports.schedule.core.primitive.Window;
import com.sports.schedule.core.placement.conflict.ClashCounter;

import java.util.List;
import java.util.Map;

/**
 * 跨时段拆分搜索（调度层）：**中午临界点把一个项目拆成上午一段 + 下午一段**。
 *
 * <h2>为什么需要它</h2>
 * 现有放置是「整块放置」——{@code u.duration} 必须整个塞进某一个时段窗口。
 * 但窗口之间有天然断点（上午 08:00–11:30、下午 14:00–17:30），
 * 一个 100 分钟的项目在「上午只剩 50 分钟」时会<b>整块跳过上午</b>去下午，
 * 于是上午尾部那 50 分钟白白空着，而下午又和一堆项目挤在一起。
 *
 * <p>现实中这正是「100 米 6 组共 100 分钟，上午排不下 4 组」的常态：
 * 编排器报一句「时段已排满未能安排」，上午剩下的半个时段只能空着。
 * 允许跨时段拆分后，它变成「前 3 组排上午 10:40–11:30，后 3 组排下午 14:00–14:30」，
 * 既排得下、又不浪费上午的余量。
 *
 * <h2>三条不可让步的红线</h2>
 * <ol>
 *   <li><b>必须落在组次边界</b>：两段各自是「若干个完整组次」。一个组次被拦腰截断，
 *       现场检录/发枪/记成绩全都对不上——这是本能力能否被接受的前提，不是优化项。</li>
 *   <li><b>两段必须同一天</b>：跨天拆分等于把项目排成两天，与「整块跨天」没有区别，
 *       反而多一条赛程行，徒增理解成本；而跨时段本来就有真实的时间断点做物理间隔。</li>
 *   <li><b>只对「整块放不下」的单元启用</b>：能整块放下的一律整块放，
 *       拆分会凭空多出一条赛程行、让导出/秩序册/统计都变复杂。</li>
 * </ol>
 *
 * <p>纯静态、无状态；拆分只做<b>只读探测</b>，真正记账由
 * {@link Cursor#reserveSplit} 完成（生成方与记账方分离，避免「探测通过但落位失败」）。</p>
 */
public final class SlotSplit {

    private SlotSplit() {
    }

    /**
     * 一次跨时段拆分的完整落位方案。
     *
     * @param slotIdx       所在并发槽位（搜索时一并定下，避免落位阶段再反查导致搜索/落位错位）
     * @param headWindowIdx 上午段所在窗口下标
     * @param headStart     上午段起点（当日分钟）
     * @param headDuration  上午段时长（分钟）
     * @param tailWindowIdx 下午段所在窗口下标
     * @param tailStart     下午段起点（当日分钟）
     * @param tailDuration  下午段时长（分钟）
     * @param conflicts     两段落位后引入的兼项冲突条数（用于择优）
     */
    public record SplitCand(int slotIdx,
                            int headWindowIdx, int headStart, int headDuration,
                            int tailWindowIdx, int tailStart, int tailDuration,
                            int conflicts) {

        /** 总时长守恒（拆分之一票否决项：时长对不上就说明切错了） */
        public boolean durationConsistent(int total) {
            return headDuration + tailDuration == total;
        }

        @Override
        public String toString() {
            return "槽位" + slotIdx + " 拆分[上午" + headDuration + "min + 下午" + tailDuration + "min]";
        }
    }

    /**
     * 在池的各槽位内寻找「可用的跨时段拆分方案」。
     *
     * <p>遍历所有<b>同一天相邻的两个窗口</b>（上午→下午），对每个槽位：
     * 先按组次粒度算出上午段能容纳多少个完整组次（{@code headRounds}），
     * 剩余组次必须能在下午段放下；两段起点都取该窗口内该槽位的最早可用位置
     * （与 {@code Cursor} 的「不压到已占用前沿之前」口径一致）。</p>
     *
     * <p>择优顺序：兼项冲突少 → 上午段承载组次多（更早开工、上午余量用得更足）
     * → 窗口靠前 → 起点靠前 → 槽位号小。</p>
     *
     * @param blockedIntervals 本项目受行政时间保护（TEACHER 个人时段）的区间；落在其中的起点整段跳过
     * @return 最优拆分方案；不存在合法拆分时返回 null（调用方据实报「排不下」）
     */
    public static SplitCand findSplit(Unit u, Pool pool, List<Window> windows, int interval,
                                      Map<Long, List<int[]>> busy, List<int[]> blockedIntervals) {
        int per = u.perRoundMinutes();
        // 红线①：时长必须能被组次用时整除，否则拆出来的段必然腰斩某个组次
        if (per <= 0 || u.duration % per != 0 || u.rounds < 2) return null;
        int totalRounds = u.rounds;
        int total = u.duration;

        SplitCand best = null;
        for (int si = 0; si < pool.cursors.size(); si++) {
            Cursor c = pool.cursors.get(si);
            for (int hi = 0; hi + 1 < windows.size(); hi++) {
                Window head = windows.get(hi);
                Window tail = windows.get(hi + 1);
                // 红线②：只拆同一天的相邻时段
                if (head.day != tail.day) continue;

                int headUsed = c.usedAt(hi);
                int headLead = headUsed == 0 ? 0 : interval;
                int headFree = head.capacity - headUsed - headLead;
                if (headFree < per) continue;                 // 上午连一个组次都塞不下
                int headRounds = Math.min(headFree / per, totalRounds - 1);
                if (headRounds < 1) continue;                 // 下午段至少留 1 个组次
                int headStart = head.startMinute + headUsed + headLead;
                int headDuration = headRounds * per;

                int tailUsed = c.usedAt(hi + 1);
                int tailLead = tailUsed == 0 ? 0 : interval;
                int tailDuration = total - headDuration;
                if (tailUsed + tailLead + tailDuration > tail.capacity) continue;
                int tailStart = tail.startMinute + tailUsed + tailLead;

                if (isBlocked(head.day, headStart, headDuration, blockedIntervals)) continue;
                if (isBlocked(tail.day, tailStart, tailDuration, blockedIntervals)) continue;

                int n = ClashCounter.countConflicts(u.athleteIds, head.day, headStart, headDuration, busy)
                        + ClashCounter.countConflicts(u.athleteIds, tail.day, tailStart, tailDuration, busy);
                SplitCand cand = new SplitCand(si, hi, headStart, headDuration,
                        hi + 1, tailStart, tailDuration, n);
                if (beats(cand, best)) best = cand;
            }
        }
        return best;
    }

    /**
     * 拆分候选择优：冲突少 → 上午段组次多（越接近「整块放上午」越好，说明上午余量用得足）
     * → 上午窗口靠前 → 上午起点靠前 → 下午起点靠前。
     */
    public static boolean beats(SplitCand a, SplitCand b) {
        if (b == null) return true;
        if (a.conflicts != b.conflicts) return a.conflicts < b.conflicts;
        int aHead = a.headDuration(), bHead = b.headDuration();
        if (aHead != bHead) return aHead > bHead;
        if (a.headWindowIdx() != b.headWindowIdx()) return a.headWindowIdx() < b.headWindowIdx();
        if (a.headStart() != b.headStart()) return a.headStart() < b.headStart();
        return a.tailStart() < b.tailStart();
    }

    /** 整段是否落入行政时间保护区间（day=-1 表示对所有天生效） */
    private static boolean isBlocked(int day, int startMin, int duration, List<int[]> blocked) {
        if (blocked == null || blocked.isEmpty()) return false;
        for (int[] b : blocked) {
            if (b == null || b.length < 3) continue;
            if (b[0] != -1 && b[0] != day) continue;
            if (startMin < b[2] && b[1] < startMin + duration) return true;
        }
        return false;
    }
}
