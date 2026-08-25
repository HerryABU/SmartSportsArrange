package com.sports.controller;

import com.sports.common.ApiResponse;
import com.sports.service.BackupService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;

/**
 * 数据库备份控制器（仅超级管理员）
 */
@Slf4j
@RestController
@RequestMapping("/api/backup")
@RequiredArgsConstructor
public class BackupController {

    private final BackupService backupService;

    /** 备份列表 */
    @GetMapping("/list")
    public ApiResponse<?> list() {
        return ApiResponse.success(backupService.list());
    }

    /** 手动备份 */
    @PostMapping("/now")
    public ApiResponse<?> backupNow() {
        log.info("手动备份数据库");
        return ApiResponse.success("备份完成", backupService.backupNow());
    }

    /** 删除备份 */
    @DeleteMapping("/{fileName}")
    public ApiResponse<?> delete(@PathVariable String fileName) {
        backupService.delete(fileName);
        return ApiResponse.success("删除成功", null);
    }

    /** 下载备份 */
    @GetMapping("/download/{fileName}")
    public ResponseEntity<Resource> download(@PathVariable String fileName) {
        File f = backupService.getBackupFile(fileName);
        Resource resource = new FileSystemResource(f);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + fileName)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}
