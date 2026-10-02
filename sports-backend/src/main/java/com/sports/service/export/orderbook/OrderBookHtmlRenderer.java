package com.sports.service.export.orderbook;

import com.sports.service.export.orderbook.document.DocBlock;
import com.sports.service.export.orderbook.document.OrderBookDoc;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 秩序册 → HTML 的渲染器：<b>在线预览</b>这一路。
 *
 * <p>为什么预览走 HTML 而不是把 docx 转成图片 / 用 docx-preview 插件：docx 前端预览库要么依赖浏览器
 * 的 Office 适配器（Electron / WPS 才有），要么把整个 OOXML 拉到前端解析，样式和打印都对不齐。
 * 而这份文档模型是我们自己建的，直接翻成 HTML 反而最准——所见即所得，还顺带支持浏览器打印成 PDF。</p>
 *
 * <p>版式刻意对齐 Word：A4 宽、宋体、与 {@link OrderBookWordRenderer} 同色系，
 * 让「预览seen」和「下载 docx 打开」是同一张脸。</p>
 */
@Component
public class OrderBookHtmlRenderer {

    /** 渲染成一份可直接 iframe / 新窗口打开的完整 HTML 文档。 */
    public String render(OrderBookDoc doc) {
        StringBuilder body = new StringBuilder();
        for (DocBlock b : doc.blocks()) {
            switch (b) {
                case DocBlock.Heading h -> body.append(heading(h.text(), h.level()));
                case DocBlock.Paragraph p -> body.append(paragraph(p.text(), p.bold(), p.size(),
                        p.color(), p.align()));
                case DocBlock.Table t -> body.append(table(t.headers(), t.rows()));
                case DocBlock.PageBreak ignored -> body.append(pageBreak());
            }
        }
        return doctype() + "<html lang=\"zh-CN\"><head><meta charset=\"UTF-8\"/>"
                + "<title>" + esc(doc.meta().meetName()) + " 秩序册（预览）</title>"
                + style() + "</head><body>"
                + "<div class=\"page\">" + body + "</div>"
                + footer(doc) + "</body></html>";
    }

    // ==================== 片段 ====================

    private String heading(String text, int level) {
        String cls = level <= 1 ? "h1" : "h2";
        return "<div class=\"" + cls + "\">" + esc(text) + "</div>";
    }

    private String paragraph(String text, boolean bold, int halfPts, String color, String align) {
        String cls = "p" + (bold ? " bold" : "") + (align != null ? " " + align : "");
        StringBuilder style = new StringBuilder();
        if (halfPts > 0) {
            style.append("font-size:").append(pt(halfPts)).append("pt;");
        }
        if (color != null) {
            style.append("color:#").append(color).append(";");
        }
        String attr = style.length() == 0 ? "" : " style=\"" + style + "\"";
        return "<div class=\"" + cls + "\"" + attr + ">" + newlineToBreak(text) + "</div>";
    }

    private String table(List<String> headers, List<List<String>> rows) {
        if (headers.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<table><thead><tr>");
        for (String h : headers) {
            sb.append("<th>").append(esc(h)).append("</th>");
        }
        sb.append("</tr></thead><tbody>");
        for (List<String> row : rows) {
            sb.append("<tr>");
            for (int i = 0; i < headers.size(); i++) {
                sb.append("<td>").append(esc(i < row.size() ? row.get(i) : "")).append("</td>");
            }
            sb.append("</tr>");
        }
        return sb.append("</tbody></table>").toString();
    }

    /** 分页符在网页上的等价物：一页结束，下一段内容另起一屏（打印时即另起一页）。 */
    private String pageBreak() {
        return "<div class=\"pagebreak\"></div>";
    }

    private String footer(OrderBookDoc doc) {
        return "<div class=\"foot\">" + esc(doc.meta().meetName()) + " · 编制于 " + esc(doc.meta().compiledAt())
                + " · 本秩序册依据报名审核与编排结果自动生成，最终以现场公告为准。</div>";
    }

    /**
     * 半磅 → 磅（WordprocessingML 的 sz 是半磅）。
     * 整数值不带小数点——浏览器对 "8.0pt" 和 "8pt" 一视同仁，但页面源码里带尾巴不好看。
     */
    private static String pt(int halfPts) {
        double v = Math.max(8, Math.round(halfPts / 2.0 * 10) / 10.0);
        String s = String.valueOf(v);
        // "8.0" → "8"（只去 ".0" 这一小尾巴，别把最后一位数字削掉）
        return s.endsWith(".0") ? s.substring(0, s.indexOf('.')) : s;
    }

    private static String newlineToBreak(String s) {
        if (s == null) {
            return "";
        }
        return esc(s).replace("\n", "<br/>");
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ==================== 文档外壳 ====================

    private static String doctype() {
        return "<!DOCTYPE html>";
    }

    private static String style() {
        return "<style>\n"
                + ":root{ --ink:#1f2937; --brand:#1f4e79; --line:#c9d3e2; }\n"
                + "*{box-sizing:border-box;}\n"
                + "body{margin:0;padding:24px;background:#eef1f6;font-family:'宋体',SimSun,'Microsoft YaHei',serif;"
                + "color:var(--ink);}\n"
                + ".page{width:794px;min-height:1123px;margin:0 auto;background:#fff;padding:56px 60px;"
                + "box-shadow:0 2px 14px rgba(15,23,42,.12);}\n"
                + ".pagebreak{height:0;}\n"
                + ".h1{font-size:22pt;font-weight:700;color:var(--brand);margin:26px 0 12px;"
                + "padding-bottom:6px;border-bottom:1.5px solid var(--brand);}\n"
                + ".h2{font-size:15pt;font-weight:700;color:#2e5496;margin:16px 0 8px;}\n"
                + ".p{font-size:10.5pt;line-height:1.9;margin:5px 0;text-align:left;white-space:pre-wrap;"
                + "word-break:break-word;}\n"
                + ".p.bold{font-weight:700;}\n"
                + ".p.center{text-align:center;}\n"
                + "table{width:100%;border-collapse:collapse;margin:10px 0 16px;font-size:10.5pt;}\n"
                + "th,td{border:1px solid var(--line);padding:5px 6px;text-align:center;"
                + "vertical-align:middle;line-height:1.5;}\n"
                + "th{background:#2e5496;color:#fff;font-weight:600;}\n"
                + "tbody tr:nth-child(even){background:#f2f5fb;}\n"
                + ".foot{margin:18px auto 0;width:794px;font-size:9pt;color:#808080;line-height:1.7;}\n"
                + "@media print{ body{padding:0;background:#fff;} .page{box-shadow:none;width:auto;"
                + "min-height:auto;padding:0;} .foot{width:auto;} }\n"
                + "</style>";
    }
}
