package com.sports.schedule.ai;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.FloatBuffer;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 跨时段拆分 AI：多个项目抢同一段上午余量时，建议<b>先拆谁</b>。
 *
 * <h2>为什么是「排序」而不是「切几组」</h2>
 * 「某项目上午切 k 组」这件事，规则已经能唯一确定
 * （{@code SlotSplit.beats}：冲突少 → 上午组次多 → 窗口/起点靠前），
 * 让模型学它等于学一段手写的 if-else（训练侧实测命中率 1.000 却什么都没学）。
 *
 * <p>真正的增量价值在<b>多解竞争</b>：上午的零头通常只够拆一个项目，
 * 而候选往往有 3~5 个。「先拆谁」的答案依赖「拆完还剩多少给下一个」的动态推演，
 * 规则只能贪心地看当前收益，模型可以学到「给后续留余量」的全局权衡。</p>
 *
 * <h2>它不做什么</h2>
 * 不决定「能不能拆」（那是三条红线：组次边界 / 同一天 / 下午装得下），
 * 也不决定「切几组」（仍由 {@code SlotSplit} 规则算）。
 * 本服务只回答一件事：<b>这批候选里，谁先拆收益最大</b>。
 *
 * <p>模型缺失或推理失败时返回 {@link Optional#empty()}，
 * 调用方回退「按上午占用效率」的贪心 —— 功能不受 AI 影响。</p>
 */
@Slf4j
@Component
public class SlotSplitAdvisorService {

    /** 候选项目特征维数（与训练侧 {@code slot_split_advisor.py} 的 SPLIT_FEAT_DIM 严格对齐）。 */
    public static final int FEAT_DIM = 8;

    private final boolean enabled;
    private final String modelDir;
    private final String modelName;

    private volatile OrtEnvironment env;
    private volatile OrtSession session;
    private volatile boolean triedLoad = false;

    public SlotSplitAdvisorService(
            @Value("${sports.schedule.ai.enabled:true}") boolean enabled,
            @Value("${sports.schedule.ai.model-dir:classpath:/models}") String modelDir,
            @Value("${sports.schedule.ai.slot-split-model:slot_split_advisor.onnx}") String modelName) {
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
        m.put("slotSplitAdvisor", ModelSource.describe(modelDir, modelName));
        m.put("loaded", enabled && ensureLoaded());
        return m;
    }

    /** 一个可考虑跨时段拆分的候选项目。 */
    public record Candidate(int heatCount, int perRound, int duration) {
    }

    /**
     * 给出「先拆谁」的顺序（候选下标，**从先到后**）。
     *
     * @param candidates 本批可拆分的候选项目（已通过三条红线过滤）
     * @param amFree     上午窗口剩余分钟
     * @param pmFree     下午窗口剩余分钟
     * @return 建议顺序；模型不可用或候选不足 2 个时 empty（调用方回退贪心）
     */
    public Optional<List<Integer>> suggestSplitOrder(List<Candidate> candidates, int amFree, int pmFree) {
        if (!isAvailable() || candidates == null || candidates.size() < 2) {
            return Optional.empty();
        }
        try {
            int p = candidates.size();
            float[] feat = new float[p * FEAT_DIM];
            float[] mask = new float[p];
            for (int i = 0; i < p; i++) {
                Candidate c = candidates.get(i);
                int heatCount = Math.max(1, c.heatCount());
                int perRound = Math.max(1, c.perRound());
                int duration = c.duration() > 0 ? c.duration() : heatCount * perRound;
                // 上午最多能吃几组（至少留 1 组给下午）
                int kMax = Math.max(0, Math.min(heatCount - 1, amFree / perRound));
                int head = kMax * perRound;
                int tail = Math.max(0, duration - head);
                int o = i * FEAT_DIM;
                feat[o] = Math.min(heatCount, 16) / 16f;                        // 0 组次数
                feat[o + 1] = Math.min(perRound, 20) / 20f;                     // 1 每组用时
                feat[o + 2] = Math.min(duration, 240) / 240f;                   // 2 总时长
                feat[o + 3] = Math.min(amFree, 240) / 240f;                     // 3 上午剩余
                feat[o + 4] = Math.min(pmFree, 240) / 240f;                     // 4 下午剩余
                feat[o + 5] = Math.min(kMax, 16) / 16f;                        // 5 可拆组次数
                feat[o + 6] = (float) Math.min(1d, head / (double) Math.max(1, duration));  // 6 上午能用掉的比例
                feat[o + 7] = (float) Math.min(1d, tail / (double) Math.max(1, pmFree));   // 7 尾段占下午的比例
                mask[i] = 1f;                                                   // 候选集已由红线保证
            }

            long[] xShape = {1, p, FEAT_DIM};
            long[] mShape = {1, p};
            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            try (OnnxTensor xt = OnnxTensor.createTensor(env, FloatBuffer.wrap(feat), xShape);
                 OnnxTensor mt = OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), mShape)) {
                inputs.put("split_feat", xt);
                inputs.put("mask", mt);
                try (OrtSession.Result r = session.run(inputs)) {
                    FloatBuffer buf = ((OnnxTensor) r.get(0)).getFloatBuffer();
                    float[] score = new float[buf.remaining()];
                    buf.get(score);
                    Integer[] order = new Integer[p];
                    for (int i = 0; i < p; i++) {
                        order[i] = i;
                    }
                    final float[] sc = score;
                    java.util.Arrays.sort(order, Comparator.comparingDouble((Integer i) -> -sc[i]));
                    java.util.List<Integer> out = new java.util.ArrayList<>(p);
                    for (Integer i : order) {
                        out.add(i);
                    }
                    return Optional.of(out);
                }
            }
        } catch (Exception ex) {
            log.warn("跨时段拆分 AI 建议失败，回退贪心顺序: {}", ex.toString());
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
                    log.info("跨时段拆分模型不可用（缺失）: {}", modelName);
                    return false;
                }
                env = OrtEnvironment.getEnvironment();
                session = env.createSession(bytes, new OrtSession.SessionOptions());
                log.info("跨时段拆分模型已加载: {}（{} 字节）", modelName, bytes.length);
                return true;
            } catch (Exception ex) {
                log.warn("跨时段拆分模型加载失败（将回退贪心）: {}", ex.toString());
                return false;
            }
        }
    }
}
