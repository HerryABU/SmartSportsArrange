package com.sports.schedule.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 超级编排模型推理服务——<b>一个模型覆盖全部九类编排</b>。
 *
 * <p>此前项目编排、道次编排、球类赛制各走各自的模型（constraint_gnn /
 * lane_advisor / tournament_gnn …），彼此的表征与目标不互通，无法学到
 * 「道次编排也要顾及装箱、球类编排也要顾及兼项」这类跨域策略。
 * 本服务把九类编排统一到一个 {@code super_moe.onnx} 上。</p>
 *
 * <h3>降级原则</h3>
 * 模型缺失 / 规模超限 / 推理异常时返回 {@link Optional#empty()}，
 * 由上层继续走各自规则路径，<b>绝不阻塞编排</b>。
 */
@Slf4j
@Service
public class SuperMoeService {

    /** 一次超级模型推理的结果。 */
    public record Advice(double[] priority,          // [N] 调度优先级，越高越先排
                         double[][] slotLogits,      // [N][K] 时间槽 logits
                         double[] taskProbs,         // [9] 九类任务权重
                         double[] formatLogits,      // [4] 球类赛制
                         double daysEstimate,        // 预计天数
                         int n,
                         java.util.Map<String, Double> expertUsage) {

        /** 每个单元的时间槽（argmax）。 */
        public int[] slots() {
            int[] out = new int[slotLogits.length];
            for (int i = 0; i < slotLogits.length; i++) {
                int best = 0;
                for (int k = 1; k < slotLogits[i].length; k++) {
                    if (slotLogits[i][k] > slotLogits[i][best]) {
                        best = k;
                    }
                }
                out[i] = best;
            }
            return out;
        }

        /** 单元按优先级降序的下标（可直接喂给求解器做初始排序）。 */
        public int[] orderByPriority() {
            Integer[] idx = new Integer[priority.length];
            for (int i = 0; i < idx.length; i++) {
                idx[i] = i;
            }
            java.util.Arrays.sort(idx, (a, b) -> Double.compare(priority[b], priority[a]));
            int[] out = new int[idx.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = idx[i];
            }
            return out;
        }

        /** 赛制推荐（group / round_robin / knockout / hybrid）。 */
        public String recommendedFormat() {
            String[] names = {"group", "round_robin", "knockout", "hybrid"};
            int best = 0;
            for (int i = 1; i < formatLogits.length && i < names.length; i++) {
                if (formatLogits[i] > formatLogits[best]) {
                    best = i;
                }
            }
            return names[Math.min(best, names.length - 1)];
        }
    }

    private final String modelDir;
    private final String modelName;
    private final boolean enabled;
    private final SuperScheduleEncoder encoder = new SuperScheduleEncoder();
    private final ai.onnxruntime.OrtEnvironment env = ai.onnxruntime.OrtEnvironment.getEnvironment();

    private volatile ai.onnxruntime.OrtSession session;
    private volatile String loadError;

    public SuperMoeService(
            @Value("${sports.schedule.ai.enabled:true}") boolean enabled,
            @Value("${sports.schedule.ai.model-dir:classpath:/models}") String modelDir,
            @Value("${sports.schedule.ai.super-model:super_moe.onnx}") String modelName) {
        this.enabled = enabled;
        this.modelDir = modelDir;
        this.modelName = modelName;
    }

    public SuperScheduleEncoder getEncoder() {
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
            session = env.createSession(bytes, OnnxSessionFactory.get().newSessionOptions());
            log.info("超级编排模型已就绪: {} ({})", modelName, ModelSource.describe(modelDir, modelName));
            return true;
        } catch (Throwable t) {
            // 缺 EP 类/驱动缺失抛的是 Error（NoClassDefFoundError / UnsatisfiedLinkError），故 catch Throwable
            loadError = t.getClass().getSimpleName() + ": " + t.getMessage();
            log.warn("超级编排模型不可用，回退规则编排: {}", loadError);
            return false;
        }
    }

    /** 推理。编码降级 / 模型不可用时返回空，由上层继续走规则。 */
    public Optional<Advice> advise(SuperScheduleEncoder.Encoded enc) {
        if (enc == null || enc.degraded() || enc.n() < 3) {
            return Optional.empty();
        }
        if (!ensureLoaded()) {
            return Optional.empty();
        }
        int n = enc.n();
        try (ai.onnxruntime.OrtSession.Result r = session.run(Map.of(
                "node_feat", featTensor(enc),
                "adj_by_type", adjTensor(enc),
                "type_mask", vecTensor(enc.typeMask()),
                "mask", vecTensor(enc.mask()),
                // ⚠️ graph_feat 是路由器的「问题结构」输入，缺了整个 MoE 的图级分支
                //    恒为全零 —— 模型能跑但等于白加。八维与 super_encode.py 对齐。
                "graph_feat", vecTensor(enc.graphFeat())))) {

            double[] priority = toVec(r.get(0));           // [B,N]
            double[][] slot = toMatrix(r.get(1));          // [B,N,K]
            double[] taskProbs = toVec(r.get(2));          // [B,9]
            double[] fmt = toVec(r.get(3));                // [B,4]
            double days = toScalar(r.get(4));

            return Optional.of(new Advice(priority, slot, taskProbs, fmt, days, n, Map.of()));
        } catch (Throwable t) {
            log.warn("超级编排推理失败，回退规则编排: {}", t.toString());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------
    private ai.onnxruntime.OnnxTensor featTensor(SuperScheduleEncoder.Encoded enc) throws Exception {
        int n = enc.n();
        int f = SuperScheduleEncoder.NODE_FEAT_DIM;
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

    private ai.onnxruntime.OnnxTensor adjTensor(SuperScheduleEncoder.Encoded enc) throws Exception {
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

    private ai.onnxruntime.OnnxTensor vecTensor(float[] src) throws Exception {
        return ai.onnxruntime.OnnxTensor.createTensor(env,
                java.nio.FloatBuffer.wrap(src), new long[]{1, src.length});
    }

    private static Object val(Object v) throws Exception {
        return (v instanceof ai.onnxruntime.OnnxValue ov) ? ov.getValue() : v;
    }

    /**
     * [1,K] 或 [B,K] → 一维 double 数组。
     *
     * <p>行数为 1 时取<b>整行</b>；多行时逐行取首个元素（多 batch 标量）。
     * 不能一律 {@code a[i][0]}——那会把 [1,9] 的任务权重压成长度 1。</p>
     */
    private static double[] toVec(Object v) throws Exception {
        Object o = val(v);
        if (o instanceof float[][] a) {
            if (a.length == 1) {
                double[] out = new double[a[0].length];
                for (int i = 0; i < out.length; i++) {
                    out[i] = a[0][i];
                }
                return out;
            }
            double[] out = new double[a.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = a[i][0];
            }
            return out;
        }
        if (o instanceof float[] a) {
            double[] out = new double[a.length];
            for (int i = 0; i < a.length; i++) {
                out[i] = a[i];
            }
            return out;
        }
        return new double[0];
    }

    private static double[][] toMatrix(Object v) throws Exception {
        Object o = val(v);
        if (o instanceof float[][][] a && a.length >= 1) {
            // [1,N,K] → 取第 0 个 batch
            float[][] m = a[0];
            double[][] out = new double[m.length][];
            for (int i = 0; i < m.length; i++) {
                out[i] = new double[m[i].length];
                for (int j = 0; j < m[i].length; j++) {
                    out[i][j] = m[i][j];
                }
            }
            return out;
        }
        return new double[0][];
    }

    private static double toScalar(Object v) throws Exception {
        double[] d = toVec(v);
        return d.length == 0 ? 0.0 : d[0];
    }

    /** 静态版 modelInfo：供 /api/ai/status 直接调用（该接口不注入本 Bean）。 */
    public static Map<String, Object> superModelInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("covers", java.util.Arrays.asList(SuperScheduleEncoder.taskNames()));
        m.put("edgeTypes", java.util.List.of("兼项", "项目块", "场地", "并发池",
                "道次", "装箱间隔", "晋级", "同队"));
        m.put("maxNodes", SuperScheduleEncoder.MAX_NODES);
        m.put("note", "一个模型覆盖九类编排；加载状态见 /api/schedule/auto 响应的 superMoeLoaded");
        return m;
    }

    /** 供 /api/ai/status 上报。 */
    public Map<String, Object> modelInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", ModelSource.describe(modelDir, modelName));
        m.put("loaded", available());
        m.put("coversTasks", java.util.Arrays.asList(SuperScheduleEncoder.taskNames()));
        m.put("edgeTypes", java.util.List.of("兼项", "项目块", "场地", "并发池",
                "道次", "装箱间隔", "晋级", "同队"));
        m.put("maxNodes", SuperScheduleEncoder.MAX_NODES);
        if (loadError != null) {
            m.put("error", loadError);
        }
        return m;
    }
}
