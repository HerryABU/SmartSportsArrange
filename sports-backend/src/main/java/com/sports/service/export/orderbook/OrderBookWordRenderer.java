package com.sports.service.export.orderbook;

import com.sports.service.export.orderbook.document.DocBlock;
import com.sports.service.export.orderbook.document.OrderBookDoc;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 秩序册 → WordprocessingML 的渲染器。
 *
 * <p>不依赖 Apache POI：直接按 OOXML 标准拼 ZIP 包（WordprocessingML），零额外依赖、离线可构建。
 * 原先这段逻辑埋在 {@code WordOrderBookService} 里，和 HTTP 下载、落盘、完整性校验挤一个文件；
 * 现在预览通道要吃同一份文档模型，渲染必须是<b>无状态的一步翻译</b>，才好被两处复用。</p>
 *
 * <p>这里只产出 {@code word/document.xml} 的 body 片段，PageSetup（纸张、页边距）仍由
 * {@code WordOrderBookService} 的文档包装负责。</p>
 */
@Component
public class OrderBookWordRenderer {

    private static final String FONT = "宋体";
    static final int PAGE_W = 11906;
    static final int MARGIN = 1080;
    private static final int USABLE = PAGE_W - MARGIN * 2; // 9746

    /** 文档渲染入口：把文档模型翻成 WordprocessingML 的 body 内容。 */
    public String renderBody(OrderBookDoc doc) {
        StringBuilder body = new StringBuilder();
        for (DocBlock b : doc.blocks()) {
            switch (b) {
                case DocBlock.Heading h -> body.append(heading(h.text(), h.level()));
                case DocBlock.Paragraph p -> body.append(para(p.text(), p.bold(), p.size(),
                        p.color(), p.align()));
                case DocBlock.Table t -> body.append(table(t.headers(), t.rows(), t.cols()));
                case DocBlock.PageBreak ignored -> body.append(pageBreak());
            }
        }
        return body.toString();
    }

    // ==================== WordprocessingML 片段 ====================

    private String para(String text, boolean bold, int halfPts, String color, String align) {
        String jc = align != null ? "<w:jc w:val=\"" + align + "\"/>" : "";
        StringBuilder rpr = new StringBuilder("<w:rPr>");
        if (bold) {
            rpr.append("<w:b/>");
        }
        if (color != null) {
            rpr.append("<w:color w:val=\"").append(color).append("\"/>");
        }
        if (halfPts > 0) {
            rpr.append("<w:sz w:val=\"").append(halfPts).append("\"/><w:szCs w:val=\"").append(halfPts).append("\"/>");
        }
        rpr.append("</w:rPr>");
        return "<w:p><w:pPr>" + jc
                + "<w:spacing w:before=\"60\" w:after=\"60\" w:line=\"288\" w:lineRule=\"auto\"/>"
                + "</w:pPr><w:r>" + rpr + "<w:t xml:space=\"preserve\">" + esc(text) + "</w:t></w:r></w:p>";
    }

    private String heading(String text, int level) {
        int size = level <= 1 ? 32 : 26;
        String color = level <= 1 ? "1F4E79" : "2E5496";
        return "<w:p><w:pPr><w:spacing w:before=\"200\" w:after=\"120\"/>"
                + (level <= 1 ? "<w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"4\" w:color=\"1F4E79\"/></w:pBdr>" : "")
                + "</w:pPr><w:r><w:rPr><w:b/><w:color w:val=\"" + color + "\"/>"
                + "<w:sz w:val=\"" + size + "\"/><w:szCs w:val=\"" + size + "\"/></w:rPr>"
                + "<w:t xml:space=\"preserve\">" + esc(text) + "</w:t></w:r></w:p>";
    }

    private String pageBreak() {
        return "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>";
    }

    private String table(List<String> headers, List<List<String>> rows, int n) {
        StringBuilder sb = new StringBuilder();
        sb.append("<w:tbl><w:tblPr>")
                .append("<w:tblW w:w=\"0\" w:type=\"auto\"/>")
                .append("<w:tblBorders>")
                .append("<w:top w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
                .append("<w:left w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
                .append("<w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
                .append("<w:right w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
                .append("<w:insideH w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
                .append("<w:insideV w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
                .append("</w:tblBorders>")
                .append("<w:tblLook w:val=\"04A0\" w:firstRow=\"1\" w:lastRow=\"0\" w:firstColumn=\"1\" w:lastColumn=\"0\" w:noHBand=\"0\" w:noVBand=\"1\"/>")
                .append("</w:tblPr><w:tblGrid>");
        int[] widths = equalWidths(Math.max(n, headers.size()));
        for (int i = 0; i < n; i++) {
            sb.append("<w:gridCol w:w=\"").append(widths[i]).append("\"/>");
        }
        sb.append("</w:tblGrid>");

        // 表头行
        sb.append("<w:tr><w:trPr><w:tblHeader/></w:trPr>");
        for (int i = 0; i < n && i < headers.size(); i++) {
            sb.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(widths[i]).append("\" w:type=\"dxa\"/>")
                    .append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"2E5496\"/>")
                    .append("<w:vAlign w:val=\"center\"/></w:tcPr>")
                    .append(cellPara(headers.get(i), true, 18, "FFFFFF", "center"))
                    .append("</w:tc>");
        }
        sb.append("</w:tr>");

        // 数据行（隔行浅底，几百行号码表扫起来不眯眼）
        int ri = 0;
        for (List<String> row : rows) {
            String fill = (ri % 2 == 1) ? "F2F5FB" : "FFFFFF";
            sb.append("<w:tr>");
            for (int i = 0; i < n; i++) {
                String val = i < row.size() ? row.get(i) : "";
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(widths[i]).append("\" w:type=\"dxa\"/>")
                        .append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(fill).append("\"/>")
                        .append("<w:vAlign w:val=\"center\"/></w:tcPr>")
                        .append(cellPara(val, false, 18, null, "center"))
                        .append("</w:tc>");
            }
            sb.append("</w:tr>");
            ri++;
        }
        sb.append("</w:tbl>");
        sb.append("<w:p><w:pPr><w:spacing w:after=\"120\"/></w:pPr></w:p>");
        return sb.toString();
    }

    private String cellPara(String text, boolean bold, int halfPts, String color, String align) {
        String jc = align != null ? "<w:jc w:val=\"" + align + "\"/>" : "";
        StringBuilder rpr = new StringBuilder("<w:rPr>");
        if (bold) {
            rpr.append("<w:b/>");
        }
        if (color != null) {
            rpr.append("<w:color w:val=\"").append(color).append("\"/>");
        }
        if (halfPts > 0) {
            rpr.append("<w:sz w:val=\"" + halfPts + "\"/><w:szCs w:val=\"" + halfPts + "\"/>");
        }
        rpr.append("</w:rPr>");
        return "<w:p><w:pPr>" + jc
                + "<w:spacing w:before=\"20\" w:after=\"20\" w:line=\"240\" w:lineRule=\"auto\"/>"
                + "</w:pPr><w:r>" + rpr + "<w:t xml:space=\"preserve\">" + esc(text) + "</w:t></w:r></w:p>";
    }

    private int[] equalWidths(int n) {
        int[] w = new int[n];
        int base = USABLE / n;
        int rem = USABLE - base * n;
        for (int i = 0; i < n; i++) {
            w[i] = base + (i < rem ? 1 : 0);
        }
        return w;
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
