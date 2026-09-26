package com.sports.schedule.opt.mnsa;

import com.sports.schedule.opt.solver.SchedulePlan;

import java.util.Random;

/**
 * 多邻域模拟退火（MNSA）的<b>一个邻域移动</b>。
 *
 * <h2>MNSA 与既有 LNS 的分工</h2>
 * LNS（{@code opt.lns}）的粒度是「拔出一整块、重新求解这一块」，每轮都要跑一次求解器，
 * 代价大但改得深；MNSA 的粒度是<b>单步移动</b>——直接在候选值域内改一两个单元的
 * 落位/时长，用评分器快速打分（不跑求解器），因此一步的成本只有 LNS 的百分之一，
 * 能在同样的预算里做几十上百步。两者互补：LNS 负责深层重构，MNSA 负责浅层精修
 * 与「允许暂时变差」的持续扰动。
 *
 * <p>实现约定：移动<b>只在工作副本上操作</b>（调用方保证传入的是深拷贝），
 * 且必须落在实体自己的候选值域内（候选位置 / 候选时长），保证评分语义合法；
 * 无处可动时返回 {@code false}，由主循环计零收益。</p>
 */
public interface MnsaMove {

    /** 移动名（同时是老虎机的臂名，用于自适应选择与日志） */
    String name();

    /**
     * 在工作副本上施加一次移动。
     *
     * @return 是否真的发生了移动（false = 没有可动之处，本步作废）
     */
    boolean apply(SchedulePlan work, Random rnd);
}
