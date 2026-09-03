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
@Table(name = "registration",
        uniqueConstraints = @UniqueConstraint(columnNames = {"athlete_id", "event_id"}),
        indexes = {
                @Index(name = "idx_registration_athlete", columnList = "athlete_id"),
                @Index(name = "idx_registration_event", columnList = "event_id"),
                @Index(name = "idx_registration_status", columnList = "status")
        })
public class Registration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "athlete_id", nullable = false)
    private Athlete athlete;

    @ManyToOne(optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(length = 20, nullable = false)
    @Builder.Default
    private String status = "pending";

    // ==================== 团体赛支持（表格1 的 G 列） ====================

    /** 是否团体赛报名（冗余自 event.team，便于查询） */
    @Column
    @Builder.Default
    @com.fasterxml.jackson.annotation.JsonProperty("isTeam")
    private Boolean team = false;

    /**
     * 队伍序号：同一班级在同一项目可报多支队伍（如 4×100 接力报两队），1-based。
     * 非团体赛固定为 0。
     */
    @Column
    @Builder.Default
    private Integer teamNo = 0;

    /** 队伍显示名，如"高一(1)班 1 队" */
    @Column(length = 50)
    private String teamName;

    /** 报名来源：onsite=班主任现场报名；offline=后置导入（已报名表导入） */
    @Column(length = 20)
    @Builder.Default
    private String source = "onsite";

    @Column(nullable = true)
    private Long registeredBy;

    @Column(nullable = false)
    private LocalDateTime registrationTime;

    @Column(nullable = true)
    private LocalDateTime auditTime;

    @Column(nullable = true)
    private Long auditorId;

    @Column(length = 255, nullable = true)
    private String auditRemark;

    @Column(length = 255, nullable = true)
    private String remark;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (registrationTime == null) {
            registrationTime = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
