package com.sports.schedule.opt.solver;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 赛程编排问题（约束求解的「规划方案」）。
 *
 * <p>评分采用 {@link HardMediumSoftScore} 三层，语义与赛会决策优先级严格对应：</p>
 * <ul>
 *   <li><b>硬</b>：方案必须合法——项目整块落在窗口内、同并发位不重叠、落在自己的池、
 *       同组项目同时开赛、每个项目都要排入；</li>
 *   <li><b>中</b>：运动员兼项不得撞车（与冲突检测端同一套 15 分钟缓冲口径）；</li>
 *   <li><b>软</b>：尽量保留项目真实用时、尽量排在前面的比赛日与较早时段。</li>
 * </ul>
 *
 * <p>用三层而不是两层，是为了在<b>客观上排不下</b>时仍能给出「最不坏」的方案：
 * 若把兼项冲突也设成硬约束，容量紧张时求解器会直接无解（连一个可交付方案都拿不到），
 * 而现实中「少几处冲突」远比「整个编排失败」有用。</p>
 */
@PlanningSolution
@Getter
@Setter
@NoArgsConstructor
public class SchedulePlan {

    /**
     * 全部可放置位置（问题事实）。
     *
     * <p>注意：这里<b>不</b>标注 {@code @ValueRangeProvider}——位置值域由
     * {@link ScheduleUnit#placementRange()} 按实体提供（每个项目只用自己池里的位置）。
     * 两处同时声明同一个 id 会导致求解器启动期报重复值域。</p>
     */
    @ProblemFactCollectionProperty
    private List<Placement> placements;

    @PlanningEntityCollectionProperty
    private List<ScheduleUnit> units;

    @PlanningScore
    private HardMediumSoftScore score;

    public SchedulePlan(List<Placement> placements, List<ScheduleUnit> units) {
        this.placements = placements;
        this.units = units;
    }

    /**
     * 深拷贝整个方案：实体逐个拷贝，位置（问题事实）共享引用。
     *
     * <p>遗传算法与 LNS 都靠它「先拷贝再改动」，保证原始解不被破坏。</p>
     */
    public SchedulePlan deepCopy() {
        List<ScheduleUnit> copies = new ArrayList<>(units.size());
        for (ScheduleUnit u : units) {
            copies.add(u.copy());
        }
        SchedulePlan p = new SchedulePlan(placements, copies);
        p.setScore(score);
        return p;
    }
}
