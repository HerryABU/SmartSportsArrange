package com.sports.entity;

import com.fasterxml.jackson.annotation.JsonAlias;
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
     * 项目内并发人数（1~n）：同一时刻该项目可同时进行的人数（田赛=工位数/试跳位，径赛=每组人数）。
     * <p>径赛留空时按道次数（{@link #laneCount}）计算；田赛留空按 1（逐个进行）。</p>
     * <p>时长估算公式：轮次 = ceil(参赛人数 / concurrency)，径赛每轮 heatMinutes、田赛每轮 fieldPerAthleteMinutes。</p>
     */
    @Column
    @JsonProperty("concurrency")
    private Integer concurrency;

    /** 每组次人数（每组/每批同时上场人数）：径赛=道次、田赛=1、游泳=泳道数；用于折算轮次 */
    @Column
    @JsonProperty("groupSize")
    private Integer groupSize;

    /**
     * 田赛并行捆绑组：填同一个字母（如 A、B、C…）的项目视为一组，编排时**安排在同一时段并行进行**；
     * 留空表示不受限制、由编排算法自动安排。对应表格2 的「并行捆绑组」列。
     */
    @Column(length = 10)
    @JsonProperty("bundleGroup")
    private String bundleGroup;

    /**
     * 组次裁判数量：每个组次（heat / 组 / 轮）需要安排的裁判人数。
     * <p>田赛如立定跳远：一组次 5 人需 x 名裁判 → 填 x；拔河：一组 3 人 → 填 3；
     * 若 N 组并行，仍按单组填写，系统按「组次裁判数量」为每组分别安排。
     * 留空 / 0 表示该项目不需要安排裁判。</p>
     */
    @Column
    @JsonProperty("refereesPerGroup")
    private Integer refereesPerGroup;

    /**
     * 抽签（随机道次）：true 时组内道次按随机抽签分配（xxx、yyy 同组随机占位，
     * 而非按班级顺序固定 x 在 1 道、y 在 2 道）。仅作用于非人工锁定占用的道次。
     */
    @Column
    @Builder.Default
    @JsonProperty("drawLots")
    private Boolean drawLots = false;

    /**
     * @deprecated 已由「并发位数」模型取代（见 {@link #concurrency} 与全局 trackSlots/fieldSlots）。
     *             该字段仅为兼容历史数据保留，不再参与任何编排计算。
     */
    @Deprecated
    @Column(length = 20)
    @JsonProperty("scheduleMode")
    private String scheduleMode;

    /** 默认比赛场地名称 */
    @Column(length = 50)
    @JsonProperty("defaultVenue")
    private String defaultVenue;

    /**
     * 默认比赛场地编码（与全局 venues 的 code 对应）。
     * 指定后，该项目编排时固定使用对应场地（建立独立并发池），可与其他场地并行；
     * 例如游泳（属特殊径赛）指定独立场馆编码后，即可在主径赛场地之外「同排」。
     * 留空则按类别（径赛/田赛）使用默认并发池。对应表格2 的「场地编码」列。
     */
    @Column(length = 20)
    @JsonProperty("defaultVenueCode")
    private String defaultVenueCode;

    /**
     * 性别限制：中文组别（男子组/女子组/混合组）或 M/F；null = 不限性别。
     *
     * <p>U22/B19：JSON 契约主键为 {@code gender}（与前端 Events.vue 一致），
     * 但该字段的 Java 名 / Excel 导入列键是 {@code genderLimit}（见 ExcelService 的
     * COLUMN_ALIASES）。历史上有客户按字段名发 {@code genderLimit}，因不在契约里被 Jackson
     * 静默丢弃 → 库中为空 → 「不限性别」 → 自动编排时男女两组都去排，空组抛
     * 「没有符合条件的已审核报名记录」并毒化外层事务导致整个接口 500。
     * 这里用 {@link JsonAlias} 同时接受两种键名，从入口消除这一类静默丢字段的坑。</p>
     *
     * <p>兼容写法归一化见 {@link com.sports.common.GenderUtil}（男子组/M、女子组/F 双轨）。</p>
     */
    @Column(length = 10)
    @JsonProperty("gender")
    @JsonAlias({"genderLimit"})
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
