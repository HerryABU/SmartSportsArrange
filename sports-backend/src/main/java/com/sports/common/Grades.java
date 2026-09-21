package com.sports.common;

/**
 * 年级称谓归一化。
 * 历史数据中 ClassInfo/Athlete.grade 存短称（"高一"、"初一"），
 * 而系统设置/年级管理/日程/项目 grade_group 常用带后缀（"高一年级"、"初一年级"）。
 * 编排等按年级精确 equals 比较处必须经本工具归一后再比，否则永远匹配不上。
 */
public final class Grades {

    private Grades() {
    }

    /** "高一年级"→"高一"、"初三年级"→"初三"；非"xx年级" 原样返回 */
    public static String shortName(String g) {
        if (g == null) return null;
        String t = g.trim();
        if (t.endsWith("年级") && t.length() > 2) {
            return t.substring(0, t.length() - 2);
        }
        return t;
    }

    /** 容忍 "高一" 与 "高一年级" 互通 的相等判断 */
    public static boolean same(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        String sa = shortName(a);
        String sb = shortName(b);
        return sa != null && sa.equals(sb);
    }
}
