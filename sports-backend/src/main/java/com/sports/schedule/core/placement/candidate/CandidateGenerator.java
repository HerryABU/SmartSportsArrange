package com.sports.schedule.core.placement.candidate;

import com.sports.schedule.core.primitive.Pool;
import com.sports.schedule.core.primitive.Window;
import com.sports.schedule.core.placement.duration.DurationTier;
import com.sports.schedule.opt.solver.Placement;

import java.util.ArrayList;
import java.util.List;

/**
 * 候选位置生成（算法系：候选枚举）。
 *
 * <p>把「并发池 × 时段窗口 × 起点档位」展开成可枚举的放置候选，供求解器/调度层逐项目挑选。
 * 纯静态、无状态，不依赖 Spring 上下文，可独立单测。</p>
 */
public final class CandidateGenerator {

    private CandidateGenerator() {
    }

    /** 生成某并发池的全部候选位置：槽位 × 时段窗口 × 起点档位（按 gridStep 步进） */
    public static List<Placement> placementsOf(Pool pool, List<Window> windows, int gridStep) {
        int step = Math.max(1, gridStep);
        List<Placement> out = new ArrayList<>();
        for (int si = 0; si < pool.slots; si++) {
            String venue = pool.venueOf.get(si);
            for (int wi = 0; wi < windows.size(); wi++) {
                Window w = windows.get(wi);
                for (int off = 0; off + DurationTier.MIN_DURATION <= w.capacity; off += step) {
                    out.add(new Placement(pool.label, si, wi, w.day, w.date, w.slotName, venue,
                            w.startMinute + off, w.startMinute, w.capacity));
                }
            }
        }
        return out;
    }
}
