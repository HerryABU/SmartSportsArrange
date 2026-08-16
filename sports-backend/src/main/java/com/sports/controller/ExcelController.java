package com.sports.controller;

import com.sports.common.ApiResponse;
import com.sports.service.ExcelService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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
}
