package com.sports.controller.excel;

import com.sports.common.web.ApiResponse;
import com.sports.service.audit.AuditService;
import com.sports.service.excel.ExcelService;
import com.sports.service.system.SystemService;
import com.sports.service.export.WordOrderBookService;
import com.sports.service.excel.ExcelSheetInspector;
import com.sports.service.excel.ImportPlanParser;
import com.sports.service.excel.MultiTableImportService;
import com.sports.service.excel.SheetPreviewBuilder;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel导入导出控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/excel")
@RequiredArgsConstructor
public class ExcelController {

    private final ExcelService excelService;
    private final WordOrderBookService wordOrderBookService;
    private final SystemService systemService;
    private final AuditService auditService;
    private final MultiTableImportService multiTableImportService;

    // ===== 模板下载 =====
    @GetMapping("/template/{type}")
    public void downloadTemplate(@PathVariable String type, HttpServletResponse response) {
        log.info("下载Excel模板: type={}", type);
        excelService.getTemplate(type, response);
    }

    // ===== 导入预览（多Sheet支持）=====
    @PostMapping("/preview")
    public ApiResponse<?> previewImport(@RequestParam MultipartFile file) throws IOException {
        log.info("Excel导入预览: filename={}", file.getOriginalFilename());
        return ApiResponse.success(excelService.previewImport(file));
    }

    // ===== 带列映射导入（用户标记列→字段） =====
    @PostMapping("/import-with-mapping")
    public ApiResponse<?> importWithMapping(
            @RequestParam MultipartFile file,
            @RequestParam Map<String, Object> mapping) throws IOException {
        log.info("Excel映射导入: filename={}, type={}", file.getOriginalFilename(), mapping.get("type"));
        return ApiResponse.success("导入完成", excelService.importWithMapping(file, mapping));
    }

    // ===== 多表导入（管理员）：一个工作簿多个 Sheet / 多个文件一次导入 =====

    /**
     * 多表导入探测：列出每个文件里的每个 Sheet（真实表名）、判定类型、自动列映射与样例。
     * 供前端「先看清再导」——管理员可逐表确认或改写类型后再导入。
     */
    @PostMapping("/multi/preview")
    public ApiResponse<?> previewMulti(@RequestParam("files") MultipartFile[] files,
                                       @RequestParam(required = false) Map<String, Object> plan) {
        log.info("多表导入探测: 文件数={}", files == null ? 0 : files.length);
        return ApiResponse.success(multiTableImportService.preview(files == null ? java.util.List.of() : java.util.List.of(files),
                plan == null ? Map.of() : plan));
    }

    /**
     * 按用户指定<b>重新解析</b>：前端把改过的「导入计划」打回来（逐表类型、逐列映射、是否参与），
     * 后端据此重新判定并出一份<b>带列候选建议、样例行数更多</b>的预览。
     *
     * <p>和 {@code /multi/preview} 的区别不是两套逻辑，而是两种意图：这是「我已经看过第一版结果、
     * 现在按我的指定重来一遍」，所以默认带上 {@code advice}（每列可以选哪些字段）方便当场改。
     * 文件与 {@code plan} 都还要求重新传 —— MultipartFile 的流读完即失效，服务端不缓存上传件。</p>
     */
    @PostMapping("/multi/reparse")
    public ApiResponse<?> reparseMulti(@RequestParam("files") MultipartFile[] files,
                                       @RequestParam(required = false) Map<String, Object> plan) {
        log.info("多表导入重新解析: 文件数={}", files == null ? 0 : files.length);
        return ApiResponse.success("已按指定重新解析",
                multiTableImportService.preview(files == null ? List.of() : List.of(files),
                        plan == null ? Map.of() : plan,
                        SheetPreviewBuilder.LARGE_SAMPLE_SIZE, true));
    }

    /**
     * 单张 Sheet 的<b>可视化预览</b>取数：返回指定页的逐行逐单元格 + 行号 + 分页信息，
     * 前端据此画出真正的 Excel 网格（含「这一列会被导入成哪个字段」的表头标注）。
     *
     * <p>{@code type} / {@code columnMap} 可传用户改过的值，这样网格里看到的列就是他要导的列，
     * 而不是后端自动猜的那套。</p>
     */
    @PostMapping("/multi/sheet-data")
    public ApiResponse<?> sheetData(@RequestParam("files") MultipartFile[] files,
                                    @RequestParam(defaultValue = "0") int fileIndex,
                                    @RequestParam(defaultValue = "0") int sheetIndex,
                                    @RequestParam(required = false) Boolean hasHeader,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "50") int pageSize,
                                    @RequestParam(required = false) String type,
                                    @RequestParam(required = false) String columnMap) {
        MultipartFile[] all = files == null ? new MultipartFile[0] : files;
        MultipartFile file = fileIndex >= 0 && fileIndex < all.length ? all[fileIndex] : null;
        if (file == null || file.isEmpty()) {
            return ApiResponse.success("请先选择文件");
        }
        String fileName = file.getOriginalFilename() == null ? ("文件" + (fileIndex + 1)) : file.getOriginalFilename();
        return ApiResponse.success(ExcelSheetInspector.inspect(
                file, fileName, fileIndex, sheetIndex,
                hasHeader == null || hasHeader,
                page, pageSize, type, ImportPlanParser.columnMapOf(columnMap)));
    }

    /** 多表导入：files 可多选；plan 可选，用于逐表指定 type / columnMap。 */
    @PostMapping("/import-multi")
    public ApiResponse<?> importMulti(@RequestParam("files") MultipartFile[] files,
                                      @RequestParam(required = false) Map<String, Object> plan) {
        log.info("多表导入: 文件数={}", files == null ? 0 : files.length);
        Map<String, Object> result = multiTableImportService.importAll(
                files == null ? java.util.List.of() : java.util.List.of(files), plan == null ? Map.of() : plan);
        auditService.record("EXCEL_IMPORT_MULTI", "EXCEL", null, "多表导入 " + summarize(result));
        return ApiResponse.success("多表导入完成", result);
    }

    @SuppressWarnings("unchecked")
    private static String summarize(Map<String, Object> result) {
        Object s = result.get("summary");
        if (!(s instanceof Map<?, ?> m)) {
            return "";
        }
        return "Sheet " + m.get("sheets") + "，成功 " + m.get("imported")
                + " 行，跳过 " + m.get("rowSkipped") + " 行，失败 " + m.get("failed") + " 行";
    }

    // ===== 直接导入（兼容旧接口）=====
    @PostMapping("/import/athletes")
    public ApiResponse<?> importAthletes(@RequestParam MultipartFile file) throws IOException {
        log.info("Excel导入运动员: filename={}", file.getOriginalFilename());
        Object r = excelService.importAthletes(file);
        auditService.record("IMPORT_ATHLETES", "ATHLETE", null,
                "导入运动员文件: " + file.getOriginalFilename() + ", 结果=" + r);
        return ApiResponse.success("导入完成", r);
    }

    @PostMapping("/import/scores")
    public ApiResponse<?> importScores(@RequestParam MultipartFile file) throws IOException {
        log.info("Excel导入成绩: filename={}", file.getOriginalFilename());
        Object r = excelService.importScores(file);
        auditService.record("IMPORT_SCORES", "RESULT", null,
                "导入成绩文件: " + file.getOriginalFilename() + ", 结果=" + r);
        return ApiResponse.success("导入完成", r);
    }

    @PostMapping("/import/registrations")
    public ApiResponse<?> importRegistrations(@RequestParam MultipartFile file) throws IOException {
        log.info("Excel导入报名: filename={}", file.getOriginalFilename());
        Object r = excelService.importRegistrations(file);
        auditService.record("IMPORT_REGISTRATIONS", "REGISTRATION", null,
                "导入报名文件: " + file.getOriginalFilename() + ", 结果=" + r);
        return ApiResponse.success("导入完成", r);
    }

    // ===== 导出 =====
    @GetMapping("/export/arrangement")
    public void exportArrangement(@RequestParam Long eventId, HttpServletResponse response) {
        log.info("导出编排表: eventId={}", eventId);
        excelService.exportArrangement(eventId, response);
    }

    @GetMapping("/export/order-book")
    public void exportOrderBook(HttpServletResponse response) {
        log.info("导出秩序册Excel");
        excelService.exportOrderBook(response);
    }

    @GetMapping("/export/result-book")
    public void exportResultBook(HttpServletResponse response) {
        log.info("导出成绩册Excel");
        excelService.exportResultBook(response);
    }

    // ===== 秩序册 Word 文档（真实 .docx，含表格） =====

    /** 下载 Word 版秩序册（.docx） */
    @GetMapping("/export/order-book-docx")
    public void exportOrderBookDocx(HttpServletResponse response) {
        log.info("导出秩序册(Word)");
        wordOrderBookService.exportOrderBook(response);
    }

    /** 生成并落盘 Word 秩序册，返回元数据（手动「生成」与自动生成共用） */
    @PostMapping("/order-book/generate")
    public ApiResponse<?> generateOrderBookDocx() {
        log.info("生成秩序册(Word)落盘");
        Object r = wordOrderBookService.generateToDisk();
        auditService.record("GENERATE_ORDER_BOOK", "ORDER_BOOK", null, "生成秩序册(Word): " + r);
        return ApiResponse.success("秩序册(Word)已生成", r);
    }

    /** U14/U15：一键生成「最终秩序册」（基于二次编排结果，附完整性校验） */
    @PostMapping("/order-book/generate-final")
    public ApiResponse<?> generateFinalOrderBookDocx() {
        log.info("一键生成最终秩序册(Word)");
        Object r = wordOrderBookService.generateFinalToDisk();
        auditService.record("GENERATE_ORDER_BOOK_FINAL", "ORDER_BOOK", null, "生成最终秩序册(Word): " + r);
        return ApiResponse.success("最终秩序册(Word)已生成", r);
    }

    /** 读取「生成预赛/编排后自动生成秩序册」开关 */
    @GetMapping("/order-book/auto")
    public ApiResponse<?> getOrderBookAuto() {
        return ApiResponse.success(Map.of("enabled", systemService.isOrderBookAutoGenerate()));
    }

    /** 设置「生成预赛/编排后自动生成秩序册」开关 */
    @PostMapping("/order-book/auto")
    public ApiResponse<?> setOrderBookAuto(@RequestBody Map<String, Object> body) {
        boolean enabled = body != null && Boolean.parseBoolean(String.valueOf(body.getOrDefault("enabled", false)));
        log.info("设置秩序册自动生成开关: enabled={}", enabled);
        systemService.setOrderBookAutoGenerate(enabled);
        return ApiResponse.success("已更新", Map.of("enabled", enabled));
    }
}
