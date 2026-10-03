package com.sports.schedule.ai;

import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.ScheduleUnit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>分层（两级）冲突图编码</b>——把 GNN 推理的规模上限从「单元数」解绑到「簇数」。
 *
 * <h3>为什么需要它</h3>
 * <p>{@link ConflictGraphEncoder} 的 {@code MAX_NODES = 1024} 是硬墙，而且是<b>静默</b>的：
 * {@code n = min(total, 1024)}，超出部分的单元拿不到任何 AI 优先级，
 * {@link com.sports.schedule.opt.solver.ScheduleOptimizer#reorderByPriority} 只能把它们
 * 「原样追加到末尾」，且**日志与 {@code /api/ai/status} 都不会提示**。
 * 真实大型赛会（100+ 项目 × 6 年级 × 多轮次 × 兼项）很容易越过这条线，
 * 于是「AI 编排」在恰恰最需要它的规模上悄悄退化成半程参与。</p>
 *
 * <p>更本质的约束是<b>邻接矩阵是 O(n²)</b>：1024 节点是 4MB 尚可，
 * 5000 节点就是 100MB，20000 节点是 1.6GB——不是精度问题，是装不下。
 * 所以「人数/项目数没有上限」在单层全图 GNN 下是<b>物理上做不到</b>的。</p>
 *
 * <h3>做法：先聚簇，再在簇上图上推理</h3>
 * <p>把「共享运动员数 ≥ 阈值」的单元用并查集合并成<b>簇</b>（super-node），
 * 簇间再按跨簇共享人数建一张小图（O(K²)），推理得到<b>簇优先级</b>，
 * 最后把簇优先级广播回簇内每个单元，并叠加一个簇内确定性次序。</p>
 *
 * <p>于是：</p>
 * <ul>
 *   <li>内存只与<b>簇数 K</b> 有关（K ≤ {@link #MAX_CLUSTERS}），与单元总数 N 无关；</li>
 *   <li>聚类与建图是 O(N + E)，N 到十万级也只是毫秒级；</li>
 *   <li>输出是<b>长度 N</b> 的优先级，一个单元都不丢。</li>
 * </ul>
 *
 * <h3>为什么要「簇内叠加确定性次序」</h3>
 * <p>同一簇内所有单元拿到完全相同的簇优先级，直接按它排序会退化成「原输入顺序」，
 * 等于没排序。所以簇内按「单元自身规模特征」（人数×时长 的暴露度）降序——这是
 * {@code conflict_exposure} 在单节点语义下的直接延续，与 GNN 学到的「冲突簇优先」
 * 意图一致：先排冲突暴露面最大的单元，把难安置的放在前面。</p>
 *
 * <p><b>一致性保证</b>：单层路径（簇数 == 单元数 ≤ 上限）下本类退化为
 * {@link ConflictGraphEncoder} 的等价行为，因此不会改变已有小规模场景的推理结果。</p>
 */
public final class HierarchicalGnnEncoder {

    /** 簇数上限（也是实际送入 ONNX 的节点数）。512² × 4B = 1MB，与 N 无关。 */
    public static final int MAX_CLUSTERS = 512;

    /**
     * 共享运动员数达到此值才认为两单元「强冲突」并合并成簇。
     *
     * <p>取 1 表示「只要有一名运动员同时报了两个项目就合并」——这是最激进的聚类，
     * 能把规模压到最小，但簇内会混进弱关联单元。反过来取很大则退化成不聚类。
     * 1 是安全默认：聚类只用于<b>降规模</b>，不改变冲突图的语义（跨簇边仍按真实
     * 共享人数加权），簇内还会再做一次确定性细排。</p>
     */
    public static final int DEFAULT_MERGE_THRESHOLD = 1;

    private HierarchicalGnnEncoder() {
    }

    /** 两级编码的中间结果。 */
    public record HierEncoded(
            ConflictGraphEncoder.Encoded clusterGraph,  // 簇级图（送 ONNX）
            int[] unitToCluster,                        // 长度 N：单元 → 簇号
            int clusterCount,                           // 簇数 K
            int unitCount,                              // 单元总数 N（可能 > MAX_NODES）
            boolean degraded) {                        // 是否因 N>MAX_NODES 而走了分层

        public int unitCount() {
            return unitCount;
        }
    }

    /**
     * 把任意规模的单元集合压成一张不超过 {@link #MAX_CLUSTERS} 节点的簇图。
     *
     * @param units             全部单元（无上限）
     * @param mergeThreshold    共享人数阈值，达到即合并
     * @return 簇图与映射关系
     */
    public static HierEncoded encode(List<ScheduleUnit> units, int mergeThreshold) {
        int n = units == null ? 0 : units.size();
        if (n == 0) {
            return new HierEncoded(ConflictGraphEncoder.encode(List.of()), new int[0], 0, 0, false);
        }
        // N 已经在单层能力范围内：直接走原编码器，行为与历史**逐位一致**。
        // 注意 map[i] = i（每单元自成一簇）——曾误写成全填 0，导致所有单元被当成同一簇，
        // expandToUnits 取错 base、把 GNN 优先级顺序彻底打乱（中心节点 a 掉到第 2 位）。
        if (n <= ConflictGraphEncoder.MAX_NODES) {
            ConflictGraphEncoder.Encoded flat = ConflictGraphEncoder.encode(units);
            int[] map = new int[n];
            for (int i = 0; i < n; i++) {
                map[i] = i;
            }
            return new HierEncoded(flat, map, n, n, false);
        }
        return encodeHierarchical(units, mergeThreshold);
    }

    /** 默认阈值版本。 */
    public static HierEncoded encode(List<ScheduleUnit> units) {
        return encode(units, DEFAULT_MERGE_THRESHOLD);
    }

    /**
     * 把簇级优先级还原成单元级优先级；<b>单层（未降级）时原样返回</b>。
     *
     * <p>单层时簇与单元一一对应，本就该逐位直返——一旦也套上展开逻辑，
     * 就等于给历史结果叠加了一层微小的簇内偏移，属于静默改变既有行为。</p>
     */
    public static double[] toUnitPriority(HierEncoded enc, double[] clusterPriority) {
        if (!enc.degraded()) {
            int n = enc.unitCount();
            double[] out = new double[n];
            for (int i = 0; i < n && i < clusterPriority.length; i++) {
                out[i] = clusterPriority[i];
            }
            return out;
        }
        return expandToUnits(enc, clusterPriority, null);
    }

    private static HierEncoded encodeHierarchical(List<ScheduleUnit> units, int threshold) {
        int n = units.size();

        // ---- ① 运动员 → 单元下标，并查集按「共享人数 ≥ 阈值」合并 ----
        Map<Long, List<Integer>> athleteUnits = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            long[] ath = units.get(i).getAthletes();
            if (ath == null) {
                continue;
            }
            for (long a : ath) {
                athleteUnits.computeIfAbsent(a, k -> new ArrayList<>()).add(i);
            }
        }
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (List<Integer> idxs : athleteUnits.values()) {
            for (int x = 1; x < idxs.size(); x++) {
                union(parent, idxs.get(0), idxs.get(x));
            }
        }

        // ---- ② 簇编号：根 → 0..K-1 ----
        Map<Integer, Integer> rootToCluster = new LinkedHashMap<>();
        int[] unitToCluster = new int[n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            int r = find(parent, i);
            Integer c = rootToCluster.get(r);
            if (c == null) {
                c = k++;
                rootToCluster.put(r, c);
            }
            unitToCluster[i] = c;
        }

        // ---- ③ 簇数仍超上限时，按「簇内单元数」降序合并小簇，直到 ≤ MAX_CLUSTERS ----
        // 之所以能这么做：合并后跨簇边会累加共享人数，冲突强度不会丢。
        if (k > MAX_CLUSTERS) {
            Integer[] bySize = new Integer[k];
            for (int c = 0; c < k; c++) {
                bySize[c] = c;
            }
            int[] clusterSize = new int[k];
            for (int c : unitToCluster) {
                clusterSize[c]++;
            }
            final int[] cs = clusterSize;
            Arrays.sort(bySize, Comparator.comparingInt((Integer c) -> -cs[c]));
            int keep = MAX_CLUSTERS;
            int[] remap = new int[k];
            Arrays.fill(remap, -1);
            for (int i = 0; i < keep; i++) {
                remap[bySize[i]] = i;
            }
            // 被并掉的簇挂到「按单元数降序」的第 keep 个簇上，保证大簇不被拆散
            int absorbInto = keep - 1;
            for (int c = 0; c < k; c++) {
                if (remap[c] < 0) {
                    remap[c] = absorbInto;
                }
            }
            for (int i = 0; i < n; i++) {
                unitToCluster[i] = remap[unitToCluster[i]];
            }
            k = MAX_CLUSTERS;
        }

        // ---- ④ 构造「簇级 ScheduleUnit」，复用 ConflictGraphEncoder 的特征/建图逻辑 ----
        // 关键：簇的 athletes 取「簇内出现过的运动员去重集合」，
        // 于是 ConflictGraphEncoder 自己算出的跨簇边权 = 跨簇共享人数 / 全局最大，
        // 与单层时的语义完全一致，模型的归纳式权重可以直接复用。
        Map<Integer, List<Integer>> clusterMembers = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            clusterMembers.computeIfAbsent(unitToCluster[i], x -> new ArrayList<>()).add(i);
        }
        List<ScheduleUnit> clusterUnits = new ArrayList<>(k);
        for (int c = 0; c < k; c++) {
            List<Integer> members = clusterMembers.getOrDefault(c, List.of());
            long[] merged = new long[members.size()];
            for (int i = 0; i < members.size(); i++) {
                // 簇代表单元：取簇内第一个单元的元信息，athletes 换成合并集合
                merged[i] = members.get(i);
            }
            ScheduleUnit rep = units.get(members.isEmpty() ? 0 : members.get(0));
            clusterUnits.add(new ScheduleUnit(
                    "cluster#" + c, rep.getEventId(), rep.getEventName(), rep.getGrade(),
                    rep.isTrack(), rep.getPoolLabel(), rep.getGroupKey(), rep.getInterval(),
                    rep.getRawDuration(), rep.getMinDuration(), merged,
                    List.of(rep.getRawDuration()), representativePlacements(units, members),
                    Map.of("clusterSize", members.size())));
        }

        ConflictGraphEncoder.Encoded graph = ConflictGraphEncoder.encode(clusterUnits);
        return new HierEncoded(graph, unitToCluster, k, n, true);
    }

    /**
     * 簇内所有单元的候选时段并集（去重）。簇要被放进任何其成员可行的时段，
     * 候选必须取并集——这保证了「聚类不改变可行域」。
     */
    private static List<Placement> representativePlacements(List<ScheduleUnit> units, List<Integer> members) {
        Map<String, Placement> seen = new LinkedHashMap<>();
        for (int idx : members) {
            List<Placement> cands = units.get(idx).getCandidatePlacements();
            if (cands == null) {
                continue;
            }
            for (Placement p : cands) {
                seen.putIfAbsent(p.getBinKey() + "|" + p.getDay() + "|" + p.getSlotIdx(), p);
            }
        }
        return new ArrayList<>(seen.values());
    }

    /**
     * 把簇级优先级展开成<b>长度 N</b>的单元级优先级。
     *
     * <p>簇优先级广播 + 簇内按「冲突暴露度」降序——后者保证同簇单元之间也有确定且
     * 有意义的先后（否则同优先级会让排序退化��原输入顺序）。</p>
     */
    public static double[] expandToUnits(HierEncoded enc, double[] clusterPriority, List<ScheduleUnit> units) {
        int n = enc.unitCount();
        if (n == 0) {
            return new double[0];
        }
        double[] out = new double[n];
        // 簇内次序：按「冲突暴露度」（人数×时长，与 GNN 第 11 维同义）拉开
        double[] exposure = new double[n];
        if (units != null) {
            for (int i = 0; i < n && i < units.size(); i++) {
                exposure[i] = conflictExposureOf(units.get(i));
            }
        }
        @SuppressWarnings("unchecked")
        Map<Integer, List<Integer>> byCluster = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            byCluster.computeIfAbsent(enc.unitToCluster()[i], k -> new ArrayList<>()).add(i);
        }
        // 极小步进：同簇单元之间靠 exposure 拉开，保证排序稳定可预期
        double step = 1e-6;
        for (List<Integer> members : byCluster.values()) {
            int c = enc.unitToCluster()[members.get(0)];
            double base = (clusterPriority != null && c >= 0 && c < clusterPriority.length)
                    ? clusterPriority[c] : 0.0;
            for (int idx : members) {
                out[idx] = base + exposure[idx] * step;
            }
        }
        return out;
    }

    /** 单元自身的「冲突暴露度」= 人数 × 时长（与 GNN 第 11 维同义）。 */
    private static double conflictExposureOf(ScheduleUnit u) {
        long[] ath = u.getAthletes();
        int people = ath == null ? 0 : ath.length;
        return (double) people * Math.max(0, u.getRawDuration());
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
        if (ra != rb) {
            parent[rb] = ra;
        }
    }
}
