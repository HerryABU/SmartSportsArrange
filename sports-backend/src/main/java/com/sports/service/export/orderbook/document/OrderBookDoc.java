package com.sports.service.export.orderbook.document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一本秩序册的模型：封面信息 + 一串有序的块。
 *
 * <p>渲染器只依赖这个模型，不碰任何 JPA 实体 / Repository，所以预览和导出走的是同一份数据、
 * 不可能出现「预览有这一章、导出少了这一章」这种事。</p>
 */
public class OrderBookDoc {

    /** 封面 / 页眉信息。 */
    public static final class Meta {
        private final String meetName;
        private final String edition;
        private final String dateRange;
        private final String organizer;
        private final String compiledAt;

        public Meta(String meetName, String edition, String dateRange,
                    String organizer, String compiledAt) {
            this.meetName = meetName;
            this.edition = edition;
            this.dateRange = dateRange;
            this.organizer = organizer;
            this.compiledAt = compiledAt;
        }

        public String meetName() { return meetName; }
        public String edition() { return edition; }
        public String dateRange() { return dateRange; }
        public String organizer() { return organizer; }
        public String compiledAt() { return compiledAt; }

        public Map<String, String> toMap() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("meetName", meetName);
            m.put("edition", edition);
            m.put("dateRange", dateRange);
            m.put("organizer", organizer);
            m.put("compiledAt", compiledAt);
            return m;
        }
    }

    private final Meta meta;
    private final List<DocBlock> blocks = new ArrayList<>();

    public OrderBookDoc(Meta meta) {
        this.meta = meta;
    }

    public Meta meta() { return meta; }
    public List<DocBlock> blocks() { return blocks; }

    public OrderBookDoc heading(String text, int level) {
        blocks.add(new DocBlock.Heading(text, level));
        return this;
    }

    public OrderBookDoc paragraph(String text, boolean bold, int size, String color, String align) {
        if (text == null || text.isBlank()) {
            return this;
        }
        blocks.add(new DocBlock.Paragraph(text, bold, size, color, align));
        return this;
    }

    /** 常用写法：普通灰字小字。 */
    public OrderBookDoc note(String text) {
        return paragraph(text, false, 16, "808080", null);
    }

    public OrderBookDoc h1(String text) { return heading(text, 1); }
    public OrderBookDoc h2(String text) { return heading(text, 2); }

    public OrderBookDoc table(List<String> headers, List<List<String>> rows) {
        int cols = headers == null ? 0 : headers.size();
        blocks.add(new DocBlock.Table(List.copyOf(headers == null ? List.of() : headers),
                rows == null ? List.of() : List.copyOf(rows), cols));
        return this;
    }

    public OrderBookDoc pageBreak() {
        blocks.add(new DocBlock.PageBreak());
        return this;
    }

    /** 空表占位（还没编排 / 还没录名单），别让渲染器画一张光秃秃的表头。 */
    public OrderBookDoc empty(String hint) {
        return paragraph(hint == null || hint.isBlank() ? "（暂无内容。）" : hint, false, 20, "808080", null);
    }
}
