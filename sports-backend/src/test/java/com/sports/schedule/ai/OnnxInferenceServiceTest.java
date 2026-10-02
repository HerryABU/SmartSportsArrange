package com.sports.schedule.ai;

import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.ScheduleUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 端到端推理验证：Java 端 onnxruntime 加载训练侧（sports-ai）导出的 ONNX 并推理。
 *
 * <p>两条加载路径都要验：</p>
 * <ul>
 *   <li><b>classpath（jar 内）</b>——生产交付形态。模型随构建同步进 {@code resources/models}，
 *       打成单个 jar 分发即可用，不需要外部目录；</li>
 *   <li><b>外部目录</b>——热替换形态。便于现场换模型而不重新打包。</li>
 * </ul>
 *
 * <p>模型缺失时跳过（如全新 clone 未跑训练），不阻塞 CI；模型就绪时提供
 * 「Python 训练 → ONNX → Java 推理」的全链路证据。</p>
 */
@DisplayName("ONNX 端到端推理（模型就绪时）")
class OnnxInferenceServiceTest {

    private static final Path MODEL_DIR = Paths.get("../sports-ai/models");

    private static List<Placement> placements() {
        List<Placement> placements = new ArrayList<>();
        for (int start = 480; start <= 690; start += 10) {
            placements.add(new Placement("径赛", 0, 0, 1, "2026-01-01", "上午", "田径场",
                    start, 480, 210));
        }
        return placements;
    }

    /** 四个单元：a[1,2,3]、b[2,3]、c[3]、d[1] → 冲突边 a-b、a-c、a-d、b-c。 */
    private static List<ScheduleUnit> units(List<Placement> placements) {
        return List.of(
                unit("a", 100, new long[]{1L, 2L, 3L}, placements),
                unit("b", 200, new long[]{2L, 3L}, placements),
                unit("c", 150, new long[]{3L}, placements),
                unit("d", 120, new long[]{1L}, placements));
    }

    private static void assertAdvisoryIsSane(OnnxInferenceService svc) {
        List<Placement> placements = placements();
        List<ScheduleUnit> units = units(placements);
        Optional<AiAdvisory> advisory = svc.advise(units, placements);

        assertTrue(advisory.isPresent(), "模型就绪时应给出建议");
        AiAdvisory a = advisory.get();
        assertNotNull(a.strategy());
        assertTrue(a.cancelProbability() >= 0.0 && a.cancelProbability() <= 1.0,
                "取消概率应落在 [0,1]");
        assertEquals(units.size(), a.nodePriority().length, "优先级按单元数截断");
        // 中心节点（a，度 3）的优先级应最高
        int argmax = 0;
        for (int i = 1; i < a.nodePriority().length; i++) {
            if (a.nodePriority()[i] > a.nodePriority()[argmax]) {
                argmax = i;
            }
        }
        assertEquals(0, argmax, "中心节点 a（连接 b/c/d）应获得最高着色优先级");
    }

    @Test
    @DisplayName("classpath（jar 内）模型可加载并推理 —— 单包交付的端到端证据")
    void advisesFromClasspathModels() {
        assumeTrue(ModelSource.read("classpath:/models", "algorithm_selector.onnx").isPresent()
                        && ModelSource.read("classpath:/models", "conflict_gnn.onnx").isPresent(),
                "跳过：resources/models 下无模型（先执行 sports-ai 训练，再构建同步）");

        OnnxInferenceService svc = new OnnxInferenceService(
                true, "classpath:/models", "algorithm_selector.onnx", "conflict_gnn.onnx");
        assertTrue(svc.isAvailable(), "随 jar 交付的模型应能直接从 classpath 加载");
        assertAdvisoryIsSane(svc);
    }

    @Test
    @DisplayName("外部目录模型可加载并推理（支持不重新打包热替换模型）")
    void advisesFromExternalDirModels() {
        assumeTrue(Files.exists(MODEL_DIR.resolve("algorithm_selector.onnx"))
                        && Files.exists(MODEL_DIR.resolve("conflict_gnn.onnx")),
                "跳过：模型未导出（先执行 sports-ai/scripts/train.ps1）");

        OnnxInferenceService svc = new OnnxInferenceService(
                true, MODEL_DIR.toString(), "algorithm_selector.onnx", "conflict_gnn.onnx");
        assertAdvisoryIsSane(svc);
    }

    @Test
    @DisplayName("模型缺失时优雅回退（返回 empty，不抛异常）")
    void missingModelsDegradeGracefully() {
        OnnxInferenceService svc = new OnnxInferenceService(
                true, "../sports-ai/__missing__", "algorithm_selector.onnx", "conflict_gnn.onnx");
        assertTrue(svc.advise(List.of(), List.of()).isEmpty());

        OnnxInferenceService svc2 = new OnnxInferenceService(
                true, "classpath:/__no_such_models__", "algorithm_selector.onnx", "conflict_gnn.onnx");
        assertTrue(svc2.advise(List.of(), List.of()).isEmpty());
        assertFalse(svc2.isAvailable());
    }

    private static ScheduleUnit unit(String key, int rawDuration, long[] athletes, List<Placement> cands) {
        return new ScheduleUnit(key, 1L, "项目" + key, "高一", true, "径赛", null, 5,
                rawDuration, 10, athletes, List.of(rawDuration), cands);
    }

    /**
     * 核心问题：<b>模型到底有没有在「根据这些点位与输入」推理？</b>
     *
     * <p>能返回建议 ≠ 在推理。若实现里存在「无论输入什么都返回同一份常量」的情况，
     * 上面的「模型可加载」用例照样会绿。所以这里用三组对照把「输入 → 输出」的因果钉死：
     * ① 只有需求时长不同（→ 张量 tension 不同）→ 取消概率必须不同；
     * ② 只有兼项关系不同（→ 冲突边/度数不同）→ 节点优先级必须不同；
     * ③ 特征向量本身逐维随输入变化。</p>
     */
    @Test
    @DisplayName("推理结果随编排输入变化：同一模型吃不同点位/兼项必须给出不同建议（非常量返回）")
    void adviceActuallyDependsOnInput() {
        assumeTrue(new OnnxInferenceService(true, MODEL_DIR.toString(),
                "algorithm_selector.onnx", "conflict_gnn.onnx").isAvailable(),
                "模型未就绪，跳过");

        OnnxInferenceService svc = new OnnxInferenceService(true, MODEL_DIR.toString(),
                "algorithm_selector.onnx", "conflict_gnn.onnx");
        List<Placement> places = placements();

        // ① 同一批运动员、同一批点位，只把单元时长从 10 分钟拉到 300 分钟
        //    → InstanceFeatures 的 demand/tension_ratio 变化 → 选择器输出应随之变化
        List<ScheduleUnit> light = List.of(
                unit("a", 10, new long[]{1L, 2L, 3L}, places),
                unit("b", 10, new long[]{2L, 3L}, places),
                unit("c", 10, new long[]{3L}, places));
        List<ScheduleUnit> heavy = List.of(
                unit("a", 300, new long[]{1L, 2L, 3L}, places),
                unit("b", 300, new long[]{2L, 3L}, places),
                unit("c", 300, new long[]{3L}, places));

        Optional<AiAdvisory> lightAdvice = svc.advise(light, places);
        Optional<AiAdvisory> heavyAdvice = svc.advise(heavy, places);
        assertTrue(lightAdvice.isPresent() && heavyAdvice.isPresent(), "两组都应给出建议");

        assertNotEquals(lightAdvice.get().cancelProbability(), heavyAdvice.get().cancelProbability(),
                1e-9, "需求时长翻了 30 倍，选择器的取消概率必须跟着变；"
                        + "若相等说明模型输出与输入无关（常量返回或特征没喂进去）");

        // ② 只改「谁和谁同场」→ 冲突图不同 → GNN 优先级必须不同。
        //    注意：兼项关系**本来就会**体现在特征里（multi_ratio / edges / density），
        //    所以不能断言两组特征全等，那只会证明特征是常量。这里钉的是「边从无到有」这条因果链。
        List<ScheduleUnit> noOverlap = List.of(
                unit("a", 60, new long[]{1L, 2L}, places),
                unit("b", 60, new long[]{3L, 4L}, places));
        List<ScheduleUnit> chained = List.of(
                unit("a", 60, new long[]{1L, 2L}, places),
                unit("b", 60, new long[]{2L, 3L}, places));
        double[] fNoOverlap = InstanceFeatures.extract(noOverlap, places);
        double[] fChained = InstanceFeatures.extract(chained, places);
        assertEquals(0.0, fNoOverlap[4], 1e-9, "两组互不重叠时无人兼项");
        assertEquals(0.0, fNoOverlap[6], 1e-9, "两组互不重叠时冲突边为 0");
        // InstanceFeatures 里 multiRatio / tension 等都过了 round4 量化，这里按同精度断言
        assertEquals(0.3333, fChained[4], 1e-4, "3 人中 1 人（2 号）兼项 → 占比 1/3");
        assertEquals(1.0, fChained[6], 1e-9, "a-b 共享 2 号运动员 → 冲突边 1 条");

        double[] pNoOverlap = svc.advise(noOverlap, places).orElseThrow().nodePriority();
        double[] pChained = svc.advise(chained, places).orElseThrow().nodePriority();
        assertFalse(java.util.Arrays.equals(pNoOverlap, pChained),
                "兼项关系不同（无边 vs a-b 相连）时 GNN 优先级不应完全相同");

        // ③ 特征向量随输入变化：需求时长翻了 30 倍，tension 必须跟着动
        double[] fLight = InstanceFeatures.extract(light, places);
        double[] fHeavy = InstanceFeatures.extract(heavy, places);
        assertNotEquals(fLight[1], fHeavy[1], 1e-9, "需求总时长应随单元时长变化");
        assertNotEquals(fLight[3], fHeavy[3], 1e-9, "tension_ratio 应随需求变化");
    }
}
