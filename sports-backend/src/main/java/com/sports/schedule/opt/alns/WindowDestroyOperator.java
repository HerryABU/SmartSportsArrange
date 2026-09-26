package com.sports.schedule.opt.alns;

import com.sports.schedule.opt.solver.SchedulePlan;
import com.sports.schedule.opt.solver.ScheduleUnit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 时段窗口破坏：随机选一个「并发位（池×槽位×窗口）」，把其中全部单元拔出。
 *
 * <p>碎片化发生在窗口内部：一个窗口被若干短项目切得七零八落，谁也挤不进新的长项目。
 * 整窗拔出后，修复算子可以重新决定「这一格到底留给谁、留多长」，
 * 这是逐点移动永远做不到的全局重组。</p>
 */
public class WindowDestroyOperator extends RandomDestroyOperator {

    @Override
    public String name() {
        return "时段窗口破坏";
    }

    @Override
    public List<ScheduleUnit> pick(SchedulePlan plan, Random rnd) {
        List<ScheduleUnit> placed = placedUnits(plan);
        if (placed.isEmpty()) {
            return List.of();
        }

        // 按并发位（binKey）分组，随机选一格整拔
        Map<String, List<ScheduleUnit>> byBin = new LinkedHashMap<>();
        for (ScheduleUnit u : placed) {
            if (u.getPlacement() == null) continue;
            byBin.computeIfAbsent(u.getPlacement().getBinKey(), k -> new ArrayList<>()).add(u);
        }
        if (byBin.isEmpty()) {
            return super.pick(plan, rnd);
        }
        List<String> bins = new ArrayList<>(byBin.keySet());
        return byBin.get(bins.get(rnd.nextInt(bins.size())));
    }
}
