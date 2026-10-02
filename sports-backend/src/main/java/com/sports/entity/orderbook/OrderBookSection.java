package com.sports.entity.orderbook;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sports.entity.meet.SportsMeet;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 秩序册「目录」节点：一章一节，管理员可以在设计器里自行增删、改名、调顺序。
 *
 * <p>和 {@link OrderBookEntry} 的分工：<b>目录只代表结构，细则才代表内容</b>。
 * 一本秩序册 = 若干目录（一、竞赛日程 / 二、参赛单位 / 三、…）+ 每个目录下的若干细则（表格或介绍文字）。
 * 之所以拆两张表而不是一条表里塞 {@code parentId} 自树：目录下要能看到自己的条目清单，
 * 独立表才能一次按 {@code section_id} 查全，不必自己拼树。</p>
 *
 * <p>{@code parentId} 用裸 Long 而不是 {@code @ManyToOne} 自引用：目录层级稳定且最多两层，
 * 引入自关联外键会让 SQLite 侧的 {@code SqliteForeignKeyMigrator} 多一轮表重建，收益却只是能写级联删除——
 * 而级联删除这里根本不需要（删目录只软删目录自身，其下细则另由
 * {@link OrderBookEntryService} 显式处理）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "order_book_section",
        indexes = {
                @Index(name = "idx_ob_section_meet", columnList = "meet_id"),
                @Index(name = "idx_ob_section_parent", columnList = "parent_id")
        })
@SQLRestriction("deleted_at IS NULL")
public class OrderBookSection {

    /** 系统内置章节（首次使用时自动铺好，管理员可改名/排序，但不建议删） */
    public static final String KIND_SYSTEM = "SYSTEM";
    /** 用户自己新增的目录 */
    public static final String KIND_CUSTOM = "CUSTOM";

    /** 一级目录 */
    public static final int LEVEL_TOP = 1;
    /** 二级（挂在某个目录下的小节） */
    public static final int LEVEL_SUB = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属届 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meet_id")
    private SportsMeet meet;

    /** 父目录 id（一级目录为 null；二级目录指向一级） */
    @Column(length = 20)
    private String parentId;

    /** 目录标题，如「一、竞赛日程」 */
    @Column(nullable = false, length = 120)
    private String title;

    /** SYSTEM / CUSTOM */
    @Column(nullable = false, length = 20)
    private String kind;

    /** 层级：1 一级 / 2 二级 */
    @Column(nullable = false)
    private Integer level;

    /** 同级展示排序（小在前） */
    @Column(nullable = false)
    private Integer sortOrder;

    /** 是否出现在生成的秩序册里 */
    @Column(nullable = false)
    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;

    @JsonProperty("meetId")
    public Long getMeetId() {
        return meet != null ? meet.getId() : null;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (kind == null) {
            kind = KIND_CUSTOM;
        }
        if (level == null) {
            level = LEVEL_TOP;
        }
        if (sortOrder == null) {
            sortOrder = 0;
        }
        if (enabled == null) {
            enabled = Boolean.TRUE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
