package com.sports.schedule.core;

import com.sports.schedule.core.placement.candidate.CandidateGenerator;
import com.sports.schedule.core.placement.capacity.CapacityBoxing;
import com.sports.schedule.core.placement.conflict.ClashCounter;
import com.sports.schedule.core.placement.duration.DurationTier;
import com.sports.schedule.core.placement.label.TimeLabels;
import com.sports.schedule.core.placement.slot.SlotSearch;
import com.sports.schedule.opt.Placement;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 赛程放置的纯静态数学/判定工具门面（从 {@code ScheduleService} 抽取，行为零变化）。
 *
 * <p>本类仅作稳定门面，把所有静态方法委托到 {@code core.placement.*} 下的算法系子包，
 * 调度层与算法系职责分离；公开方法签名与常量保持不变，调用方（SchedulePlacementComponent /
 * ScheduleSolveComponent / ScheduleBuildComponent / ScheduleQueryExportComponent / ScheduleAnalysisMath /
 * ScheduleSelfCheckComponent）无需改动。</p>
 */
public final class SchedulePlacementMath {

    /** 单项目最短占用时间（分钟），避免 0 人报名时挤成一团 */
    public static final int MIN_DURATION = DurationTier.MIN_DURATION;
    /** 单项目时长缩放下限比例：再挤也不该把一个大项压到不足真实用时的 35% */
    public static final double MIN_DURATION_RATIO = DurationTier.MIN_DURATION_RATIO;

    private SchedulePlacementMath() {
    }

    // ── 候选位置生成（core.placement.candidate） ──────────────────────────────

    /** 生成某并发池的全部候选位置：槽位 × 时段窗口 × 起点档位（按 gridStep 步进） */
    public static List<Placement> placementsOf(Pool pool, List<Window> windows, int gridStep) {
        return CandidateGenerator.placementsOf(pool, windows, gridStep);
    }

    // ── 时长档位（core.placement.duration） ──────────────────────────────────

    /** 项目时长下限：再挤也不该把一个大项压到不足真实用时的 35% */
    public static int minDurationOf(Unit u) {
        return DurationTier.minDurationOf(u);
    }

    /** 候选时长档位（降序）：从真实用时按 5% 递减到下限，供求解器逐项目权衡「保真」与「排得下」 */
    public static List<Integer> durationChoicesOf(Unit u) {
        return DurationTier.durationChoicesOf(u);
    }

    // ── 兼项冲突计数（core.placement.conflict） ──────────────────────────────

    /** 参赛运动员 id 升序数组（升序是为了让兼项判定能用双指针求交） */
    public static long[] sortedAthletes(Unit u) {
        return ClashCounter.sortedAthletes(u);
    }

    /** 两个单元是否有共同运动员（小集合驱动，避免全量遍历） */
    public static boolean sharesAthlete(Unit a, Unit b) {
        return ClashCounter.sharesAthlete(a, b);
    }

    /** 求解结果的残余兼项冲突数（与检测端同口径）；与 solverStat 一起进日志，便于核对「到底规避掉多少」 */
    public static int countResidualClashes(Map<Unit, Placement> solved) {
        return ClashCounter.countResidualClashes(solved);
    }

    /** 把单元放到「第 day 天 startMinute 起、持续 duration 分钟」会撞上多少条已排项目（与检测端同口径） */
    public static int countConflicts(Set<Long> athleteIds, int day, int startMinute, int duration,
                                    Map<Long, List<int[]>> busy) {
        return ClashCounter.countConflicts(athleteIds, day, startMinute, duration, busy);
    }

    // ── 放置候选搜索（core.placement.slot，调度层） ───────────────────────────

    /** 在池的各槽位内扫描候选起点，返回排序最优者（冲突最少 → 窗口靠前 → 起点靠前 → 槽位号小） */
    public static Cand findBestSlot(Unit u, Pool pool, List<Window> windows, int interval,
                                    Map<Long, List<int[]>> busy) {
        return SlotSearch.findBestSlot(u, pool, windows, interval, busy);
    }

    /** 候选排序：冲突少者优先（尽量避开兼项）→ 窗口靠前 → 起点靠前 → 槽位号小 */
    public static boolean beats(Cand a, Cand b) {
        return SlotSearch.beats(a, b);
    }

    /** 单元在所属池内的游标：多单元共用同池时依次占用不同槽位 */
    public static Cursor cursorOf(Pool pool, int unitIdx) {
        return SlotSearch.cursorOf(pool, unitIdx);
    }

    // ── 时间解析与标签（core.placement.label） ───────────────────────────────

    public static int intervalOf(Unit u, int defaultInterval) {
        return TimeLabels.intervalOf(u, defaultInterval);
    }

    public static int parseHHmm(String s) {
        return TimeLabels.parseHHmm(s);
    }

    /** 性别原值 → 中文标签（用于告警文案） */
    public static String genderLabel(String g) {
        return TimeLabels.genderLabel(g);
    }

    public static String roundKey(Long eventId, String grade, String startTime, String venue) {
        return TimeLabels.roundKey(eventId, grade, startTime, venue);
    }

    // ── 容量与装箱（core.placement.capacity） ─────────────────────────────────

    /** 单日可用分钟总数（各天取最大值：各天时段配置通常一致，取最大避免低估容量而误报利用率） */
    public static int dailyCapacityOf(List<Window> windows) {
        return CapacityBoxing.dailyCapacityOf(windows);
    }

    /** 模拟真实放置策略的整块装箱可行性；全部单元都能整块放下则为 true */
    public static boolean greedyPacks(long[] durations, int slots, int windowCount,
                                      int windowCapacity, int interval) {
        return CapacityBoxing.greedyPacks(durations, slots, windowCount, windowCapacity, interval);
    }
}
