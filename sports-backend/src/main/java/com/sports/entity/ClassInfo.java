package com.sports.entity;

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
@Table(name = "class_info")
@SQLRestriction("deleted_at IS NULL")
public class ClassInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 50, unique = true)
    private String name;

    @Column(length = 20)
    private String grade;

    @Column
    @Builder.Default
    private Integer gradeOrder = 0;

    @Column(name = "class_order")
    @Builder.Default
    private Integer classOrder = 0;

    @Column(length = 20, unique = true)
    private String code;

    @Column(length = 50)
    private String teacherName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_user_id")
    private User teacherUser;

    @Column(length = 20)
    private String phone;

    @Column
    @Builder.Default
    private Integer studentCount = 0;

    @Column
    @Builder.Default
    private Boolean isParticipating = true;

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
