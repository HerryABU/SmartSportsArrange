package com.sports.schedule.opt.alns;

import com.sports.schedule.opt.solver.ScheduleConstraintProvider;
import com.sports.schedule.opt.solver.SchedulePlan;
import com.sports.schedule.opt.solver.ScheduleUnit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 冲突簇破坏：<b>把当前解里相互撞车的单元连同它们的一跳运动员邻居整簇拔出</b>。
 *
 * <h2>为什么它是「兼项规避」的定向武器</h2>
 * 兼项冲突天然构成一张「单元—单元」冲突图（两个单元共享运动员且时间重叠即连边）。
 * 冲突不是孤立的：A 与 B 撞车、B 又与 C 共享运动员——只拔出 A、B，
 * 修复时 C 的既成事实会把它们重新压回老位置。把<b>冲突连通分量（带一跳扩展）</b>
 * 整体拔出，修复算子才有自由度把这簇人重新错开。这是 ALNS 针对
 * 「冲突图结构特征」的直接利用，也是对既有 LNS「按最忙运动员」邻域的结构化升级。
 *
 * <p>无冲突时退化为随机小破坏（保持探索能力）。</p>
 */
public class ClashClusterDestroyOperator extends RandomDestroyOperator {

    /** 簇规模上限：兼项密集实例的冲突闭包可能吞掉半个解，必须截断 */
    static final int MAX_CLUSTER = 12;

    @Override
    public String name() {
        return "冲突簇破坏";
    }

    @Override
    public List<ScheduleUnit> pick(SchedulePlan plan, Random rnd) {
        List<ScheduleUnit> placed = placedUnits(plan);
        if (placed.isEmpty()) {
            return List.of();
        }

        // ① 种子：当前所有兼项冲突对的两端
        Set<ScheduleUnit> cluster = new LinkedHashSet<>();
        for (int i = 0; i < placed.size(); i++) {
            ScheduleUnit a = placed.get(i);
            if (!a.hasAthletes()) continue;
            for (int j = i + 1; j < placed.size(); j++) {
                ScheduleUnit b = placed.get(j);
                if (b.hasAthletes() && a.sharesAthlete(b)
                        && ScheduleConstraintProvider.athleteClash(a, b)) {
                    cluster.add(a);
                    cluster.add(b);
                }
            }
        }
        if (cluster.isEmpty()) {
            return super.pick(plan, rnd);   // 无冲突：退化为随机小破坏
        }

        // ② 一跳扩展：与簇内任一单元共享运动员的已排单元一并拔出
        //    （它们是修复时最容易「把冲突压回去」的既成事实）
        boolean grown = true;
        while (grown && cluster.size() < MAX_CLUSTER) {
            grown = false;
            for (ScheduleUnit u : placed) {
                if (cluster.size() >= MAX_CLUSTER) break;
                if (cluster.contains(u) || !u.hasAthletes()) continue;
                for (ScheduleUnit in : cluster) {
                    if (u.sharesAthlete(in)) {
                        cluster.add(u);
                        grown = true;
                        break;
                    }
                }
            }
        }
        return new ArrayList<>(cluster);
    }
}
