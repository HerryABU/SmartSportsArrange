package com.sports.controller;

import com.sports.common.ApiResponse;
import com.sports.service.ScheduleService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 项目赛程编排控制器（项目编排）
 */
@Slf4j
@RestController
@RequestMapping("/api/schedule")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    /** 查看当前赛程 */
    @GetMapping
    public ApiResponse<?> list() {
        return ApiResponse.success(scheduleService.list());
    }

    /** 自动编排赛程 */
    @PostMapping("/auto")
    public ApiResponse<?> autoSchedule(@RequestBody(required = false) Map<String, Object> config) {
        log.info("自动编排项目赛程: config={}", config);
        return ApiResponse.success("赛程编排完成", scheduleService.autoSchedule(config));
    }

    /** 手动保存赛程（替换全部） */
    @PostMapping("/save")
    public ApiResponse<?> save(@RequestBody List<Map<String, Object>> items) {
        log.info("手动保存赛程: {}条", items != null ? items.size() : 0);
        return ApiResponse.success("赛程保存成功", scheduleService.save(items != null ? items : List.of()));
    }

    /** 清空赛程 */
    @DeleteMapping
    public ApiResponse<?> clear() {
        scheduleService.clear();
        return ApiResponse.success("赛程已清空", null);
    }

    /** 导出赛程 Excel */
    @GetMapping("/export")
    public void export(HttpServletResponse response) {
        scheduleService.export(response);
    }
}
