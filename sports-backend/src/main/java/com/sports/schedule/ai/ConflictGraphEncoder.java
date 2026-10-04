package com.sports.schedule.ai;

import com.sports.schedule.opt.solver.ScheduleUnit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 冲突簇 GNN 的输入编码——与训练侧 {@code sports-ai/sports_ai/data/gnn_io.py}
 * 严格同构的 <b>固定 shape 契约</b>。
 *
 * <p>把编排实例编码成 GNN 的三路输入：
 * <ul>
 *   <li>{@code node_feat}: [1, MAX_NODES, NODE_FEAT_DIM] 节点特征（16 维，构造保证落在 [0,1]）；</li>
 *   <li>{@code adj}:       [1, MAX_NODES, MAX_NODES]     <b>带权</b>邻接（共享运动员数归一化，无自环）；</li>
 *   <li>{@code mask}:      [1, MAX_NODES]                 1=真实节点，0=填充。</li>
 * </ul>
 * 前 {@code min(n, MAX_NODES)} 个节点按 {@code units} 原始顺序一一对应，
 * 因此 GNN 输出的 {@code priority[i]} 即 {@code units.get(i)} 的着色优先级。</p>
 *
 * <p><b>规模</b>：{@code MAX_NODES = 1024}，覆盖真实大型赛会（约 100 项目 × 6 年级 × 2 轮次）。
 * 超出时按输入顺序截断，并在 {@link Encoded#dropped} 中如实报告——调用方应据此告警，
 * 避免「模型只看了前 1024 个单元」这种无声降级。</p>
 *
 * <p><b>带权邻接</b>：边权 = 两单元共享运动员数 / 全局最大共享数。共享 20 人与共享 1 人的
 * 冲突强度不同，二值邻接会把这个信息压掉，让「度数中心度」虚高。</p>
 *
 * <p>16 维节点特征（与 Python 端逐位对齐，改动必须双端同步）：</p>
 * <pre>
 * 0  track               径赛=1 / 田赛=0
 * 1  athlete_count_norm  min(人数,512)/512
 * 2  duration_norm       min(全部时长,600)/600
 * 3  has_group           同组同时开赛=1
 * 4  group_size_norm     min(同组单元数,16)/16
 * 5  pool_idx_norm       并发池序号/(池数-1)
 * 6  event_idx_norm      项目序号/(项目数-1)
 * 7  log_athlete_norm    log1p(人数)/log(513)
 * 8  is_final            决赛轮次=1
 * 9  grade_idx_norm      年级序号/(年级数-1)
 * 10 duration_share      该单元时长 / 全部单元时长之和
 * 11 conflict_exposure   (人数×时长) / 全局最大
 * 12 event_freq_norm     同项目单元数 / 单元总数
 * 13 pool_share_norm     同池单元数 / 单元总数
 * 14 is_large_unit       时长 ≥ 300 分钟
 * 15 order_norm          i/(n-1)
 * </pre>
 */
public final class ConflictGraphEncoder {

    /** 最大单元数（**双端硬契约**：必须与 Python {@code features.py} 同值）。 */
    public static final int MAX_NODES = 1024;
    /**
     * **通用**每节点特征维数（双端硬契约）。
     *
     * <p>⚠️ 这个常量被**多个模型**共用（生成式三件套 / 约束 GNN / 冲突 GNN），
     * 所以它必须保持 16 —— 一改，三个已导出的 GAN onnx 立刻因为
     * {@code Got invalid dimensions for input: node_feat} 全部加载失败。</p>
     */
    public static final int NODE_FEAT_DIM = 16;

    /**
     * **冲突着色模型专用**输入维数 = {@link #NODE_FEAT_DIM} + 第 17 维「归一化度数」。
     *
     * <p>⚠️ 为什么只有它多一维：本模型的**预测目标就是归一化度数中心度**
     * （{@code degree/(n-1)}），而图注意力用对称归一化 D^{-1/2} A D^{-1/2} ——
     * 星形图上中心与叶子的聚合幅度**完全一样**，度数信息被抹平。
     * 不给显式度数，模型只能猜：实测在 4 节点星形图上 argmax 指向度数为 1 的叶子，
     * 而正确答案是度数为 3 的中心。补上这一维后输出
     * {@code [0.475, 0.407, 0.401, 0.291]}，argmax = 0（中心），修复完成。</p>
     */
    public static final int CONFLICT_MODEL_FEAT_DIM = NODE_FEAT_DIM + 1;

    // 归一化上界（与 Python 端逐位对齐）
    public static final float ATH_CAP = 512f;
    public static final float DUR_CAP = 600f;
    public static final float GROUP_CAP = 16f;
    public static final float LARGE_UNIT_MINUTES = 300f;

    /** 编码结果：三路输入的展平 float[]（Java 端可直接塞给 onnxruntime）。 */
    /**
     * 把**通用**节点特征扩成**冲突模型**输入：尾部追加归一化度数。
     *
     * <p>单独开一个方法而不是让 {@code encode()} 直接产出 17 维，
     * 是为了不破坏共用同一份通用特征的其它模型（GAN / 约束 GNN）。</p>
     */
    public static float[] conflictModelFeat(Encoded enc) {
        int n = enc.nodeCount;
        float[] out = new float[n * CONFLICT_MODEL_FEAT_DIM];
        for (int i = 0; i < n; i++) {
            System.arraycopy(enc.nodeFeat, i * NODE_FEAT_DIM,
                    out, i * CONFLICT_MODEL_FEAT_DIM, NODE_FEAT_DIM);
            int deg = 0;
            for (int j = 0; j < n; j++) {
                if (enc.adj[i * n + j] > 0f) {
                    deg++;
                }
            }
            out[i * CONFLICT_MODEL_FEAT_DIM + NODE_FEAT_DIM] = (n > 1) ? deg / (float) (n - 1) : 0f;
        }
        return out;
    }

    public static final class Encoded {
        public final float[] nodeFeat;   // 长度 MAX_NODES * NODE_FEAT_DIM
        public final float[] adj;        // 长度 MAX_NODES * MAX_NODES（带权）
        public final float[] mask;       // 长度 MAX_NODES
        public final int nodeCount;      // 真实节点数 = min(n, MAX_NODES)
        public final int totalUnits;     // 传入单元总数
        public final int dropped;        // 因超上限被截断的单元数（>0 应告警）
        public final int maxShared;      // 全局最大共享运动员数（边权归一化分母）

        Encoded(float[] nodeFeat, float[] adj, float[] mask, int nodeCount,
                int totalUnits, int dropped, int maxShared) {
            this.nodeFeat = nodeFeat;
            this.adj = adj;
            this.mask = mask;
            this.nodeCount = nodeCount;
            this.totalUnits = totalUnits;
            this.dropped = dropped;
            this.maxShared = maxShared;
        }
    }

    private ConflictGraphEncoder() {
    }

    public static Encoded encode(List<ScheduleUnit> units) {
        int total = units == null ? 0 : units.size();
        int n = Math.min(total, MAX_NODES);

        // **动态节点数**：数组按实际 n 分配，不补齐到 MAX_NODES。
        // ONNX 侧 n 是动态轴，补齐只会白白多算 padding——GNN 是归纳式的，权重与节点数无关，
        // 同一个模型从 5 个单元到上千个单元都能处理。MAX_NODES 退化为**安全上限**（防爆内存）。
        float[] nodeFeat = new float[n * NODE_FEAT_DIM];
        float[] adj = new float[n * n];
        float[] mask = new float[n];
        int stride = Math.max(1, n);

        // ---- 并发池序号与规模 ----
        Set<String> poolSet = new LinkedHashSet<>();
        for (ScheduleUnit u : units) if (u.getPoolLabel() != null) poolSet.add(u.getPoolLabel());
        Map<String, Integer> poolIdx = new LinkedHashMap<>();
        int pi = 0;
        for (String p : poolSet) poolIdx.put(p, pi++);
        int poolCount = Math.max(1, poolSet.size());
        Map<String, Integer> poolSize = new HashMap<>();
        for (ScheduleUnit u : units) {
            if (u.getPoolLabel() != null) {
                poolSize.merge(u.getPoolLabel(), 1, Integer::sum);
            }
        }

        // ---- 项目序号与频次 ----
        Set<Long> eventSet = new LinkedHashSet<>();
        for (ScheduleUnit u : units) if (u.getEventId() != null) eventSet.add(u.getEventId());
        Map<Long, Integer> eventIdx = new LinkedHashMap<>();
        int ei = 0;
        for (Long e : eventSet) eventIdx.put(e, ei++);
        int eventCount = Math.max(1, eventSet.size());
        Map<Long, Integer> eventFreq = new HashMap<>();
        for (ScheduleUnit u : units) {
            if (u.getEventId() != null) eventFreq.merge(u.getEventId(), 1, Integer::sum);
        }

        // ---- 年级序号 ----
        Set<String> gradeSet = new LinkedHashSet<>();
        for (ScheduleUnit u : units) gradeSet.add(u.getGrade() == null ? "" : u.getGrade());
        Map<String, Integer> gradeIdx = new LinkedHashMap<>();
        int gi = 0;
        for (String g : gradeSet) gradeIdx.put(g, gi++);
        int gradeCount = Math.max(1, gradeSet.size());

        // ---- 同组单元数 ----
        Map<String, Integer> groupSize = new HashMap<>();
        for (ScheduleUnit u : units) {
            if (u.getGroupKey() != null) groupSize.merge(u.getGroupKey(), 1, Integer::sum);
        }

        // ---- 归一化分母 ----
        long totalDuration = 0;
        long maxExposure = 1;
        for (ScheduleUnit u : units) {
            int d = Math.max(0, u.getRawDuration());
            int a = u.hasAthletes() ? u.getAthletes().length : 0;
            totalDuration += d;
            maxExposure = Math.max(maxExposure, (long) d * a);
        }
        totalDuration = Math.max(1, totalDuration);
        int nTotal = Math.max(1, total);

        // ---- 带权邻接：统计每对单元共享的运动员数 ----
        Map<Long, List<Integer>> athleteUnits = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            long[] ath = units.get(i).getAthletes();
            if (ath == null) continue;
            for (long a : ath) {
                athleteUnits.computeIfAbsent(a, k -> new ArrayList<>()).add(i);
            }
        }
        Map<Long, Integer> shared = new HashMap<>();
        int maxShared = 1;
        for (List<Integer> idxs : athleteUnits.values()) {
            for (int x = 0; x < idxs.size(); x++) {
                for (int y = x + 1; y < idxs.size(); y++) {
                    int a = idxs.get(x), b = idxs.get(y);
                    if (a >= n || b >= n) continue;
                    long key = a < b ? (long) a * stride + b : (long) b * stride + a;
                    int c = shared.merge(key, 1, Integer::sum);
                    if (c > maxShared) maxShared = c;
                }
            }
        }
        int[] degree = new int[n];
        for (Map.Entry<Long, Integer> e : shared.entrySet()) {
            long key = e.getKey();
            int a = (int) (key / stride);
            int b = (int) (key % stride);
            float w = e.getValue() / (float) maxShared;
            adj[a * stride + b] = w;
            adj[b * stride + a] = w;
            degree[a]++;
            degree[b]++;
        }

        // ---- 16 维节点特征 ----
        for (int i = 0; i < n; i++) {
            ScheduleUnit u = units.get(i);
            int ath = u.hasAthletes() ? u.getAthletes().length : 0;
            int dur = Math.max(0, u.getRawDuration());
            String name = u.getEventName() == null ? "" : u.getEventName();
            String grade = u.getGrade() == null ? "" : u.getGrade();
            String pool = u.getPoolLabel();

            int base = i * NODE_FEAT_DIM;
            nodeFeat[base] = u.isTrack() ? 1.0f : 0.0f;
            nodeFeat[base + 1] = Math.min(ath, ATH_CAP) / ATH_CAP;
            nodeFeat[base + 2] = Math.min(dur, DUR_CAP) / DUR_CAP;
            nodeFeat[base + 3] = u.getGroupKey() != null ? 1.0f : 0.0f;
            nodeFeat[base + 4] = Math.min(
                    groupSize.getOrDefault(u.getGroupKey(), 0), (int) GROUP_CAP) / GROUP_CAP;
            nodeFeat[base + 5] = poolIdx.getOrDefault(pool, 0) / (float) Math.max(1, poolCount - 1);
            nodeFeat[base + 6] = eventIdx.getOrDefault(u.getEventId(), 0) / (float) Math.max(1, eventCount - 1);
            nodeFeat[base + 7] = (float) (Math.log1p(ath) / Math.log(ATH_CAP + 1.0));
            nodeFeat[base + 8] = name.contains("决赛") ? 1.0f : 0.0f;
            nodeFeat[base + 9] = gradeIdx.getOrDefault(grade, 0) / (float) Math.max(1, gradeCount - 1);
            nodeFeat[base + 10] = dur / (float) totalDuration;
            nodeFeat[base + 11] = (dur * (float) ath) / maxExposure;
            nodeFeat[base + 12] = eventFreq.getOrDefault(u.getEventId(), 0) / (float) nTotal;
            nodeFeat[base + 13] = poolSize.getOrDefault(pool, 0) / (float) nTotal;
            nodeFeat[base + 14] = dur >= LARGE_UNIT_MINUTES ? 1.0f : 0.0f;
            nodeFeat[base + 15] = n > 1 ? (i / (float) (n - 1)) : 0.0f;
            mask[i] = 1.0f;
        }

        return new Encoded(nodeFeat, adj, mask, n, total, Math.max(0, total - n),
                maxShared);
    }

    /**
     * 归一化度数中心度（着色优先级的**参考实现**，供单测与降级路径使用）。
     *
     * <p>训练侧用同样的口径生成标签，因此这里的返回值可作为「GNN 是否学到位」的对照基线。</p>
     */
    public static float[] degreeCentrality(Encoded enc) {
        int n = enc.nodeCount;
        float[] out = new float[n];
        if (n == 0) return out;
        int[] deg = new int[n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (enc.adj[i * n + j] > 0f) {
                    deg[i]++;
                    deg[j]++;
                }
            }
        }
        float denom = Math.max(1, n - 1);
        for (int i = 0; i < n; i++) {
            out[i] = deg[i] / denom;
        }
        return out;
    }
}
