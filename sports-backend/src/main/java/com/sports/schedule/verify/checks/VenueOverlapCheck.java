package com.sports.schedule.verify.checks;

import com.sports.schedule.verify.ScheduleVerifier.Row;
import com.sports.schedule.verify.ScheduleViolation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检查②：同场地同时段重叠——按<b>真实场地</b>判，与求解器按「并发位」判是两套独立口径。
 *
 * <p>槽位与场地是两套映射（并发位可能映射到同一场地），只有按场地查才查得出真实冲突。</p>
 */
public final class VenueOverlapCheck {

    private VenueOverlapCheck() {
    }

    public static void check(List<Row> rows, List<ScheduleViolation> out, VerifyCounters counters) {
        Map<String, List<Row>> byVenueDay = new LinkedHashMap<>();
        for (Row r : rows) {
            byVenueDay.computeIfAbsent(r.day() + "|" + r.venue(), k -> new ArrayList<>()).add(r);
        }
        for (List<Row> group : byVenueDay.values()) {
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    counters.venuePairs++;
                    Row a = group.get(i);
                    Row b = group.get(j);
                    if (overlaps(a.startMinute(), a.endMinute(), b.startMinute(), b.endMinute())) {
                        out.add(new ScheduleViolation(ScheduleViolation.VENUE_OVERLAP,
                                ScheduleViolation.LEVEL_BLOCKER, a.label(), b.label(),
                                a.when() + " 与 " + b.when(),
                                "同一场地「" + a.venue() + "」被两个项目同时占用",
                                "把其中一个项目移到其它时段，或改到其它场地"));
                    }
                }
            }
        }
    }

    /** 两个时间区间是否重叠（左闭右开） */
    static boolean overlaps(int aStart, int aEnd, int bStart, int bEnd) {
        return aStart < bEnd && bStart < aEnd;
    }
}
