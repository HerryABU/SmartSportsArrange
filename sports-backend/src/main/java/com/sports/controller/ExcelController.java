package com.sports.controller;

import com.sports.common.ApiResponse;
import com.sports.service.ExcelService;
import com.sports.service.SystemService;
import com.sports.service.WordOrderBookService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
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

    // ===== 直接导入（兼容旧接口）=====
    @PostMapping("/import/athletes")
    public ApiResponse<?> importAthletes(@RequestParam MultipartFile file) throws IOException {
        log.info("Excel导入运动员: filename={}", file.getOriginalFilename());
        return ApiResponse.success("导入完成", excelService.importAthletes(file));
    }

    @PostMapping("/import/scores")
    public ApiResponse<?> importScores(@RequestParam MultipartFile file) throws IOException {
        log.info("Excel导入成绩: filename={}", file.getOriginalFilename());
        return ApiResponse.success("导入完成", excelService.importScores(file));
    }

    @PostMapping("/import/registrations")
    public ApiResponse<?> importRegistrations(@RequestParam MultipartFile file) throws IOException {
        log.info("Excel导入报名: filename={}", file.getOriginalFilename());
        return ApiResponse.success("导入完成", excelService.importRegistrations(file));
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
        return ApiResponse.success("秩序册(Word)已生成", wordOrderBookService.generateToDisk());
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
