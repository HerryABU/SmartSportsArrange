package com.sports.schedule.verify.checks;

import com.sports.schedule.verify.ScheduleVerifier;
import com.sports.schedule.verify.ScheduleVerifier.Row;
import com.sports.schedule.verify.ScheduleViolation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检查③：运动员兼项冲突（含赶场缓冲）。
 *
 * <p>用「运动员 → 其所有赛程行」倒排表，只比对同一运动员的行对，避免全局 O(n²)。
 * 必须<b>全配对</b>（而非相邻比较）：3 个互相重叠的区间，相邻比较只数到 2 对，
 * 实际应为 C(3,2)=3 对——否则 {@code comparedAthletePairs} 审计计数失真。</p>
 */
public final class AthleteClashCheck {

    private AthleteClashCheck() {
    }

    public static void check(List<Row> rows, List<ScheduleViolation> out, VerifyCounters counters) {
        Map<Long, List<Row>> byAthlete = new LinkedHashMap<>();
        for (Row r : rows) {
            if (r.athletes() == null) continue;
            for (Long id : r.athletes()) {
                byAthlete.computeIfAbsent(id, k -> new ArrayList<>()).add(r);
            }
        }
        for (Map.Entry<Long, List<Row>> e : byAthlete.entrySet()) {
            List<Row> list = e.getValue();
            int k = list.size();
            if (k < 2) continue;
            for (int i = 0; i < k; i++) {
                for (int j = i + 1; j < k; j++) {
                    counters.athletePairs++;
                    Row a = list.get(i);
                    Row b = list.get(j);
                    if (athleteClash(a, b)) {
                        String who = a.athleteLabel(e.getKey());
                        out.add(new ScheduleViolation(ScheduleViolation.ATHLETE_CLASH,
                                ScheduleViolation.LEVEL_BLOCKER, a.label(), b.label(),
                                a.when() + " 与 " + b.when(),
                                "运动员「" + who + "」兼报这两项，中间赶不上（需 ≥ "
                                        + ScheduleVerifier.CONFLICT_BUFFER_MIN + " 分钟缓冲）",
                                "把其中一项后移，或确认该运动员是否应当兼报"));
                    }
                }
            }
        }
    }

    /** 同一运动员两个项目是否赶不上（跨天用绝对分钟比较；口径同 ConflictService） */
    static boolean athleteClash(Row a, Row b) {
        int aS = a.absoluteStart();
        int aE = aS + a.durationMinutes();
        int bS = b.absoluteStart();
        int bE = bS + b.durationMinutes();
        return aS < bE + ScheduleVerifier.CONFLICT_BUFFER_MIN && bS < aE + ScheduleVerifier.CONFLICT_BUFFER_MIN;
    }
}
