package com.sports.schedule.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 球类赛制 AI 推理：ONNX {@code tournament_gnn.onnx} 的 Java 侧加载与调用。
 *
 * <p>本类让球类赛程第一次拥有 AI 决策——此前 {@code round_robin / elimination / hybrid}
 * 全是规则、种子只按名次、轮转公平性无人评估。三个输出：</p>
 * <ul>
 *   <li><b>赛制推荐</b>：3 个 logits（round_robin / elimination / hybrid）</li>
 *   <li><b>种子排序分</b>：谁该进前半个签位（分数越高越靠前）</li>
 *   <li><b>公平性成本</b>：每队「对手实力方差惩罚」，越低越公平</li>
 * </ul>
 *
 * <p><b>降级原则</b>（与本项目其它 AI 一致）：模型缺失 / 加载失败 / 推理异常时
 * 返回 {@link Optional#empty()}，由上层继续走规则赛制，绝不阻塞赛程生成。</p>
 */
@Slf4j
@Service
public class TournamentAiService {

    /** 赛制枚举——顺序即 ONNX 输出向量的下标，与训练侧 FORMAT_* 一致。 */
    public static final int FORMAT_ROUND_ROBIN = 0;
    public static final int FORMAT_ELIMINATION = 1;
    public static final int FORMAT_HYBRID = 2;
    public static final String[] FORMAT_NAMES = {"round_robin", "elimination", "hybrid"};

    private final String modelDir;
    private final String modelName;
    private final boolean enabled;
    private final ai.onnxruntime.OrtEnvironment env = ai.onnxruntime.OrtEnvironment.getEnvironment();

    private final TournamentGnnEncoder encoder = new TournamentGnnEncoder();

    private volatile ai.onnxruntime.OrtSession session;
    private volatile String loadError;

    public TournamentAiService(
            @Value("${sports.schedule.ai.enabled:true}") boolean enabled,
            @Value("${sports.schedule.ai.model-dir:classpath:/models}") String modelDir,
            @Value("${sports.schedule.ai.tournament-model:tournament_gnn.onnx}") String modelName) {
        this.enabled = enabled;
        this.modelDir = modelDir;
        this.modelName = modelName;
    }

    /** 一次推理的结果。 */
    public record Advice(double[] formatScores, double[] seedScores, double[] fairnessCost,
                         int n, boolean degraded, String degradeReason) {

        /** softmax 归一化后的赛制概率（调用方直接用概率比 logits 稳）。 */
        public double[] formatProbabilities() {
            double mx = Double.NEGATIVE_INFINITY;
            for (double v : formatScores) {
                mx = Math.max(mx, v);
            }
            double sum = 0;
            double[] p = new double[formatScores.length];
            for (int i = 0; i < formatScores.length; i++) {
                p[i] = Math.exp(formatScores[i] - mx);
                sum += p[i];
            }
            for (int i = 0; i < p.length; i++) {
                p[i] = sum <= 0 ? 1.0 / p.length : p[i] / sum;
            }
            return p;
        }

        /** 推荐赛制名（概率最大者）。 */
        public String recommendedFormat() {
            double[] p = formatProbabilities();
            int best = 0;
            for (int i = 1; i < p.length; i++) {
                if (p[i] > p[best]) {
                    best = i;
                }
            }
            return FORMAT_NAMES[Math.min(best, FORMAT_NAMES.length - 1)];
        }
    }

    /** 图编码器（无状态，共享一份即可）。 */
    public TournamentGnnEncoder getEncoder() {
        return encoder;
    }

    public boolean available() {
        return enabled && ensureLoaded();
    }

    private boolean ensureLoaded() {
        if (session != null) {
            return true;
        }
        if (!enabled) {
            loadError = "AI 总开关关闭";
            return false;
        }
        try {
            byte[] bytes = ModelSource.read(modelDir, modelName)
                    .orElseThrow(() -> new IllegalStateException("模型不存在: " + modelName));
            ai.onnxruntime.OrtSession.SessionOptions opt = OnnxSessionFactory.get().newSessionOptions();
            session = env.createSession(bytes, opt);
            log.info("球类赛制模型已就绪: {} ({})", modelName, ModelSource.describe(modelDir, modelName));
            return true;
        } catch (Throwable t) {
            // 缺 EP 类/驱动缺失抛的是 Error（NoClassDefFoundError / UnsatisfiedLinkError），故 catch Throwable
            loadError = t.getClass().getSimpleName() + ": " + t.getMessage();
            log.warn("球类赛制模型不可用，回退规则赛制: {}", loadError);
            return false;
        }
    }

    /**
     * 推理。编码失败（超规模等）或模型不可用时返回空，由上层继续走规则。
     */
    public Optional<Advice> advise(TournamentGnnEncoder.Encoded enc) {
        if (enc == null || enc.degraded() || enc.n() < 3) {
            return Optional.empty();
        }
        if (!ensureLoaded()) {
            return Optional.empty();
        }
        int n = enc.n();
        try (ai.onnxruntime.OrtSession.Result r = session.run(java.util.Map.of(
                "node_feat", featTensor(enc),
                "adj_by_type", adjTensor(enc),
                "type_mask", vecTensor(enc.typeMask()),
                "mask", vecTensor(enc.mask())))) {

            double[] fmt = firstDim(r.get(0).getValue());
            double[] seed = firstDim(r.get(1).getValue());
            double[] fair = firstDim(r.get(2).getValue());
            return Optional.of(new Advice(fmt, seed, fair, n, false, null));
        } catch (Throwable t) {
            log.warn("球类赛制推理失败，回退规则赛制: {}", t.toString());
            return Optional.empty();
        }
    }

    /** [1, N, F] → OnnxTensor。 */
    private ai.onnxruntime.OnnxTensor featTensor(TournamentGnnEncoder.Encoded enc) throws Exception {
        int n = enc.n();
        int f = TournamentGnnEncoder.NODE_FEAT_DIM;
        float[] flat = new float[n * f];
        int p = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < f; j++) {
                flat[p++] = enc.nodeFeat()[i][j];
            }
        }
        return ai.onnxruntime.OnnxTensor.createTensor(env,
                java.nio.FloatBuffer.wrap(flat), new long[]{1, n, f});
    }

    /** [1, T] / [1, N] → OnnxTensor。 */
    private ai.onnxruntime.OnnxTensor vecTensor(float[] src) throws Exception {
        return ai.onnxruntime.OnnxTensor.createTensor(env,
                java.nio.FloatBuffer.wrap(src), new long[]{1, src.length});
    }

    private ai.onnxruntime.OnnxTensor adjTensor(TournamentGnnEncoder.Encoded enc) throws Exception {
        // [1, T, N, N] → 扁平 float 缓冲
        int t = enc.adjByType().length;
        int nn = enc.n();
        float[] flat = new float[t * nn * nn];
        int p = 0;
        for (int ti = 0; ti < t; ti++) {
            for (int i = 0; i < nn; i++) {
                for (int j = 0; j < nn; j++) {
                    flat[p++] = enc.adjByType()[ti][i][j];
                }
            }
        }
        return ai.onnxruntime.OnnxTensor.createTensor(env,
                java.nio.FloatBuffer.wrap(flat), new long[]{1, t, nn, nn});
    }

    /**
     * 把 ONNX 输出摊平成一维 double 数组。
     *
     * <p>⚠️ 三种形状都要处理，且**不能一律取 {@code a[i][0]}**：
     * {@code format_logits} 是 [1,3]（batch × 类别），若沿用「每行取首列」会得到
     * 长度 1 的数组——单测 {@code 赛制 logits 应为 3 维} 就是这么变红的。
     * 正确规则：行数为 1 时取<b>整行</b>；否则每行取首列（多 batch 的逐样本标量）。</p>
     */
    private static double[] firstDim(Object value) throws Exception {
        Object v = (value instanceof ai.onnxruntime.OnnxValue ov) ? ov.getValue() : value;
        if (v instanceof float[][] a) {
            if (a.length == 1) {
                // [1, K] → 取整行
                double[] out = new double[a[0].length];
                for (int i = 0; i < out.length; i++) {
                    out[i] = a[0][i];
                }
                return out;
            }
            // [B, 1] → 逐样本标量
            double[] out = new double[a.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = a[i][0];
            }
            return out;
        }
        if (v instanceof float[] a) {
            double[] out = new double[a.length];
            for (int i = 0; i < a.length; i++) {
                out[i] = a[i];
            }
            return out;
        }
        return new double[0];
    }

    /** 供 /api/ai/status 上报。 */
    public Map<String, Object> modelInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", ModelSource.describe(modelDir, modelName));
        m.put("loaded", available());
        if (loadError != null) {
            m.put("error", loadError);
        }
        return m;
    }
}
