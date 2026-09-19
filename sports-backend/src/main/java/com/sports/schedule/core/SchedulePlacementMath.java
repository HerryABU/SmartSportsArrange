package com.sports.schedule.core;

import com.sports.schedule.opt.Placement;
import com.sports.service.ConflictService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 赛程放置的纯静态数学/判定工具（从 {@code ScheduleService} 抽取，行为零变化）。
 *
 * <p>含：候选位置生成、时长下限与档位、兼项冲突计数、放置最优候选搜索、游标辅助、性别/轮次键、
 * 时间解析、装箱可行性预判、单日容量聚合。全部为无状态静态方法，不依赖任何 Repository 或 Spring 上下文，
 * 可独立单测。</p>
 */
public final class SchedulePlacementMath {

    /** 单项目最短占用时间（分钟），避免 0 人报名时挤成一团 */
    public static final int MIN_DURATION = 10;
    /** 单项目时长缩放下限比例：再挤也不该把一个大项压到不足真实用时的 35% */
    public static final double MIN_DURATION_RATIO = 0.35;

    private SchedulePlacementMath() {
    }

    /** 生成某并发池的全部候选位置：槽位 × 时段窗口 × 起点档位（按 gridStep 步进） */
    public static List<Placement> placementsOf(Pool pool, List<Window> windows, int gridStep) {
        int step = Math.max(1, gridStep);
        List<Placement> out = new ArrayList<>();
        for (int si = 0; si < pool.slots; si++) {
            String venue = pool.venueOf.get(si);
            for (int wi = 0; wi < windows.size(); wi++) {
                Window w = windows.get(wi);
                for (int off = 0; off + MIN_DURATION <= w.capacity; off += step) {
                    out.add(new Placement(pool.label, si, wi, w.day, w.date, w.slotName, venue,
                            w.startMinute + off, w.startMinute, w.capacity));
                }
            }
        }
        return out;
    }

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

    /** 参赛运动员 id 升序数组（升序是为了让兼项判定能用双指针求交） */
    public static long[] sortedAthletes(Unit u) {
        long[] arr = new long[u.athleteIds.size()];
        int i = 0;
        for (Long id : u.athleteIds) arr[i++] = id;
        Arrays.sort(arr);
        return arr;
    }

    /** 求解结果的残余兼项冲突数（与检测端同口径）；与 solverStat 一起进日志，便于核对「到底规避掉多少」 */
    public static int countResidualClashes(Map<Unit, Placement> solved) {
        List<Unit> placed = new ArrayList<>();
        for (Map.Entry<Unit, Placement> e : solved.entrySet()) {
            if (e.getKey().participants > 0) placed.add(e.getKey());
        }
        int n = 0;
        for (int i = 0; i < placed.size(); i++) {
            for (int j = i + 1; j < placed.size(); j++) {
                Unit a = placed.get(i);
                Unit b = placed.get(j);
                if (!sharesAthlete(a, b)) continue;
                int aS = solved.get(a).getAbsoluteStartMinute();
                int bS = solved.get(b).getAbsoluteStartMinute();
                if (aS < bS + b.duration + ConflictService.CONFLICT_BUFFER_MIN
                        && bS < aS + a.duration + ConflictService.CONFLICT_BUFFER_MIN) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 两个单元是否有共同运动员（小集合驱动，避免全量遍历） */
    public static boolean sharesAthlete(Unit a, Unit b) {
        if (a.athleteIds.isEmpty() || b.athleteIds.isEmpty()) return false;
        Set<Long> small = a.athleteIds.size() <= b.athleteIds.size() ? a.athleteIds : b.athleteIds;
        Set<Long> big = small == a.athleteIds ? b.athleteIds : a.athleteIds;
        for (Long id : small) {
            if (big.contains(id)) return true;
        }
        return false;
    }

    /**
     * 在池的各槽位内扫描候选起点，返回排序最优者：
     * 冲突数最少 → 日期/时段最早 → 起点最早 → 槽位号最小。
     *
     * <p>同槽位内的候选按 interval 步进枚举（而非只取「最早可用」这一个点），
     * 这样才能为了避让兼项冲突而主动后移若干分钟；容量边界与 {@link Cursor#place} 保持一致。</p>
     *
     * @return 最优候选；该池在剩余时段内完全放不下时返回 null
     */
    public static Cand findBestSlot(Unit u, Pool pool, List<Window> windows, int interval,
                                    Map<Long, List<int[]>> busy) {
        Cand best = null;
        for (int si = 0; si < pool.cursors.size(); si++) {
            Cursor c = pool.cursors.get(si);
            for (int wi = 0; wi < windows.size(); wi++) {
                Window w = windows.get(wi);
                int base = c.usedAt(wi);
                int gap = base == 0 ? 0 : interval;             // 与 Cursor.place 的段前间隔口径一致
                if (base + gap + u.duration > w.capacity) continue;
                int start = w.startMinute + base + gap;
                int n = countConflicts(u.athleteIds, w.day, start, u.duration, busy);
                for (int off = base + gap + interval; n > 0 && off + u.duration <= w.capacity; off += interval) {
                    int s2 = w.startMinute + off;
                    int n2 = countConflicts(u.athleteIds, w.day, s2, u.duration, busy);
                    if (n2 < n) {
                        n = n2;
                        start = s2;
                    }
                }
                Cand cand = new Cand(si, wi, start, n);
                if (beats(cand, best)) best = cand;
            }
        }
        return best;
    }

    /**
     * 候选排序：冲突少者优先（尽量避开兼项）→ 窗口靠前 → 起点靠前 → 槽位号小。
     *
     * <p>U26/B23：不再有「是否缩短」这一维——项目时长是固定的编排单位，候选里全是能整块放下的位置。</p>
     */
    public static boolean beats(Cand a, Cand b) {
        if (b == null) return true;
        if (a.conflicts != b.conflicts) return a.conflicts < b.conflicts;
        if (a.windowIdx != b.windowIdx) return a.windowIdx < b.windowIdx;
        if (a.startMinute != b.startMinute) return a.startMinute < b.startMinute;
        return a.slotIdx < b.slotIdx;
    }

    /**
     * 把单元放到「第 day 天 startMinute 起、持续 duration 分钟」会撞上多少条已排项目。
     *
     * <p>与 {@link ConflictService#detectConflicts()} 同口径：必须是同一天，且两段区间重叠、
     * 或间隔小于 {@link ConflictService#CONFLICT_BUFFER_MIN} 分钟才计一次。</p>
     *
     * @param busy 运动员 → 已占用时间段（绝对分钟 = 天 × 1440 + 当日分钟）
     */
    public static int countConflicts(Set<Long> athleteIds, int day, int startMinute, int duration,
                                    Map<Long, List<int[]>> busy) {
        if (athleteIds == null || athleteIds.isEmpty() || busy == null || busy.isEmpty()) return 0;
        int absStart = day * 1440 + startMinute;
        int absEnd = absStart + duration;
        int n = 0;
        for (Long aid : athleteIds) {
            List<int[]> spans = busy.get(aid);
            if (spans == null) continue;
            for (int[] p : spans) {
                int gap = Math.max(absStart - p[1], p[0] - absEnd);   // 对称间隔
                if (gap < ConflictService.CONFLICT_BUFFER_MIN) n++;
            }
        }
        return n;
    }

    /** 单元在所属池内的游标：多单元共用同池时依次占用不同槽位 */
    public static Cursor cursorOf(Pool pool, int unitIdx) {
        return pool.cursors.get(unitIdx % pool.cursors.size());
    }

    public static int intervalOf(Unit u, int defaultInterval) {
        return u.event.getIntervalMinutes() != null ? u.event.getIntervalMinutes() : defaultInterval;
    }

    public static int parseHHmm(String s) {
        if (s == null) return -1;
        String[] p = s.split(":");
        if (p.length < 2) return -1;
        try {
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 性别原值 → 中文标签（用于告警文案） */
    public static String genderLabel(String g) {
        if (g == null) return "未知";
        if ("M".equalsIgnoreCase(g) || "男".equals(g)) return "男子";
        if ("F".equalsIgnoreCase(g) || "女".equals(g)) return "女子";
        return g;
    }

    public static String roundKey(Long eventId, String grade, String startTime, String venue) {
        return (eventId == null ? "" : eventId)
                + "|" + (grade == null ? "" : grade.trim())
                + "|" + (startTime == null ? "" : startTime.trim())
                + "|" + (venue == null ? "" : venue.trim());
    }

    /** 单日可用分钟总数（各天取最大值：各天时段配置通常一致，取最大避免低估容量而误报利用率） */
    public static int dailyCapacityOf(List<Window> windows) {
        Map<Integer, Integer> byDay = new java.util.LinkedHashMap<>();
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
