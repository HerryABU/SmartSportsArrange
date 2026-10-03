package com.sports.schedule.ai;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 裁判派遣图编码器——Python 侧 {@code sports_ai/referee_advisor.py} 的<b>双端契约</b>。
 *
 * <h3>为什么裁判编排也要独立模型</h3>
 * 现有实现是纯规则（{@code ArrangementService.assignReferees}：专长优先 → 负载均衡 →
 * 并行组次不重用）。规则能保证「不出错」，但表达不了「专长匹配、负载、保护时段、
 * 同单位回避、经验」这些因素之间该怎样**加权取舍**——那正是模型的强项。
 * 按用户要求「先独立建模，再合并进主模型」，本编码器与 {@code referee_gnn.onnx} 构成独立模型这一层。
 *
 * <h3>契约（与 Python 逐位对齐，改动必须双端同步 + 重训）</h3>
 * <pre>
 *   输入  node_feat    [B, N, 12]
 *         adj_by_type  [B, 4, N, N]
 *         type_mask    [B, 4]
 *         mask         [B, N]
 *   输出  priority     [B, N]     每裁判的派遣优先级（越高越先派）
 * </pre>
 *
 * <b>4 类边</b>：0 同专长竞争 / 1 同单位回避 / 2 同受保护 / 3 负载耦合。
 *
 * <p>模型只负责<b>排序</b>，不负责「并行组次不得重用同一裁判」这类硬规则——
 * 那些仍由 Java 侧把关。这样模型输出只可能让派遣更合理，不会绕过任何安全约束。</p>
 */
public class RefereeGnnEncoder {

    /** 节点特征维度（顺序即契约）。 */
    public static final int N_REF_FEAT = 12;
    /** 边类型数。 */
    public static final int N_TYPES = 4;
    /** 单次编码的裁判数上限。 */
    public static final int MAX_NODES = 256;

    /** 特征下标（与 Python {@code FEAT_NAMES} 一一对应，禁止改序）。 */
    public static final int F_SPECIALTY_MATCH = 0;
    public static final int F_LOAD = 1;
    public static final int F_AVAILABLE = 2;
    public static final int F_PROTECTED = 3;
    public static final int F_EXPERIENCE = 4;
    public static final int F_SAME_UNIT = 5;
    public static final int F_PARALLEL_RISK = 6;
    public static final int F_SERVED_NORM = 7;
    public static final int F_CONTINUOUS = 8;
    public static final int F_PARTNER = 9;
    public static final int F_SLOT_PREF = 10;
    public static final int F_SENIORITY = 11;

    /**
     * 一名裁判的编码入参。
     *
     * <p>字段刻意全部显式传入而不是去查实体：编码器保持<b>纯函数</b>，
     * 才能在不启动 Spring、不碰数据库的前提下做契约单测。</p>
     */
    public record RefereeInput(String id,
                               String name,
                               Set<String> specialties,
                               String unit,
                               boolean protectedTime,
                               int servedCount,
                               double experience,
                               double availableRatio,
                               double continuousWork,
                               double partnerSynergy,
                               double slotPreference) {

        public static RefereeInput of(String id, String name, Set<String> specialties, String unit) {
            return new RefereeInput(id, name, specialties, unit, false, 0, 0.5, 1.0, 0.0, 0.0, 0.0);
        }
    }

    /** 编码结果；{@code degraded} 表示输入不满足编码条件，调用方应直接走规则路径。 */
    public record Encoded(float[][] nodeFeat,
                          float[][][] adjByType,
                          float[] typeMask,
                          float[] mask,
                          int n,
                          boolean degraded,
                          String reason) {
    }

    /** 每人对每个项目的专长匹配度统计（用于诊断/测试）。 */
    public Map<String, Double> specialtyMatchOf(List<RefereeInput> refs, List<String> batchSports) {
        Map<String, Double> out = new java.util.LinkedHashMap<>();
        Set<String> batch = new LinkedHashSet<>(batchSports == null ? List.of() : batchSports);
        for (RefereeInput r : refs) {
            out.put(r.id(), matchRatio(r.specialties(), batch));
        }
        return out;
    }

    /**
     * 编码。
     *
     * @param refs        本批可派遣的裁判
     * @param batchSports 本批要安排的项目名（用于算专长匹配度）
     * @param batchUnits  本批参赛单位（用于算同单位回避）
     */
    public Encoded encode(List<RefereeInput> refs, List<String> batchSports, Set<String> batchUnits) {
        if (refs == null || refs.isEmpty()) {
            return degrade("无裁判可选");
        }
        int n = Math.min(refs.size(), MAX_NODES);
        if (n < 3) {
            // 裁判少于 3 人时负载均衡无从谈起，模型没有决策空间，交给规则更稳
            return degrade("裁判人数不足 3，模型无决策空间");
        }

        Set<String> batch = new LinkedHashSet<>(batchSports == null ? List.of() : batchSports);
        Set<String> units = batchUnits == null ? Set.of() : batchUnits;

        float[][] nodeFeat = new float[n][N_REF_FEAT];
        // 已派组次的最大值用于负载归一（口径与 Python 侧 served/n 保持一致：都按裁判总数归一）
        double servedMax = 1.0;
        for (int i = 0; i < n; i++) {
            servedMax = Math.max(servedMax, refs.get(i).servedCount());
        }

        for (int i = 0; i < n; i++) {
            RefereeInput r = refs.get(i);
            float[] f = nodeFeat[i];
            f[F_SPECIALTY_MATCH] = (float) matchRatio(r.specialties(), batch);
            f[F_LOAD] = (float) clamp01(r.servedCount() / servedMax);
            f[F_AVAILABLE] = (float) clamp01(r.availableRatio());
            f[F_PROTECTED] = r.protectedTime() ? 1f : 0f;
            f[F_EXPERIENCE] = (float) clamp01(r.experience());
            f[F_SAME_UNIT] = sameUnit(r.unit(), units) ? 1f : 0f;
            f[F_PARALLEL_RISK] = (float) (1.0 - clamp01(r.availableRatio()));
            f[F_SERVED_NORM] = (float) clamp01(r.servedCount() / 6.0);
            f[F_CONTINUOUS] = (float) clamp01(r.continuousWork());
            f[F_PARTNER] = (float) clamp01(r.partnerSynergy());
            f[F_SLOT_PREF] = (float) clamp01(r.slotPreference());
            f[F_SENIORITY] = (float) clamp01(r.experience());
        }

        float[][][] adj = new float[N_TYPES][n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                RefereeInput a = refs.get(i);
                RefereeInput b = refs.get(j);
                if (sharesSpecialty(a, b)) {
                    set(adj, 0, i, j);
                }
                if (sameText(a.unit(), b.unit())) {
                    set(adj, 1, i, j);
                }
                if (a.protectedTime() && b.protectedTime()) {
                    set(adj, 2, i, j);
                }
                if (Math.abs(a.servedCount() - b.servedCount()) < 1) {
                    set(adj, 3, i, j);
                }
            }
        }

        float[] typeMask = new float[N_TYPES];
        java.util.Arrays.fill(typeMask, 1f);
        float[] mask = new float[n];
        java.util.Arrays.fill(mask, 1f);

        return new Encoded(nodeFeat, adj, typeMask, mask, n, false, "");
    }

    // ---------------------------------------------------------------- 内部
    private static Encoded degrade(String reason) {
        return new Encoded(new float[0][], new float[N_TYPES][0][0], new float[N_TYPES],
                new float[0], 0, true, reason);
    }

    private static void set(float[][][] adj, int t, int i, int j) {
        adj[t][i][j] = 1f;
        adj[t][j][i] = 1f;
    }

    private static double matchRatio(Set<String> specialties, Set<String> batch) {
        if (batch.isEmpty()) {
            return 0.0;
        }
        if (specialties == null || specialties.isEmpty()) {
            return 0.0;
        }
        int hit = 0;
        for (String s : batch) {
            if (specialties.contains(s)) {
                hit++;
            }
        }
        return hit / (double) batch.size();
    }

    private static boolean sharesSpecialty(RefereeInput a, RefereeInput b) {
        if (a.specialties() == null || b.specialties() == null
                || a.specialties().isEmpty() || b.specialties().isEmpty()) {
            return false;
        }
        for (String s : a.specialties()) {
            if (b.specialties().contains(s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameUnit(String unit, Set<String> batchUnits) {
        return unit != null && !unit.isBlank() && batchUnits.contains(unit);
    }

    private static boolean sameText(String a, String b) {
        return a != null && !a.isBlank() && a.equals(b);
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }
}
