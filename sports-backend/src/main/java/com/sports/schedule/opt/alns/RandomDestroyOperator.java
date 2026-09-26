package com.sports.schedule.opt.alns;

import com.sports.schedule.opt.solver.SchedulePlan;
import com.sports.schedule.opt.solver.ScheduleUnit;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 随机破坏：从已排单元里随机抽一小撮拔出。
 *
 * <p>它治的是「热点盲区」：定向破坏（冲突簇/最忙运动员）总围着已知热点转，
 * 长尾区域的劣化结构永远碰不到。随机破坏保证搜索不收敛到有偏的邻域序列——
 * ALNS 文献里它与其他破坏算子的配比由老虎机自适应决定，而非人为固定。</p>
 */
public class RandomDestroyOperator implements DestroyOperator {

    /** 单次破坏的规模上限（过大 = 破坏面失控，修复退化成整体重排） */
    static final int MAX_DESTROY = 8;

    @Override
    public String name() {
        return "随机破坏";
    }

    @Override
    public List<ScheduleUnit> pick(SchedulePlan plan, Random rnd) {
        List<ScheduleUnit> placed = placedUnits(plan);
        if (placed.isEmpty()) {
            return List.of();
        }
        int k = Math.min(Math.max(2, placed.size() / 4), MAX_DESTROY);
        List<ScheduleUnit> pool = new ArrayList<>(placed);
        List<ScheduleUnit> picked = new ArrayList<>(k);
        for (int i = 0; i < k && !pool.isEmpty(); i++) {
            picked.add(pool.remove(rnd.nextInt(pool.size())));
        }
        return picked;
    }

    static List<ScheduleUnit> placedUnits(SchedulePlan plan) {
        List<ScheduleUnit> placed = new ArrayList<>();
        for (ScheduleUnit u : plan.getUnits()) {
            if (u.isPlaced()) placed.add(u);
        }
        return placed;
    }
}
