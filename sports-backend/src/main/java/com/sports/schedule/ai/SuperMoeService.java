package com.sports.schedule.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 超级编排模型推理服务——<b>一个模型覆盖全部十七类编排能力</b>。
 *
 * <p>此前项目编排、道次编排、球类赛制各走各自的模型（constraint_gnn /
 * lane_advisor / tournament_gnn …），彼此的表征与目标不互通，无法学到
 * 「道次编排也要顾及装箱、球类编排也要顾及兼项」这类跨域策略。
 * 本服务把十七类编排统一到一个 {@code super_moe.onnx} 上。</p>
 *
 * <p>专家分两层：0..10 是<b>任务专家</b>（节点级路由），11..16 是<b>能力专家</b>
 * （图级路由：方案生成/精修/扩散/道次派遣/工期预测/方案判别），
 * 后六位分别替代了此前独立服役的 scheme_generator / scheme_refiner /
 * scheme_diffusion / lane_advisor / forecast_* / scheme_discriminator 七个模型。</p>
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
                         double[] taskProbs,         // [N_TASKS] 任务/能力权重（19 维）
                         double[] formatLogits,      // [4] 球类赛制
                         double daysEstimate,        // 预计工期（天）
                         double[][] laneLogits,      // [N][K] 道次派遣 logits（替代 lane_advisor）
                         double qualityScore,        // [0,1] 方案质量分（替代 GAN 判别器）
                         double[] nextStepLogits,    // [n_steps] 后续步骤 logits（编排行程自我调度）
                         int n,
                         java.util.Map<String, Double> expertUsage) {

        /**
         * 后续步骤的语义位（与 Python 侧 next_step_head 逐位对应）。
         *
         * <p>模型回答的是「**按当前编排状态，下一步该做什么**」，而不是「下一个时刻的赛程」。
         * 编排链本身是一条多阶段流水线（贪心 → GA → LNS → MNSA → ALNS → 精修 → 微调），
         * 过去每一步都按固定顺序跑；有了这个头，编排器可以据此<b>建议跳过哪一步</b>。</p>
         */
        public static final int STEP_REFINE = 0;       // 继续精修
        public static final int STEP_HEAT_STAGGER = 1; // 建议做组次顺序错开
        public static final int STEP_SLOT_SPLIT = 2;   // 建议做跨时段拆分
        public static final int STEP_FINISH = 3;       // 可以收尾

        /** 后续步骤的 argmax 建议；旧版模型无此输出时返回 -1（= 无建议）。 */
        public int nextStep() {
            if (nextStepLogits == null || nextStepLogits.length == 0) {
                return -1;
            }
            int best = 0;
            for (int k = 1; k < nextStepLogits.length; k++) {
                if (nextStepLogits[k] > nextStepLogits[best]) {
                    best = k;
                }
            }
            return best;
        }

        /** 后续步骤名（可观测 / 直接进日志与编排响应）。 */
        public String nextStepName() {
            return switch (nextStep()) {
                case STEP_REFINE -> "继续精修";
                case STEP_HEAT_STAGGER -> "组次错开";
                case STEP_SLOT_SPLIT -> "跨时段拆分";
                case STEP_FINISH -> "可收尾";
                default -> "无建议";
            };
        }

        /** 每个单元的时间槽（argmax）。 */
        public int[] slots() {
            return argmaxRows(slotLogits);
        }

        /** 道次派遣建议（argmax）。旧版模型没有该输出时返回空数组。 */
        public int[] laneSlots() {
            return argmaxRows(laneLogits);
        }

        /** 是否有道次派遣输出（旧版 onnx 无此通道）。 */
        public boolean hasLaneAdvice() {
            return laneLogits != null && laneLogits.length > 0;
        }

        /**
         * 方案质量的定性判定。
         *
         * <p>⚠️ 这只是**参考信号**，不是裁决：质量分由模型回归得到，
         * 真正「方案能不能用」始终由 {@code ScheduleFeasibilityService} 与
         * 硬约束校验决定。模型自评再高也不能覆盖不可行。</p>
         */
        public String qualityLevel() {
            if (qualityScore >= 0.75) {
                return "good";
            }
            if (qualityScore >= 0.5) {
                return "fair";
            }
            return "poor";
        }

        private static int[] argmaxRows(double[][] m) {
            if (m == null || m.length == 0) {
                return new int[0];
            }
            int[] out = new int[m.length];
            for (int i = 0; i < m.length; i++) {
                int best = 0;
                for (int k = 1; k < m[i].length; k++) {
                    if (m[i][k] > m[i][best]) {
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
    /**
     * 最近一次**推理**失败的原因（与 {@link #loadError} 分开）。
     *
     * <p>两者必须分开：模型能加载 ≠ 推理能成功。最常见的失败是
     * 「磁盘上的 onnx 是旧结构」（例如 7 输出而新代码按 8 输出读）——
     * 这种情况 {@code available()} 为 true、{@code loadError} 为 null，
     * 于是降级原因彻底消失，现场只看到 {@code degraded=true} 却没有 why。</p>
     */
    private volatile String lastInferenceError;

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

    /** 模型是否声明了某个输入名（用于兼容旧版 onnx 缺新增输入的情况）。 */
    private boolean modelHasInput(String name) {
        if (session == null) {
            return false;
        }
        try {
            for (var info : session.getInputNames()) {
                if (name.equals(info)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            log.warn("读取模型输入名失败，按「无该输入」处理: {}", t.toString());
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
        // ⚠️ 只喂模型**真的声明了**的输入名：磁盘上的 super_moe.onnx 可能是旧版
        //    （无 graph_feat 输入），硬塞一个未知名字会直接跑不起来 → 整个 AI
        //    推理静默回退规则，比「喂全零图级上下文」严重得多。
        //    图级分支拿不到输入时退化成全零上下文，模型仍可用（只是路由少个信号）。
        // ⚠️ feed 的构造也必须包在 try 里：featTensor/vecTensor 会抛受检异常，
        //    抽出来放到 try 外面编译期就直接报「未报告的异常」。
        try {
            // ⚠️ 只喂模型**真的声明了**的输入名：磁盘上的 super_moe.onnx 可能是旧版
            //    （无 graph_feat 输入），硬塞一个未知名字会直接跑不起来 → 整个 AI
            //    推理静默回退规则，比「喂全零图级上下文」严重得多。
            //    图级分支拿不到输入时退化成全零上下文，模型仍可用（只是路由少个信号）。
            Map<String, ai.onnxruntime.OnnxTensor> feed = new java.util.LinkedHashMap<>();
            feed.put("node_feat", featTensor(enc));
            feed.put("adj_by_type", adjTensor(enc));
            feed.put("type_mask", vecTensor(enc.typeMask()));
            feed.put("mask", vecTensor(enc.mask()));
            // ⚠️ graph_feat 是路由器的「问题结构」输入，缺了整个 MoE 的图级分支
            //    恒为全零 —— 模型能跑但等于白加。八维与 super_encode.py 对齐。
            if (modelHasInput("graph_feat")) {
                feed.put("graph_feat", vecTensor(enc.graphFeat()));
            }
            try (ai.onnxruntime.OrtSession.Result r = session.run(feed)) {

                // ⚠️ **按输出名读取，而不是按索引**。
                //    本轮输出从 5 个扩到 7 个（新增 lane_logits / quality_score）。
                //    按索引读的话，今后任何一次「在中间插入输出」都会让老代码
                //    静默读错张量 —— 不报错、数值看着还挺像，是最难查的一类故障；
                //    而名字是稳定不变量。旧版模型缺某个名字时回退到历史索引，
                //    保证磁盘上还是旧 onnx 时也能正常跑。
                double[] priority = toVec(named(r, "priority", 0));       // [B,N]
                double[][] slot = toMatrix(named(r, "slot_logits", 1));   // [B,N,K]
                double[] taskProbs = toVec(named(r, "task_probs", 2));    // [B,N_TASKS]
                double[] fmt = toVec(named(r, "format_logits", 3));       // [B,4]
                double days = toScalar(named(r, "days_estimate", 4));
                double[][] lane = r.get("lane_logits").isPresent()
                        ? toMatrix(named(r, "lane_logits", 5)) : new double[0][];
                double quality = r.get("quality_score").isPresent()
                        ? toScalar(named(r, "quality_score", 6)) : 0.0;
                // 后续步骤（第 8 个输出）：旧版 onnx 没有该通道，
                // 给空数组让 nextStep() 返回 -1 =「无建议」，调用方按原固定链跑。
                double[] nextStep = r.get("next_step").isPresent()
                        ? toVec(named(r, "next_step", 7)) : new double[0];

                lastInferenceError = null;
                return Optional.of(new Advice(priority, slot, taskProbs, fmt, days,
                        lane, quality, nextStep, n, Map.of()));
            }
        } catch (Throwable t) {
            // ⚠️ 推理失败必须**留下原因**：模型能加载 ≠ 推理能成功。
            //    最常见的失败是「磁盘上的 onnx 是旧结构」（如 7 输出而新代码按 8 输出读），
            //    此时 available() 为 true、loadError 为 null —— 不记这里的话，
            //    调用方拿到的降级原因是 null，现场只见 degraded=true 却不知为何。
            lastInferenceError = t.getClass().getSimpleName() + ": " + t.getMessage();
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

    /**
     * 按输出名取值；磁盘上是旧版模型（没有该输出名）时回退到历史索引。
     *
     * <p>返回 {@code null} 不抛异常 —— 上层 {@code toVec/toMatrix} 对 null 已做空值保护，
     * 缺通道时退化成空数组，不会把整个推理拖进「回退规则」。</p>
     */
    private static ai.onnxruntime.OnnxValue named(ai.onnxruntime.OrtSession.Result r,
                                                  String name, int legacyIndex) {
        java.util.Optional<ai.onnxruntime.OnnxValue> byName = r.get(name);
        if (byName.isPresent()) {
            return byName.get();
        }
        try {
            return r.get(legacyIndex);
        } catch (Throwable t) {
            log.warn("模型缺少输出 {} 且索引 {} 越界: {}", name, legacyIndex, t.toString());
            return null;
        }
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
        m.put("nTasks", SuperScheduleEncoder.N_TASKS);
        m.put("nUnitTasks", SuperScheduleEncoder.N_UNIT_TASKS);
        m.put("edgeTypes", java.util.List.of("兼项", "项目块", "场地", "并发池",
                "道次", "装箱间隔", "晋级", "同队"));
        m.put("maxNodes", SuperScheduleEncoder.MAX_NODES);
        m.put("note", "一个模型覆盖十七类编排能力；加载状态见 /api/schedule/auto 响应的 superMoeLoaded");
        return m;
    }

    /** 供 /api/ai/status 上报。 */
    public Map<String, Object> modelInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", ModelSource.describe(modelDir, modelName));
        m.put("loaded", available());
        m.put("coversTasks", java.util.Arrays.asList(SuperScheduleEncoder.taskNames()));
        m.put("edgeTypes", java.util.List.of("兼项", "项目块", "场地", "并发池",
                "道次", "装箱间隔", "晋级", "同队", "组次撞车", "跨时段相邻"));
        m.put("maxNodes", SuperScheduleEncoder.MAX_NODES);
        // 输出契约明细：运维排查「模型是不是新版」时最需要这一行 ——
        // 只见 loaded=true 看不出磁盘上是 7 输出还是 8 输出。
        m.put("nOutputs", 8);
        m.put("outputs", java.util.List.of("priority", "slot_logits", "task_probs",
                "format_logits", "days_estimate", "lane_logits", "quality_score", "next_step"));
        // ⚠️ **error 键必须始终存在**。此前只在「加载失败」时写入，于是
        //    「onnx 能加载但结构不匹配（磁盘上是旧版 7 输出、新代码按 8 输出读）」
        //    这类**最常见**的失败会没有原因：调用方
        //    `out.put("error", modelInfo().get("error"))` 塞进 null，
        //    降级原因在响应里消失，现场只见 degraded=true 却不知为何。
        //    典型静默失效 —— 必须兜底。
        if (loadError != null) {
            m.put("error", loadError);
        } else if (!available()) {
            m.put("error", "模型不可用（路径缺失或加载失败）");
        } else if (lastInferenceError != null) {
            m.put("error", lastInferenceError);
        } else {
            m.put("error", "");
        }
        return m;
    }
}
