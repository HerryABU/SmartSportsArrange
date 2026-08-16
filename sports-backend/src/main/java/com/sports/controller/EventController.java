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