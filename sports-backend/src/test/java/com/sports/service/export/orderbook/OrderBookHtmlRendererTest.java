package com.sports.service.export.orderbook;

import com.sports.service.export.orderbook.document.OrderBookDoc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 秩序册 HTML 渲染器的单测。
 *
 * <p>关键点两条：一是<b>转义</b>——规则标题「A & B」这种必须原样显示而不是变成标签，
 * 默认值、空值一律不能炸；二是<b>模型能被完整翻干净</b>——块一个都不能漏翻，
 * 漏了就是「预览里少一章」这种最难查的问题。</p>
 */
class OrderBookHtmlRendererTest {

    private final OrderBookHtmlRenderer renderer = new OrderBookHtmlRenderer();

    private static OrderBookDoc sampleDoc() {
        OrderBookDoc doc = new OrderBookDoc(new OrderBookDoc.Meta(
                "第 30 届校运动会", "30", "2026-10-08 至 2026-10-09",
                "学校体育运动委员会", "2026-10-08 08:00"));
        doc.paragraph("第 30 届校运动会", true, 48, "1F3864", "center");
        doc.h1("一、竞赛日程");
        doc.h2("径赛项目");
        doc.table(List.of("天次", "项目"), List.of(List.of("第1天", "100米"), List.of("第1天", "800米")));
        doc.paragraph("凡 & 违规 < 者，取消成绩", false, 21, null, null);
        doc.pageBreak();
        doc.h1("竞赛须知");
        doc.paragraph("请各班级于 8:00 前入场。", false, 21, null, null);
        return doc;
    }

    @Test
    @DisplayName("四种块都能翻出来，顺序与文档一致")
    void rendersEveryBlockKind() {
        String html = renderer.render(sampleDoc());

        assertTrue(html.contains("<div class=\"p bold center\""), "封面大字要带居中样式");
        assertTrue(html.contains("<div class=\"h1\">一、竞赛日程</div>"));
        assertTrue(html.contains("<div class=\"h2\">径赛项目</div>"));
        assertTrue(html.contains("<div class=\"pagebreak\"></div>"), "分页符必须落地");
        assertTrue(html.contains("<div class=\"h1\">竞赛须知</div>"), "翻到分页之后的内容也必须在");

        int toc = html.indexOf("一、竞赛日程");
        int firstTable = html.indexOf("<table>");
        // 注意：CSS 里也有 .pagebreak 字样，必须按 DOM 片段断言，否则比对的是样式表
        int breakAt = html.indexOf("<div class=\"pagebreak\">");
        int last = html.indexOf("竞赛须知");
        assertTrue(toc < firstTable && firstTable < breakAt && breakAt < last, "块的顺序不能被渲染器打乱");
    }

    @Test
    @DisplayName("表格按表头列数补齐，缺行不越界")
    void tableAlwaysAlignedWithHeaders() {
        String html = renderer.render(sampleDoc());

        assertTrue(html.contains("<th>天次</th><th>项目</th>"));
        assertTrue(html.contains("<td>第1天</td><td>100米</td>"));
        assertTrue(html.contains("<td>第1天</td><td>800米</td>"));
        assertEquals(1, countOf(html, "<tbody>"), "一张表一个 tbody");
    }

    @Test
    @DisplayName("正文里的尖括号与 & 被转义，不能当成 HTML")
    void escapesHtmlMetacharacters() {
        String html = renderer.render(sampleDoc());

        assertTrue(html.contains("凡 &amp; 违规 &lt; 者，取消成绩"), "尖括号与 & 必须转义");
        assertFalse(html.contains("凡 & 违规 < 者"), "未转义的原样文本不能出现");
    }

    @Test
    @DisplayName("空文档 / 空表不炸，只出壳子")
    void emptyDocIsSafe() {
        OrderBookDoc empty = new OrderBookDoc(new OrderBookDoc.Meta("测试", null, null, null, null));
        String html = renderer.render(empty);

        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("<title>测试 秩序册（预览）</title>"));
        assertFalse(html.contains("null"), "元信息为 null 时不能把 null 拼进页面");
    }

    @Test
    @DisplayName("超半磅字号换算成 pt 且夹在可读下限之上")
    void fontSizesArePrintable() {
        OrderBookDoc doc = new OrderBookDoc(new OrderBookDoc.Meta("x", null, null, null, null));
        doc.paragraph("小字", false, 16, null, null);
        doc.paragraph("大字", false, 48, null, null);

        String html = renderer.render(doc);
        assertTrue(html.contains("font-size:8pt;"), "16 半磅 = 8pt");
        assertTrue(html.contains("font-size:24pt;"), "48 半磅 = 24pt");
    }

    private static int countOf(String haystack, String needle) {
        int n = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
