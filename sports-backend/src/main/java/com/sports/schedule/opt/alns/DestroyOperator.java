package com.sports.schedule.opt.alns;

import com.sports.schedule.opt.solver.SchedulePlan;
import com.sports.schedule.opt.solver.ScheduleUnit;

import java.util.List;
import java.util.Random;

/**
 * ALNS 的<b>破坏算子</b>：决定把解里的哪些单元「拔出来」（置为未排，等待修复）。
 *
 * <h2>为什么破坏算子是 ALNS 的灵魂</h2>
 * 破坏决定「往哪里挖」。随机破坏是纯探索；围绕<b>当前兼项冲突簇</b>破坏是定向攻击；
 * 围绕<b>最忙运动员</b>破坏是抓住冲突根源。哪个算子在当前实例上最有效无法先验知道——
 * 这正是 ALNS 用多臂老虎机（UCB）自适应选择的原因：按每个算子实际带来的解改进
 * 分配使用频率，而非固定轮换或纯随机。
 *
 * <p>实现约定：只从「已排」单元里挑；返回的单元随后由主循环置为未排，
 * 再交给修复算子回插。规模必须有上限（防止破坏面过大导致修复退化成整体重排）。</p>
 */
public interface DestroyOperator {

    /** 算子名（同时是老虎机的臂名） */
    String name();

    /**
     * 从解中选出要拔出的单元集合。
     *
     * @return 待拔出的已排单元；无可破坏时返回空列表（主循环跳过该轮）
     */
    List<ScheduleUnit> pick(SchedulePlan plan, Random rnd);
}
