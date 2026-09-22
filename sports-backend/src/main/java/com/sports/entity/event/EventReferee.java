package com.sports.entity.event;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import com.sports.entity.arrange.Arrangement;

/**
 * 组次-裁判分配结果表（编排引擎产出）。
 * <p>每个 (event × grade × gender × round × heat) 的「组次」对应一行，
 * 记录该组次实际安排到的裁判。自然键由以下字段共同决定：</p>
 * <ul>
 *   <li>{@link #event} 项目</li>
 *   <li>{@link #grade} 年级组（空 = 适用于该项目全部年级组）</li>
 *   <li>{@link #gender} 性别限制（M/F/空 = 不限）</li>
 *   <li>{@link #round} 赛次（preliminary / final / single）</li>
 *   <li>{@link #heat} 组次号</li>
 * </ul>
 * <p>与 {@link Arrangement} 的分组键（event × grade × gender × round × heat）保持一致，
 * 便于在编排视图中按组次直接挂载裁判。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "event_referee", indexes = {
        @Index(name = "idx_er_event", columnList = "event_id"),
        @Index(name = "idx_er_round", columnList = "round"),
        @Index(name = "idx_er_heat", columnList = "heat")
})
public class EventReferee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    /** 年级组；为空表示适用于该项目全部年级组 */
    @Column(length = 20)
    private String grade;

    /** 性别限制：M / F / 空（空 = 不限） */
    @Column(length = 1)
    private String gender;

    /** 赛次：preliminary / final / single */
    @Column(length = 20)
    private String round;

    /** 组次号（heat） */
    @Column
    private Integer heat;

    /** 已分配裁判 ID 列表（JSON 数组字符串） */
    @Column(columnDefinition = "TEXT")
    private String refereeIds;

    /** 已分配裁判姓名列表（JSON 数组字符串，冗余存储便于展示） */
    @Column(columnDefinition = "TEXT")
    private String refereeNames;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
