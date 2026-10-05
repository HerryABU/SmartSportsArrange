package com.sports.schedule.ai;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 组次错开 AI：在<b>已过滤的合法候选组次</b>里，建议换到第几组。
 *
 * <h2>它在编排链里的位置</h2>
 * 精修链（GA/LNS/MNSA/ALNS/Fix-opt）全都只在「挪项目时间」这一维度找改进，
 * 受「同并发位不重叠 + 组次必须连续」约束总有挪不动的残余冲突。
 * 此时唯一不占时段容量的出路是：<b>项目时间窗一分不动</b>，只改换组次顺序。
 * 本服务给出这个建议，采纳与否由
 * {@code com.sports.schedule.core.arrange.heat.HeatStaggerMath} 决定。
 *
 * <h2>职责边界：只排序，不判合法</h2>
 * <b>同班不同班 / 组容量 / 人工锁定</b>这三条红线由
 * {@code HeatStaggerMath.legalCandidates} 过滤后才轮到本服务 ——
 * 模型只在剩下的候选里比较「哪个更好」。
 * 这条边界是刻意的：训练侧刻意<b>不把判据喂进特征</b>
 * （否则模型只是把判据读一遍，命中率冲到 1.000 却什么都没学，
 * 两行 if 规则就能替代；见训练侧 {@code heat_stagger_advisor.py} 的消融记录）。
 *
 * <p>模型缺失或推理失败时返回 {@link Optional#empty()}，
 * 调用方回退纯规则排序（间隔最大者）——功能不受 AI 影响。</p>
 */
@Slf4j
@Component
public class HeatStaggerAdvisorService {

    /** 候选组次特征维数（与训练侧 {@code heat_stagger_advisor.py} 的 HEAT_FEAT_DIM 严格对齐）。 */
    public static final int FEAT_DIM = 6;

    private final boolean enabled;
    private final String modelDir;
    private final String modelName;

    private volatile OrtEnvironment env;
    private volatile OrtSession session;
    private volatile boolean triedLoad = false;

    public HeatStaggerAdvisorService(
            @Value("${sports.schedule.ai.enabled:true}") boolean enabled,
            @Value("${sports.schedule.ai.model-dir:classpath:/models}") String modelDir,
            @Value("${sports.schedule.ai.heat-stagger-model:heat_stagger_advisor.onnx}") String modelName) {
        this.enabled = enabled;
        this.modelDir = modelDir;
        this.modelName = modelName;
    }

    public boolean isAvailable() {
        return enabled && ensureLoaded();
    }

    /** 模型加载状态（供 {@code /api/ai/status} 观测）。 */
    public Map<String, Object> modelInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("modelDir", modelDir);
        m.put("heatStaggerAdvisor", ModelSource.describe(modelDir, modelName));
        m.put("loaded", enabled && ensureLoaded());
        return m;
    }

    /**
     * 建议换到哪个组次。
     *
     * @param heatCount  该项目的组次数（= 候选矩阵的行数）
     * @param perRound  每组用时（分钟）
     * @param legal     合法候选组次（1-based，已过滤同班/容量/锁定/当前组次）
     * @param curHeat   当前组次（1-based）
     * @param gapOf     该组次与其余项目的最小间隔（分钟），长度 = {@code heatCount}；无对比项给 {@link Integer#MAX_VALUE}
     * @param fillOf    该组次的人数 / 并道数，长度 = {@code heatCount}
     * @return 建议的目标组次（1-based）；模型不可用或候选不足时 empty
     */
    public Optional<Integer> suggestHeat(int heatCount, int perRound, List<Integer> legal,
                                         int curHeat, int[] gapOf, double[] fillOf) {
        if (!isAvailable() || heatCount < 2 || legal == null || legal.size() < 2) {
            return Optional.empty();
        }
        try {
            boolean[] isLegal = new boolean[heatCount + 1];
            for (Integer h : legal) {
                if (h != null && h >= 1 && h <= heatCount) {
                    isLegal[h] = true;
                }
            }
            int n = heatCount;
            float[] feat = new float[n * FEAT_DIM];
            float[] mask = new float[n];
            for (int h = 1; h <= heatCount; h++) {
                int i = (h - 1) * FEAT_DIM;
                int gap = (gapOf != null && h - 1 < gapOf.length) ? gapOf[h - 1] : 0;
                double fill = (fillOf != null && h - 1 < fillOf.length) ? fillOf[h - 1] : 0d;
                feat[i] = (float) ((h - 1) / (double) Math.max(1, heatCount - 1));   // 0 组次序号
                feat[i + 1] = Math.min(heatCount, 16) / 16f;                          // 1 组次数
                feat[i + 2] = Math.min(perRound, 20) / 20f;                           // 2 每组用时
                feat[i + 3] = (float) Math.min(1d, Math.max(0d, fill));                 // 3 该组填充率
                feat[i + 4] = Math.min(1f, Math.max(0, gap) / 60f);                    // 4 与其余项目最小间隔
                feat[i + 5] = h == curHeat ? 1f : 0f;                                  // 5 是否当前组次
                mask[h - 1] = isLegal[h] ? 1f : 0f;                                   // 只让合法候选参与
            }

            long[] xShape = {1, n, FEAT_DIM};
            long[] mShape = {1, n};
            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            try (OnnxTensor xt = OnnxTensor.createTensor(env, FloatBuffer.wrap(feat), xShape);
                 OnnxTensor mt = OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), mShape)) {
                inputs.put("heat_feat", xt);
                inputs.put("mask", mt);
                try (OrtSession.Result r = session.run(inputs)) {
                    FloatBuffer buf = ((OnnxTensor) r.get(0)).getFloatBuffer();
                    float[] score = new float[buf.remaining()];
                    buf.get(score);
                    // ⚠️ argmax 必须**只在合法候选内**取：模型输出在 mask=0 的行
                    //    被置 0，若直接全局 argmax，一旦所有合法候选都打了负分，
                    //    就会选中一个非法组次 —— 而线上根本不会让那些组次参与。
                    int best = -1;
                    float bestScore = Float.NEGATIVE_INFINITY;
                    for (Integer h : legal) {
                        if (h == null || h < 1 || h > heatCount || h == curHeat) {
                            continue;
                        }
                        float sc = score[h - 1];
                        if (sc > bestScore) {
                            bestScore = sc;
                            best = h;
                        }
                    }
                    return best > 0 ? Optional.of(best) : Optional.empty();
                }
            }
        } catch (Exception ex) {
            log.warn("组次错开 AI 建议失败，回退规则择优: {}", ex.toString());
            return Optional.empty();
        }
    }

    private boolean ensureLoaded() {
        if (triedLoad) {
            return session != null;
        }
        synchronized (this) {
            if (triedLoad) {
                return session != null;
            }
            triedLoad = true;
            try {
                // ⚠️ 传 byte[] 而非路径：模型随 jar 交付（classpath:/models），
                //    落临时文件会引入权限/清理/并发覆盖问题（与 OnnxInferenceService 同口径）。
                byte[] bytes = ModelSource.read(modelDir, modelName).orElse(null);
                if (bytes == null || bytes.length == 0) {
                    log.info("组次错开模型不可用（缺失）: {}", modelName);
                    return false;
                }
                env = OrtEnvironment.getEnvironment();
                session = env.createSession(bytes, new OrtSession.SessionOptions());
                log.info("组次错开模型已加载: {}（{} 字节）", modelName, bytes.length);
                return true;
            } catch (Exception ex) {
                log.warn("组次错开模型加载失败（将回退规则）: {}", ex.toString());
                return false;
            }
        }
    }
}
