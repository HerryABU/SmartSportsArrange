package com.sports.entity.notification;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 站内通知（内通知）。
 *
 * <p>「通知班主任」等编排 / 兼项冲突消解动作产生的消息投递载体。
 * 收件人可为具体用户（{@code recipientUserId}）或整类角色（{@code recipientRole}，如 CLASS_TEACHER）。
 * 前端顶部通知中心按当前登录用户拉取未读/已读。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "notification", indexes = {
        @Index(name = "idx_notif_user", columnList = "recipient_user_id"),
        @Index(name = "idx_notif_role", columnList = "recipient_role"),
        @Index(name = "idx_notif_read", columnList = "is_read")
})
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 收件人用户 id（可为空 = 按角色广播） */
    @Column(name = "recipient_user_id")
    private Long recipientUserId;

    /** 收件人角色（可为空 = 按用户定向），如 CLASS_TEACHER / REFEREE */
    @Column(name = "recipient_role", length = 30)
    private String recipientRole;

    /** 标题 */
    @Column(length = 200, nullable = false)
    private String title;

    /** 内容 */
    @Column(columnDefinition = "TEXT")
    private String content;

    /** 通知类型（如 CONFLICT_CANCEL / ARRANGE_NOTICE） */
    @Column(length = 40)
    private String type;

    @Column(name = "is_read")
    @Builder.Default
    private Boolean isRead = false;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
