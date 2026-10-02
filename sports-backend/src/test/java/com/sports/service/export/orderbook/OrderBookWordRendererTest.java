package com.sports.service.export.orderbook;

import com.sports.service.export.orderbook.document.OrderBookDoc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 秩序册 Word 渲染器的单测。
 *
 * <p>Word 这路没有浏览器能顺手验，所以断言往 XML 结构上靠：<b>表头必须带底纹（深蓝 + 白字）</b>、
 * <b>分页符必须是真正的分页 break</b>、<b>内容必须转义</b>。这三点错了，
 * 表现出来的都是「Word 打开是空白 / 乱码 / 表格糊成一片」这类隔了很久才回过味来的问题。</p>
 */
class OrderBookWordRendererTest {

    private final OrderBookWordRenderer renderer = new OrderBookWordRenderer();

    private static OrderBookDoc sampleDoc() {
        OrderBookDoc doc = new OrderBookDoc(new OrderBookDoc.Meta("x", null, null, null, null));
        doc.h1("一、竞赛日程");
        doc.h2("径赛项目");
        // 两条数据行才能看出隔行浅底（单行只有白底， zebra 无从体现）
        doc.table(List.of("天次", "项目"), List.of(List.of("第1天", "100米"), List.of("第1天", "800米")));
        doc.paragraph("凡 & 违规 < 者，取消成绩", false, 21, null, null);
        doc.pageBreak();
        doc.h1("竞赛须知");
        return doc;
    }

    @Test
    @DisplayName("标题按级别渲染：一级带下边框，二级不带")
    void headingsHonorLevel() {
        String xml = renderer.renderBody(sampleDoc());

        assertTrue(xml.contains("<w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\""), "一级标题要带下边框");
        int firstH1 = xml.indexOf("<w:pBdr>");
        int h2At = xml.indexOf("径赛项目");
        int secondH1 = xml.indexOf("竞赛须知");
        assertTrue(firstH1 < h2At && h2At < secondH1);
    }

    @Test
    @DisplayName("表格表头有深蓝底 + 白字，数据行隔行浅底")
    void tableHeaderAndZebra() {
        String xml = renderer.renderBody(sampleDoc());

        // 底纹是 <w:shd .../> 上的属性，不是独立标签，断言要落在属性串上
        assertTrue(xml.contains("w:fill=\"2E5496\""), "表头底纹色");
        assertTrue(xml.contains("<w:color w:val=\"FFFFFF\"/>"), "表头白字");
        assertTrue(xml.contains("w:fill=\"F2F5FB\""), "隔行浅底");
        assertTrue(xml.contains("<w:gridCol w:w=\""), "列宽必须逐列给，Word 才能对齐");
    }

    @Test
    @DisplayName("页与页之间是真的分页，不是空段落")
    void pageBreakIsRealBreak() {
        String xml = renderer.renderBody(sampleDoc());

        assertTrue(xml.contains("<w:br w:type=\"page\"/>"), "分页必须是 page break");
    }

    @Test
    @DisplayName("内容里的 XML 元字符被转义，否则整个 document.xml 作废")
    void escapesXmlMetacharacters() {
        String xml = renderer.renderBody(sampleDoc());

        assertTrue(xml.contains("&amp; 违规 &lt; 者"), "尖括号与 & 必须转义");
        assertFalse(xml.contains("凡 & 违规 < 者"));
    }

    @Test
    @DisplayName("空文档渲染出的是干净的空 body，不是 null")
    void emptyDocIsSafe() {
        OrderBookDoc empty = new OrderBookDoc(new OrderBookDoc.Meta("x", null, null, null, null));

        assertDoesNotThrow(() -> renderer.renderBody(empty));
        String xml = renderer.renderBody(empty);
        assertNotNull(xml);
        assertFalse(xml.contains("null"));
    }

    @Test
    @DisplayName("表头比数据行长时也不越界（列宽按表头数给）")
    void wideHeadersAreSafe() {
        OrderBookDoc doc = new OrderBookDoc(new OrderBookDoc.Meta("x", null, null, null, null));
        doc.table(List.of("A", "B", "C"), List.of(List.of("1")));

        String xml = renderer.renderBody(doc);
        assertEquals(3, xml.split("<w:gridCol", -1).length - 1, "三列各给一个 gridCol");
    }
}
