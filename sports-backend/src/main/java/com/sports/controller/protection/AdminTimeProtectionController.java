package com.sports.controller.protection;

import com.sports.common.web.ApiResponse;
import com.sports.service.protection.AdminTimeProtectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 行政时间保护（规避时间）控制器。
 */
@Slf4j
@RestController
@RequestMapping("/api/protections")
@RequiredArgsConstructor
public class AdminTimeProtectionController {

    private final AdminTimeProtectionService protectionService;

    @GetMapping
    public ApiResponse<?> list() {
        return ApiResponse.success(protectionService.list());
    }

    @PostMapping
    public ApiResponse<?> create(@RequestBody Map<String, Object> body) {
        log.info("新增行政时间保护: {}", body);
        return ApiResponse.success("已添加规避时间", protectionService.create(body));
    }

    @PutMapping("/{id}")
    public ApiResponse<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        log.info("更新行政时间保护: id={}, body={}", id, body);
        return ApiResponse.success("已更新", protectionService.update(id, body));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        log.info("删除行政时间保护: id={}", id);
        protectionService.delete(id);
        return ApiResponse.success("已删除", null);
    }
}
