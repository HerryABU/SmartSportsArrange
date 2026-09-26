package com.sports.schedule.opt.alns;

import com.sports.schedule.opt.solver.SchedulePlan;
import com.sports.schedule.opt.solver.ScheduleUnit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 最忙运动员破坏：找出「兼报单元数最多」的运动员，把其所有单元整体拔出。
 *
 * <p>冲突往往锁在少数最忙的人身上——他们报的项目越多，被既成事实压死的空间越大。
 * 围着一个人整体重排，比零敲碎打更可能一次性错开其全部项目。
 * 找不到任何运动员数据时退化为随机小破坏。</p>
 */
public class BusyAthleteDestroyOperator extends RandomDestroyOperator {

    @Override
    public String name() {
        return "最忙运动员破坏";
    }

    @Override
    public List<ScheduleUnit> pick(SchedulePlan plan, Random rnd) {
        List<ScheduleUnit> placed = placedUnits(plan);
        if (placed.isEmpty()) {
            return List.of();
        }

        // ① 统计每个运动员的已排单元数，找最忙者
        Map<Long, Integer> entries = new LinkedHashMap<>();
        for (ScheduleUnit u : placed) {
            if (u.getAthletes() == null) continue;
            for (long a : u.getAthletes()) {
                entries.merge(a, 1, Integer::sum);
            }
        }
        long busiest = -1;
        int most = 1;
        for (Map.Entry<Long, Integer> e : entries.entrySet()) {
            if (e.getValue() > most) {
                most = e.getValue();
                busiest = e.getKey();
            }
        }
        if (busiest < 0) {
            return super.pick(plan, rnd);   // 人人只报一项：退化为随机小破坏
        }

        // ② 拔出最忙运动员的全部已排单元（上限内全拔，不给既成事实留尾巴）
        final long target = busiest;
        List<ScheduleUnit> picked = new ArrayList<>();
        for (ScheduleUnit u : placed) {
            if (u.getAthletes() == null) continue;
            for (long a : u.getAthletes()) {
                if (a == target) {
                    picked.add(u);
                    break;
                }
            }
        }
        return picked;
    }
}
