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
@Table(name = "arrangement",
        uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "athlete_id", "round"}),
        indexes = {
                @Index(name = "idx_arrangement_event", columnList = "event_id"),
                @Index(name = "idx_arrangement_heat", columnList = "heat"),
                @Index(name = "idx_arrangement_lane", columnList = "lane"),
                @Index(name = "idx_arrangement_grade", columnList = "grade"),
                @Index(name = "idx_arrangement_gender", columnList = "gender"),
                @Index(name = "idx_arrangement_round", columnList = "round")
        })
public class Arrangement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(optional = false)
    @JoinColumn(name = "athlete_id", nullable = false)
    private Athlete athlete;

    @Column(length = 20)
    private String grade;

    @Column(length = 1)
    private String gender;

    @Column
    private Integer heat;

    @Column
    private Integer lane;

    /**
     * 赛次：preliminary=预赛，final=决赛，single=直接决赛（无预赛）。
     * 仅当项目 needHeats=true 时才会出现预赛。
     */
    @Column(length = 20)
    @Builder.Default
    private String round = "final";

    /** 是否晋级下一赛次（预赛淘汰「立刻计算」的结果） */
    @Column
    @Builder.Default
    private Boolean qualified = false;

    /** 预赛名次（预赛淘汰计算时写入） */
    @Column
    private Integer prelimRank;

    /** 预赛成绩文本（如 12.34 / 1:02.5，用于淘汰计算） */
    @Column(length = 50)
    private String prelimTime;

    /** 预赛成绩（秒） */
    @Column
    private Double prelimTimeSeconds;

    /** 团体赛：所属队伍序号（同队同号） */
    @Column
    @Builder.Default
    private Integer teamNo = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isManual = false;

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
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
