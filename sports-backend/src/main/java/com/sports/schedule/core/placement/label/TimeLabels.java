package com.sports.schedule.core.placement.label;

import com.sports.schedule.core.Unit;

/**
 * 时间解析与标签（算法系：时间/标签辅助）。
 *
 * <p>项目间隔取值、HH:mm 解析、性别中文标签、轮次键拼装——纯字符串/数值小工具，供放置与导出复用。</p>
 */
public final class TimeLabels {

    private TimeLabels() {
    }

    public static int intervalOf(Unit u, int defaultInterval) {
        return u.event.getIntervalMinutes() != null ? u.event.getIntervalMinutes() : defaultInterval;
    }

    public static int parseHHmm(String s) {
        if (s == null) return -1;
        String[] p = s.split(":");
        if (p.length < 2) return -1;
        try {
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 性别原值 → 中文标签（用于告警文案） */
    public static String genderLabel(String g) {
        if (g == null) return "未知";
        if ("M".equalsIgnoreCase(g) || "男".equals(g)) return "男子";
        if ("F".equalsIgnoreCase(g) || "女".equals(g)) return "女子";
        return g;
    }

    public static String roundKey(Long eventId, String grade, String startTime, String venue) {
        return (eventId == null ? "" : eventId)
                + "|" + (grade == null ? "" : grade.trim())
                + "|" + (startTime == null ? "" : startTime.trim())
                + "|" + (venue == null ? "" : venue.trim());
    }
}
