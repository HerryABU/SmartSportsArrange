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
        uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "athlete_id"}),
        indexes = {
                @Index(name = "idx_arrangement_event", columnList = "event_id"),
                @Index(name = "idx_arrangement_heat", columnList = "heat"),
                @Index(name = "idx_arrangement_lane", columnList = "lane"),
                @Index(name = "idx_arrangement_grade", columnList = "grade"),
                @Index(name = "idx_arrangement_gender", columnList = "gender")
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
