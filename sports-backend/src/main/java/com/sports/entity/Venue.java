package com.sports.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 场地（运动场馆/区域）。
 *
 * <p>取代原先仅存于编排配置 JSON 的 venues 列表，成为编排引擎的「并行上限」数据来源：
 * 一个场地的 {@code parallelMax} 即该场地同一时刻可并行进行的项目数（1=串行，n=并行）。
 * 项目通过 {@code Event.defaultVenueCode} 绑定到具体场地，从而受该场地的并行上限约束。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "venue", indexes = {
        @Index(name = "idx_venue_code", columnList = "code"),
        @Index(name = "idx_venue_enabled", columnList = "enabled")
})
@SQLRestriction("deleted_at IS NULL")
public class Venue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 场地编码，如 TRACK-01 / FIELD-A / POOL-01（与 Event.defaultVenueCode 对应，唯一） */
    @Column(length = 32, unique = true, nullable = false)
    private String code;

    /** 场地名称，如 主跑道 / 跳远区A / 游泳馆 */
    @Column(length = 128, nullable = false)
    private String name;

    /** 场地类型：track / field / pool / other */
    @Column(length = 32, nullable = false)
    private String type;

    /** 该场地可容纳的项目数（冗余展示用，编排以 parallelMax 为准） */
    @Builder.Default
    private int capacity = 1;

    /** 该场地最大并行数：同一时刻可同时进行的项目数（1=串行，n=并行） */
    @Builder.Default
    private int parallelMax = 1;

    /** 展示/排序顺序 */
    @Builder.Default
    private int sortOrder = 0;

    @Builder.Default
    private boolean enabled = true;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
