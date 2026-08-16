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
