package com.sports.schedule.opt.ga;

import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.SchedulePlan;
import com.sports.schedule.opt.solver.ScheduleUnit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 遗传算法的算子（交叉、变异）——纯函数，无状态、可独立单测。
 *
 * <h2>基因编码</h2>
 * 一个「个体」= 一份 {@link SchedulePlan}。它的「基因」是每个 {@link ScheduleUnit}
 * 的两个计划变量：<b>落位（placement）+ 时长（duration）</b>。交叉就是交换父代的基因，
 * 变异就是随机改动某个基因。
 *
 * <h2>为什么是「交换落位模式」而不是「拼接时间序列」</h2>
 * 排程问题的好解体现在「哪些项目放在了哪些并发位」这些<b>局部模式</b>上。均匀交叉
 * 让子代从父 A、父 B 各继承一部分「项目→落位」的对应关系，正是遗传算法「组合好的
 * 局部模式」的直觉——这与 GA+SA 混合文献里「GA 做全局探索、交换好的时间段分配」一致。
 */
public final class GeneticOperators {

    private GeneticOperators() {
    }

    /**
     * 均匀交叉：逐个基因（单元）随机决定继承父 A 还是父 B 的落位+时长。
     *
     * <p>按 {@code key}（计划 id）对齐两个父代，而不是按下标——保证即便列表顺序
     * 不同，交换的也是<b>同一个项目</b>的基因，避免张冠李戴。</p>
     */
    public static SchedulePlan uniformCrossover(SchedulePlan a, SchedulePlan b, long seed) {
        Map<String, ScheduleUnit> bByKey = indexByKey(b);
        Random rnd = new Random(seed);
        List<ScheduleUnit> offspring = new ArrayList<>(a.getUnits().size());
        for (ScheduleUnit ua : a.getUnits()) {
            ScheduleUnit ub = bByKey.get(ua.getKey());
            // 若 B 里没有这个单元（理论上不会），退回继承 A
            ScheduleUnit child = (ub != null && rnd.nextBoolean()) ? ub.copy() : ua.copy();
            child.setPinned(false);
            offspring.add(child);
        }
        SchedulePlan childPlan = new SchedulePlan(a.getPlacements(), offspring);
        return childPlan;
    }

    /**
     * 变异：按概率把某个单元重新随机分配（落位 + 时长），其余基因不变。
     *
     * <p>变异是逃离局部最优的「扰动」来源——只靠交叉会在种群趋同后停滞。
     * 为让变异尽量产出<b>可行</b>个体，这里先随机选落位、再从「不超过该落位余量、
     * 不低于时长下限」的候选中选时长，而不是完全随机组合。</p>
     */
    public static SchedulePlan mutate(SchedulePlan plan, double rate, long seed) {
        Random rnd = new Random(seed);
        SchedulePlan copy = plan.deepCopy();
        for (ScheduleUnit u : copy.getUnits()) {
            if (rnd.nextDouble() < rate) {
                mutateUnit(u, rnd);
            }
        }
        return copy;
    }

    /** 单个基因的随机重置（先选落位，再选不超过其余量的时长） */
    private static void mutateUnit(ScheduleUnit u, Random rnd) {
        List<Placement> cands = u.getCandidatePlacements();
        List<Integer> durChoices = u.getDurationChoices();
        if (cands == null || cands.isEmpty()) {
            u.setPlacement(null);
            u.setDuration(null);
            return;
        }
        Placement p = cands.get(rnd.nextInt(cands.size()));
        u.setPlacement(p);
        int max = p.getMaxDuration();
        List<Integer> feasible = new ArrayList<>();
        if (durChoices != null) {
            for (Integer d : durChoices) {
                if (d != null && d >= u.getMinDuration() && d <= max) feasible.add(d);
            }
        }
        u.setDuration(feasible.isEmpty()
                ? (durChoices == null || durChoices.isEmpty() ? null : durChoices.get(durChoices.size() - 1))
                : feasible.get(rnd.nextInt(feasible.size())));
    }

    private static Map<String, ScheduleUnit> indexByKey(SchedulePlan plan) {
        Map<String, ScheduleUnit> m = new HashMap<>();
        for (ScheduleUnit u : plan.getUnits()) {
            m.put(u.getKey(), u);
        }
        return m;
    }
}
