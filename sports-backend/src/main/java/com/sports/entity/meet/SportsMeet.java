package com.sports.entity.meet;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 届 / 运动会（数据库一等公民）。
 *
 * <p>历史版本里「届次」只是导出封面的文本字段，系统是「单届隐式」——所有成绩 / 报名 / 出场
 * 都默认属于同一届。本实体把「第 X 届 Y 季节运动会」显式建模到数据库，使系统支持
 * <b>全量多届</b>：同一运动员可跨多届参赛，成绩按届归属，从而支撑跨届「进步榜」。</p>
 *
 * <p>字段语义：</p>
 * <ul>
 *   <li>{@code edition} —— 届次 X（如 3 表示第 3 届）。</li>
 *   <li>{@code season} —— 季节（自由文本，如「秋季」「春季」「冬季」），与 edition 拼成名称。</li>
 *   <li>{@code year} —— 举办年份（用于年级递归与自然升级计算）。</li>
 *   <li>{@code name} —— 展示名 {@code 第X届Y季节运动会}，由 {@code composeName} 生成并落库。</li>
 *   <li>{@code active} —— 是否为「当前届」：全校同时仅一个 active。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sports_meet", indexes = {
        @Index(name = "idx_meet_active", columnList = "active"),
        @Index(name = "idx_meet_year", columnList = "year")
})
public class SportsMeet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 届次 X（第 X 届） */
    @Column(nullable = false)
    private Integer edition;

    /** 季节（自由文本）：秋季 / 春季 / 冬季 / 夏季 … */
    @Column(length = 20, nullable = false)
    private String season;

    /** 举办年份：年级递归与自然升级的基准 */
    @Column(nullable = false)
    private Integer year;

    /** 展示名：第X届Y季节运动会（由 composeName 生成） */
    @Column(length = 60, nullable = false)
    private String name;

    /** 运动会地点 */
    @Column(length = 60)
    private String location;

    /** 是否为当前届（全校同时仅一个） */
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = false;

    /** 开始日期 */
    private LocalDate startDate;

    /** 结束日期 */
    private LocalDate endDate;

    @Column(columnDefinition = "TEXT")
    private String remark;

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

    /**
     * 由届次 + 季节拼装展示名：{@code 第X届Y季节运动会}。
     * 对 season 做防御：若用户误填已含「运动会 / 届」则不再重复拼接。
     */
    public static String composeName(Integer edition, String season) {
        String s = season == null ? "" : season.trim();
        if (s.isEmpty()) s = "运动";
        StringBuilder sb = new StringBuilder("第")
                .append(edition == null ? "?" : edition)
                .append("届");
        if (!s.contains("运动会")) {
            sb.append(s);
            if (!s.endsWith("运动会") && !s.endsWith("会")) sb.append("运动会");
        } else {
            // season 已是「秋季运动会」之类：去掉重复的「运动会」
            sb.append(s.replace("运动会", ""));
            sb.append("运动会");
        }
        return sb.toString();
    }
}
