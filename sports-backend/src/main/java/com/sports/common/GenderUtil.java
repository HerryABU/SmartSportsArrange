package com.sports.common;

/**
 * 性别取值归一化工具。
 *
 * <p>历史原因，项目(event.genderLimit)存在两种写法：中文组别（男子组/女子组/混合组）
 * 与单字母（M/F/mixed）；运动员(athlete.gender)统一存 M/F。报名/导入校验前必须归一化，
 * 否则会出现「男生(M)报男子组项目被误判性别不符」的双轨不一致。</p>
 */
public final class GenderUtil {

    private GenderUtil() {
    }

    /** 归一化：男/男生/男子/男子组/M → M；女/女生/女子/女子组/F → F；其余原样返回（含 mixed/混合组） */
    public static String normalize(String v) {
        if (v == null) return null;
        String t = v.trim();
        return switch (t) {
            case "男", "男生", "男子", "男子组", "M", "m" -> "M";
            case "女", "女生", "女子", "女子组", "F", "f" -> "F";
            default -> t;
        };
    }

    /**
     * 运动员(athlete.gender)是否允许报名该项目(event.genderLimit)。
     * 项目不限性别（null/mixed/混合/混合组）恒通过。
     */
    public static boolean matches(String eventLimit, String athleteGender) {
        if (eventLimit == null || eventLimit.isBlank()) return true;
        String limit = normalize(eventLimit);
        if (limit.isEmpty()) return true;
        String mix = normalize("mixed");
        if (limit.equalsIgnoreCase(mix)
                || "混合".equals(limit) || "混合组".equals(limit)
                || "mixed".equalsIgnoreCase(limit)) {
            return true;
        }
        if (athleteGender == null) return false;
        String a = normalize(athleteGender);
        return limit.equalsIgnoreCase(a);
    }
}
