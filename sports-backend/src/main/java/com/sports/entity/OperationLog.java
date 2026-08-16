package com.sports.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "operation_log",
        indexes = {
                @Index(name = "idx_operation_log_user_id", columnList = "userId"),
                @Index(name = "idx_operation_log_operation", columnList = "operation"),
                @Index(name = "idx_operation_log_module", columnList = "module"),
                @Index(name = "idx_operation_log_created_at", columnList = "createdAt")
        })
public class OperationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = true)
    private Long userId;

    @Column(length = 50, nullable = false)
    private String username;

    @Column(length = 50, nullable = false)
    private String operation;

    @Column(length = 50, nullable = false)
    private String module;

    @Column(length = 255, nullable = false)
    private String target;

    @Column(length = 255)
    private String requestUrl;

    @Column(length = 10)
    private String requestMethod;

    @Column(columnDefinition = "TEXT", nullable = true)
    private String requestParams;

    @Column
    private Integer responseStatus;

    @Column(length = 50)
    private String ipAddress;

    @Column(nullable = true)
    private Long duration;

    @Column(columnDefinition = "TEXT", nullable = true)
    private String errorMsg;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
