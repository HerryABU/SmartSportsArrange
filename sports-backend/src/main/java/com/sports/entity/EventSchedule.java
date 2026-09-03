package com.sports.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 项目赛程编排（项目编排）—— 将比赛项目安排到具体的天/时段/场地
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "event_schedule", indexes = {
        @Index(name = "idx_event_schedule_event", columnList = "event_id"),
        @Index(name = "idx_event_schedule_day", columnList = "day")
})
public class EventSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id")
    private Event event;

    /** 第几天（1-based） */
    @Column(nullable = false)
    private Integer day;

    /** 具体日期 yyyy-MM-dd（由 meet_schedule 的 startDate 推算） */
    @Column(length = 10)
    private String scheduleDate;

    /** 年级（赛程按年级顺序展开；不分年级时为 null） */
    @Column(length = 20)
    private String grade;

    /** 时段，如 "上午" / "下午" / "晚上" */
    @Column(length = 20)
    private String timeSlot;

    /** 开始时间 HH:mm */
    @Column(length = 8)
    private String startTime;

    /** 结束时间 HH:mm */
    @Column(length = 8)
    private String endTime;

    /** 场地 */
    @Column(length = 50)
    private String venue;

    /** 顺序（同一天时段内的先后） */
    @Column
    private Integer sortOrder;

    /** 预计用时（分钟） */
    @Column
    private Integer durationMinutes;

    @Column(columnDefinition = "TEXT")
    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

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
