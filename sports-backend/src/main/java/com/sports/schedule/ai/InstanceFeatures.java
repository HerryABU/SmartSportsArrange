package com.sports.schedule.ai;

import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.ScheduleUnit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 实例特征提取（兼项共现统计层）——与训练侧 {@code sports-ai/sports_ai/data/features.py}
 * 严格同构的 <b>16 维特征契约</b>。
 *
 * <p>训练侧与推理侧必须用<b>完全相同的口径</b>算出同一个 16 维向量，否则训练出的
 * ONNX 模型喂给 Java 推理时语义就对不上。特征全部是「原始可计算量」、<b>不做归一化</b>
 * ——归一化在导出 ONNX 时已作为模型第一层固化（见训练侧 {@code export_onnx.py}），
 * 因此本类只喂原始特征。</p>
 *
 * <p>冲突图口径与 {@code EventCooccurrenceService} 一致：顶点 = 单元（项目×年级），
 * 边 = 同一运动员同时出现在两个单元。</p>
 */
public final class InstanceFeatures {

    public static final int N_FEATURES = 16;

    /** 16 维特征名（顺序固定，与训练侧 FEATURE_NAMES 逐位对齐） */
    public static final String[] FEATURE_NAMES = {
            "unit_count", "demand_minutes", "supply_minutes", "tension_ratio",
            "multi_event_athlete_ratio", "athlete_count", "conflict_edges", "conflict_density",
            "conflict_components", "max_degree", "avg_degree", "pool_count",
            "day_count", "avg_duration", "duration_cv", "group_count"
    };

    private InstanceFeatures() {
    }

    /**
     * 从编排实例提取 16 维特征向量（原始值，未归一化）。
     */
    public static double[] extract(List<ScheduleUnit> units, List<Placement> placements) {
        int n = units == null ? 0 : units.size();

        // 0 unit_count / 1 demand / 13 avg_duration / 14 duration_cv / 15 group_count / 11 pool_count
        double demand = 0;
        double durSum = 0;
        int durCount = 0;
        Set<String> pools = new LinkedHashSet<>();
        Set<String> groups = new LinkedHashSet<>();
        for (ScheduleUnit u : units) {
            int d = u.getRawDuration();
            if (d > 0) {
                demand += d;
                durSum += d;
                durCount++;
            }
            if (u.getPoolLabel() != null) pools.add(u.getPoolLabel());
            if (u.getGroupKey() != null) groups.add(u.getGroupKey());
        }
        double avgDuration = durCount > 0 ? durSum / durCount : 0.0;
        double durationCv = 0.0;
        if (durCount > 0 && avgDuration > 0) {
            double var = 0;
            for (ScheduleUnit u : units) {
                int d = u.getRawDuration();
                if (d > 0) var += (d - avgDuration) * (d - avgDuration);
            }
            durationCv = Math.sqrt(var / durCount) / avgDuration;
        }

        // 2 supply：去重并发位容量之和
        Map<String, Integer> binCap = new LinkedHashMap<>();
        if (placements != null) {
            for (Placement p : placements) {
                binCap.putIfAbsent(p.getBinKey(), p.getWindowCapacity());
            }
        }
        double supply = 0;
        for (Integer c : binCap.values()) supply += c != null ? c : 0;

        double tension = supply > 0 ? demand / supply : 0.0;
        double dayCount = 0;
        if (placements != null) {
            Set<Integer> days = new LinkedHashSet<>();
            for (Placement p : placements) days.add(p.getDay());
            dayCount = days.size();
        }

        // 5 athlete_count / 4 multi_ratio —— 运动员 → 所在单元下标
        Map<Long, List<Integer>> athleteUnits = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            long[] ath = units.get(i).getAthletes();
            if (ath == null) continue;
            for (long a : ath) {
                athleteUnits.computeIfAbsent(a, k -> new ArrayList<>()).add(i);
            }
        }
        int athleteCount = athleteUnits.size();
        int multi = 0;
        for (List<Integer> idxs : athleteUnits.values()) {
            if (idxs.size() >= 2) multi++;
        }
        double multiRatio = athleteCount > 0 ? multi * 1.0 / athleteCount : 0.0;

        // 6..10 冲突图（边去重 + 度数 + 连通分量）
        int[] degree = new int[n];
        int edges = 0;
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        Set<Long> edgeSet = new LinkedHashSet<>();
        for (List<Integer> idxs : athleteUnits.values()) {
            for (int x = 0; x < idxs.size(); x++) {
                for (int y = x + 1; y < idxs.size(); y++) {
                    int a = idxs.get(x), b = idxs.get(y);
                    if (a > b) { int t = a; a = b; b = t; }
                    edgeSet.add((long) a * n + b);
                }
            }
        }
        for (Long e : edgeSet) {
            int a = (int) (e / n);
            int b = (int) (e % n);
            degree[a]++;
            degree[b]++;
            edges++;
            union(parent, a, b);
        }
        double density = n >= 2 ? 2.0 * edges / (n * (n - 1)) : 0.0;
        int components = 0;
        for (int i = 0; i < n; i++) {
            if (find(parent, i) == i) components++;
        }
        int maxDegree = 0;
        double degreeSum = 0;
        for (int d : degree) {
            maxDegree = Math.max(maxDegree, d);
            degreeSum += d;
        }
        double avgDegree = n > 0 ? degreeSum / n : 0.0;

        return new double[]{
                n, demand, supply, round4(tension), round4(multiRatio),
                athleteCount, edges, round4(density), components, maxDegree,
                round4(avgDegree), pools.size(), dayCount, round2(avgDuration), round4(durationCv),
                groups.size()
        };
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) parent[rb] = ra;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
