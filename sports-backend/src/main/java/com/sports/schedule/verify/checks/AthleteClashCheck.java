package com.sports.schedule.verify.checks;

import com.sports.schedule.verify.ScheduleVerifier;
import com.sports.schedule.verify.ScheduleVerifier.Row;
import com.sports.schedule.verify.ScheduleViolation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.sports.service.arrange.ConflictService;

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

    /**
     * 同一运动员两个项目是否「赶不上」——必须与主检测链 ConflictMath 同口径：
     *
     * <p>① <b>跨天隔离</b>：不同天的项目隔了整夜，绝不算兼项冲突（原先只用绝对分钟裸算，
     * day1 23:50 与 day2 00:00 会被误判为赶不上）。</p>
     * <p>② <b>径田可顺延</b>：径赛与田赛同段重叠属于 Kind.SEQUENCED——运动员跑完径赛可直接去田赛，
     * 非致命、不阻塞。只有同类项目（径径 / 田田）重叠才是真兼项冲突。</p>
     *
     * <p>track 任一为 null（旧调用）时退回保守口径：仅按时间重叠判，兼容历史自检用例。</p>
     */
    static boolean athleteClash(Row a, Row b) {
        if (a.day() != b.day()) return false;   // 跨天隔离：隔夜不算赶不上
        Boolean ta = a.track(), tb = b.track();
        if (ta != null && tb != null && ta != tb) return false;  // 径田同段：可顺延，非致命
        int aS = a.absoluteStart();
        int aE = aS + a.durationMinutes();
        int bS = b.absoluteStart();
        int bE = bS + b.durationMinutes();
        return aS < bE + ScheduleVerifier.CONFLICT_BUFFER_MIN && bS < aE + ScheduleVerifier.CONFLICT_BUFFER_MIN;
    }
}
