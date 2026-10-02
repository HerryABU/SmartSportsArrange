package com.sports.controller.orderbook;

import com.sports.common.web.ApiResponse;
import com.sports.service.audit.AuditService;
import com.sports.service.orderbook.OrderBookEntryService;
import com.sports.service.orderbook.OrderBookLayoutService;
import com.sports.service.orderbook.OrderBookSectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 秩序册设计器：目录（章节）与细则（介绍内容）的增删改排序。
 *
 * <p>和 {@code /api/excel/export/order-book} 那类「一键生成」接口的分工：
 * 那边是<b>系统按固定章节灌数据</b>出文档；这里是<b>管理员自己编排目录结构</b>。
 * 两条路并存 —— 默认章节由系统铺，用户想加「竞赛须知」「应急预案」这类，走这里。</p>
 */
@RestController
@RequestMapping("/api/order-book")
@RequiredArgsConstructor
public class OrderBookDesignController {

    private final OrderBookSectionService sectionService;
    private final OrderBookEntryService entryService;
    private final OrderBookLayoutService layoutService;
    private final AuditService auditService;

    // ==================== 目录 ====================

    /** 目录清单（首次调用会铺好五个默认章节）。 */
    @GetMapping("/sections")
    public ApiResponse<?> sections(@RequestParam(required = false) Long meetId) {
        return ApiResponse.success(sectionService.listTree(meetId));
    }

    /** 首次打开时确保有一套默认目录。 */
    @PostMapping("/sections/ensure-default")
    public ApiResponse<?> ensureDefaults(@RequestParam(required = false) Long meetId) {
        return ApiResponse.success("默认章节已就绪", sectionService.ensureDefaults(meetId));
    }

    @PostMapping("/sections")
    public ApiResponse<?> createSection(@RequestBody Map<String, Object> body) {
        Long meetId = longOf(body.get("meetId"));
        String parentId = body.get("parentId") == null ? null : String.valueOf(body.get("parentId"));
        String title = body.get("title") == null ? null : String.valueOf(body.get("title"));
        Integer level = body.get("level") == null ? null : ((Number) body.get("level")).intValue();
        Map<String, Object> saved = sectionService.create(meetId, parentId, title, level);
        auditService.record("ORDER_BOOK_SECTION_CREATE", "ORDER_BOOK", null, "新增秩序册目录");
        return ApiResponse.success("目录已新增", saved);
    }

    @PutMapping("/sections/{id}")
    public ApiResponse<?> updateSection(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        sectionService.update(id, str(body.get("title")), str(body.get("parentId")),
                bool(body.get("enabled")), body.get("level") == null ? null : ((Number) body.get("level")).intValue());
        return ApiResponse.success("目录已更新");
    }

    /** dir = -1 上移 / 1 下移。 */
    @PostMapping("/sections/{id}/move")
    public ApiResponse<?> moveSection(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        int dir = body.get("dir") == null ? 1 : ((Number) body.get("dir")).intValue();
        sectionService.move(longOf(body.get("meetId")), id, dir);
        return ApiResponse.success("顺序已调整");
    }

    @DeleteMapping("/sections/{id}")
    public ApiResponse<?> deleteSection(@PathVariable Long id) {
        sectionService.delete(id);
        auditService.record("ORDER_BOOK_SECTION_DELETE", "ORDER_BOOK", null, "删除秩序册目录（软删）");
        return ApiResponse.success("目录已删除（可恢复）");
    }

    @PostMapping("/sections/{id}/restore")
    public ApiResponse<?> restoreSection(@PathVariable Long id) {
        sectionService.restore(id);
        return ApiResponse.success("目录已恢复");
    }

    // ==================== 细则 ====================

    /** 某个目录下的细则清单。 */
    @GetMapping("/entries")
    public ApiResponse<?> entries(@RequestParam Long sectionId) {
        return ApiResponse.success(entryService.listBySection(sectionId));
    }

    /** 新增细则：自定义介绍内容，或系统板块（sourceKey）。 */
    @PostMapping("/entries")
    public ApiResponse<?> createEntry(@RequestBody Map<String, Object> body) {
        Long sectionId = longOf(body.get("sectionId"));
        Map<String, Object> saved = entryService.create(
                sectionId,
                str(body.get("title")),
                str(body.get("kind")),
                str(body.get("sourceKey")),
                str(body.get("contentType")),
                body.get("content") == null ? null : String.valueOf(body.get("content")));
        auditService.record("ORDER_BOOK_ENTRY_CREATE", "ORDER_BOOK", null, "新增秩序册细则");
        return ApiResponse.success("细则已新增", saved);
    }

    @PutMapping("/entries/{id}")
    public ApiResponse<?> updateEntry(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        entryService.update(id, str(body.get("title")),
                body.get("content") == null ? null : String.valueOf(body.get("content")),
                bool(body.get("enabled")));
        return ApiResponse.success("细则已更新");
    }

    @PostMapping("/entries/{id}/move")
    public ApiResponse<?> moveEntry(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        int dir = body.get("dir") == null ? 1 : ((Number) body.get("dir")).intValue();
        entryService.move(id, dir);
        return ApiResponse.success("顺序已调整");
    }

    @DeleteMapping("/entries/{id}")
    public ApiResponse<?> deleteEntry(@PathVariable Long id) {
        entryService.delete(id);
        auditService.record("ORDER_BOOK_ENTRY_DELETE", "ORDER_BOOK", null, "删除秩序册细则（软删）");
        return ApiResponse.success("细则已删除（可恢复）");
    }

    @PostMapping("/entries/{id}/restore")
    public ApiResponse<?> restoreEntry(@PathVariable Long id) {
        entryService.restore(id);
        return ApiResponse.success("细则已恢复");
    }

    // ==================== 排版 / 预览 ====================

    /** 整本秩序册的结构（含未启用的，设计器用）。 */
    @GetMapping("/layout")
    public ApiResponse<?> layout(@RequestParam(required = false) Long meetId) {
        return ApiResponse.success(layoutService.build(meetId));
    }

    /** 只含启用的部分，给预览/导出用。 */
    @GetMapping("/layout/enabled")
    public ApiResponse<?> layoutEnabled(@RequestParam(required = false) Long meetId) {
        return ApiResponse.success(layoutService.buildEnabled(meetId));
    }

    // ==================== 内部 ====================

    private static Long longOf(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : Long.valueOf(s);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Boolean bool(Object o) {
        return o == null ? null : Boolean.valueOf(String.valueOf(o));
    }
}
