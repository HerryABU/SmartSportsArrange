package com.sports.entity.orderbook;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 秩序册「细则」：某个目录下的<b>具体内容</b>，也就是管理员要写的介绍文字，或系统代填的数据表格。
 *
 * <p>两种身份，靠 {@code kind} + {@code sourceKey} 区分：</p>
 * <ul>
 *   <li>{@code SYSTEM}：系统据实生成的板块（{@code sourceKey} = SCHEDULE / EVENTS / CLASSES / ARRANGE / NUMBERS），
 *       {@code contentType} 为 {@code TABLE}，{@code content} 为空。数据每次从编排结果现取，管理员改不动——
 *       秩序册最不能出错的就是日程和号码，手写必然与编排结果不符。</li>
 *   <li>{@code CUSTOM}：用户新增的细则，{@code contentType} 为 {@code TEXT}，正文落在 {@code content}。
 *       比如「竞赛须知」「安全应急预案」「开幕式流程说明」这类纯介绍内容。</li>
 * </ul>
 *
 * <p>{@code content} 存纯文本（设计器用{@code <textarea>}），不做 HTML 富文本：
 * 富文本会带来 XSS 与 docx 转义两头麻烦，而秩序册的"介绍内容"多为几段话，
 * 纯文本 + 打印样式已经够看。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "order_book_entry",
        indexes = {
                @Index(name = "idx_ob_entry_section", columnList = "section_id"),
                @Index(name = "idx_ob_entry_source", columnList = "source_key")
        })
@SQLRestriction("deleted_at IS NULL")
public class OrderBookEntry {

    /** 系统板块 */
    public static final String KIND_SYSTEM = "SYSTEM";
    /** 用户新增 */
    public static final String KIND_CUSTOM = "CUSTOM";

    /** 正文型（介绍内容） */
    public static final String CONTENT_TEXT = "TEXT";
    /** 表格型（系统数据渲染） */
    public static final String CONTENT_TABLE = "TABLE";

    // ==================== 系统板块的 sourceKey ====================
    /** 竞赛日程 */
    public static final String SRC_SCHEDULE = "SCHEDULE";
    /** 竞赛项目设置（径赛 / 田赛 / 其他） */
    public static final String SRC_EVENTS = "EVENTS";
    /** 参赛单位（班级）名单 */
    public static final String SRC_CLASSES = "CLASSES";
    /** 分组与道次编排 */
    public static final String SRC_ARRANGE = "ARRANGE";
    /** 运动员号码对照 */
    public static final String SRC_NUMBERS = "NUMBERS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属目录 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "section_id")
    private OrderBookSection section;

    /** 细则标题（如「径赛分组与道次」）；系统板块可为空，由渲染层给标题 */
    @Column(length = 120)
    private String title;

    /** SYSTEM / CUSTOM */
    @Column(nullable = false, length = 20)
    private String kind;

    /** 系统板块的数据源；CUSTOM 为空 */
    @Column(length = 40)
    private String sourceKey;

    /** TEXT / TABLE */
    @Column(nullable = false, length = 20)
    private String contentType;

    /** 正文（contentType=TEXT 时有效） */
    @Column(columnDefinition = "TEXT")
    private String content;

    /** 展示排序（同目录下小在前） */
    @Column(nullable = false)
    private Integer sortOrder;

    /** 是否出现在生成的秩序册里 */
    @Column(nullable = false)
    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;

    @JsonProperty("sectionId")
    public Long getSectionId() {
        return section != null ? section.getId() : null;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (kind == null) {
            kind = KIND_CUSTOM;
        }
        if (contentType == null) {
            contentType = CONTENT_TEXT;
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
