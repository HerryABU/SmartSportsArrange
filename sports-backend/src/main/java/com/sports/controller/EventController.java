package com.sports.controller;

import com.sports.common.ApiResponse;
import com.sports.entity.Event;
import com.sports.service.EventService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 比赛项目控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

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

    @PutMapping("/{id}")
    public ApiResponse<Event> update(@PathVariable Long id, @RequestBody @Valid Event event) {
        log.info("更新比赛项目: id={}", id);
        return ApiResponse.success("更新成功", eventService.update(id, event));
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

    /** 批量部分更新：body = { ids: Long[], patch: {...} }（patch 非空字段生效） */
    @PutMapping("/batch")
    public ApiResponse<Map<String, Object>> batchUpdate(@RequestBody Map<String, Object> body) {
        List<Long> ids = castIds(body.get("ids"));
        Map<String, Object> patchMap = castPatch(body.get("patch"));
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        Event patch = om.convertValue(patchMap, Event.class);
        log.info("批量更新比赛项目: count={}", ids.size());
        return ApiResponse.success("批量更新完成", eventService.batchUpdate(ids, patch));
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
}