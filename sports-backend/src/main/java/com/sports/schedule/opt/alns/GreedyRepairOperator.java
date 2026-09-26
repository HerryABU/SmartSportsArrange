package com.sports.schedule.opt.alns;

import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.ScheduleConstraintProvider;
import com.sports.schedule.opt.solver.SchedulePlan;
import com.sports.schedule.opt.solver.ScheduleUnit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 贪心修复：对每个被拔出的单元，在全部候选组合（位置 × 放得下的最大档位）里
 * 选<b>新增冲突代价最小</b>的一处插入。
 *
 * <p>代价函数与评分的三层结构同构（字典序压倒）：</p>
 * <ul>
 *   <li>同并发位时间重叠 ×10⁶——硬约束，宁可未排也不制造非法重叠；</li>
 *   <li>新增兼项撞车 ×10³——中等层，即本次修复的「正事」：插到撞车最少的地方；</li>
 *   <li>起点分钟 ×1——同代价时偏好更早的时间（与「preferEarlierDay/Start」软层同向）。</li>
 * </ul>
 *
 * <p>这是「ALNS 数学启发式」里修复环节的最小精确化：虽然不是完整 IP 求解，
 * 但逐单元最优插入已经能在破坏面不大时把冲突簇重新错开——
 * 剩余的全局权衡交给主循环的评分与接受准则。</p>
 */
public class GreedyRepairOperator implements RepairOperator {

    @Override
    public String name() {
        return "贪心修复";
    }

    @Override
    public int repair(SchedulePlan plan, List<ScheduleUnit> removed, Random rnd) {
        // 候选少的先插（最受限的单元优先占位，避免被后来者挤死）
        List<ScheduleUnit> queue = new ArrayList<>(removed);
        queue.sort(Comparator.comparingInt(u -> u.getCandidatePlacements().size()));

        int inserted = 0;
        for (ScheduleUnit u : queue) {
            Placement bestPlacement = null;
            int bestDuration = -1;
            double bestCost = Double.POSITIVE_INFINITY;

            for (Placement p : u.getCandidatePlacements()) {
                int duration = largestFitting(u, p);
                if (duration < u.getMinDuration()) continue;
                double cost = insertionCost(plan, u, p, duration);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestPlacement = p;
                    bestDuration = duration;
                }
            }
            if (bestPlacement != null) {
                u.setPlacement(bestPlacement);
                u.setDuration(bestDuration);
                inserted++;
            }
        }
        return inserted;
    }

    /** 插入 (placement, duration) 的冲突代价（字典序：重叠 ≫ 撞车 ≫ 起点偏后） */
    private double insertionCost(SchedulePlan plan, ScheduleUnit unit, Placement placement, int duration) {
        int start = placement.getStartMinute();
        int end = start + duration;
        double cost = 0;

        for (ScheduleUnit other : plan.getUnits()) {
            if (other == unit || !other.isPlaced()) continue;
            boolean sameBin = placement.getBinKey().equals(other.getPlacement().getBinKey());
            int oStart = other.getPlacement().getStartMinute();
            int oEnd = oStart + other.getDuration();
            boolean overlaps = start < oEnd && oStart < end;

            if (sameBin && overlaps) {
                cost += 1_000_000;   // 硬约束：同并发位重叠
            }
            if (overlaps && unit.hasAthletes() && other.hasAthletes()
                    && unit.sharesAthlete(other)
                    && candidateClashes(unit, placement, duration, other)) {
                cost += 1_000;       // 中等层：新增兼项撞车
            }
        }
        return cost + start * 1.0;   // 软层：同代价偏好更早
    }

    /**
     * 「候选落位」与已排单元是否兼项撞车。
     *
     * <p>被修复的单元此刻还没有落位（placement 为 null），不能走
     * {@link ScheduleConstraintProvider#athleteClash(ScheduleUnit, ScheduleUnit)}；
     * 用候选位置的绝对起点 + 同一冲突公式（含缓冲，口径不改）评估插入后果。</p>
     */
    private boolean candidateClashes(ScheduleUnit unit, Placement placement, int duration, ScheduleUnit other) {
        int candidateStart = (placement.getDay() - 1) * 1440 + placement.getStartMinute();
        int otherStart = other.getPlacement().getAbsoluteStartMinute();
        return ScheduleConstraintProvider.athleteClash(candidateStart, duration,
                otherStart, other.getDuration());
    }

    /** 候选档位中「放得下该位置的最大时长」（保持上游压缩口径，不主动多压） */
    static int largestFitting(ScheduleUnit unit, Placement placement) {
        int best = -1;
        for (int d : unit.getDurationChoices()) {
            if (d <= placement.getMaxDuration() && d > best) best = d;
        }
        return best;
    }
}
