package com.sports.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 裁判花名册（独立于登录账号的角色体系）。
 * <p>裁判只是「被编排的人力资源」，不拥有系统登录权限；通过智能编排引擎按
 * 「项目.组次裁判数量」分配到各个组次（heat / 组 / 轮）。</p>
 * <p>专长项目 {@link #specialties} 以 JSON 数组字符串存储（如 ["立定跳远","拔河"]），
 * Excel 导入时兼容 [a,b，c]（中英文逗号混合）写法。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "referee", indexes = {
        @Index(name = "idx_referee_name", columnList = "name"),
        @Index(name = "idx_referee_phone", columnList = "phone"),
        @Index(name = "idx_referee_status", columnList = "status")
})
@SQLRestriction("deleted_at IS NULL")
public class Referee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 裁判姓名 */
    @Column(length = 50, nullable = false)
    private String name;

    /** 联系电话 */
    @Column(length = 20)
    private String phone;

    /**
     * 专长项目：JSON 数组字符串，如 ["立定跳远","拔河","跳绳"]。
     * 来自 Excel 导入时兼容 [a,b，c]（中英文逗号混合）写法，统一落库为标准 JSON。
     */
    @Column(columnDefinition = "TEXT")
    private String specialties;

    @Column(length = 20)
    @Builder.Default
    private String status = "active";

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
