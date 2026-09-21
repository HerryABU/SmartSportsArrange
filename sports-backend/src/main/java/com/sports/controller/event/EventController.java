package com.sports.controller.event;

import com.sports.common.web.ApiResponse;
import com.sports.entity.event.Event;
import com.sports.service.event.EventService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import com.sports.common.util.ExportNaming;

/**
 * 比赛项目控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @GetMapping
    public ApiResponse<List<Event>> list(
            @RequestParam(required = false) String grade,
            @RequestParam(required = false) String gender,
            @RequestParam(required = false) String eventType) {
        log.info("查询比赛项目列表: grade={}, gender={}, eventType={}", grade, gender, eventType);
        return ApiResponse.success(eventService.list(grade, gender, eventType));
    }

    @GetMapping("/{id}")
    public ApiResponse<Event> getById(@PathVariable Long id) {
        log.info("查询比赛项目详情: id={}", id);
        return ApiResponse.success(eventService.getById(id));
    }

    @PostMapping
    public ApiResponse<Event> create(@RequestBody @Valid Event event) {
        log.info("创建比赛项目: name={}", event.getName());
        return ApiResponse.success("创建成功", eventService.create(event));
    }

    /** 部分更新：仅覆盖请求中出现的字段（批量修改单个字段不会误伤其它字段） */
    @PutMapping("/{id}")
    public ApiResponse<Event> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        log.info("更新比赛项目: id={}, 字段={}", id, body.keySet());
        return ApiResponse.success("更新成功", eventService.update(id, body));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Event> updateStatus(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        Boolean enabled = body.get("enabled");
        if (enabled == null) {
            throw new IllegalArgumentException("缺少 enabled 参数");
        }
        log.info("{}比赛项目: id={}", enabled ? "启用" : "禁用", id);
        return ApiResponse.success(enabled ? "启用成功" : "禁用成功", eventService.updateStatus(id, enabled));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        log.info("删除比赛项目: id={}", id);
        eventService.delete(id);
        return ApiResponse.success("删除成功", null);
    }

    // ==================== 批量操作（体育老师/管理员） ====================

    /** 批量新增：body = Event[]，逐条校验，返回 成功/失败 明细 */
    @PostMapping("/batch")
    public ApiResponse<Map<String, Object>> batchCreate(@RequestBody List<Event> events) {
        log.info("批量新增比赛项目: count={}", events == null ? 0 : events.size());
        return ApiResponse.success("批量创建完成", eventService.batchCreate(events == null ? List.of() : events));
    }

    /** 批量部分更新：body = { ids: Long[], patch: {...} }（仅 patch 中出现的字段生效） */
    @PutMapping("/batch")
    public ApiResponse<Map<String, Object>> batchUpdate(@RequestBody Map<String, Object> body) {
        List<Long> ids = castIds(body.get("ids"));
        Map<String, Object> patchMap = castPatch(body.get("patch"));
        log.info("批量更新比赛项目: count={}, 字段={}", ids.size(), patchMap.keySet());
        return ApiResponse.success("批量更新完成", eventService.batchUpdate(ids, patchMap));
    }

    /** 批量启用/禁用：body = { ids: Long[], enabled: boolean } */
    @PostMapping("/batch-status")
    public ApiResponse<Map<String, Object>> batchStatus(@RequestBody Map<String, Object> body) {
        List<Long> ids = castIds(body.get("ids"));
        Boolean enabled = body.get("enabled") != null && Boolean.TRUE.equals(body.get("enabled"));
        log.info("批量{}比赛项目: count={}", enabled ? "启用" : "禁用", ids.size());
        return ApiResponse.success(enabled ? "批量启用完成" : "批量禁用完成", eventService.batchStatus(ids, enabled));
    }

    /** 批量删除（软删除）：body = { ids: Long[] } */
    @PostMapping("/batch-delete")
    public ApiResponse<Map<String, Object>> batchDelete(@RequestBody Map<String, Object> body) {
        List<Long> ids = castIds(body.get("ids"));
        log.info("批量删除比赛项目: count={}", ids.size());
        return ApiResponse.success("批量删除完成", eventService.batchDelete(ids));
    }

    private List<Long> castIds(Object o) {
        List<Long> ids = new java.util.ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object v : list) {
                if (v instanceof Number n) ids.add(n.longValue());
                else if (v != null) {
                    try { ids.add(Long.parseLong(v.toString())); } catch (NumberFormatException ignored) { }
                }
            }
        }
        return ids;
    }

    private Map<String, Object> castPatch(Object o) {
        return o instanceof Map<?, ?> m ? new java.util.HashMap<>((Map<String, Object>) m) : Map.of();
    }

    @PostMapping("/presets")
    public ApiResponse<?> getPresets(@RequestBody Map<String, Object> categoryFilter) {
        log.info("获取预设模板: category={}", categoryFilter);
        return ApiResponse.success(eventService.getPresets(categoryFilter));
    }

    @PostMapping("/import")
    public ApiResponse<?> importEvents(@RequestParam MultipartFile file) throws IOException {
        log.info("导入比赛项目: filename={}", file.getOriginalFilename());
        return ApiResponse.success("导入成功", eventService.importEvents(file));
    }

    @GetMapping("/export")
    public void export(HttpServletResponse response) throws IOException {
        log.info("导出比赛项目数据");
        eventService.exportEvents(response);
    }

    // ==================== JSON 导出 / 导入 / 模板（全字段往返） ====================

    /** 导出全部项目为 JSON（含 meta / defaults / events 全字段） */
    @GetMapping("/export/json")
    public void exportJson(HttpServletResponse response) throws IOException {
        log.info("导出比赛项目 JSON");
        Map<String, Object> data = eventService.exportEventsJson();
        response.setContentType("application/json");
        response.setCharacterEncoding("utf-8");
        String fileName = "比赛项目_" + com.sports.common.util.ExportNaming.stamp() + ".json";
        String enc = java.net.URLEncoder.encode(fileName, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment;filename=" + enc + ";filename*=UTF-8''" + enc);
        objectMapper.writeValue(response.getWriter(), data);
    }

    /** 下载 JSON 导入模板（默认值 + 示例） */
    @GetMapping("/template/json")
    public void templateJson(HttpServletResponse response) throws IOException {
        log.info("下载比赛项目 JSON 模板");
        Map<String, Object> data = eventService.jsonTemplate();
        response.setContentType("application/json");
        response.setCharacterEncoding("utf-8");
        String enc = java.net.URLEncoder.encode("比赛项目模板.json", java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment;filename=" + enc + ";filename*=UTF-8''" + enc);
        objectMapper.writeValue(response.getWriter(), data);
    }

    /** 导入项目 JSON（全字段往返，按 code 覆盖/新增） */
    @PostMapping("/import/json")
    public ApiResponse<?> importJson(@RequestParam MultipartFile file) throws IOException {
        log.info("导入比赛项目 JSON: filename={}", file.getOriginalFilename());
        return ApiResponse.success("导入完成", eventService.importEventsJson(file));
    }
}