package com.sports.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "athlete", indexes = {
        @Index(name = "idx_athlete_name", columnList = "name"),
        @Index(name = "idx_athlete_class_info_id", columnList = "class_info_id"),
        @Index(name = "idx_athlete_number", columnList = "number"),
        @Index(name = "idx_athlete_grade", columnList = "grade"),
        @Index(name = "idx_athlete_status", columnList = "status")
})
@SQLRestriction("deleted_at IS NULL")
public class Athlete {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 50, nullable = false)
    private String name;

    @Column(length = 1)
    private String gender;

    @Column(length = 20)
    private String grade;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_info_id")
    private ClassInfo classInfo;

    @Column(length = 20, unique = true)
    private String number;

    @Column(length = 50, unique = true)
    @JsonProperty("studentNo")
    private String studentId;

    /**
     * 班级名称（序列化用，前端表格直接取 className，避免懒加载 classInfo 为 null）
     */
    @JsonProperty("className")
    public String getClassName() {
        if (classInfo == null) return null;
        try {
            return classInfo.getName();
        } catch (Exception e) {
            return null;
        }
    }

    @Column(length = 18, unique = true)
    private String idCard;

    private LocalDate birthDate;

    @Column(length = 50)
    private String emergencyContact;

    @Column(length = 20)
    private String emergencyPhone;

    @Column(columnDefinition = "TEXT")
    private String healthStatus;

    @Column(length = 255)
    private String photo;

    @Column(length = 20)
    @Builder.Default
    private String status = "normal";

    @Column(columnDefinition = "TEXT")
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