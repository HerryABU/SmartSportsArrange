package com.sports.schedule.ai;

import com.sports.schedule.opt.solver.ScheduleUnit;

import java.util.ArrayList;
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
 *   <li>{@code node_feat}: [1, MAX_NODES, NODE_FEAT_DIM] 节点特征；</li>
 *   <li>{@code adj}:       [1, MAX_NODES, MAX_NODES]     二值邻接（无自环、无归一化）；</li>
 *   <li>{@code mask}:      [1, MAX_NODES]                 1=真实节点，0=填充。</li>
 * </ul>
 * 前 {@code min(n, MAX_NODES)} 个节点按 {@code units} 原始顺序一一对应，
 * 因此 GNN 输出的 {@code priority[i]} 即 {@code units.get(i)} 的着色优先级。</p>
 *
 * <p>节点特征 8 维全部由构造保证落在 [0,1]（无需额外归一化）：</p>
 * <pre>
 * 0 track 1 athlete_count_norm 2 duration_norm 3 has_group
 * 4 group_size_norm 5 pool_idx_norm 6 event_idx_norm 7 log_athlete_norm
 * </pre>
 */
public final class ConflictGraphEncoder {

    public static final int MAX_NODES = 256;
    public static final int NODE_FEAT_DIM = 8;

    /** 编码结果：三路输入的展平 float[]（Java 端可直接塞给 onnxruntime）。 */
    public static final class Encoded {
        public final float[] nodeFeat;   // 长度 MAX_NODES * NODE_FEAT_DIM
        public final float[] adj;        // 长度 MAX_NODES * MAX_NODES
        public final float[] mask;       // 长度 MAX_NODES
        public final int nodeCount;      // 真实节点数 = min(n, MAX_NODES)

        Encoded(float[] nodeFeat, float[] adj, float[] mask, int nodeCount) {
            this.nodeFeat = nodeFeat;
            this.adj = adj;
            this.mask = mask;
            this.nodeCount = nodeCount;
        }
    }

    private ConflictGraphEncoder() {
    }

    public static Encoded encode(List<ScheduleUnit> units) {
        int n = units == null ? 0 : Math.min(units.size(), MAX_NODES);

        float[] nodeFeat = new float[MAX_NODES * NODE_FEAT_DIM];
        float[] adj = new float[MAX_NODES * MAX_NODES];
        float[] mask = new float[MAX_NODES];

        // 并发池序号
        Set<String> poolSet = new LinkedHashSet<>();
        for (ScheduleUnit u : units) if (u.getPoolLabel() != null) poolSet.add(u.getPoolLabel());
        Map<String, Integer> poolIdx = new LinkedHashMap<>();
        int pi = 0;
        for (String p : poolSet) poolIdx.put(p, pi++);
        int poolCount = Math.max(1, poolSet.size());

        // 项目序号
        Set<Long> eventSet = new LinkedHashSet<>();
        for (ScheduleUnit u : units) if (u.getEventId() != null) eventSet.add(u.getEventId());
        Map<Long, Integer> eventIdx = new LinkedHashMap<>();
        int ei = 0;
        for (Long e : eventSet) eventIdx.put(e, ei++);
        int eventCount = Math.max(1, eventSet.size());

        // 同组单元数
        Map<String, Integer> groupSize = new LinkedHashMap<>();
        for (ScheduleUnit u : units) {
            if (u.getGroupKey() != null) {
                groupSize.merge(u.getGroupKey(), 1, Integer::sum);
            }
        }

        // 运动员 → 单元下标（求冲突边）
        Map<Long, List<Integer>> athleteUnits = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            long[] ath = units.get(i).getAthletes();
            if (ath == null) continue;
            for (long a : ath) {
                athleteUnits.computeIfAbsent(a, k -> new ArrayList<>()).add(i);
            }
        }
        int[] degree = new int[n];
        for (List<Integer> idxs : athleteUnits.values()) {
            for (int x = 0; x < idxs.size(); x++) {
                for (int y = x + 1; y < idxs.size(); y++) {
                    int a = idxs.get(x), b = idxs.get(y);
                    if (a >= n || b >= n) continue;
                    adj[a * MAX_NODES + b] = 1.0f;
                    adj[b * MAX_NODES + a] = 1.0f;
                    degree[a]++;
                    degree[b]++;
                }
            }
        }

        for (int i = 0; i < n; i++) {
            ScheduleUnit u = units.get(i);
            int ath = u.hasAthletes() ? u.getAthletes().length : 0;
            float track = u.isTrack() ? 1.0f : 0.0f;
            float athNorm = Math.min(ath, 64) / 64.0f;
            float durNorm = Math.min(u.getRawDuration(), 300) / 300.0f;
            float hasGroup = u.getGroupKey() != null ? 1.0f : 0.0f;
            float groupNorm = Math.min(groupSize.getOrDefault(u.getGroupKey(), 0), 8) / 8.0f;
            float poolNorm = poolIdx.getOrDefault(u.getPoolLabel(), 0) / (float) Math.max(1, poolCount - 1);
            float eventNorm = eventIdx.getOrDefault(u.getEventId(), 0) / (float) Math.max(1, eventCount - 1);
            float logAth = (float) (Math.log1p(ath) / Math.log(65.0));

            int base = i * NODE_FEAT_DIM;
            nodeFeat[base] = track;
            nodeFeat[base + 1] = athNorm;
            nodeFeat[base + 2] = durNorm;
            nodeFeat[base + 3] = hasGroup;
            nodeFeat[base + 4] = groupNorm;
            nodeFeat[base + 5] = poolNorm;
            nodeFeat[base + 6] = eventNorm;
            nodeFeat[base + 7] = logAth;
            mask[i] = 1.0f;
        }

        return new Encoded(nodeFeat, adj, mask, n);
    }
}
