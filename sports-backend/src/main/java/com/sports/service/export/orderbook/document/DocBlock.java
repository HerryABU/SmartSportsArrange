package com.sports.service.export.orderbook.document;

import java.util.List;

/**
 * 秩序册文档的一个「块」——渲染的最小单位。
 *
 * <p>为什么先把文档建模、再谈渲染：一本秩序册要同时喂三种出口——浏览器里直接预览的 HTML、
 * Office 打开的 .docx、以及打印。三个出口要长得一样，就不能各自去翻数据库、各自拼数据；
 * 数据只算一遍、建成这堆块，再由各渲染器把块翻译成自己那种语言。</p>
 *
 * <p>块只描述「这里有一段标题 / 一张表 / 一页纸」，不描述任何排版细节（字号、颜色在渲染器里定）——
 * 否则 HTML 和 Word 的观感必然越走越偏。</p>
 */
public sealed interface DocBlock {

    /** 章节标题（1 = 一级，带下划边框；2 = 二级，纯标题）。 */
    record Heading(String text, int level) implements DocBlock {
    }

    /** 正文段落。size 为「半磅」（WordprocessingML 的单位），HTML 渲染器自行换算。 */
    record Paragraph(String text, boolean bold, int size, String color, String align) implements DocBlock {
    }

    /** 表格。cols 只给列数，列宽由渲染器按可用宽度均分。 */
    record Table(List<String> headers, List<List<String>> rows, int cols) implements DocBlock {
    }

    /** 分页符（封面、目录、每章之间都要断页，否则 Word 里挤成一坨没法看）。 */
    record PageBreak() implements DocBlock {
    }
}
