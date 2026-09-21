package com.sports.service;

import com.sports.entity.AuditLog;
import com.sports.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 操作审计服务（U16）：记录导入、编排、成绩修改、锁定等关键动作，可追溯谁在何时改了什么。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    /** 记录一条审计日志（独立事务，失败不影响主流程） */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String targetType, Long targetId, String detail) {
        try {
            AuditLog entry = AuditLog.builder()
                    .action(action)
                    .targetType(targetType)
                    .targetId(targetId)
                    .detail(detail)
                    .operator(currentOperator())
                    .build();
            auditLogRepository.save(entry);
        } catch (Exception e) {
            log.warn("写审计日志失败（已忽略）: action={}, {}", action, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(String action, int limit) {
        Pageable pageable = PageRequest.of(0, Math.max(1, Math.min(limit, 500)));
        List<AuditLog> logs = (action == null || action.isBlank())
                ? auditLogRepository.findAllByOrderByCreatedAtDesc(pageable)
                : auditLogRepository.findByActionOrderByCreatedAtDesc(action, pageable);
        return Map.of("records", logs, "total", logs.size());
    }

    private String currentOperator() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && auth.getName() != null
                    && !"anonymousUser".equals(auth.getName())) {
                return auth.getName();
            }
        } catch (Exception ignored) {
        }
        return "system";
    }
}
