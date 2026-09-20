package com.sports.schedule.verify.checks;

import com.sports.schedule.verify.ScheduleVerifier.Row;
import com.sports.schedule.verify.ScheduleViolation;

import java.util.List;

/**
 * 检查①：时间自洽——结束必须晚于开始。
 *
 * <p>最廉价也最易被忽略的一条；一旦不成立，后续所有区间判定都失去意义。</p>
 */
public final class TimeConsistencyCheck {

    private TimeConsistencyCheck() {
    }

    public static void check(List<Row> rows, List<ScheduleViolation> out) {
        for (Row r : rows) {
            if (r.endMinute() <= r.startMinute()) {
                out.add(new ScheduleViolation(ScheduleViolation.TIME_INCONSISTENT,
                        ScheduleViolation.LEVEL_BLOCKER, r.label(), null, r.when(),
                        "结束时间不晚于开始时间（时长 " + r.durationMinutes() + " 分钟）",
                        "检查该项目的时长配置与所处时段边界"));
            }
        }
    }
}
