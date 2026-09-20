package com.sports.schedule.verify.checks;

import com.sports.schedule.verify.ScheduleVerifier;
import com.sports.schedule.verify.ScheduleVerifier.Expected;
import com.sports.schedule.verify.ScheduleVerifier.Row;
import com.sports.schedule.verify.ScheduleViolation;

import java.util.List;
import java.util.Map;

/**
 * 检查⑥：时长低于可执行下限——项目被压得太狠，现场跑不完。
 */
public final class DurationFloorCheck {

    private DurationFloorCheck() {
    }

    public static void check(List<Row> rows, Map<String, Expected> expectedByKey, List<ScheduleViolation> out) {
        for (Row r : rows) {
            Expected ex = expectedByKey.get(r.key());
            if (ex == null || ex.rawDurationMinutes() <= 0) continue;
            int floor = Math.max(ScheduleVerifier.MIN_DURATION,
                    (int) Math.round(ex.rawDurationMinutes() * ScheduleVerifier.MIN_DURATION_RATIO));
            if (r.durationMinutes() < floor) {
                out.add(new ScheduleViolation(ScheduleViolation.DURATION_BELOW_FLOOR,
                        ScheduleViolation.LEVEL_WARNING, r.label(), null, r.when(),
                        "实际时长 " + r.durationMinutes() + " 分钟，低于可执行下限 " + floor
                                + " 分钟（真实估算 " + ex.rawDurationMinutes() + " 分钟）",
                        "增加该项目所在池的并发位，或延长当天时段"));
            }
        }
    }
}
