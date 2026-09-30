package com.sports.schedule.analysis;

import com.sports.schedule.core.primitive.Unit;

import java.util.List;

/**
 * 空时间限制（自动推算比赛天数）的纯静态工具。
 *
 * <p>当运动会日程配置未显式指定 {@code days}（或开启 {@code autoDays}）时，
 * 编排需要先回答「按每天安排的时段，究竟要排多少天才装得下全部项目」。
 * 本类按「真实需求分钟 ÷ 每日可用分钟」向上取整得到天数，供
 * {@code ScheduleService#autoSchedule} 在铺时间窗之前动态生成 dayConfigs。</p>
 *
 * <p>口径与可行性预检 {@code ScheduleAnalysisMath#assessFeasibility} 一致：
 * 每个有报名单元的「需求」为其 {@code rawDuration}（未压缩真实用时）+ 项目间隔；
 * 径赛 / 田赛分别累计后，按各自并发位（{@code trackSlots} / {@code fieldSlots}）
 * 折算每日可提供的分钟，取两类池所需天数的最大值。</p>
 */
public final class DaysEstimator {

    private DaysEstimator() {
    }

    /**
     * 估算所需天数。
     *
     * @param units        已估算用时的赛程单元（复用 {@code estimateDurations} 后的结果）
     * @param dailyMinutes 单日时段总容量（分钟，由某一天的 slots 累加得到）
     * @param trackSlots   径赛并发位（&ge;1）
     * @param fieldSlots   田赛并发位（&ge;1）
     * @param interval     项目间隔（分钟，与可行性预检同口径）
     * @return 所需天数（至少 1 天）
     */
    public static int estimateDays(List<Unit> units, int dailyMinutes,
                                   int trackSlots, int fieldSlots, int interval) {
        int trackNeed = 0;
        int fieldNeed = 0;
        for (Unit u : units) {
            if (u.participants <= 0) continue;
            int need = Math.max(0, u.rawDuration) + Math.max(0, interval);
            if (u.track) trackNeed += need;
            else fieldNeed += need;
        }
        int trackCap = Math.max(1, Math.max(1, dailyMinutes) * Math.max(1, trackSlots));
        int fieldCap = Math.max(1, Math.max(1, dailyMinutes) * Math.max(1, fieldSlots));

        int days = 1;
        if (trackNeed > 0) days = Math.max(days, ceilDiv(trackNeed, trackCap));
        if (fieldNeed > 0) days = Math.max(days, ceilDiv(fieldNeed, fieldCap));
        return Math.max(1, days);
    }

    /** 向上取整除法（正数域） */
    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
