package com.sports.schedule.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 裁判派遣模型推理服务（独立模型层）。
 *
 * <p>对应「裁判编排 / 教师规避先独立建模，再合并进主模型」里的<b>独立建模</b>这一步；
 * 合并进 {@code super_moe} 属于后续动作（那会改变超级模型的任务数与输出契约，
 * 必须连同全量重训一起做，不能顺手改）。</p>
 *
 * <h3>降级原则（与其它 AI 服务一致）</h3>
 * 模型缺失 / 裁判数不足 / 推理异常 → 返回 {@link Optional#empty()}，
 * 上层继续走 {@code ArrangementService.assignReferees} 的规则派遣，<b>绝不阻塞编排</b>。
 *
 * <p><b>模型只给排序，不做裁决</b>：并行组次不得重用同一裁判这类硬规则仍由 Java 把关，
 * 因此即使模型输出离谱，也不会产生不合规的派遣结果。</p>
 */
@Slf4j
@Service
public class RefereeAiService {

    /** 一次裁判派遣推理的结果。 */
    public record Advice(double[] priority, int n) {

        /** 裁判按优先级降序的下标（可直接作为规则派遣的尝试顺序）。 */
        public int[] orderByPriority() {
            Integer[] idx = new Integer[priority.length];
            for (int i = 0; i < idx.length; i++) {
                idx[i] = i;
            }
            Arrays.sort(idx, (a, b) -> Double.compare(priority[b], priority[a]));
            int[] out = new int[idx.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = idx[i];
            }
            return out;
        }
    }

    private final String modelDir;
    private final String modelName;
    private final boolean enabled;
    private final RefereeGnnEncoder encoder = new RefereeGnnEncoder();
    private final ai.onnxruntime.OrtEnvironment env = ai.onnxruntime.OrtEnvironment.getEnvironment();

    private volatile ai.onnxruntime.OrtSession session;
    private volatile String loadError;

    public RefereeAiService(
            @Value("${sports.schedule.ai.enabled:true}") boolean enabled,
            @Value("${sports.schedule.ai.model-dir:classpath:/models}") String modelDir,
            @Value("${sports.schedule.ai.referee-model:referee_gnn.onnx}") String modelName) {
        this.enabled = enabled;
        this.modelDir = modelDir;
        this.modelName = modelName;
    }

    public RefereeGnnEncoder getEncoder() {
        return encoder;
    }

    public boolean available() {
        return enabled && ensureLoaded();
    }

    /** 供状态接口展示（模型"就绪 / 因何不可用"）。 */
    public String statusText() {
        if (ensureLoaded()) {
            return "就绪";
        }
        return enabled ? ("不可用: " + loadError) : "AI 总开关关闭";
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
            log.info("裁判派遣模型已就绪: {} ({})", modelName, ModelSource.describe(modelDir, modelName));
            return true;
        } catch (Throwable t) {
            // 缺 EP 类 / 驱动缺失抛的是 Error（NoClassDefFoundError / UnsatisfiedLinkError），故 catch Throwable
            loadError = t.getClass().getSimpleName() + ": " + t.getMessage();
            log.warn("裁判派遣模型不可用，回退规则派遣: {}", loadError);
            return false;
        }
    }

    /** 推理。编码降级 / 模型不可用时返回空，由上层继续走规则。 */
    public Optional<Advice> advise(RefereeGnnEncoder.Encoded enc) {
        if (enc == null || enc.degraded() || enc.n() < 3) {
            return Optional.empty();
        }
        if (!ensureLoaded()) {
            return Optional.empty();
        }
        int n = enc.n();
        float[][][] nodeFeat = new float[1][n][RefereeGnnEncoder.N_REF_FEAT];
        nodeFeat[0] = enc.nodeFeat();
        float[][][][] adj = new float[1][RefereeGnnEncoder.N_TYPES][n][n];
        for (int t = 0; t < RefereeGnnEncoder.N_TYPES; t++) {
            adj[0][t] = enc.adjByType()[t];
        }
        try (ai.onnxruntime.OnnxTensor featTensor = ai.onnxruntime.OnnxTensor.createTensor(env, nodeFeat);
             ai.onnxruntime.OnnxTensor adjTensor = ai.onnxruntime.OnnxTensor.createTensor(env, adj);
             ai.onnxruntime.OnnxTensor typeTensor =
                     ai.onnxruntime.OnnxTensor.createTensor(env, new float[][]{enc.typeMask()});
             ai.onnxruntime.OnnxTensor maskTensor =
                     ai.onnxruntime.OnnxTensor.createTensor(env, new float[][]{enc.mask()})) {
            Map<String, ai.onnxruntime.OnnxTensor> feed = new LinkedHashMap<>();
            feed.put("node_feat", featTensor);
            feed.put("adj_by_type", adjTensor);
            feed.put("type_mask", typeTensor);
            feed.put("mask", maskTensor);
            try (ai.onnxruntime.OrtSession.Result result = session.run(feed)) {
                float[][] out = (float[][]) result.get(0).getValue();
                double[] priority = new double[n];
                for (int i = 0; i < n; i++) {
                    priority[i] = out[0][i];
                }
                return Optional.of(new Advice(priority, n));
            }
        } catch (Throwable t) {
            log.warn("裁判派遣模型推理失败，回退规则派遣: {}", t.toString());
            return Optional.empty();
        }
    }
}
