package com.sports.schedule.verify.checks;

import com.sports.schedule.verify.ScheduleVerifier.Expected;
import com.sports.schedule.verify.ScheduleViolation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检查⑤：被静默丢弃的项目——编排表里有、赛程表里没有。
 *
 * <p>「没排入」比「排错」更危险：排错至少看得见，没排入会到比赛当天才发现跑不完。</p>
 */
public final class AssignmentCoverageCheck {

    private AssignmentCoverageCheck() {
    }

    public static void check(Set<String> placedKeys, Map<String, Expected> expectedByKey,
                             List<ScheduleViolation> out, List<Map<String, Object>> unassigned) {
        for (Expected ex : expectedByKey.values()) {
            if (placedKeys.contains(ex.key())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", ex.key());
            m.put("eventName", ex.eventName());
            m.put("grade", ex.grade());
            unassigned.add(m);
            out.add(new ScheduleViolation(ScheduleViolation.NOT_ASSIGNED,
                    ScheduleViolation.LEVEL_BLOCKER, ex.label(), null, null,
                    "该项目在赛程表里找不到对应条目（容量不足或编排未覆盖）",
                    "增加比赛天数/时段、提高并发位，或削减项目规模"));
        }
    }
}
