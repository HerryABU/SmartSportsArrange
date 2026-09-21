package com.sports.schedule.core.placement.capacity;

import com.sports.schedule.core.primitive.Window;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 容量与装箱（算法系：容量聚合 / 整块装箱可行性）。
 *
 * <p>单日可用分钟聚合，以及模拟真实放置策略的整块装箱可行性预判。预判口径必须与放置口径一致——
 * 这是本类方法存在的唯一理由：实际放置是按项目顺序、对每个单元取「窗口靠前 → 起点靠前 → 槽位号小」
 * 的首次适应(first-fit)，且槽位之间并行。</p>
 */
public final class CapacityBoxing {

    private CapacityBoxing() {
    }

    /** 单日可用分钟总数（各天取最大值：各天时段配置通常一致，取最大避免低估容量而误报利用率） */
    public static int dailyCapacityOf(List<Window> windows) {
        Map<Integer, Integer> byDay = new LinkedHashMap<>();
        for (Window w : windows) byDay.merge(w.day, w.capacity, Integer::sum);
        int max = 0;
        for (Integer v : byDay.values()) {
            if (v != null && v > max) max = v;
        }
        return max;
    }

    /**
     * 模拟真实放置策略的整块装箱可行性（U27/B24）。
     *
     * <p>预判口径必须与放置口径一致——这是本方法存在的唯一理由。实际放置是按项目顺序，
     * 对每个单元取「窗口靠前 → 起点靠前 → 槽位号小」的首次适应(first-fit)，且槽位之间并行。</p>
     *
     * @return 全部单元都能整块放下则为 true
     */
    public static boolean greedyPacks(long[] durations, int slots, int windowCount,
                                      int windowCapacity, int interval) {
        int slotCount = Math.max(1, slots);
        int[] used = new int[slotCount * Math.max(1, windowCount)];
        for (long d : durations) {
            int dur = (int) d;
            int bestIdx = -1;
            int bestStart = Integer.MAX_VALUE;
            for (int wi = 0; wi < windowCount; wi++) {
                for (int si = 0; si < slotCount; si++) {
                    int idx = wi * slotCount + si;
                    int u = used[idx];
                    int gap = u == 0 ? 0 : interval;
                    if (u + gap + dur > windowCapacity) continue;          // 整块放不下
                    int start = wi * 1440 + u + gap;                       // 绝对分钟，跨天可比
                    if (start < bestStart || (start == bestStart && idx < bestIdx)) {
                        bestStart = start;
                        bestIdx = idx;
                    }
                }
            }
            if (bestIdx < 0) return false;                                 // 没有任何块装得下
            used[bestIdx] += (used[bestIdx] == 0 ? 0 : interval) + dur;
        }
        return true;
    }
}
