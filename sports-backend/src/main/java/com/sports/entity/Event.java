package com.sports.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "event", indexes = {
        @Index(name = "idx_event_category", columnList = "category"),
        @Index(name = "idx_event_gender_limit", columnList = "gender_limit"),
        @Index(name = "idx_event_code", columnList = "code")
})
@SQLRestriction("deleted_at IS NULL")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 50)
    private String name;

    @Column(length = 20, unique = true)
    private String code;

    @Column(length = 20)
    @JsonProperty("eventType")
    private String category;

    @Column(length = 20)
    private String distanceType;

    // ==================== 表格2 字段（项目字典） ====================

    /**
     * 是否田径（径赛）。true=径赛（跑道竞速，占道次）；false=田赛（跳跃/投掷，不占道次）。
     * 对应表格2 的 C 列「是否田径」。
     */
    @Column
    @Builder.Default
    @JsonProperty("isTrack")
    private Boolean track = true;

    /**
     * 道次数。田赛固定为 0；径赛为实际跑道数（如 8）。
     * 对应表格2 的 D 列「道次（如果田赛，写0）」。
     */
    @Column
    @Builder.Default
    @JsonProperty("laneCount")
    private Integer laneCount = 8;

    /**
     * 是否团体赛（接力 / 集体项目）。对应表格1 的 G 列「是否团体赛，数量」。
     */
    @Column
    @Builder.Default
    @JsonProperty("isTeam")
    private Boolean team = false;

    /**
     * 团体赛每队人数；0 表示非团体赛。对应表格1 的 G 列数量部分。
     */
    @Column
    @Builder.Default
    @JsonProperty("teamSize")
    private Integer teamMembers = 0;

    // ==================== 赛程调度参数 ====================

    /** 单个项目最大用时（分钟）；为空时回退到全局配置 */
    @Column
    @JsonProperty("maxDurationMinutes")
    private Integer maxDurationMinutes;

    /** 该项目结束后与下一项目的间隔时间（分钟）；为空时回退到全局配置 */
    @Column
    @JsonProperty("intervalMinutes")
    private Integer intervalMinutes;

    /**
     * 赛程调度模式：serial=串行（独占场地依次进行）；parallel=并行（可与其他项目同时进行）。
     * 径赛通常串行、田赛通常并行；为空时按项目类别自动推断。
     */
    @Column(length = 20)
    @Builder.Default
    @JsonProperty("scheduleMode")
    private String scheduleMode = "serial";

    /** 默认比赛场地 */
    @Column(length = 50)
    @JsonProperty("defaultVenue")
    private String defaultVenue;

    @Column(length = 10)
    @JsonProperty("gender")
    private String genderLimit;

    /** 年级组（如"高一年级"、"初二年级"等） */
    @Column(length = 20)
    @JsonProperty("gradeGroup")
    private String gradeGroup;

    @Column
    @Builder.Default
    private Integer defaultLanes = 8;

    @Column
    @Builder.Default
    private Boolean needHeats = true;

    @Column
    @Builder.Default
    private Integer maxPerHeat = 8;

    /** 最大报名人数 */
    @Column
    @JsonProperty("maxParticipants")
    private Integer maxParticipants;

    @Column
    @Builder.Default
    private Integer advanceCount = 8;

    @Column(length = 20)
    @Builder.Default
    private String scoringType = "global";

    @Column(columnDefinition = "JSON")
    private String scoringRules;

    @Column
    @Builder.Default
    private Integer sortOrder = 0;

    @Column
    @Builder.Default
    @JsonProperty("enabled")
    private Boolean isEnabled = true;

    private LocalDateTime registrationStart;

    private LocalDateTime registrationEnd;

    @Column(length = 50)
    private String record;

    @Column(columnDefinition = "TEXT")
    @JsonProperty("description")
    private String remark;

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
