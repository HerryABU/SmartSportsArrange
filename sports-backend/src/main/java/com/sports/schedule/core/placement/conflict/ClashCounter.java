package com.sports.schedule.core.placement.conflict;

import com.sports.schedule.core.Unit;
import com.sports.schedule.opt.Placement;
import com.sports.service.ConflictService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 兼项冲突计数（算法系：冲突检测）。
 *
 * <p>与 {@link ConflictService#detectConflicts()} 同口径：必须是同一天、两段区间重叠或间隔小于
 * {@link ConflictService#CONFLICT_BUFFER_MIN} 分钟才计一次。提供运动员集合求交、残余冲突统计、
 * 以及「放到某天某起点会撞多少已排项目」的查询，供贪心放置与求解器规避兼项。</p>
 */
public final class ClashCounter {

    private ClashCounter() {
    }

    /** 参赛运动员 id 升序数组（升序是为了让兼项判定能用双指针求交） */
    public static long[] sortedAthletes(Unit u) {
        long[] arr = new long[u.athleteIds.size()];
        int i = 0;
        for (Long id : u.athleteIds) arr[i++] = id;
        Arrays.sort(arr);
        return arr;
    }

    /** 两个单元是否有共同运动员（小集合驱动，避免全量遍历） */
    public static boolean sharesAthlete(Unit a, Unit b) {
        if (a.athleteIds.isEmpty() || b.athleteIds.isEmpty()) return false;
        Set<Long> small = a.athleteIds.size() <= b.athleteIds.size() ? a.athleteIds : b.athleteIds;
        Set<Long> big = small == a.athleteIds ? b.athleteIds : a.athleteIds;
        for (Long id : small) {
            if (big.contains(id)) return true;
        }
        return false;
    }

    /** 求解结果的残余兼项冲突数（与检测端同口径）；与 solverStat 一起进日志，便于核对「到底规避掉多少」 */
    public static int countResidualClashes(Map<Unit, Placement> solved) {
        List<Unit> placed = new ArrayList<>();
        for (Map.Entry<Unit, Placement> e : solved.entrySet()) {
            if (e.getKey().participants > 0) placed.add(e.getKey());
        }
        int n = 0;
        for (int i = 0; i < placed.size(); i++) {
            for (int j = i + 1; j < placed.size(); j++) {
                Unit a = placed.get(i);
                Unit b = placed.get(j);
                if (!sharesAthlete(a, b)) continue;
                int aS = solved.get(a).getAbsoluteStartMinute();
                int bS = solved.get(b).getAbsoluteStartMinute();
                if (aS < bS + b.duration + ConflictService.CONFLICT_BUFFER_MIN
                        && bS < aS + a.duration + ConflictService.CONFLICT_BUFFER_MIN) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * 把单元放到「第 day 天 startMinute 起、持续 duration 分钟」会撞上多少条已排项目。
     *
     * <p>与 {@link ConflictService#detectConflicts()} 同口径：必须是同一天，且两段区间重叠、
     * 或间隔小于 {@link ConflictService#CONFLICT_BUFFER_MIN} 分钟才计一次。</p>
     *
     * @param busy 运动员 → 已占用时间段（绝对分钟 = 天 × 1440 + 当日分钟）
     */
    public static int countConflicts(Set<Long> athleteIds, int day, int startMinute, int duration,
                                    Map<Long, List<int[]>> busy) {
        if (athleteIds == null || athleteIds.isEmpty() || busy == null || busy.isEmpty()) return 0;
        int absStart = day * 1440 + startMinute;
        int absEnd = absStart + duration;
        int n = 0;
        for (Long aid : athleteIds) {
            List<int[]> spans = busy.get(aid);
            if (spans == null) continue;
            for (int[] p : spans) {
                int gap = Math.max(absStart - p[1], p[0] - absEnd);   // 对称间隔
                if (gap < ConflictService.CONFLICT_BUFFER_MIN) n++;
            }
        }
        return n;
    }
}
