package com.sports.entity.parade;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;
import com.sports.entity.clazz.ClassInfo;
import com.sports.entity.meet.SportsMeet;

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
                @Index(name = "idx_parade_score_class_grade", columnList = "class_info_id, grade"),
                @Index(name = "idx_parade_score_grade", columnList = "grade"),
                @Index(name = "idx_parade_score_meet", columnList = "meet_id"),
                @Index(name = "idx_parade_score_project", columnList = "project_code")
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

    /** 所属自定义项目编码（入场式=parade）；自定义项目区下每班每项目一条 */
    @Column(length = 40)
    private String projectCode;

    /** 项目名称冗余（序列化给前端，便于展示） */
    @Column(length = 60)
    private String projectName;

    /** 项目类型：PARADE / GYMNASTICS / CUSTOM（冗余，便于统计） */
    @Column(length = 20)
    private String type;

    /** 入场式得分（百分制或十分制由使用者约定） */
    @Column(nullable = false)
    private Double score;

    /** 所属届；历史数据经 MeetBackfillInitializer 回填默认届 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meet_id")
    private SportsMeet meet;

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
