package com.sports.controller.orderbook;

import com.sports.service.export.WordOrderBookService;
import com.sports.service.export.orderbook.OrderBookDocumentBuilder;
import com.sports.service.export.orderbook.OrderBookHtmlRenderer;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 秩序册<b>在线预览</b>。
 *
 * <p>文档模型由 {@link OrderBookDocumentBuilder} 产出，HTML 通道 {@link OrderBookHtmlRenderer}
 * 渲染、Word 通道 {@link WordOrderBookService} 打包 —— 两边吃同一份模型，
 * 所以「预览看到的」和「下载到的 docx 打开是同一个样」。</p>
 *
 * <p>预览页自带打印样式，浏览器「打印 → 存为 PDF」即可下发，不必先装 Office。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/order-book")
@RequiredArgsConstructor
public class OrderBookPreviewController {

    private final OrderBookDocumentBuilder documentBuilder;
    private final OrderBookHtmlRenderer htmlRenderer;
    private final WordOrderBookService wordOrderBookService;

    /**
     * 浏览器直接看的一页：返回完整 HTML（iframe src 指过来即可，
     * 前端不需要任何 docx 预览插件）。
     */
    @GetMapping(value = "/preview", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public String preview(@RequestParam(required = false) String grade) {
        log.info("秩序册在线预览: grade={}", grade);
        return htmlRenderer.render(documentBuilder.build(grade));
    }

    /** 预览页上的「下载 Word」：和正式导出走同一条链路，避免两处文档长得不一样。 */
    @GetMapping("/preview/docx")
    public void previewDocx(HttpServletResponse response) {
        log.info("从预览页下载秩序册(Word)");
        wordOrderBookService.exportOrderBook(response);
    }
}
