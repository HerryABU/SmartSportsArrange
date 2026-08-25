package com.sports.controller;

import com.sports.common.ApiResponse;
import com.sports.service.DatabaseMigrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 数据库热迁移控制器（仅超级管理员）
 * 参照 WordPress / Discuz 数据库配置体验：查看当前库 → 选择目标类型 → 填连接参数 → 测试 → 迁移 → 进度反馈。
 */
@Slf4j
@RestController
@RequestMapping("/api/db-migration")
@RequiredArgsConstructor
public class DatabaseMigrationController {

    private final DatabaseMigrationService migrationService;

    /** 当前数据库信息 */
    @GetMapping("/current")
    public ApiResponse<?> current() {
        return ApiResponse.success(migrationService.getCurrentDbInfo());
    }

    /** 支持的目标数据库类型 */
    @GetMapping("/targets")
    public ApiResponse<?> targets() {
        return ApiResponse.success(migrationService.getSupportedTargets());
    }

    /** 测试目标库连接 */
    @PostMapping("/test")
    public ApiResponse<?> test(@RequestBody Map<String, Object> target) {
        log.info("测试数据库连接: type={}", target.get("type"));
        return ApiResponse.success(migrationService.testConnection(target));
    }

    /** 启动迁移（异步） */
    @PostMapping("/start")
    public ApiResponse<?> start(@RequestBody Map<String, Object> target) {
        log.info("启动数据库迁移: type={}", target.get("type"));
        return ApiResponse.success("迁移已启动", migrationService.startMigration(target));
    }

    /** 查询迁移进度 */
    @GetMapping("/progress/{taskId}")
    public ApiResponse<?> progress(@PathVariable String taskId) {
        return ApiResponse.success(migrationService.getProgress(taskId));
    }
}
