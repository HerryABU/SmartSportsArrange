package com.sports.schedule.core.math;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 兼项冲突计数（纯函数核心，无 DB / 无 Spring / 无状态）。
 *
 * <p>与 {@code com.sports.service.arrange.ConflictService#detectConflicts()} 事后检测<b>完全同口径</b>：
 * 同一天、两段区间重叠（或同场地同时开赛）记「严重」；仅间隔小于 {@code bufferMin} 分钟记「一般」；
 * 否则不计。本类是「检测端」与「编排消解端」之间唯一的冲突判定真理源，二者共用、杜绝口径漂移。</p>
 *
 * <p>之所以抽成纯函数：编排求解器（{@code RuleBasedScheduler} / {@code ScheduleOptimizer}）必须遵守
 * 「不读库、不落库」的<b>纪律</b>；冲突计数作为消解循环的目标函数，也必须能脱离仓库独立单测与复用。</p>
 */
public final class ConflictMath {

    private ConflictMath() {
    }

    /** 一条赛程条目（已解析为绝对分钟），供纯函数消费 */
    public record SchedEntry(long schedId, long eventId, int day, int startMin, int endMin, String venue) {
    }

    /** 冲突类型（与 {@code ConflictService} 的 severity/type 一一对应） */
    public enum Kind {
        /** 无冲突 */
        NONE,
        /** 间隔不足（一般） */
        TIGHT,
        /** 时间重叠（严重） */
        OVERLAP,
        /** 同场地同时开赛（严重，最典型的撞车） */
        SAME_VENUE_START
    }

    /** 冲突判定结果 */
    public record Gap(Kind kind, int gapMinutes) {
    }

    /**
     * 判定两条赛程条目是否冲突（与 {@code ConflictService#gapOf} 对称间隔口径一致）。
     *
     * @param bufferMin 间隔不足阈值（分钟）
     */
    public static Gap gapOf(SchedEntry x, SchedEntry y, int bufferMin) {
        if (x.day != y.day) return new Gap(Kind.NONE, 0);
        // 时间非法/缺失：与检测端一致，无法判定则跳过（不误报）
        if (x.startMin < 0 || x.endMin < 0 || y.startMin < 0 || y.endMin < 0) {
            return new Gap(Kind.NONE, 0);
        }
        int xe = x.endMin <= x.startMin ? x.startMin + 1 : x.endMin;
        int ye = y.endMin <= y.startMin ? y.startMin + 1 : y.endMin;

        boolean overlapping = x.startMin < ye && y.startMin < xe;
        int gap = Math.max(y.startMin - xe, x.startMin - ye);   // 对称间隔

        if (overlapping) {
            boolean sameVenueSameStart = sameText(x.venue, y.venue) && x.startMin == y.startMin;
            return new Gap(sameVenueSameStart ? Kind.SAME_VENUE_START : Kind.OVERLAP, 0);
        }
        if (gap < bufferMin) {
            return new Gap(Kind.TIGHT, gap);
        }
        return new Gap(Kind.NONE, gap);
    }

    /**
     * 统计兼项冲突总数（与 {@code ConflictService#detectConflicts()} 命中条数一致）。
     *
     * <p>对每位运动员，逐一比对它名下不同项目的赛程条目两两组合：
     * 仅当两条来自<b>不同项目</b>且 {@link #gapOf} 非 {@link Kind#NONE} 时计一次。</p>
     *
     * @return {@code int[]{total, severe}}，severe = 严重（重叠 / 同场地同时开赛）冲突数
     */
    public static int[] countConflicts(List<SchedEntry> entries, Map<Long, Set<Long>> athleteEvents, int bufferMin) {
        Map<Long, List<SchedEntry>> byEvent = new HashMap<>();
        for (SchedEntry e : entries) {
            byEvent.computeIfAbsent(e.eventId, k -> new ArrayList<>()).add(e);
        }
        int total = 0;
        int severe = 0;
        for (Map.Entry<Long, Set<Long>> ae : athleteEvents.entrySet()) {
            Set<Long> evs = ae.getValue();
            List<SchedEntry> mine = new ArrayList<>();
            for (Long ev : evs) {
                List<SchedEntry> lst = byEvent.get(ev);
                if (lst != null) mine.addAll(lst);
            }
            for (int i = 0; i < mine.size(); i++) {
                for (int j = i + 1; j < mine.size(); j++) {
                    SchedEntry x = mine.get(i);
                    SchedEntry y = mine.get(j);
                    if (x.eventId == y.eventId) continue;   // 仅不同项目之间才算兼项冲突
                    Gap g = gapOf(x, y, bufferMin);
                    if (g.kind() != Kind.NONE) {
                        total++;
                        if (g.kind() == Kind.OVERLAP || g.kind() == Kind.SAME_VENUE_START) severe++;
                    }
                }
            }
        }
        return new int[]{total, severe};
    }

    private static boolean sameText(String a, String b) {
        return a != null && b != null && a.trim().equals(b.trim());
    }
}
