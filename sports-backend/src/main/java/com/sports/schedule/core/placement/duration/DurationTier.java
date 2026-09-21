package com.sports.schedule.core.placement.duration;

import com.sports.schedule.core.primitive.Unit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 时长档位（算法系：时长缩放）。
 *
 * <p>项目时长下限与候选档位计算：再挤也不该把一个大项压到不足真实用时的 35%，
 * 同时给出从真实用时按 5% 递减到下限的候选档位，供求解器权衡「保真」与「排得下」。</p>
 */
public final class DurationTier {

    private DurationTier() {
    }

    /** 单项目最短占用时间（分钟），避免 0 人报名时挤成一团 */
    public static final int MIN_DURATION = 10;
    /** 单项目时长缩放下限比例：再挤也不该把一个大项压到不足真实用时的 35% */
    public static final double MIN_DURATION_RATIO = 0.35;

    /** 项目时长下限：再挤也不该把一个大项压到不足真实用时的 35% */
    public static int minDurationOf(Unit u) {
        return Math.max(MIN_DURATION, (int) Math.round(u.rawDuration * MIN_DURATION_RATIO));
    }

    /** 候选时长档位（降序）：从真实用时按 5% 递减到下限，供求解器逐项目权衡「保真」与「排得下」 */
    public static List<Integer> durationChoicesOf(Unit u) {
        int raw = u.rawDuration;
        int floor = minDurationOf(u);
        LinkedHashSet<Integer> set = new LinkedHashSet<>();
        for (int pct = 100; pct >= 35; pct -= 5) {
            int v = (int) Math.round(raw * pct / 100.0);
            if (v >= floor) set.add(v);
        }
        set.add(floor);
        List<Integer> out = new ArrayList<>(set);
        out.sort(Comparator.reverseOrder());
        return out;
    }
}
