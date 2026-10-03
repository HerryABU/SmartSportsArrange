package com.sports.schedule.ai;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 道次编排 AI：给出「运动员派遣顺序」建议。
 *
 * <p>径赛一个项目 N 名运动员、分 H 组、每组 L 条道。分组要同时满足：</p>
 * <ul>
 *   <li><b>硬约束</b>：同一组不能出现同班运动员；</li>
 *   <li><b>软目标</b>：各组实力均衡、同班运动员在时间上分散。</li>
 * </ul>
 *
 * <p>经典款型按报名顺序蛇形分组，硬约束能满足但顺序本身任意。本服务用模型学到的
 * **派遣优先级**替代这个任意顺序：按它的输出 ``argsort`` 后再走既有的蛇形分组管线，
 * 同班自然错开、强弱自然交替。<b>与现有管线零阻抗</b>——只换「谁先派」，不换「怎么分」。</p>
 *
 * <p>模型随 jar 交付（{@link ModelSource} 从 classpath 直读），运动员数 ``n`` 是 ONNX
 * 动态轴；模型缺失/推理失败时返回 {@link Optional#empty()}，调用方回退既有顺序。</p>
 */
@Slf4j
@Component
public class LaneAdvisorService {

    /** 运动员特征维数（与训练侧 {@code lane_advisor.py} 的 LANE_FEAT_DIM 严格对齐）。 */
    public static final int FEAT_DIM = 8;

    private final boolean enabled;
    private final String modelDir;
    private final String modelName;

    private volatile OrtEnvironment env;
    private volatile OrtSession session;
    private volatile boolean triedLoad = false;

    public LaneAdvisorService(
            @Value("${sports.schedule.ai.enabled:true}") boolean enabled,
            @Value("${sports.schedule.ai.model-dir:classpath:/models}") String modelDir,
            @Value("${sports.schedule.ai.lane-advisor-model:lane_advisor.onnx}") String modelName) {
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
        m.put("laneAdvisor", ModelSource.describe(modelDir, modelName));
        m.put("loaded", enabled && ensureLoaded());
        return m;
    }

    /**
     * 建议派遣顺序。
     *
     * @param classes     每名运动员的班级标识（用于「同班分散」，可比较即可）
     * @param isFemale    性别（true=女）——模型用它做分组偏好
     * @param eventCounts 每名运动员的报名项目数
     * @param seedScores  种子/成绩归一化值（无成绩给 0），可为 null
     * @param lanes       每组道次数（决定组数）
     * @return 派遣顺序（运动员下标，按优先级**降序**）；模型不可用时 empty
     */
    public Optional<int[]> suggestOrder(List<String> classes, boolean[] isFemale,
                                        int[] eventCounts, double[] seedScores, int lanes) {
        if (!isAvailable() || classes == null || classes.isEmpty()) {
            return Optional.empty();
        }
        try {
            int n = classes.size();
            // 单输入：全局特征（组数/每组人数）已广播进每行的第 6、7 维，
            // 因此所有输入的第 1 维都是同一个动态轴 n（早期额外开一路 [1,2] 全局输入，
            // 会让 ONNX 动态轴声明与 tracing 推断的静态形状冲突而导出失败）。
            float[] feat = buildFeatures(classes, isFemale, eventCounts, seedScores, lanes, n);
            float[] mask = new float[n];
            Arrays.fill(mask, 1f);

            long[] xShape = {1, n, FEAT_DIM};
            long[] mShape = {1, n};

            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            try (OnnxTensor xt = OnnxTensor.createTensor(env, FloatBuffer.wrap(feat), xShape);
                 OnnxTensor mt = OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), mShape)) {
                inputs.put("athlete_feat", xt);
                inputs.put("mask", mt);
                try (OrtSession.Result r = session.run(inputs)) {
                    FloatBuffer buf = ((OnnxTensor) r.get(0)).getFloatBuffer();
                    float[] score = new float[buf.remaining()];
                    buf.get(score);
                    return Optional.of(argsortDesc(score, n));
                }
            }
        } catch (Exception ex) {
            log.warn("道次 AI 建议失败，回退既有顺序: {}", ex.toString());
            return Optional.empty();
        }
    }

    /** 与训练侧 ``lane_advisor.py`` 逐位对齐的 8 维运动员特征。 */
    private static float[] buildFeatures(List<String> classes, boolean[] isFemale,
                                         int[] eventCounts, double[] seedScores,
                                         int lanes, int n) {
        Set<String> distinct = new LinkedHashSet<>(classes);
        List<String> sorted = new ArrayList<>(distinct);
        sorted.sort(String::compareTo);
        Map<String, Integer> classIdx = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            classIdx.put(sorted.get(i), i);
        }
        int classCount = Math.max(1, sorted.size());
        Map<String, Integer> classSize = new HashMap<>();
        for (String c : classes) {
            classSize.merge(c, 1, Integer::sum);
        }
        int nGroups = lanes > 0 ? Math.max(1, (n + lanes - 1) / lanes) : 1;
        float perGroup = Math.min(Math.max(1, lanes), Math.max(1, Math.min(lanes, n)));

        float[] feat = new float[n * FEAT_DIM];
        for (int i = 0; i < n; i++) {
            String c = classes.get(i);
            float seed = seedScores != null && i < seedScores.length ? (float) seedScores[i] : 0f;
            int ec = eventCounts != null && i < eventCounts.length ? eventCounts[i] : 1;
            int base = i * FEAT_DIM;
            feat[base] = classIdx.getOrDefault(c, 0) / (float) Math.max(1, classCount - 1);
            feat[base + 1] = (isFemale != null && i < isFemale.length && isFemale[i]) ? 1f : 0f;
            feat[base + 2] = Math.min(ec, 4) / 4f;
            feat[base + 3] = seed > 0 ? 1f : 0f;
            feat[base + 4] = Math.min(Math.max(seed, 0f), 1f);
            feat[base + 5] = classSize.getOrDefault(c, 0) / (float) n;
            feat[base + 6] = Math.min(nGroups, 16) / 16f;
            feat[base + 7] = Math.min(perGroup, 32) / 32f;
        }
        return feat;
    }

    /** 按得分降序给出下标（稳定排序，保证可复现）——与训练侧「优先级越大越先派」一致。 */
    static int[] argsortDesc(float[] score, int n) {
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (a, b) -> {
            int cmp = Float.compare(score[b], score[a]);
            return cmp != 0 ? cmp : Integer.compare(a, b);
        });
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = idx[i];
        }
        return out;
    }

    private synchronized boolean ensureLoaded() {
        if (triedLoad) return session != null;
        triedLoad = true;
        try {
            byte[] bytes = ModelSource.read(modelDir, modelName).orElse(null);
            if (bytes == null) {
                log.info("道次 AI 模型缺失（{}），道次编排使用既有顺序", modelDir);
                return false;
            }
            env = OrtEnvironment.getEnvironment();
            session = env.createSession(bytes, OnnxSessionFactory.get().newSessionOptions());
            log.info("道次 AI 模型就绪: {}", ModelSource.describe(modelDir, modelName));
            return true;
        } catch (Exception ex) {
            log.warn("道次 AI 模型加载失败: {}", ex.toString());
            return false;
        }
    }
}
