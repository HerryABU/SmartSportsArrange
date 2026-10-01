package com.sports.schedule.ai;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
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
 * GAN 生成器推理服务——用训练好的**生成器**直接生成候选「时间槽着色方案」。
 *
 * <p>加载训练侧（sports-ai/generative）导出的 ``scheme_generator.onnx``：输入冲突图 + 噪声，
 * 输出每个单元的**时间槽分配**（16 个抽象槽）。这是「GNN 直接学习着色」这条路径的落地
 * （对应架构文档 5.1 第一条 / 5.3 对抗式网络）。</p>
 *
 * <p>失败即降级：模型缺失 / 加载失败 / 推理异常返回 {@link Optional#empty()}，不阻塞主流程。</p>
 */
@Slf4j
@Component
public class SchemeGeneratorService {

    /** GAN 输出的抽象时间槽数（与训练侧 scheme.py 的 MAX_SLOTS 一致）。 */
    public static final int MAX_SLOTS = 16;

    private final boolean enabled;
    private final Path modelDir;
    private final String generatorModel;

    private volatile OrtEnvironment env;
    private volatile OrtSession session;
    private volatile boolean triedLoad = false;

    public SchemeGeneratorService(
            @Value("${sports.schedule.ai.enabled:true}") boolean enabled,
            @Value("${sports.schedule.ai.model-dir:./ai-models}") String modelDir,
            @Value("${sports.schedule.ai.scheme-generator-model:scheme_generator.onnx}") String generatorModel) {
        this.enabled = enabled;
        this.modelDir = Paths.get(modelDir);
        this.generatorModel = generatorModel;
    }

    public boolean isAvailable() {
        return enabled && ensureLoaded();
    }

    /**
     * 用 GAN 生成器为单元序列生成抽象时间槽方案。
     *
     * @return 每个单元的槽索引（长度 = min(单元数, MAX_NODES)）；不可用时 empty
     */
    public Optional<int[]> generateSlots(List<ScheduleUnit> units) {
        if (!isAvailable()) return Optional.empty();
        try {
            ConflictGraphEncoder.Encoded enc = ConflictGraphEncoder.encode(units);
            int n = enc.nodeCount;
            int max = ConflictGraphEncoder.MAX_NODES;

            float[] z = new float[max * 8];                       // 确定性推理：噪声取 0
            float[] forbid = new float[max * MAX_SLOTS];          // 行政时间保护（此处留空）
            long[] nfShape = {1, max, ConflictGraphEncoder.NODE_FEAT_DIM};
            long[] adjShape = {1, max, max};
            long[] maskShape = {1, max};
            long[] zShape = {1, max, 8};
            long[] forbShape = {1, max, MAX_SLOTS};

            try (OnnxTensor nf = OnnxTensor.createTensor(env, FloatBuffer.wrap(enc.nodeFeat), nfShape);
                 OnnxTensor at = OnnxTensor.createTensor(env, FloatBuffer.wrap(enc.adj), adjShape);
                 OnnxTensor mt = OnnxTensor.createTensor(env, FloatBuffer.wrap(enc.mask), maskShape);
                 OnnxTensor zt = OnnxTensor.createTensor(env, FloatBuffer.wrap(z), zShape);
                 OnnxTensor ft = OnnxTensor.createTensor(env, FloatBuffer.wrap(forbid), forbShape)) {
                Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
                inputs.put("node_feat", nf);
                inputs.put("adj", at);
                inputs.put("mask", mt);
                inputs.put("z", zt);
                inputs.put("forbid", ft);
                try (OrtSession.Result r = session.run(inputs)) {
                    // 输出 [logits, scheme]：scheme 为 one-hot [1,max,K]，取 argmax 得槽索引
                    float[] scheme = toArray(((OnnxTensor) r.get(1)).getFloatBuffer());
                    int[] slots = new int[n];
                    for (int i = 0; i < n; i++) {
                        int best = 0;
                        float bestV = -Float.MAX_VALUE;
                        for (int k = 0; k < MAX_SLOTS; k++) {
                            float v = scheme[i * MAX_SLOTS + k];
                            if (v > bestV) { bestV = v; best = k; }
                        }
                        slots[i] = best;
                    }
                    return Optional.of(slots);
                }
            }
        } catch (Exception ex) {
            log.warn("GAN 生成器推理失败: {}", ex.toString());
            return Optional.empty();
        }
    }

    private synchronized boolean ensureLoaded() {
        if (triedLoad) return session != null;
        triedLoad = true;
        try {
            Path p = modelDir.resolve(generatorModel);
            if (!Files.exists(p)) {
                log.info("GAN 生成器模型缺失（{}），跳过", modelDir.toAbsolutePath());
                return false;
            }
            env = OrtEnvironment.getEnvironment();
            session = env.createSession(p.toString());
            log.info("GAN 生成器模型就绪: {}", p.toAbsolutePath());
            return true;
        } catch (Exception ex) {
            log.warn("GAN 生成器模型加载失败: {}", ex.toString());
            return false;
        }
    }

    private static float[] toArray(FloatBuffer buf) {
        float[] out = new float[buf.remaining()];
        buf.get(out);
        return out;
    }
}
