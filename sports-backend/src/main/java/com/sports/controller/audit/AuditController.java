package com.sports.controller.audit;

import com.sports.common.web.ApiResponse;
import com.sports.service.audit.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 操作审计日志接口（U16）
 */
@Slf4j
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    @GetMapping("/logs")
    public ApiResponse<?> logs(@RequestParam(required = false) String action,
                               @RequestParam(defaultValue = "100") int limit) {
        log.info("查询审计日志: action={}, limit={}", action, limit);
        return ApiResponse.success(auditService.list(action, limit));
    }
}
