package com.sports.controller.ai;

import com.sports.common.web.ApiResponse;
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
 * AI 编排核心状态接口：可观测 ONNX 模型的加载状态。
 *
 * <p>AI 是「建议优先、规则回退」：模型缺失或加载失败时编排自动回退规则，接口仍可用；
 * 本接口用于运维侧确认当前到底跑在 AI 路径还是规则路径上。</p>
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "AI 编排核心", description = "ONNX 模型状态（算法选择器 / 冲突簇 GNN / GAN 生成器）")
public class AiController {

    private final OnnxInferenceService inference;
    private final SchemeGeneratorService schemeGenerator;

    @GetMapping("/status")
    @Operation(summary = "查询 AI 模型加载状态")
    public ApiResponse<Map<String, Object>> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("advisoryAvailable", inference.isAvailable());
        out.put("schemeGeneratorAvailable", schemeGenerator.isAvailable());
        out.put("mode", inference.isAvailable() ? "AI 建议优先" : "规则编排（AI 未就绪）");
        return ApiResponse.success(out);
    }
}
