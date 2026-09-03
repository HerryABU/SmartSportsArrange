package com.sports.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 入场式（开幕式方阵）得分 —— 需手动录入或按 Excel 导入。
 * 一个班级一条记录；合分时可选择「含入场式」或「去除入场式」两种口径。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "parade_score",
        indexes = {
                @Index(name = "idx_parade_score_class", columnList = "class_info_id"),
                @Index(name = "idx_parade_score_grade", columnList = "grade")
        })
@SQLRestriction("deleted_at IS NULL")
public class ParadeScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_info_id")
    private ClassInfo classInfo;

    /** 班级名称冗余（导入时便于校验，序列化给前端） */
    @Column(length = 50)
    private String className;

    @Column(length = 20)
    private String grade;

    /** 入场式得分（百分制或十分制由使用者约定） */
    @Column(nullable = false)
    private Double score;

    /** 该班级入场式名次（可选，录入时可按分数自动排） */
    @Column
    private Integer rank;

    @Column(length = 255)
    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;

    @JsonProperty("classId")
    public Long getClassInfoId() {
        return classInfo != null ? classInfo.getId() : null;
    }

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
