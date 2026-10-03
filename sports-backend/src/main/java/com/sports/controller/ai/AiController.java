package com.sports.controller.ai;

import com.sports.common.web.ApiResponse;
import com.sports.schedule.ai.AdversarialSchemeService;
import com.sports.schedule.ai.OnnxInferenceService;
import com.sports.schedule.ai.SchemeGeneratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 编排核心状态接口：可观测 ONNX 模型的加载状态与**模型来源**。
 *
 * <p>AI 是「建议优先、规则回退」：模型缺失或加载失败时编排自动回退规则，接口仍可用；
 * 本接口用于运维侧确认当前到底跑在 AI 路径还是规则路径上，以及模型来自 jar 内
 * （{@code classpath:/models}）还是外部目录（热替换）。</p>
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "AI 编排核心", description = "ONNX 模型状态（算法选择器 / 冲突簇 GNN / GAN 生成器 / 推理时自对抗）")
public class AiController {

    private final OnnxInferenceService inference;
    private final SchemeGeneratorService schemeGenerator;
    private final AdversarialSchemeService adversarial;
    private final com.sports.schedule.ai.LaneAdvisorService laneAdvisor;

    @GetMapping("/status")
    @Operation(summary = "查询 AI 模型加载状态")
    public ApiResponse<Map<String, Object>> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("advisoryAvailable", inference.isAvailable());
        out.put("schemeGeneratorAvailable", schemeGenerator.isAvailable());
        out.put("adversarialAvailable", adversarial.isAvailable());
        out.put("laneAdvisorAvailable", laneAdvisor.isAvailable());
        out.put("mode", inference.isAvailable() ? "AI 建议优先" : "规则编排（AI 未就绪）");
        out.put("adversarialMode", "推理时自对抗：G 生成 → 精修器精修 → D 评判 → 多轮择优");
        out.put("laneMode", laneAdvisor.isAvailable()
                ? "道次 AI 派遣（款型 ai）可用" : "道次编排使用既有排序（AI 未就绪）");

        // 模型来源与逐模型明细——运维最常问的两个问题：
        // 「模型到底打进去了吗」「我替换外部模型生效了吗」，这里直接给出答案。
        Map<String, Object> models = new LinkedHashMap<>();
        models.put("advisory", inference.modelInfo());
        models.put("schemeGenerator", schemeGenerator.modelInfo());
        models.put("adversarial", adversarial.modelInfo());
        models.put("laneAdvisor", laneAdvisor.modelInfo());
        out.put("models", models);
        out.put("modelSource", String.valueOf(inference.modelInfo().get("modelDir")));

        // 推理后端（CPU / DirectML / CUDA）与降级原因——「机器有显卡却没跑在显卡上」
        // 是最常见的困惑点，这里直接给出实际生效的 EP、探测过程与 OS 架构。
        out.put("execution", com.sports.schedule.ai.OnnxSessionFactory.get().describe());
        return ApiResponse.success(out);
    }
}
