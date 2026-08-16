package com.sports.controller;

import com.sports.common.ApiResponse;
import com.sports.service.SystemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final SystemService systemService;

    // ---- 配置 ----

    @GetMapping("/config")
    public ApiResponse<?> getAllConfig() {
        log.info("获取全部系统配置");
        return ApiResponse.success(systemService.getAllConfig());
    }

    @GetMapping("/config/{key}")
    public ApiResponse<?> getConfig(@PathVariable String key) {
        log.info("获取系统配置: key={}", key);
        return ApiResponse.success(systemService.getConfig(key));
    }

    @PutMapping("/config/{key}")
    public ApiResponse<?> updateConfig(@PathVariable String key, @RequestBody Map<String, Object> body) {
        log.info("更新系统配置: key={}", key);
        return ApiResponse.success("配置更新成功", systemService.updateConfig(key, body));
    }

    @PutMapping("/config/basic")
    public ApiResponse<?> saveBasic(@RequestBody Map<String, Object> body) {
        log.info("保存基本设置");
        systemService.saveBasic(body);
        return ApiResponse.success("基本设置保存成功", null);
    }

    @PutMapping("/config/scoring")
    public ApiResponse<?> saveScoring(@RequestBody Map<String, Object> body) {
        log.info("保存积分规则");
        systemService.saveScoring(body);
        return ApiResponse.success("积分规则保存成功", null);
    }

    // ---- 年级 ----

    @GetMapping("/grades")
    public ApiResponse<?> getGrades() {
        log.info("查询年级列表");
        return ApiResponse.success(systemService.getGrades());
    }

    @PostMapping("/grades")
    public ApiResponse<?> addGrade(@RequestBody Map<String, Object> body) {
        log.info("新增年级: {}", body.get("name"));
        return ApiResponse.success("新增成功", systemService.addGrade(body));
    }

    @PutMapping("/grades/{id}")
    public ApiResponse<?> editGrade(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        log.info("编辑年级: id={}", id);
        return ApiResponse.success("编辑成功", systemService.editGrade(id, body));
    }

    @DeleteMapping("/grades/{id}")
    public ApiResponse<?> deleteGrade(@PathVariable Long id) {
        log.info("删除年级: id={}", id);
        systemService.deleteGrade(id);
        return ApiResponse.success("删除成功", null);
    }

    // ---- 健康检查 ----

    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.success(Map.of(
                "status", "UP",
                "service", "Sports Meet System",
                "timestamp", String.valueOf(System.currentTimeMillis())
        ));
    }

    @GetMapping("/logs")
    public ApiResponse<?> getLogs() {
        log.info("获取近期日志");
        return ApiResponse.success(systemService.getRecentLogs());
    }
}
