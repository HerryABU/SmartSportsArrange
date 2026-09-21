package com.sports.schedule.opt.score;

import com.sports.schedule.opt.solver.ScheduleConstraintProvider;
import com.sports.schedule.opt.solver.SchedulePlan;
import com.sports.schedule.opt.solver.ScheduleUnit;

/**
 * 方案度量（算法系：评分/度量）。
 *
 * <p>对已有方案做轻量统计：未分配单元数、残余兼项冲突数。纯静态、无求解代价，
 * 供求解器日志与组合调度层快速评估，不依赖 Spring。</p>
 */
public final class PlanMetrics {

    private PlanMetrics() {
    }

    /** 未分配单元数（报名了却没排进任何并发位） */
    public static long countUnassigned(SchedulePlan plan) {
        return plan.getUnits().stream().filter(u -> !u.isPlaced()).count();
    }

    /** 残余兼项冲突数（与检测端同口径：同运动员、且两单元都已放置、且时间重叠） */
    public static long countClashes(SchedulePlan plan) {
        var units = plan.getUnits();
        long n = 0;
        for (int i = 0; i < units.size(); i++) {
            ScheduleUnit a = units.get(i);
            if (!a.isPlaced() || !a.hasAthletes()) continue;
            for (int j = i + 1; j < units.size(); j++) {
                ScheduleUnit b = units.get(j);
                if (b.isPlaced() && b.hasAthletes()
                        && a.sharesAthlete(b) && ScheduleConstraintProvider.athleteClash(a, b)) {
                    n++;
                }
            }
        }
        return n;
    }
}
