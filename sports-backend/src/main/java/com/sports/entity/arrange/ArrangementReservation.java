package com.sports.entity.arrange;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import com.sports.entity.event.Event;

/**
 * 编排「预留模拟空位」（项目级编排）。
 *
 * <p>项目级编排时，可在某组次（年级 × 性别 × 赛次 × 组次）预留若干**模拟空位**并标注**时间**，
 * 用于占位（如决赛待定名额、弃权/轮空位、直播/转场预留等）。这些空位不占用真实运动员，
 * 因此不写入 {@code arrangement} 表（该表 athlete 非空且有唯一约束），而是以本实体单独记录，
 * 在查看/导出编排时与真实编排合并展示。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "arrangement_reservation", indexes = {
        @Index(name = "idx_resv_event", columnList = "event_id"),
        @Index(name = "idx_resv_round", columnList = "round")
})
public class ArrangementReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(length = 20)
    private String grade;

    @Column(length = 1)
    private String gender;

    /** 赛次：preliminary=预赛，final=决赛 */
    @Column(length = 20)
    @Builder.Default
    private String round = "final";

    @Column
    private Integer heat;

    @Column
    private Integer lane;

    /** 预留时间（模拟空位的计划开始时间） */
    @Column
    private LocalDateTime scheduledTime;

    /** 空位类型：reserved=模拟空位（预留），byes=轮空/弃权预留 */
    @Column(length = 20)
    @Builder.Default
    private String kind = "reserved";

    @Column(length = 255)
    private String note;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
