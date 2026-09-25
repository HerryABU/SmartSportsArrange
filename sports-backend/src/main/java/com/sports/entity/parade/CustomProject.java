package com.sports.entity.parade;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;
import com.sports.entity.meet.SportsMeet;

/**
 * 自定义项目（自定义项目区）：入场式 / 广播操比赛 / 用户手动设立的任意集体项目。
 *
 * <p>一个项目一条记录，供「班级打分」复用——打分表 {@link ParadeScore} 通过 {@code projectCode}
 * 归属到本项目。{@code code} 在同一届内唯一（SQLite 唯一索引由 SqliteConstraintInitializer 兜底）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "custom_project",
        indexes = {
                @Index(name = "idx_custom_project_meet", columnList = "meet_id"),
                @Index(name = "idx_custom_project_code", columnList = "code")
        })
@SQLRestriction("deleted_at IS NULL")
public class CustomProject {

    public static final String TYPE_PARADE = "PARADE";       // 入场式
    public static final String TYPE_GYMNASTICS = "GYMNASTICS"; // 广播操
    public static final String TYPE_CUSTOM = "CUSTOM";        // 其他自定义

    public static final String DEFAULT_PARADE_CODE = "parade";
    public static final String DEFAULT_PARADE_NAME = "入场式";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目编码（同届唯一，业务主键）；入场式固定为 parade */
    @Column(nullable = false, length = 40)
    private String code;

    /** 项目名称：入场式 / 广播操比赛 / … */
    @Column(nullable = false, length = 60)
    private String name;

    /** 项目类型：PARADE / GYMNASTICS / CUSTOM */
    @Column(length = 20)
    private String type;

    /** 所属届 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meet_id")
    private SportsMeet meet;

    /** 展示排序（小在前） */
    @Column(nullable = false)
    private Integer sortOrder;

    /** 是否计入合分（true 时该项目的班级得分纳入总分口径） */
    @Column(nullable = false)
    private Boolean countInTotal;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;

    @JsonProperty("meetId")
    public Long getMeetId() {
        return meet != null ? meet.getId() : null;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (sortOrder == null) sortOrder = 0;
        if (countInTotal == null) countInTotal = Boolean.TRUE;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
