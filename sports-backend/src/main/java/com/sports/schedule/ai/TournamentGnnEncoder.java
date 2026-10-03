package com.sports.schedule.ai;

import com.sports.schedule.tournament.RoundRobinGenerator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 球类赛制异构图编码器：把参赛队编成 4 类约束边的图，喂给 {@code tournament_gnn.onnx}。
 *
 * <p>与训练侧 {@code sports_ai/data/ball_tournament.py} 的 {@code encode_ball_graph} 逐位对齐。
 * 边类型顺序即 ONNX 通道号，<b>双端契约</b>，改序必须两侧同步：</p>
 * <ol start="0">
 *   <li>{@code STRENGTH} 实力相近（同档位）——实力接近的队相遇风险/观赏性都更高</li>
 *   <li>{@code SAME_CLASS} 同单位（同班同队）——<b>应当错开</b>，首轮相遇最刺眼</li>
 *   <li>{@code ROUND} 同轮次——同轮必须塞得进现有场地数</li>
 *   <li>{@code VENUE} 偏好同场地——同场地同时开赛会冲突</li>
 * </ol>
 *
 * <p>此前球类赛制（循环/淘汰/混合）全是规则、种子只按名次、轮转公平性无人评估，
 * 本类把这三件事变成可学习的打分。</p>
 */
@Component
public class TournamentGnnEncoder {

    /** 边类型数 = ONNX 的 T 维。 */
    public static final int T_STRENGTH = 0;
    public static final int T_SAME_CLASS = 1;
    public static final int T_ROUND = 2;
    public static final int T_VENUE = 3;
    public static final int N_TYPES = 4;

    /** 节点特征维度 = ONNX 的 F 维。 */
    public static final int NODE_FEAT_DIM = 14;

    /** 单层编码上限：邻接是 O(N²)，超过则退化到「不建模」而非静默截断。 */
    public static final int MAX_NODES = 512;

    /** 一支参赛队的最小信息。 */
    public record Team(String name, double strength, String unit, String venuePref) {
        public Team {
            if (name == null) {
                name = "";
            }
            // ⚠️ 这里**只能夹 NaN/负值**，不能写 strength <= 0 ? 0.5 : strength。
            //    0.0 是合法的「最弱队」实力，写成 0.5 会把它当成中等队，
            //    连带把实力分档（bucket(v,4)=int(v*4)）算错、STRENGTH 边连错人。
            //    「未提供实力」的默认值由调用方给（TournamentController 传 0.5）。
            if (Double.isNaN(strength)) {
                strength = 0.5;
            }
            strength = Math.max(0.0, Math.min(1.0, strength));
            unit = unit == null ? "" : unit;
            venuePref = venuePref == null || venuePref.isBlank() ? "A" : venuePref;
        }
    }

    /** 编码结果。 */
    public record Encoded(float[][] nodeFeat, float[][][] adjByType, float[] typeMask,
                          float[] mask, int n, boolean degraded, String degradeReason) {

        /** node_feat 展平成 ONNX 需要的 [1, N, 14]。 */
        public float[][][] nodeFeatBatch() {
            return new float[][][]{nodeFeat};
        }

        /** adj_by_type 展平成 [1, T, N, N]。 */
        public float[][][][] adjBatch() {
            return new float[][][][]{adjByType};
        }

        public float[][] typeMaskBatch() {
            return new float[][]{typeMask};
        }

        public float[][] maskBatch() {
            return new float[][]{mask};
        }
    }

    /**
     * 编码一支赛事的参赛队。
     *
     * @param teams       参赛队（实力 0..1，1=最强）
     * @param venues      场地名列表
     * @param minutesPerMatch 单场耗时（分钟）
     * @param availableSlots  可用时段数，0 表示不限
     * @param days        天数
     */
    public Encoded encode(List<Team> teams, List<String> venues, int minutesPerMatch,
                          int availableSlots, int days) {
        int n = teams == null ? 0 : teams.size();
        float[] mask = new float[Math.max(0, n)];
        java.util.Arrays.fill(mask, 1f);
        if (n == 0) {
            return new Encoded(new float[0][NODE_FEAT_DIM], new float[N_TYPES][0][0],
                    new float[N_TYPES], mask, 0, false, null);
        }
        if (n > MAX_NODES) {
            // ⚠️ 不静默截断：邻接是 O(N²)，512 队已经是 1MB/通道。
            // 直接返回空图让上层回退规则，并如实带上原因。
            return new Encoded(new float[0][NODE_FEAT_DIM], new float[N_TYPES][0][0],
                    new float[N_TYPES], mask, 0, true,
                    "参赛队 " + n + " 超过单层上限 " + MAX_NODES + "，已回退规则赛制");
        }

        float[][][] adj = new float[N_TYPES][n][n];
        float[] tmask = new float[N_TYPES];

        // ---- T_STRENGTH：实力相近（4 档） ----
        Map<Integer, List<Integer>> buckets = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            buckets.computeIfAbsent(bucket(teams.get(i).strength(), 4), k -> new ArrayList<>()).add(i);
        }
        for (List<Integer> idxs : buckets.values()) {
            if (idxs.size() < 2) {
                continue;
            }
            float press = Math.min(1f, (float) idxs.size() / n);
            for (int x = 0; x < idxs.size(); x++) {
                for (int y = x + 1; y < idxs.size(); y++) {
                    int a = idxs.get(x);
                    int b = idxs.get(y);
                    float w = (float) (1.0 - Math.abs(teams.get(a).strength() - teams.get(b).strength()));
                    float v = Math.max(w, press * 0.3f);
                    adj[T_STRENGTH][a][b] = v;
                    adj[T_STRENGTH][b][a] = v;
                }
            }
            tmask[T_STRENGTH] = 1f;
        }

        // ---- T_SAME_CLASS：同单位 ----
        Map<String, List<Integer>> byUnit = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            byUnit.computeIfAbsent(teams.get(i).unit(), k -> new ArrayList<>()).add(i);
        }
        for (List<Integer> idxs : byUnit.values()) {
            if (idxs.size() < 2) {
                continue;
            }
            float press = Math.min(1f, (float) idxs.size() / n);
            for (int x = 0; x < idxs.size(); x++) {
                for (int y = x + 1; y < idxs.size(); y++) {
                    int a = idxs.get(x);
                    int b = idxs.get(y);
                    adj[T_SAME_CLASS][a][b] = press;
                    adj[T_SAME_CLASS][b][a] = press;
                }
            }
            tmask[T_SAME_CLASS] = 1f;
        }

        // ---- T_ROUND：同轮次（用现有循环赛规则生成一版参考赛程） ----
        List<String> names = teams.stream().map(Team::name).toList();
        Map<String, Integer> name2idx = new HashMap<>();
        for (int i = 0; i < n; i++) {
            name2idx.put(names.get(i), i);
        }
        Map<Integer, Integer> roundOf = new HashMap<>();
        try {
            for (RoundRobinGenerator.Match m : RoundRobinGenerator.generate(names, false, true)) {
                Integer h = name2idx.get(m.home());
                Integer a = name2idx.get(m.away());
                if (h == null || a == null) {
                    continue;
                }
                roundOf.putIfAbsent(h, m.round());
                roundOf.putIfAbsent(a, m.round());
                if (sameRound(roundOf.get(h), m.round()) && sameRound(roundOf.get(a), m.round())) {
                    adj[T_ROUND][h][a] = 1f;
                    adj[T_ROUND][a][h] = 1f;
                    tmask[T_ROUND] = 1f;
                }
            }
        } catch (RuntimeException ignored) {
            // 循环赛在某些规模下会抛（队伍数为奇数且要求成对）——没有参考轮次就没有 ROUND 边，
            // type_mask 会如实保持 0，不是错误。
        }

        // ---- T_VENUE：同偏好场地 ----
        Map<String, List<Integer>> byPref = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            byPref.computeIfAbsent(teams.get(i).venuePref(), k -> new ArrayList<>()).add(i);
        }
        for (List<Integer> idxs : byPref.values()) {
            if (idxs.size() < 2) {
                continue;
            }
            float press = Math.min(1f, (float) idxs.size() / n);
            for (int x = 0; x < idxs.size(); x++) {
                for (int y = x + 1; y < idxs.size(); y++) {
                    int a = idxs.get(x);
                    int b = idxs.get(y);
                    adj[T_VENUE][a][b] = press;
                    adj[T_VENUE][b][a] = press;
                }
            }
            tmask[T_VENUE] = 1f;
        }

        // ---- 14 维节点特征 ----
        double sMax = teams.stream().mapToDouble(Team::strength).max().orElse(1.0);
        double sMin = teams.stream().mapToDouble(Team::strength).min().orElse(0.0);
        double sRng = (sMax - sMin) == 0 ? 1.0 : (sMax - sMin);
        int nRounds = Math.max(1, new java.util.HashSet<>(roundOf.values()).size());
        int venueCount = venues == null ? 0 : venues.size();
        int courts = Math.max(1, venueCount);
        int slots = availableSlots <= 0 ? days * 8 : availableSlots;
        float[][] feat = new float[n][NODE_FEAT_DIM];
        for (int i = 0; i < n; i++) {
            Team t = teams.get(i);
            int unitSize = byUnit.getOrDefault(t.unit(), List.of()).size();
            int prefSize = byPref.getOrDefault(t.venuePref(), List.of()).size();
            feat[i] = new float[]{
                    (float) t.strength(),
                    (float) ((t.strength() - sMin) / sRng),
                    bucket(t.strength(), 4) / 3f,
                    n / 32f,
                    Math.min(1f, byUnit.size() / 8f),
                    unitSize / (float) n,
                    Math.min(unitSize, 8) / 8f,
                    prefSize / (float) n,
                    Math.min(1f, venueCount / 4f),
                    Math.min(1f, courts / 8f),
                    minutesPerMatch / 60f,
                    Math.min(1f, slots / 32f),
                    Math.min(1f, days / 4f),
                    (roundOf.getOrDefault(i, 0)) / (float) nRounds
            };
        }
        return new Encoded(feat, adj, tmask, mask, n, false, null);
    }

    private static boolean sameRound(Integer a, int b) {
        return a != null && a == b;
    }

    private static int bucket(double v, int nb) {
        return Math.min(nb - 1, Math.max(0, (int) (v * nb)));
    }
}
