package com.sports.schedule.verify.quality;

import com.sports.schedule.verify.ScheduleVerifier.Expected;
import com.sports.schedule.verify.ScheduleVerifier.Quality;
import com.sports.schedule.verify.ScheduleVerifier.Row;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 解的质量自评：没有最优解，但可以量化「当前这版有多好、离理论下界多远」。
 *
 * <p>{@code totalMinutes} 是各行时长之和，{@code netUsedMinutes} 是按「天 × 场地」合并重叠后的
 * <b>净占用</b>。利用率必须用净占用算——用行时长之和会把重叠部分重复计入，
 * 得出 >100% 的荒谬值；而「有重叠」这件事应该由 {@code violations} 明确报告，
 * 不该被稀释进一个比例数字里。</p>
 */
public final class QualityEstimator {

    private QualityEstimator() {
    }

    public static Quality estimate(List<Row> rows, List<Expected> expected, int dailyCapacityMinutes) {
        int totalMinutes = 0;
        int minAbs = Integer.MAX_VALUE;
        int maxAbs = Integer.MIN_VALUE;
        Set<Integer> days = new HashSet<>();
        Set<String> venues = new HashSet<>();
        for (Row r : rows) {
            totalMinutes += r.durationMinutes();
            minAbs = Math.min(minAbs, r.absoluteStart());
            maxAbs = Math.max(maxAbs, r.absoluteStart() + r.durationMinutes());
            days.add(r.day());
            venues.add(r.venue());
        }
        int rawMinutes = 0;
        for (Expected ex : expected) rawMinutes += Math.max(0, ex.rawDurationMinutes());

        // 净占用：按 (天, 场地) 把各行区间合并掉重叠后再求和。
        // 直接用「行时长之和」算利用率会把重叠部分重复计入，产出 >100% 的荒谬值——
        // 那其实是「存在重叠」的信号，应由 violations 明确报告，而不是污染利用率指标。
        Map<String, List<int[]>> byVenueDay = new LinkedHashMap<>();
        for (Row r : rows) {
            byVenueDay.computeIfAbsent(r.day() + "|" + r.venue(), k -> new ArrayList<>())
                    .add(new int[]{r.startMinute(), r.endMinute()});
        }
        int netUsed = 0;
        for (List<int[]> list : byVenueDay.values()) {
            list.sort(Comparator.comparingInt(iv -> iv[0]));
            int curStart = -1;
            int curEnd = -1;
            for (int[] iv : list) {
                if (curStart < 0) {
                    curStart = iv[0];
                    curEnd = iv[1];
                } else if (iv[0] <= curEnd) {
                    curEnd = Math.max(curEnd, iv[1]);
                } else {
                    netUsed += Math.max(0, curEnd - curStart);
                    curStart = iv[0];
                    curEnd = iv[1];
                }
            }
            if (curStart >= 0) netUsed += Math.max(0, curEnd - curStart);
        }

        double compression = rawMinutes > 0 ? round1(totalMinutes * 100.0 / rawMinutes) : 0;
        int span = rows.isEmpty() ? 0 : Math.max(0, maxAbs - minAbs);
        // 供给 = 单日可用分钟 × 比赛天数 × 场地数（多个场地是并行资源）。
        int supply = dailyCapacityMinutes * Math.max(1, days.size()) * Math.max(1, venues.size());
        double utilization = supply > 0 ? round1(netUsed * 100.0 / supply) : 0;

        return new Quality(rows.size(), totalMinutes, netUsed, rawMinutes, compression, span,
                dailyCapacityMinutes, utilization);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
