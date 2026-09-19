package com.sports.schedule.core;

import com.sports.entity.Event;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 赛程可行性 / 时长 / 压缩的纯静态分析工具（从 {@code ScheduleService} 抽取，行为零变化）。
 *
 * <p>含：项目内并发判定、时间窗容量可行性预检、单池可行性量化、按池等比缩放、压缩亏损报表。
 * 全部为无状态静态方法，不依赖任何 Repository 或 Spring 上下文，可独立单测。</p>
 */
public final class ScheduleAnalysisMath {

    private ScheduleAnalysisMath() {
    }

    /** 与 {@code ScheduleService#intVal} 同语义的本地副本：从 Object 安全取 int（默认兜底） */
    private static int intVal(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(o.toString().trim());
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * 项目内并发人数（不含任何场地并行上限）：
     * 显式 concurrency 优先；田赛回退 groupSize（每组工位）；径赛回退 groupSize（泳道）> laneCount > defaultLanes。
     */
    public static int concurrencyOf(Event e) {
        Integer c = e.getConcurrency();
        if (c != null && c > 0) return c;
        if (Boolean.FALSE.equals(e.getTrack())) {
            // 田赛回退链：显式 groupSize（每组次几人，如田赛工位数）> 1
            Integer gs = e.getGroupSize();
            if (gs != null && gs > 0) return gs;
            return 1;
        }
        // 径赛回退链：groupSize（每组次几人/泳道数，如游泳）> 道次数 > 默认道次
        Integer gs = e.getGroupSize();
        if (gs != null && gs > 0) return gs;
        Integer lc = e.getLaneCount();
        if (lc != null && lc > 0) return lc;
        return e.getDefaultLanes() != null && e.getDefaultLanes() > 0 ? e.getDefaultLanes() : 8;
    }

    /**
     * 时间窗容量可行性预检（B05/U06）：把「需求」与「供给」分别量化后对比。
     *
     * @see #poolFeasibility
     */
    public static Map<String, Object> assessFeasibility(List<Unit> units, List<Window> windows,
                                                        int trackSlots, int fieldSlots, int interval) {
        int totalCapacity = windows.stream().mapToInt(w -> w.capacity).sum();
        int trackNeed = 0, trackCount = 0;
        int fieldNeed = 0, fieldCount = 0;
        for (Unit u : units) {
            if (u.participants <= 0) continue;
            int need = u.rawDuration + interval;   // 含项目间间隔
            if (u.track) { trackNeed += need; trackCount++; } else { fieldNeed += need; fieldCount++; }
        }

        Map<String, Object> track = poolFeasibility("径赛", trackNeed, trackCount, totalCapacity, trackSlots, windows.size());
        Map<String, Object> field = poolFeasibility("田赛", fieldNeed, fieldCount, totalCapacity, fieldSlots, windows.size());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("windowCount", windows.size());
        m.put("windowCapacityMinutes", totalCapacity);
        m.put("track", track);
        m.put("field", field);
        m.put("feasible", Boolean.TRUE.equals(track.get("feasible")) && Boolean.TRUE.equals(field.get("feasible")));
        return m;
    }

    /** 单池可行性量化：供给 = 总容量 × 并发位数；并给出可执行结论（该池至少需几位并发） */
    public static Map<String, Object> poolFeasibility(String label, int need, int unitCount,
                                                      int totalCapacity, int slots, int windowCount) {
        int supply = totalCapacity * Math.max(1, slots);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("unitCount", unitCount);
        m.put("slots", Math.max(1, slots));
        m.put("windowCount", windowCount);
        m.put("requiredMinutes", need);
        m.put("supplyMinutes", supply);
        m.put("deficitMinutes", Math.max(0, need - supply));
        m.put("feasible", need <= supply);
        m.put("requiredSlots", (int) Math.ceil((double) need / Math.max(1, totalCapacity)));
        return m;
    }

    /**
     * 把「真实估算时长」按各并发池的可用容量<b>等比公平缩放</b>（U24/B21）。
     * 口径与放置同源（{@link SchedulePlacementMath#greedyPacks}），不会出现「预判装得下、实际却丢项目」。
     */
    public static void fitDurationsToPools(List<Unit> units, Map<String, Object> feasibility, int interval) {
        for (String key : List.of("track", "field")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> pool = (Map<String, Object>) feasibility.get(key);
            if (pool == null) continue;
            int need = intVal(pool.get("requiredMinutes"), 0);
            int supply = intVal(pool.get("supplyMinutes"), 0);
            if (need <= 0 || need <= supply) continue;   // 容量够 → 保留真实用时
            boolean isTrack = "track".equals(key);
            long totalRaw = 0;
            int unitsInPool = 0;
            for (Unit u : units) {
                if (u.participants <= 0 || u.track != isTrack) continue;
                totalRaw += u.rawDuration;
                unitsInPool++;
            }
            if (totalRaw <= 0) continue;
            int slotsInPool = Math.max(1, intVal(pool.get("slots"), 1));
            int windowCount = Math.max(1, intVal(pool.get("windowCount"), 1));
            int capacityPerWindow = Math.max(1, supply / slotsInPool / windowCount);
            // 二分搜索的上界取「纯总量口径」；真正的可行性判定交给 greedyPacks。
            double kSum = (supply - (long) unitsInPool * interval) / (double) totalRaw;
            double hi = Math.min(1.0, kSum);
            double lo = 0.05;
            double k = lo;
            long[] probeDur = new long[unitsInPool];
            for (int it = 0; it < 24; it++) {              // 24 次足够收敛到 1e-7
                double mid = (lo + hi) / 2;
                int idx = 0;
                for (Unit u : units) {
                    if (u.participants <= 0 || u.track != isTrack) continue;
                    probeDur[idx++] = Math.max(SchedulePlacementMath.MIN_DURATION, (int) Math.floor(u.rawDuration * mid));
                }
                if (SchedulePlacementMath.greedyPacks(probeDur, slotsInPool, windowCount, capacityPerWindow, interval)) {
                    k = mid;
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            for (Unit u : units) {
                if (u.participants <= 0 || u.track != isTrack) continue;
                // 缩放下限 = SchedulePlacementMath.MIN_DURATION：不可拿 defaultDurationMinutes 当下限。
                int scaled = Math.max(SchedulePlacementMath.MIN_DURATION, (int) Math.floor(u.rawDuration * k));
                int target = u.explicitMaxDuration > 0 ? Math.min(scaled, u.explicitMaxDuration) : scaled;
                u.duration = Math.min(u.duration, Math.max(SchedulePlacementMath.MIN_DURATION, target));
            }
            pool.put("scalePercent", Math.round(k * 1000.0) / 10.0);
        }
    }

    /**
     * 压缩亏损量化（B05/U06）：把每个「实际用时 &lt; 未压缩预计用时」的项目列成表，
     * 输出需求分钟、实给分钟、压缩比与缺口分钟，供现场判断哪一项最危险。
     */
    public static List<Map<String, Object>> compressionReport(List<Unit> units) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Unit u : units) {
            if (u.participants <= 0 || u.duration >= u.rawDuration) continue;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("eventId", u.event.getId());
            c.put("eventName", u.event.getName());
            c.put("grade", u.grade);
            c.put("track", u.track);
            c.put("participants", u.participants);
            c.put("concurrency", u.concurrency);
            c.put("rounds", u.rounds);
            c.put("requiredMinutes", u.rawDuration);
            c.put("givenMinutes", u.duration);
            c.put("deficitMinutes", u.rawDuration - u.duration);
            c.put("ratioPercent", Math.round(u.duration * 1000.0 / u.rawDuration) / 10.0);
            out.add(c);
        }
        out.sort(Comparator.comparingInt(c -> -intVal(c.get("deficitMinutes"), 0)));
        return out;
    }
}
