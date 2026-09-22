package com.sports.common.util;

/**
 * 轮次标签统一（U09 / B09 / B10）：
 * preliminary=预赛；final=决赛；null/blank/其他——若该项目存在预赛轮则视为决赛（经预赛晋级），
 * 否则为「直接决赛」，杜绝秩序册/道次表轮次混乱。
 */
public final class RoundLabelUtil {

    public static String label(String round, boolean hasPreliminary) {
        if ("preliminary".equals(round)) return "预赛";
        if ("final".equals(round)) return "决赛";
        return hasPreliminary ? "决赛" : "直接决赛";
    }

    /** 仅知当前行 round、不知项目是否含预赛时使用（null 一律判为直接决赛） */
    public static String label(String round) {
        return label(round, false);
    }
}
