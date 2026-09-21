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
@Table(name = "result",
        uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "athlete_id", "round"}),
        indexes = {
                @Index(name = "idx_result_event", columnList = "event_id"),
                @Index(name = "idx_result_athlete", columnList = "athlete_id"),
                @Index(name = "idx_result_status", columnList = "status"),
                @Index(name = "idx_result_total_rank", columnList = "totalRank")
        })
public class Result {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(optional = false)
    @JoinColumn(name = "athlete_id", nullable = false)
    private Athlete athlete;

    /**
     * 赛次：preliminary=预赛成绩，final=决赛成绩，single=直接决赛。
     * 预赛淘汰项目按赛次分别录成绩与排名。
     */
    @Column(length = 20)
    @Builder.Default
    private String round = "final";

    @Column
    private Integer heat;

    @Column
    private Integer lane;

    @Column(length = 50)
    private String rawTime;

    @Column(nullable = true)
    private Double timeSeconds;

    @Column(nullable = true)
    private Integer heatRank;

    @Column(nullable = true)
    private Integer totalRank;

    @Column(nullable = true)
    private Double score;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isRecord = false;

    @Column(nullable = true)
    private Double windSpeed;

    @Column(length = 20, nullable = false)
    @Builder.Default
    private String status = "valid";

    @Column(nullable = true)
    private Long enteredBy;

    @Column(nullable = false)
    private LocalDateTime enteredAt;

    @Column(nullable = true)
    private Long reviewedBy;

    @Column(nullable = true)
    private LocalDateTime reviewedAt;

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
        if (enteredAt == null) {
            enteredAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
