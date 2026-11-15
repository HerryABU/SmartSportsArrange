package com.sports.controller.system;

import com.sports.common.web.ApiResponse;
import com.sports.service.preset.EventTemplatePresetService;
import com.sports.service.preset.ScheduleOrderPresetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 命名预设方案 API：编排顺序预设（整份日程配置快照）+ 项目模板预设（一组预设项目）。
 * 都由体育老师及以上维护；权限在 SecurityConfig 中配置（须排在 /api/system/** 通配之前）。
 */
@Slf4j
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class PresetController {

    private final ScheduleOrderPresetService orderPresetService;
    private final EventTemplatePresetService templatePresetService;

    // ==================== 编排顺序预设 ====================

    @GetMapping("/order-presets")
    public ApiResponse<?> listOrderPresets() {
        return ApiResponse.success(orderPresetService.list());
    }

    @PostMapping("/order-presets")
    public ApiResponse<?> saveOrderPreset(@RequestBody Map<String, Object> body) {
        return ApiResponse.success("方案已保存", orderPresetService.save(
                str(body.get("name")), str(body.get("note")), mapOf(body.get("snapshot"))));
    }

    @PutMapping("/order-presets/{id}")
    public ApiResponse<?> updateOrderPreset(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.success("方案已更新", orderPresetService.update(
                id, str(body.get("name")), str(body.get("note")), mapOf(body.get("snapshot"))));
    }

    /** 应用方案：把快照灌回当前日程配置；前端随后刷新配置与赛程即可 */
    @PostMapping("/order-presets/{id}/apply")
    public ApiResponse<?> applyOrderPreset(@PathVariable Long id) {
        return ApiResponse.success("方案已应用", orderPresetService.apply(id));
    }

    @DeleteMapping("/order-presets/{id}")
    public ApiResponse<?> deleteOrderPreset(@PathVariable Long id) {
        orderPresetService.delete(id);
        return ApiResponse.success("方案已删除");
    }

    @PostMapping("/order-presets/{id}/restore")
    public ApiResponse<?> restoreOrderPreset(@PathVariable Long id) {
        int n = orderPresetService.restore(id);
        return ApiResponse.success(n > 0 ? "方案已恢复" : "方案不存在或未被删除");
    }

    // ==================== 项目模板预设 ====================

    @GetMapping("/template-presets")
    public ApiResponse<?> listTemplatePresets() {
        return ApiResponse.success(templatePresetService.listWithDefaults());
    }

    @PostMapping("/template-presets")
    public ApiResponse<?> saveTemplatePreset(@RequestBody Map<String, Object> body) {
        return ApiResponse.success("方案已保存", templatePresetService.save(
                str(body.get("name")), str(body.get("note")), listOf(body.get("templates"))));
    }

    @PutMapping("/template-presets/{id}")
    public ApiResponse<?> updateTemplatePreset(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.success("方案已更新", templatePresetService.update(
                id, str(body.get("name")), str(body.get("note")), listOf(body.get("templates"))));
    }

    @DeleteMapping("/template-presets/{id}")
    public ApiResponse<?> deleteTemplatePreset(@PathVariable Long id) {
        templatePresetService.delete(id);
        return ApiResponse.success("方案已删除");
    }

    @PostMapping("/template-presets/{id}/restore")
    public ApiResponse<?> restoreTemplatePreset(@PathVariable Long id) {
        int n = templatePresetService.restore(id);
        return ApiResponse.success(n > 0 ? "方案已恢复" : "方案不存在或未被删除");
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object o) {
        if (!(o instanceof List)) return null;
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Object item : (List<?>) o) {
            if (item instanceof Map) out.add((Map<String, Object>) item);
        }
        return out;
    }
}
