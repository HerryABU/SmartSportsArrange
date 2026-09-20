package com.sports.schedule.verify.checks;

import com.sports.schedule.verify.ScheduleVerifier.Row;
import com.sports.schedule.verify.ScheduleViolation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检查④：同组项目未同时开赛——违反赛会惯例（集中裁判与器材），不算阻塞但要人工确认。
 */
public final class GroupSimultaneousCheck {

    private GroupSimultaneousCheck() {
    }

    public static void check(List<Row> rows, List<ScheduleViolation> out) {
        Map<String, List<Row>> byGroup = new LinkedHashMap<>();
        for (Row r : rows) {
            if (r.groupKey() == null || r.groupKey().isBlank()) continue;
            byGroup.computeIfAbsent(r.groupKey(), k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<String, List<Row>> e : byGroup.entrySet()) {
            List<Row> g = e.getValue();
            if (g.size() < 2) continue;
            Row first = g.get(0);
            for (int i = 1; i < g.size(); i++) {
                Row r = g.get(i);
                if (r.day() != first.day() || r.startMinute() != first.startMinute()) {
                    out.add(new ScheduleViolation(ScheduleViolation.GROUP_NOT_SIMULTANEOUS,
                            ScheduleViolation.LEVEL_WARNING, first.label(), r.label(),
                            first.when() + " 与 " + r.when(),
                            "同组「" + e.getKey() + "」项目未同时开赛",
                            "如需集中裁判/器材，请把同组项目对齐到同一时刻"));
                }
            }
        }
    }
}
