package com.sports.schedule.opt.alns;

import com.sports.schedule.opt.solver.SchedulePlan;
import com.sports.schedule.opt.solver.ScheduleUnit;

import java.util.List;
import java.util.Random;

/**
 * ALNS 的<b>修复算子</b>：把被破坏算子拔出的单元重新插回解里。
 *
 * <p>修复决定「怎么填」。贪心修复按「新增冲突代价最小」逐个回插——精确但短视；
 * 随机修复随便找空位——快速且多样。两者的优劣取决于当前破坏面与解的拥挤程度，
 * 交给多臂老虎机按实际收益自适应权衡。</p>
 *
 * <p>实现约定：只能取<b>实体自己的候选值域</b>（位置 ∈ candidatePlacements、
 * 时长 ∈ durationChoices 且 ≤ 位置余量）；实在插不回的单元保持未排
 * （中等层的「mustBePlaced」会如实扣分，由主循环的接受准则裁决这轮修复的好坏）。</p>
 */
public interface RepairOperator {

    /** 算子名（同时是老虎机的臂名） */
    String name();

    /**
     * 把被拔出的单元插回解里。
     *
     * @param plan    被破坏后的工作副本（拔出的单元处于未排状态）
     * @param removed 被拔出的单元（与 plan 中的实例同引用）
     * @param rnd     随机源（同种子可复现）
     * @return 成功插回的个数
     */
    int repair(SchedulePlan plan, List<ScheduleUnit> removed, Random rnd);
}
