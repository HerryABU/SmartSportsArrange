package com.sports.schedule.verify.focus;

import com.sports.schedule.verify.ScheduleVerifier.Audit;
import com.sports.schedule.verify.ScheduleVerifier.Row;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对抗性聚焦：主动盯住「最可能出问题的地方」——最忙的运动员、最紧张的场地。
 *
 * <p>与其等冲突自己暴露，不如把资源最紧张的那几个对象直接列出来让人过目——
 * 这是「把最难排的、最忙的单独拎出来检验」的落地。</p>
 */
public final class FocusScanner {

    private FocusScanner() {
    }

    public static Audit scan(List<Row> rows, long venuePairs, long athletePairs) {
        Map<Long, List<Row>> byAthlete = new LinkedHashMap<>();
        for (Row r : rows) {
            if (r.athletes() == null) continue;
            for (Long id : r.athletes()) byAthlete.computeIfAbsent(id, k -> new ArrayList<>()).add(r);
        }
        List<Map<String, Object>> busiest = new ArrayList<>();
        byAthlete.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<Long, List<Row>> e) -> e.getValue().size()).reversed())
                .limit(5)
                .forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("athleteId", e.getKey());
                    m.put("athleteName", e.getValue().get(0).athleteLabel(e.getKey()));
                    m.put("entries", e.getValue().size());
                    List<String> items = new ArrayList<>();
                    for (Row r : e.getValue()) items.add(r.label() + "@" + r.when());
                    m.put("items", items);
                    busiest.add(m);
                });

        Map<String, List<Row>> byVenueDay = new LinkedHashMap<>();
        for (Row r : rows) byVenueDay.computeIfAbsent(r.day() + "|" + r.venue(), k -> new ArrayList<>()).add(r);
        List<Map<String, Object>> tightest = new ArrayList<>();
        byVenueDay.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, List<Row>> e) -> sumMinutes(e.getValue())).reversed())
                .limit(5)
                .forEach(e -> {
                    List<Row> list = e.getValue();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("bucket", e.getKey());
                    m.put("rowCount", list.size());
                    m.put("usedMinutes", sumMinutes(list));
                    tightest.add(m);
                });

        return new Audit(rows.size(), venuePairs, athletePairs, busiest, tightest);
    }

    private static int sumMinutes(List<Row> rows) {
        int s = 0;
        for (Row r : rows) s += r.durationMinutes();
        return s;
    }
}
