package com.sports.entity.preset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 项目预设模板方案：把一组「预设项目模板」（名称/项目类型/性别/分类）命名保存为一套，
 * 支持多套方案切换。此前前端 Events.vue 硬编码 20 条、后端 EventService.buildPresets 另有
 * 一套死代码且口径不一；此实体把预设数据收敛到数据库，前后端共用同一份。
 *
 * <p>templatesJson 存 {@code List<Map<String,Object>>}，每项含 name/eventType/gender/category。</p>
 * <p>软删由类级 {@code @SQLRestriction} 兜底；恢复走原生 SQL（见 Repository）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "event_template_preset")
@SQLRestriction("deleted_at IS NULL")
public class EventTemplatePreset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 方案名（如「默认模板」「田径专项」） */
    @Column(nullable = false, length = 80)
    private String name;

    @Column(length = 255)
    private String note;

    /** 模板项 JSON 数组 */
    @Column(columnDefinition = "TEXT")
    private String templatesJson;

    /** 展示排序号：内置方案排前 */
    private Integer sortOrder;

    /** 内置方案标记：内置项不可改名/删除，避免用户误删后无模板可用 */
    @Builder.Default
    private Boolean builtin = false;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (sortOrder == null) sortOrder = 0;
        if (builtin == null) builtin = Boolean.FALSE;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
