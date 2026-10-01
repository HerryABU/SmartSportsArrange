package com.sports.schedule.ai;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.ScheduleUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ONNX 推理服务——AI 编排核心的<b>部署侧</b>入口。
 *
 * <p>加载训练侧导出的两个 ONNX 模型（见 {@code sports-ai/} 目录），在 JVM 内直接推理，
 * 生产环境<b>不依赖 Python 解释器</b>（对应架构文档第九节「Java + ONNX 解耦」）。</p>
 *
 * <ul>
 *   <li>{@code algorithm_selector.onnx}：16 维实例特征 → 硬解 / 取消路径概率；</li>
 *   <li>{@code conflict_gnn.onnx}：冲突图 → 各单元着色优先级。</li>
 * </ul>
 *
 * <p><b>失败即回退</b>：模型文件缺失、加载异常、推理异常时返回 {@link Optional#empty()}，
 * 调用方回退到既有规则编排，接口在任何情况下都能出方案（与求解器「失败即降级」同一原则）。</p>
 */
@Slf4j
@Component
public class OnnxInferenceService {

    private final boolean enabled;
    private final Path modelDir;
    private final String selectorModel;
    private final String gnnModel;

    private volatile OrtEnvironment env;
    private volatile OrtSession selectorSession;
    private volatile OrtSession gnnSession;
    private volatile boolean triedLoad = false;

    public OnnxInferenceService(
            @Value("${sports.schedule.ai.enabled:true}") boolean enabled,
            @Value("${sports.schedule.ai.model-dir:./ai-models}") String modelDir,
            @Value("${sports.schedule.ai.selector-model:algorithm_selector.onnx}") String selectorModel,
            @Value("${sports.schedule.ai.gnn-model:conflict_gnn.onnx}") String gnnModel) {
        this.enabled = enabled;
        this.modelDir = Paths.get(modelDir);
        this.selectorModel = selectorModel;
        this.gnnModel = gnnModel;
    }

    /** 模型是否已就绪（可安全推理）。 */
    public boolean isAvailable() {
        return enabled && ensureLoaded();
    }

    /**
     * 推理编排建议：选择器（硬解/取消）+ 冲突簇 GNN（着色优先级）。
     *
     * @return 建议；模型不可用或推理失败时返回 {@link Optional#empty()}
     */
    public Optional<AiAdvisory> advise(List<ScheduleUnit> units, List<Placement> placements) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        try {
            // ① 16 维实例特征 → 选择器
            double[] feat = InstanceFeatures.extract(units, placements);
            float[] featFlat = new float[feat.length];
            for (int i = 0; i < feat.length; i++) {
                featFlat[i] = (float) feat[i];
            }
            float[] logits = runSelector(featFlat);
            double pCancel = cancelProbability(logits);
            AiAdvisory.Strategy strategy = pCancel >= 0.5
                    ? AiAdvisory.Strategy.CANCEL_PATH : AiAdvisory.Strategy.HARD_SOLVE;

            // ② 冲突图 → GNN 着色优先级（按 units 顺序对齐，截断到真实节点数）
            ConflictGraphEncoder.Encoded enc = ConflictGraphEncoder.encode(units);
            float[] priority = runGnn(enc);
            double[] trimmed = new double[enc.nodeCount];
            for (int i = 0; i < enc.nodeCount; i++) {
                trimmed[i] = priority[i];
            }

            return Optional.of(new AiAdvisory(strategy, pCancel, trimmed));
        } catch (Exception ex) {
            log.warn("AI 推理失败，回退规则编排: {}", ex.toString());
            return Optional.empty();
        }
    }

    private synchronized boolean ensureLoaded() {
        if (triedLoad) {
            return selectorSession != null && gnnSession != null;
        }
        triedLoad = true;
        try {
            Path sel = modelDir.resolve(selectorModel);
            Path gnn = modelDir.resolve(gnnModel);
            if (!Files.exists(sel) || !Files.exists(gnn)) {
                log.info("AI 编排模型缺失（{}），回退规则编排", modelDir.toAbsolutePath());
                return false;
            }
            env = OrtEnvironment.getEnvironment();
            selectorSession = env.createSession(sel.toString());
            gnnSession = env.createSession(gnn.toString());
            log.info("AI 编排模型就绪: {}", modelDir.toAbsolutePath());
            return true;
        } catch (Exception ex) {
            log.warn("AI 编排模型加载失败，回退规则编排: {}", ex.toString());
            return false;
        }
    }

    private float[] runSelector(float[] feat) throws OrtException {
        long[] shape = {1, InstanceFeatures.N_FEATURES};
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(feat), shape)) {
            try (OrtSession.Result r = selectorSession.run(Map.of("features", t))) {
                return toArray(((OnnxTensor) r.get(0)).getFloatBuffer());
            }
        }
    }

    private float[] runGnn(ConflictGraphEncoder.Encoded enc) throws OrtException {
        long[] nodeShape = {1, ConflictGraphEncoder.MAX_NODES, ConflictGraphEncoder.NODE_FEAT_DIM};
        long[] adjShape = {1, ConflictGraphEncoder.MAX_NODES, ConflictGraphEncoder.MAX_NODES};
        long[] maskShape = {1, ConflictGraphEncoder.MAX_NODES};
        try (OnnxTensor nt = OnnxTensor.createTensor(env, FloatBuffer.wrap(enc.nodeFeat), nodeShape);
             OnnxTensor at = OnnxTensor.createTensor(env, FloatBuffer.wrap(enc.adj), adjShape);
             OnnxTensor mt = OnnxTensor.createTensor(env, FloatBuffer.wrap(enc.mask), maskShape)) {
            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put("node_feat", nt);
            inputs.put("adj", at);
            inputs.put("mask", mt);
            try (OrtSession.Result r = gnnSession.run(inputs)) {
                return toArray(((OnnxTensor) r.get(0)).getFloatBuffer());
            }
        }
    }

    private static double cancelProbability(float[] logits) {
        if (logits == null || logits.length < 2) return 0.5;
        double l0 = logits[0], l1 = logits[1];
        double m = Math.max(l0, l1);
        double e0 = Math.exp(l0 - m), e1 = Math.exp(l1 - m);
        return e1 / (e0 + e1);
    }

    private static float[] toArray(FloatBuffer buf) {
        float[] out = new float[buf.remaining()];
        buf.get(out);
        return out;
    }
}
