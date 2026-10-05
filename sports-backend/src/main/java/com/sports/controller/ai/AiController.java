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
    /** 组次错开建议模型（2026-10-05）：只排序，合法性仍由 HeatStaggerMath 裁定 */
    private final com.sports.schedule.ai.HeatStaggerAdvisorService heatStaggerAdvisor;
    /** 跨时段拆分优先级模型（2026-10-05）：只排序「先拆谁」，不判能否拆 */
    private final com.sports.schedule.ai.SlotSplitAdvisorService slotSplitAdvisor;
    /**
     * AI 分层调度门面（2026-10-05）：第一次编排走主 MoE，微调环节走专项 MoE。
     * 存在的意义是让「哪一环该用哪个模型」有唯一答案，并集中暴露调用次数。
     */
    private final com.sports.schedule.ai.AiTiers tiers;

    @GetMapping("/status")
    @Operation(summary = "查询 AI 模型加载状态")
    public ApiResponse<Map<String, Object>> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("advisoryAvailable", inference.isAvailable());
        out.put("schemeGeneratorAvailable", schemeGenerator.isAvailable());
        out.put("adversarialAvailable", adversarial.isAvailable());
        out.put("laneAdvisorAvailable", laneAdvisor.isAvailable());
        out.put("heatStaggerAdvisorAvailable", heatStaggerAdvisor.isAvailable());
        out.put("slotSplitAdvisorAvailable", slotSplitAdvisor.isAvailable());
        out.put("mode", inference.isAvailable() ? "AI 建议优先" : "规则编排（AI 未就绪）");
        out.put("adversarialMode", "推理时自对抗：G 生成 → 精修器精修 → D 评判 → 多轮择优");
        out.put("laneMode", laneAdvisor.isAvailable()
                ? "道次 AI 派遣（款型 ai）可用" : "道次编排使用既有排序（AI 未就绪）");
        // 分层调度：主 MoE（第一次编排）+ 两个专项 MoE（组次错开 / 跨时段拆分）。
        // tiers 里带 calls 计数与 wiredButNeverCalled —— 「模型可用但一次没被调用」
        // 是接线断了的唯一可观测信号，不报错、只是功能一直没生效。
        out.put("tiers", tiers.status());

        // 模型来源与逐模型明细——运维最常问的两个问题：
        // 「模型到底打进去了吗」「我替换外部模型生效了吗」，这里直接给出答案。
        Map<String, Object> models = new LinkedHashMap<>();
        models.put("advisory", inference.modelInfo());
        models.put("schemeGenerator", schemeGenerator.modelInfo());
        models.put("adversarial", adversarial.modelInfo());
        models.put("laneAdvisor", laneAdvisor.modelInfo());
        models.put("heatStaggerAdvisor", heatStaggerAdvisor.modelInfo());
        models.put("slotSplitAdvisor", slotSplitAdvisor.modelInfo());
        out.put("models", models);
        out.put("modelSource", String.valueOf(inference.modelInfo().get("modelDir")));

        // 推理后端（CPU / DirectML / CUDA）与降级原因——「机器有显卡却没跑在显卡上」
        // 是最常见的困惑点，这里直接给出实际生效的 EP、探测过程与 OS 架构。
        out.put("execution", com.sports.schedule.ai.OnnxSessionFactory.get().describe());

        // 冲突图规模：AI 在多大规模的编排上真正参与了（超过单层上限会自动启用分层推理）
        Object adv = models.get("advisory");
        if (adv instanceof Map<?, ?> advMap) {
            Map<String, Object> scale = new LinkedHashMap<>();
            for (String k : new String[]{"unitCount", "clusterCount", "singleLayerLimit", "hierarchical"}) {
                if (advMap.containsKey(k)) {
                    scale.put(k, advMap.get(k));
                }
            }
            if (!scale.isEmpty()) {
                out.put("conflictGraphScale", scale);
            }
        }

        // 超级编排模型（一个模型覆盖九类编排）：加载状态 + 覆盖范围
        out.put("superMoe", com.sports.schedule.ai.SuperMoeService.superModelInfo());

        return ApiResponse.success(out);
    }
}
