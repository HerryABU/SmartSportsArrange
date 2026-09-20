package com.sports.schedule.support.time;

import java.time.LocalDate;

/**
 * 时间解析与格式化（support 系：时间）。
 *
 * <p>纯函数：HH:mm ↔ 当日分钟互转、日期顺延。无状态、无 IO、不依赖 Spring，
 * 可脱离服务独立单测。</p>
 */
public final class TimeParse {

    private TimeParse() {
    }

    /** 解析 "HH:mm" → 当日分钟（与 {@link #fmt} 互逆）；解析不了返回 0 */
    public static int parseHhMm(String hhmm) {
        return parseMinute(hhmm);
    }

    /** 解析 "HH:mm"（与 {@link #fmt} 互逆）；解析不了返回 0 */
    public static int parseMinute(String hhmm) {
        if (hhmm == null || hhmm.isBlank()) return 0;
        String t = hhmm.trim();
        int colon = t.indexOf(':');
        try {
            if (colon < 0) return Integer.parseInt(t);
            int h = Integer.parseInt(t.substring(0, colon));
            int m = Integer.parseInt(t.substring(colon + 1));
            return Math.max(0, h * 60 + m);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** 当日分钟 → "HH:mm"（与 {@link #parseMinute} 互逆） */
    public static String fmt(int minuteOfDay) {
        int m = ((minuteOfDay % 1440) + 1440) % 1440;
        return String.format("%02d:%02d", m / 60, m % 60);
    }

    /** 日期顺延 offset 天（解析失败原样返回，不抛异常） */
    public static String shiftDate(String startDate, int offset) {
        try {
            return LocalDate.parse(startDate).plusDays(offset).toString();
        } catch (Exception e) {
            return startDate;
        }
    }
}
