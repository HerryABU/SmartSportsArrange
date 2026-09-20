package com.sports.schedule.support.verify;

import com.sports.schedule.verify.ScheduleViolation;

import java.util.List;
import java.util.Map;

/**
 * 校验摘要（support 系：校验）。
 *
 * <p>从 verification 结果里摘出第一条阻塞级问题的单行摘要，供 warnings 提示；
 * 完整清单仍走 verification 字段。纯函数、无状态、不依赖 Spring，可独立单测。</p>
 */
public final class ViolationBrief {

    private ViolationBrief() {
    }

    /** 摘出第一条阻塞级问题的摘要，用于 warnings 里的一行提示（完整清单走 verification 字段） */
    @SuppressWarnings("unchecked")
    public static String firstViolationBrief(Map<String, Object> verification) {
        Object vs = verification.get("violations");
        if (!(vs instanceof List<?> list)) return "";
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) o;
            if (!ScheduleViolation.LEVEL_BLOCKER.equals(String.valueOf(m.get("level")))) continue;
            return String.valueOf(m.get("subject")) + " → " + String.valueOf(m.get("detail"));
        }
        return "";
    }
}
