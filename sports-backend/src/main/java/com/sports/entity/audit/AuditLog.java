package com.sports.entity.audit;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 操作审计日志（U16）：记录导入、编排、成绩修改、锁定等关键动作，可追溯谁在何时改了什么。
 * 采用冗余文本字段（不建外键），避免删除对象后审计记录失效。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_action", columnList = "action"),
        @Index(name = "idx_audit_operator", columnList = "operator"),
        @Index(name = "idx_audit_created", columnList = "createdAt")
})
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 动作类型，如 IMPORT_SCORES / ARRANGE / SCORE_MODIFY / LOCK */
    @Column(length = 50, nullable = false)
    private String action;

    /** 操作对象类型，如 EVENT / ATHLETE / RESULT / ARRANGEMENT */
    @Column(length = 50)
    private String targetType;

    /** 操作对象 ID（可空） */
    @Column
    private Long targetId;

    /** 详情描述 */
    @Column(length = 1000)
    private String detail;

    /** 操作人（登录用户名，未登录为 system） */
    @Column(length = 100)
    private String operator;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
