package com.sports.entity.protection;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 行政时间保护（规避时间）。
 *
 * <p>记录「某人在某时间段不能参与协调/执裁」的规避窗口，对编排与裁判编排构成<b>硬约束</b>：</p>
 * <ul>
 *   <li>{@code targetType=GLOBAL}：全校统一避让时段（闭幕式 / 全校大会 / 考试等），
 *       编排时整段时间不可排任何项目（「对编排率先影响」）；</li>
 *   <li>{@code targetType=TEACHER}：班主任/教师个人不可用时段（{@code targetId}=用户 id），
 *       其班级参与的项目避开对应时间；</li>
 *   <li>{@code targetType=REFEREE}：裁判个人不可用时段（{@code targetId}=裁判 id），
 *       裁判编排（执裁分配）时该裁判不被分到落在受保护时段内的组次（裁判既是约束对象，
 *       也是保护的需求者）。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "admin_time_protection", indexes = {
        @Index(name = "idx_atp_type", columnList = "target_type"),
        @Index(name = "idx_atp_target", columnList = "target_type,target_id"),
        @Index(name = "idx_atp_day", columnList = "day")
})
@SQLRestriction("deleted_at IS NULL")
public class AdminTimeProtection {

    /** 全校统一避让时段 */
    public static final String TYPE_GLOBAL = "GLOBAL";
    /** 班主任 / 教师个人不可用时段 */
    public static final String TYPE_TEACHER = "TEACHER";
    /** 裁判个人不可用时段 */
    public static final String TYPE_REFEREE = "REFEREE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 保护对象类型：GLOBAL / TEACHER / REFEREE */
    @Column(name = "target_type", length = 20, nullable = false)
    private String targetType;

    /** 保护对象 id：TEACHER=用户 id、REFEREE=裁判 id；GLOBAL 为 null */
    @Column(name = "target_id")
    private Long targetId;

    /** 保护对象显示名（教师姓名 / 裁判姓名 / 「全校」，冗余存储便于列表展示） */
    @Column(name = "target_name", length = 50)
    private String targetName;

    /** 第几天（1-based）；null = 适用于全部天数 */
    @Column
    private Integer day;

    /** 开始时间 HH:mm */
    @Column(name = "start_time", length = 8, nullable = false)
    private String startTime;

    /** 结束时间 HH:mm */
    @Column(name = "end_time", length = 8, nullable = false)
    private String endTime;

    /** 原因说明（如「监考」「开会」） */
    @Column(length = 255)
    private String reason;

    @Column
    @Builder.Default
    private Boolean enabled = true;

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
