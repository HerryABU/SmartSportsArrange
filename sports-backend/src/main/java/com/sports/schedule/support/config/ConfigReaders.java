package com.sports.schedule.support.config;

import com.sports.common.util.Grades;

/**
 * 配置项读取（support 系：配置解析）。
 *
 * <p>从「任意 Object」安全读出 int/double/String，并做年级比较。纯函数、无状态、
 * 不依赖 Spring，可独立单测。</p>
 */
public final class ConfigReaders {

    private ConfigReaders() {
    }

    /** 读取 int 配置项，解析失败返回默认值 */
    public static int intVal(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try {
                return Integer.parseInt(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    /** 读取 double 配置项，解析失败返回默认值 */
    public static double dblVal(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        if (v != null) {
            try {
                return Double.parseDouble(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    /** 读取字符串配置项（空值回退默认） */
    public static String str(Object v, String def) {
        return v != null && !String.valueOf(v).isBlank() ? String.valueOf(v) : def;
    }

    /** null 安全空串 */
    public static String n(String s) {
        return s != null ? s : "";
    }

    /** 两个年级是否同一（空 = 不分年级，视为相同） */
    public static boolean sameGrade(String a, String b) {
        boolean ea = a == null || a.isBlank();
        boolean eb = b == null || b.isBlank();
        if (ea && eb) return true;
        if (ea || eb) return false;
        return Grades.same(a, b);
    }
}
