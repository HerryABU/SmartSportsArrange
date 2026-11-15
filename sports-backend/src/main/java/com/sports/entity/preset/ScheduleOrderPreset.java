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
 * 编排顺序预设方案：把一套「年级顺序 + 项目顺序 + 田赛分组 + 日期时段」配置命名保存，
 * 之后可一键切换/应用。快照以整份 meet_schedule JSON 存储，应用时灌回 SystemService，
 * 复用其归一化逻辑，避免顺序配置与日程配置各存一份导致漂移。
 *
 * <p>软删由类级 {@code @SQLRestriction} 兜底；恢复走原生 SQL（见 Repository）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "schedule_order_preset")
@SQLRestriction("deleted_at IS NULL")
public class ScheduleOrderPreset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 方案名（用户可读，如「短跑优先」「田赛优先」） */
    @Column(nullable = false, length = 80)
    private String name;

    /** 备注说明，可空 */
    @Column(length = 255)
    private String note;

    /** 整份 meet_schedule 配置快照（JSON），应用时原样灌回 SystemService.saveMeetSchedule */
    @Column(columnDefinition = "TEXT")
    private String snapshotJson;

    /** 展示排序号 */
    private Integer sortOrder;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (sortOrder == null) sortOrder = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
