package com.sports.schedule.ai;

import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.ScheduleUnit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>异构图约束编码器</b>——把「不同约束类别」编码成「不同边类型」，让消息传递按约束本体进行。
 *
 * <h3>为什么不能只用一张加权邻接</h3>
 * <p>现有 {@link ConflictGraphEncoder} 把所有约束压进<b>一个</b> {@code adj[n][n]}，
 * 边权 = 共享运动员数 / 全局最大。这有两个实质问题：</p>
 * <ol>
 *   <li><b>语义混淆</b>：径赛并发池的资源竞争、场地独占、田赛同组同时开赛、
 *       兼项串行、间隔约束——五类性质完全不同的约束，被同一个标量强度表示。
 *       模型无法区分「因为兼项要错开」和「因为场地被占要错开」；</li>
 *   <li><b>二值化信息损失</b>：共享 20 人与共享 1 人只体现在权重 1.0 与 0.05 上，
 *       关系类型信息基本被压掉。</li>
 * </ol>
 *
 * <h3>依据的前沿工作</h3>
 * <p><b>GOAL</b>（arXiv:2605.19119, 2026）：<i>"We introduce a heterogeneous graph encoding in which
 * distinct edge types, corresponding to different classes of constraints, define the message
 * passing structure of the graph neural network, which allows information to propagate
 * selectively according to the ontology of each constraint."</i>
 * 该工作在 Flow Shop / Job Shop / Flexible Job Shop 三种<b>约束结构完全不同</b>的基准上，
 * <b>不改架构</b>即获得 100% 可行性与 MAPE &lt; 0.2%，并比 NSGA-II / MOEA/D 快最多 25×。</p>
 *
 * <p>另有 <b>IC/DC</b>（arXiv:2411.00003）给出训练侧的自监督路径：不需要标注的最优解，
 * 也不需要问题特定的后处理搜索——这让「没有历史优质解」的现实场景同样能训练。</p>
 *
 * <h3>本类的输出</h3>
 * <pre>
 * node_feat    [1, N, 16]            节点特征（与旧模型同维，保持可复用）
 * adj_by_type  [1, T, N, N]          T = {@link #TYPES} 逐类型归一化邻接
 * adj          [1, N, N]             汇总邻接（各类型取 max），**兼容旧模型**
 * mask         [1, N]
 * type_mask    [1, T]                该实例实际存在哪些约束类型（全 0 的通道可跳过）
 * </pre>
 *
 * <h3>规模</h3>
 * <p>邻接仍是 O(N²)（任何全图 GNN 都逃不掉），因此与 {@link HierarchicalGnnEncoder}
 * 配合使用：<b>先用分层把 N 压到簇级，再在簇图上做类型化消息传递</b>——
 * 两者叠加后 N 可达十万级，而 T×N² 的显存只发生在簇级。</p>
 */
public final class ConstraintAwareGraphEncoder {

    /** 约束边类型。<b>顺序即 {@code adj_by_type} 的通道号，改动必须双端同步。</b> */
    public static final String[] TYPES = {
            "ATHLETE",   // 0 共享运动员（兼项）：同一人的项目必须串行
            "POOL",      // 1 同并发池：径赛/田赛各自的并发位竞争
            "VENUE",     // 2 同场地：场地在同一时段只能被一个单元占用
            "GROUP",     // 3 同分组：田赛「同组同时开赛」
            "GRADE",     // 4 同年级：同年级单元共享运动员/资源，冲突面更集中
            "TIME",      // 5 时间邻接：单元时长与间隔耦合（装箱与间隔约束）
    };

    public static final int T = TYPES.length;

    /** 某类边的类型 embedding 维度（每型一个可学习的向量）。 */
    public static final int TYPE_EMBED_DIM = 8;

    /** 允许的最大节点数（单层上限；更大规模走 {@link HierarchicalGnnEncoder}）。 */
    public static final int MAX_NODES = 512;

    public record Encoded(
            float[] nodeFeat,     // [N * 16]
            float[] adjByType,    // [T * N * N]
            float[] adj,          // [N * N]   各类型取 max，供旧模型使用
            float[] mask,         // [N]
            float[] typeMask,     // [T]       该实例实际出现的类型
            int n,                 // 真实节点数
            int[] degreeByType,    // [T]       各类型已建立的边数（可观测性）
            int[] degreeUnion) {  // [N]       合并度数

        public int n() {
            return n;
        }
    }

    private ConstraintAwareGraphEncoder() {
    }

    public static Encoded encode(List<ScheduleUnit> units) {
        int n = units == null ? 0 : Math.min(units.size(), MAX_NODES);
        float[] nodeFeat = new float[n * ConflictGraphEncoder.NODE_FEAT_DIM];
        float[] adjByType = new float[T * n * n];
        float[] adj = new float[n * n];
        float[] mask = new float[n];
        int[] degreeByType = new int[T];
        int[] degreeUnion = new int[n];
        if (n == 0) {
            return new Encoded(nodeFeat, adjByType, adj, mask, new float[T], 0, degreeByType, degreeUnion);
        }

        // ---- 节点特征复用现有编码器（16 维同维，保证旧模型权重可复用） ----
        ConflictGraphEncoder.Encoded base = ConflictGraphEncoder.encode(units);
        System.arraycopy(base.nodeFeat, 0, nodeFeat, 0, Math.min(nodeFeat.length, base.nodeFeat.length));
        for (int i = 0; i < n; i++) {
            mask[i] = 1f;
        }

        // ---- 逐类型建边 ----
        // 0 ATHLETE：共享运动员，权重 = 共享人数
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
        Map<Long, Integer> sharedCount = new LinkedHashMap<>();
        for (List<Integer> idxs : athleteUnits.values()) {
            for (int x = 0; x < idxs.size(); x++) {
                for (int y = x + 1; y < idxs.size(); y++) {
                    long key = pairKey(idxs.get(x), idxs.get(y), n);
                    sharedCount.merge(key, 1, Integer::sum);
                }
            }
        }
        int maxShared = sharedCount.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        for (Map.Entry<Long, Integer> e : sharedCount.entrySet()) {
            int a = (int) (e.getKey() / n);
            int b = (int) (e.getKey() % n);
            putEdge(adjByType, adj, degreeByType, degreeUnion, 0, a, b, n,
                    e.getValue() / (float) maxShared);
        }

        // 1 POOL / 2 VENUE / 3 GROUP / 4 GRADE：同组即连边，权重由该组的资源紧张度给出
        buildGroupEdges(units, n, 1, poolKeyExtractor(), adjByType, adj, degreeByType, degreeUnion);
        buildGroupEdges(units, n, 2, venueKeyExtractor(), adjByType, adj, degreeByType, degreeUnion);
        buildGroupEdges(units, n, 3, groupKeyExtractor(), adjByType, adj, degreeByType, degreeUnion);
        buildGroupEdges(units, n, 4, gradeKeyExtractor(), adjByType, adj, degreeByType, degreeUnion);

        // 5 TIME：时长耦合——两个单元时长之和超过其公共候选时段容量时冲突
        Map<String, List<Integer>> byCandidate = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            List<Placement> cands = units.get(i).getCandidatePlacements();
            if (cands == null) {
                continue;
            }
            Set<String> keys = new LinkedHashSet<>();
            for (Placement p : cands) {
                keys.add(p.getBinKey());
            }
            for (String k : keys) {
                byCandidate.computeIfAbsent(k, x -> new ArrayList<>()).add(i);
            }
        }
        for (List<Integer> idxs : byCandidate.values()) {
            if (idxs.size() < 2) {
                continue;
            }
            int cap = 0;
            for (int i : idxs) {
                List<Placement> cands = units.get(i).getCandidatePlacements();
                if (cands != null && !cands.isEmpty() && cands.get(0).getWindowCapacity() > cap) {
                    cap = cands.get(0).getWindowCapacity();
                }
            }
            for (int x = 0; x < idxs.size(); x++) {
                for (int y = x + 1; y < idxs.size(); y++) {
                    int a = idxs.get(x), b = idxs.get(y);
                    int need = units.get(a).getRawDuration() + units.get(b).getRawDuration()
                            + Math.max(units.get(a).getInterval(), units.get(b).getInterval());
                    if (cap > 0 && need > cap) {
                        // 超容量越多，边越强（上限 1.0）
                        float w = Math.min(1f, need / (float) cap);
                        putEdge(adjByType, adj, degreeByType, degreeUnion, 5, a, b, n, w);
                    }
                }
            }
        }

        // ---- 类型掩码：标出该实例真实存在哪些约束类型 ----
        float[] typeMask = new float[T];
        for (int t = 0; t < T; t++) {
            typeMask[t] = degreeByType[t] > 0 ? 1f : 0f;
        }
        return new Encoded(nodeFeat, adjByType, adj, mask, typeMask, n, degreeByType, degreeUnion);
    }

    private interface KeyExtractor {
        String key(ScheduleUnit u, Placement p);
    }

    private static KeyExtractor poolKeyExtractor() {
        return (u, p) -> u.getPoolLabel() != null ? u.getPoolLabel() : (p != null ? p.getBinKey() : null);
    }

    private static KeyExtractor venueKeyExtractor() {
        return (u, p) -> p == null ? null : (p.getVenue() + "@" + p.getDay());
    }

    private static KeyExtractor groupKeyExtractor() {
        return (u, p) -> u.getGroupKey();
    }

    private static KeyExtractor gradeKeyExtractor() {
        return (u, p) -> u.getGrade();
    }

    /**
     * 同类资源的两两连边。权重 = 该资源上「单元数 / 全体单元数」的占比，
     * 反映资源紧张程度——同一时段挤 50 个单元比挤 3 个冲突面大得多。
     */
    private static void buildGroupEdges(List<ScheduleUnit> units, int n, int type,
                                        KeyExtractor ex, float[] adjByType, float[] adj,
                                        int[] degreeByType, int[] degreeUnion) {
        Map<String, List<Integer>> byKey = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            List<Placement> cands = units.get(i).getCandidatePlacements();
            Set<String> keys = new LinkedHashSet<>();
            for (Placement p : cands == null ? List.<Placement>of() : cands) {
                String k = ex.key(units.get(i), p);
                if (k != null) {
                    keys.add(k);
                }
            }
            // 单元自身的属性（pool/grade/group）即使没有候选时段也要参与
            String self = ex.key(units.get(i), null);
            if (self != null) {
                keys.add(self);
            }
            for (String k : keys) {
                byKey.computeIfAbsent(k, x -> new ArrayList<>()).add(i);
            }
        }
        for (List<Integer> idxs : byKey.values()) {
            if (idxs.size() < 2) {
                continue;
            }
            float pressure = Math.min(1f, idxs.size() / (float) Math.max(1, n));
            for (int x = 0; x < idxs.size(); x++) {
                for (int y = x + 1; y < idxs.size(); y++) {
                    putEdge(adjByType, adj, degreeByType, degreeUnion, type,
                            idxs.get(x), idxs.get(y), n, pressure);
                }
            }
        }
    }

    private static void putEdge(float[] adjByType, float[] adj, int[] degreeByType, int[] degreeUnion,
                               int type, int a, int b, int n, float w) {
        if (a == b || w <= 0f) {
            return;
        }
        adjByType[(int) ((long) type * n * n + (long) a * n + b)] = w;
        adjByType[(int) ((long) type * n * n + (long) b * n + a)] = w;
        // 汇总邻接取各类型最大值（n ≤ MAX_NODES=512，int 索引足够；用 long 算偏移再收窄）
        int ia = (int) ((long) a * n + b);
        int ib = (int) ((long) b * n + a);
        if (adj[ia] < w) {
            adj[ia] = w;
        }
        if (adj[ib] < w) {
            adj[ib] = w;
        }
        degreeByType[type]++;
        degreeUnion[a]++;
        degreeUnion[b]++;
    }

    private static long pairKey(int a, int b, int n) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        return (long) lo * n + hi;
    }
}
