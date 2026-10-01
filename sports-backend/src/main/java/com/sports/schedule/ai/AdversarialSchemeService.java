package com.sports.schedule.ai;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.sports.schedule.opt.solver.ScheduleUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.FloatBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * <b>推理时的自对抗</b>编排服务——让生成器 G 与判别器 D 在**生成推导过程中**继续博弈
 * （不只是训练时对抗一次就固定）。
 *
 * <p>流程（每一轮都是一次 G↔D 的博弈）：</p>
 * <ol>
 *   <li>G 用随机噪声生成候选方案；</li>
 *   <li>（可选）对抗精修器 Refiner 对候选做残差精修（把 Python 侧的迭代自对抗精修蒸馏成一次前向）；</li>
 *   <li>D 给候选打分（「像不像真实可行解」）；</li>
 *   <li>服务自身计算候选的**真实残余冲突**（冲突图同槽边占比）；</li>
 *   <li>综合得分 {@code D分 - λ·冲突}，多轮择优保留最优。</li>
 * </ol>
 *
 * <p>这就是「推理时自对抗」：G 负责生成、D 负责批评，二者在推理时交替，最终交出
 * 「D 认为最真、且冲突最低」的方案。模型缺失时返回 empty，不阻塞主流程。</p>
 */
@Slf4j
@Component
public class AdversarialSchemeService {

    public static final int MAX_SLOTS = 16;
    private static final int NOISE_DIM = 8;

    private final boolean enabled;
    private final String modelDir;
    private final String genName;
    private final String disName;
    private final String refName;

    private volatile OrtEnvironment env;
    private volatile OrtSession gen;
    private volatile OrtSession dis;
    private volatile OrtSession ref;
    private volatile boolean triedLoad = false;

    public AdversarialSchemeService(
            @Value("${sports.schedule.ai.enabled:true}") boolean enabled,
            @Value("${sports.schedule.ai.model-dir:classpath:/models}") String modelDir,
            @Value("${sports.schedule.ai.scheme-generator-model:scheme_generator.onnx}") String genName,
            @Value("${sports.schedule.ai.scheme-discriminator-model:scheme_discriminator.onnx}") String disName,
            @Value("${sports.schedule.ai.scheme-refiner-model:scheme_refiner.onnx}") String refName) {
        this.enabled = enabled;
        this.modelDir = modelDir;
        this.genName = genName;
        this.disName = disName;
        this.refName = refName;
    }

    public boolean isAvailable() {
        return enabled && ensureLoaded();
    }

    /** 模型加载状态（供 {@code /api/ai/status} 观测）。 */
    public Map<String, Object> modelInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("modelDir", modelDir);
        m.put("generator", ModelSource.describe(modelDir, genName));
        m.put("discriminator", ModelSource.describe(modelDir, disName));
        m.put("refiner", ModelSource.describe(modelDir, refName));
        m.put("refinerPresent", ModelSource.exists(modelDir, refName));
        m.put("loaded", enabled && ensureLoaded());
        return m;
    }

    /** 一次推理时自对抗的结果。 */
    public record SchemeResult(int[] slots, double dScore, double conflict, double conflictBefore,
                               boolean refined, int rounds) {
        /** 综合得分：判别器评分越高、残余冲突越低越好。 */
        public double score(double lambda) {
            return dScore - lambda * conflict;
        }
    }

    /**
     * 推理时自对抗生成方案。
     *
     * @param units  待排单元
     * @param rounds 对抗轮数（G↔D 交替次数；越多越可能逼出更好的方案）
     * @param lambda 冲突惩罚权重（越大越偏向「零冲突」而非「像真解」）
     */
    public Optional<SchemeResult> generateAdversarially(List<ScheduleUnit> units, int rounds, double lambda) {
        if (!isAvailable()) return Optional.empty();
        try {
            ConflictGraphEncoder.Encoded enc = ConflictGraphEncoder.encode(units);
            int n = enc.nodeCount;
            int max = ConflictGraphEncoder.MAX_NODES;
            if (n == 0) return Optional.empty();

            float[] forbid = new float[max * MAX_SLOTS];
            Random rng = new Random(20260918L);

            // 基线：G 一次前向（z=0）的残余冲突。它**作为初始候选**参与择优，
            // 保证「推理时自对抗」的结果在冲突上绝不劣于单次生成。
            float[] baseLogits = runGenerator(enc, new float[max * NOISE_DIM], forbid);
            int[] baseSlots = argmaxSlots(baseLogits, n);
            double baselineConflict = hardConflict(baseSlots, enc.adj, n);
            SchemeResult best = new SchemeResult(baseSlots, runDiscriminator(enc, oneHot(baseLogits, n)),
                    baselineConflict, baselineConflict, false, 0);

            for (int r = 0; r < Math.max(1, rounds); r++) {
                float[] z = gaussian(max * NOISE_DIM, rng);
                float[] logits = runGenerator(enc, z, forbid);           // [max*K]
                float[] usedLogits = logits;
                boolean refined = false;
                if (ref != null) {
                    usedLogits = runRefiner(enc, logits, forbid);        // 精修（一次前向）
                    refined = true;
                }
                float[] scheme = oneHot(usedLogits, n);                  // [max*K] one-hot
                int[] slots = argmaxSlots(usedLogits, n);
                double conflict = hardConflict(slots, enc.adj, n);
                double dScore = runDiscriminator(enc, scheme);
                SchemeResult cand = new SchemeResult(slots, dScore, conflict, baselineConflict, refined, r + 1);
                // 择优准则：**残余冲突优先**（编排的核心目标），冲突相同再看判别器评分。
                if (cand.conflict() < best.conflict() - 1e-9
                        || (Math.abs(cand.conflict() - best.conflict()) <= 1e-9
                            && cand.dScore() > best.dScore())) {
                    best = cand;
                }
            }
            log.info("推理时自对抗: {} 轮，基线冲突={} → 最终冲突={}, D={}, 精修={}",
                    rounds, String.format("%.3f", best.conflictBefore()),
                    String.format("%.3f", best.conflict()),
                    String.format("%.3f", best.dScore()), best.refined());
            return Optional.of(best);
        } catch (Exception ex) {
            log.warn("推理时自对抗失败: {}", ex.toString());
            return Optional.empty();
        }
    }

    // ==================== 单步推理 ====================

    private float[] runGenerator(ConflictGraphEncoder.Encoded enc, float[] z, float[] forbid) throws Exception {
        int max = ConflictGraphEncoder.MAX_NODES;
        try (OnnxTensor nf = tensor(enc.nodeFeat, 1, max, ConflictGraphEncoder.NODE_FEAT_DIM);
             OnnxTensor at = tensor(enc.adj, 1, max, max);
             OnnxTensor mt = tensor(enc.mask, 1, max);
             OnnxTensor zt = tensor(z, 1, max, NOISE_DIM);
             OnnxTensor ft = tensor(forbid, 1, max, MAX_SLOTS)) {
            Map<String, OnnxTensor> in = new LinkedHashMap<>();
            in.put("node_feat", nf);
            in.put("adj", at);
            in.put("mask", mt);
            in.put("z", zt);
            in.put("forbid", ft);
            try (OrtSession.Result res = gen.run(in)) {
                return toArray(res.get(0));       // logits
            }
        }
    }

    private float[] runRefiner(ConflictGraphEncoder.Encoded enc, float[] initLogits, float[] forbid) throws Exception {
        int max = ConflictGraphEncoder.MAX_NODES;
        try (OnnxTensor nf = tensor(enc.nodeFeat, 1, max, ConflictGraphEncoder.NODE_FEAT_DIM);
             OnnxTensor at = tensor(enc.adj, 1, max, max);
             OnnxTensor mt = tensor(enc.mask, 1, max);
             OnnxTensor it = tensor(initLogits, 1, max, MAX_SLOTS);
             OnnxTensor ft = tensor(forbid, 1, max, MAX_SLOTS)) {
            Map<String, OnnxTensor> in = new LinkedHashMap<>();
            in.put("node_feat", nf);
            in.put("adj", at);
            in.put("mask", mt);
            in.put("init_logits", it);
            in.put("forbid", ft);
            try (OrtSession.Result res = ref.run(in)) {
                return toArray(res.get(0));
            }
        }
    }

    private double runDiscriminator(ConflictGraphEncoder.Encoded enc, float[] scheme) throws Exception {
        int max = ConflictGraphEncoder.MAX_NODES;
        try (OnnxTensor nf = tensor(enc.nodeFeat, 1, max, ConflictGraphEncoder.NODE_FEAT_DIM);
             OnnxTensor at = tensor(enc.adj, 1, max, max);
             OnnxTensor mt = tensor(enc.mask, 1, max);
             OnnxTensor st = tensor(scheme, 1, max, MAX_SLOTS)) {
            Map<String, OnnxTensor> in = new LinkedHashMap<>();
            in.put("node_feat", nf);
            in.put("adj", at);
            in.put("mask", mt);
            in.put("scheme", st);
            try (OrtSession.Result res = dis.run(in)) {
                float[] out = toArray(res.get(0));
                return out.length > 0 ? out[0] : 0.0;
            }
        }
    }

    // ==================== 工具 ====================

    /** 残余冲突 = 同槽且相邻的边数 ÷ 总边数。 */
    static double hardConflict(int[] slots, float[] adj, int n) {
        int bad = 0, edges = 0;
        int max = ConflictGraphEncoder.MAX_NODES;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (adj[i * max + j] > 0) {
                    edges++;
                    if (slots[i] == slots[j]) bad++;
                }
            }
        }
        return edges == 0 ? 0.0 : (double) bad / edges;
    }

    private static float[] oneHot(float[] logits, int n) {
        int max = ConflictGraphEncoder.MAX_NODES;
        float[] out = new float[max * MAX_SLOTS];
        for (int i = 0; i < n; i++) {
            int best = 0;
            float bv = -Float.MAX_VALUE;
            for (int k = 0; k < MAX_SLOTS; k++) {
                float v = logits[i * MAX_SLOTS + k];
                if (v > bv) { bv = v; best = k; }
            }
            out[i * MAX_SLOTS + best] = 1.0f;
        }
        return out;
    }

    private static int[] argmaxSlots(float[] logits, int n) {
        int[] slots = new int[n];
        for (int i = 0; i < n; i++) {
            int best = 0;
            float bv = -Float.MAX_VALUE;
            for (int k = 0; k < MAX_SLOTS; k++) {
                float v = logits[i * MAX_SLOTS + k];
                if (v > bv) { bv = v; best = k; }
            }
            slots[i] = best;
        }
        return slots;
    }

    private static float[] gaussian(int len, Random rng) {
        float[] out = new float[len];
        for (int i = 0; i < len; i++) out[i] = (float) rng.nextGaussian();
        return out;
    }

    private static OnnxTensor tensor(float[] data, long... shape) throws Exception {
        return OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(data), shape);
    }

    private static float[] toArray(ai.onnxruntime.OnnxValue v) throws Exception {
        FloatBuffer buf = ((OnnxTensor) v).getFloatBuffer();
        float[] out = new float[buf.remaining()];
        buf.get(out);
        return out;
    }

    private synchronized boolean ensureLoaded() {
        if (triedLoad) return gen != null && dis != null;
        triedLoad = true;
        try {
            byte[] genBytes = ModelSource.read(modelDir, genName).orElse(null);
            byte[] disBytes = ModelSource.read(modelDir, disName).orElse(null);
            if (genBytes == null || disBytes == null) {
                log.info("推理时自对抗模型缺失（{}），跳过", modelDir);
                return false;
            }
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            gen = env.createSession(genBytes, options);
            dis = env.createSession(disBytes, options);
            byte[] refBytes = ModelSource.read(modelDir, refName).orElse(null);
            if (refBytes != null) {
                ref = env.createSession(refBytes, options);
                log.info("推理时自对抗就绪（含精修器）: {}", ModelSource.describe(modelDir, genName));
            } else {
                log.info("推理时自对抗就绪（无精修器，仅多轮择优）: {}", ModelSource.describe(modelDir, genName));
            }
            return true;
        } catch (Exception ex) {
            log.warn("推理时自对抗模型加载失败: {}", ex.toString());
            return false;
        }
    }
}
