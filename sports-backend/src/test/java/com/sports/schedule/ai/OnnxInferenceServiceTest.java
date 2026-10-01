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
}
