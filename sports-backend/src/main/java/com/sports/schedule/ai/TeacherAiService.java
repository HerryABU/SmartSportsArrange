package com.sports.schedule.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 教师（行政）规避模型推理服务——独立模型层。
 *
 * <p>对应 Python 侧 {@code sports_ai/teacher_advisor.py} + {@code teacher_gnn.onnx}，
 * 与 {@link RefereeAiService} 同构：两者都是「人 × 时段 × 约束」的分配问题，
 * 骨架一致、只换特征与边的语义。</p>
 *
 * <h3>为什么教师侧也要模型</h3>
 * 原实现是纯规则（查 {@code AdminTimeProtection} 表 → 教师→班级→运动员→项目→保护区间）。
 * 规则保证「不出错」，但表达不了多因素权衡：某教师既是高三班主任、又连着两天有监考、
 * 所带学生还要参加 3 个项目 —— 三个约束撞一起时先保护谁？规则只能给固定优先级，
 * 模型能学出取舍。</p>
 *
 * <p><b>模型只给排序，不做裁决</b>：真正「哪些时段不可排」仍由规则把关（硬约束），
 * 模型输出的是「避让优先级」，用于在多个教师冲突时决定先照顾谁。</p>
 *
 * <h3>降级</h3>
 * 模型缺失 / 教师数不足 / 推理异常 → {@link Optional#empty()}，上层继续走规则，绝不阻塞编排。
 */
@Slf4j
@Service
public class TeacherAiService {

    /** 教师节点特征维度（与 Python {@code N_TCH_FEAT} 逐位对齐，禁止改序）。 */
    public static final int N_TCH_FEAT = 10;
    /** 边类型数：0 同班级 / 1 同教研组 / 2 同保护时段 / 3 同行政层级 */
    public static final int N_TYPES = 4;
    public static final int MAX_NODES = 256;

    /** 一名教师的编码入参（显式传入，保持编码为纯函数、可单测）。 */
    public record TeacherInput(String id,
                               String name,
                               Set<String> classes,
                               boolean headTeacher,
                               double adminWeight,
                               double busyRatio,
                               int protectedCount,
                               double conflictHistory,
                               double redundancy,
                               String group) {
    }

    /** 编码结果；{@code degraded} 表示不满足编码条件，调用方直接走规则。 */
    public record Encoded(float[][] nodeFeat,
                          float[][][] adjByType,
                          float[] typeMask,
                          float[] mask,
                          int n,
                          boolean degraded,
                          String reason) {
    }

    /** 推理结果：每个教师的「避让优先级」（越大越该优先照顾）。 */
    public record Advice(double[] aversion, int n) {

        public int[] orderByAversion() {
            Integer[] idx = new Integer[aversion.length];
            for (int i = 0; i < idx.length; i++) {
                idx[i] = i;
            }
            Arrays.sort(idx, (a, b) -> Double.compare(aversion[b], aversion[a]));
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
    private final ai.onnxruntime.OrtEnvironment env = ai.onnxruntime.OrtEnvironment.getEnvironment();

    private volatile ai.onnxruntime.OrtSession session;
    private volatile String loadError;

    public TeacherAiService(
            @Value("${sports.schedule.ai.enabled:true}") boolean enabled,
            @Value("${sports.schedule.ai.model-dir:classpath:/models}") String modelDir,
            @Value("${sports.schedule.ai.teacher-model:teacher_gnn.onnx}") String modelName) {
        this.enabled = enabled;
        this.modelDir = modelDir;
        this.modelName = modelName;
    }

    public boolean available() {
        return enabled && ensureLoaded();
    }

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
            log.info("教师规避模型已就绪: {} ({})", modelName, ModelSource.describe(modelDir, modelName));
            return true;
        } catch (Throwable t) {
            loadError = t.getClass().getSimpleName() + ": " + t.getMessage();
            log.warn("教师规避模型不可用，回退规则规避: {}", loadError);
            return false;
        }
    }

    // ---------------------------------------------------------------- 编码（纯函数，可单测）
    /**
     * 编码。{@code batchClasses} 是本批有比赛的班级集合，用于算「项目关联度」——
     * 所带学生要比赛的教师必须先被保护，这是该模型最重要的一维。
     */
    static Encoded encode(List<TeacherInput> teachers, Set<String> batchClasses) {
        if (teachers == null || teachers.isEmpty()) {
            return degrade("无教师");
        }
        int n = Math.min(teachers.size(), MAX_NODES);
        if (n < 3) {
            return degrade("教师人数不足 3，模型无决策空间");
        }
        Set<String> batch = new LinkedHashSet<>(batchClasses == null ? List.of() : batchClasses);

        float[][] feat = new float[n][N_TCH_FEAT];
        for (int i = 0; i < n; i++) {
            TeacherInput t = teachers.get(i);
            Set<String> cls = t.classes() == null ? Set.of() : t.classes();
            double link = batch.isEmpty() || cls.isEmpty()
                    ? 0.0
                    : cls.stream().filter(batch::contains).count() / (double) batch.size();
            float[] f = feat[i];
            f[0] = (float) clamp01(cls.size() / 3.0);
            f[1] = (float) clamp01(link);
            f[2] = t.headTeacher() ? 1f : 0f;
            f[3] = (float) clamp01(t.busyRatio());
            f[4] = (float) clamp01(t.protectedCount() / 3.0);
            f[5] = (float) clamp01(t.adminWeight());
            f[6] = (float) clamp01(t.conflictHistory());
            f[7] = link > 0 ? 1f : 0f;
            f[8] = (float) clamp01(t.redundancy());
            f[9] = 1f;   // 时段紧度：由上层按需覆盖，默认满
        }

        float[][][] adj = new float[N_TYPES][n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                TeacherInput a = teachers.get(i);
                TeacherInput b = teachers.get(j);
                if (shares(a.classes(), b.classes())) {
                    set(adj, 0, i, j);
                }
                // ⚠️ 这里必须用**显式教研组**判定。曾经偷懒用「冗余度相近」近似，
                //    结果所有教师该值相同时整条通道全连 —— 边失去区分力还不报错。
                if (a.group() != null && !a.group().isBlank() && a.group().equals(b.group())) {
                    set(adj, 1, i, j);
                }
                if (a.protectedCount() > 0 && b.protectedCount() > 0) {
                    set(adj, 2, i, j);
                }
                if (Math.abs(a.adminWeight() - b.adminWeight()) < 1e-6) {
                    set(adj, 3, i, j);
                }
            }
        }
        float[] typeMask = new float[N_TYPES];
        Arrays.fill(typeMask, 1f);
        float[] mask = new float[n];
        Arrays.fill(mask, 1f);
        return new Encoded(feat, adj, typeMask, mask, n, false, "");
    }

    // ---------------------------------------------------------------- 推理
    public Optional<Advice> advise(Encoded enc) {
        if (enc == null || enc.degraded() || enc.n() < 3) {
            return Optional.empty();
        }
        if (!ensureLoaded()) {
            return Optional.empty();
        }
        int n = enc.n();
        float[][][] nodeFeat = new float[1][n][N_TCH_FEAT];
        nodeFeat[0] = enc.nodeFeat();
        float[][][][] adj = new float[1][N_TYPES][n][n];
        for (int t = 0; t < N_TYPES; t++) {
            adj[0][t] = enc.adjByType()[t];
        }
        try (ai.onnxruntime.OnnxTensor featT = ai.onnxruntime.OnnxTensor.createTensor(env, nodeFeat);
             ai.onnxruntime.OnnxTensor adjT = ai.onnxruntime.OnnxTensor.createTensor(env, adj);
             ai.onnxruntime.OnnxTensor typeT =
                     ai.onnxruntime.OnnxTensor.createTensor(env, new float[][]{enc.typeMask()});
             ai.onnxruntime.OnnxTensor maskT =
                     ai.onnxruntime.OnnxTensor.createTensor(env, new float[][]{enc.mask()})) {
            Map<String, ai.onnxruntime.OnnxTensor> feed = new LinkedHashMap<>();
            feed.put("node_feat", featT);
            feed.put("adj_by_type", adjT);
            feed.put("type_mask", typeT);
            feed.put("mask", maskT);
            try (ai.onnxruntime.OrtSession.Result result = session.run(feed)) {
                float[][] out = (float[][]) result.get(0).getValue();
                double[] aversion = new double[n];
                for (int i = 0; i < n; i++) {
                    aversion[i] = out[0][i];
                }
                return Optional.of(new Advice(aversion, n));
            }
        } catch (Throwable t) {
            log.warn("教师规避模型推理失败，回退规则规避: {}", t.toString());
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- 内部
    private static Encoded degrade(String reason) {
        return new Encoded(new float[0][], new float[N_TYPES][0][0], new float[N_TYPES],
                new float[0], 0, true, reason);
    }

    private static void set(float[][][] adj, int t, int i, int j) {
        adj[t][i][j] = 1f;
        adj[t][j][i] = 1f;
    }

    private static boolean shares(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return false;
        }
        for (String s : a) {
            if (b.contains(s)) {
                return true;
            }
        }
        return false;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }
}
