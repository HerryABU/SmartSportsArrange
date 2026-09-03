package com.sports.controller;

import com.sports.common.ApiResponse;
import com.sports.service.ArrangementService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 编排控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/arrange")
@RequiredArgsConstructor
public class ArrangementController {

    private final ArrangementService arrangementService;

    @PostMapping("/events/{eventId}")
    public ApiResponse<?> executeArrangement(
            @PathVariable Long eventId,
            @RequestBody Map<String, Object> config) {
        log.info("执行编排: eventId={}, config={}", eventId, config);
        return ApiResponse.success("编排成功", arrangementService.executeArrangement(eventId, config));
    }

    @PostMapping("/preview")
    public ApiResponse<?> previewArrangement(@RequestBody Map<String, Object> config) {
        log.info("预览编排: config={}", config);
        return ApiResponse.success(arrangementService.previewArrangement(config));
    }

    @GetMapping("/events/{eventId}")
    public ApiResponse<?> viewArrangement(@PathVariable Long eventId) {
        log.info("查看编排结果: eventId={}", eventId);
        return ApiResponse.success(arrangementService.viewArrangement(eventId));
    }

    @PutMapping("/events/{eventId}")
    public ApiResponse<?> manualAdjust(
            @PathVariable Long eventId,
            @RequestBody List<Map<String, Object>> adjustments) {
        log.info("手动调整编排: eventId={}, adjustments={}", eventId, adjustments);
        return ApiResponse.success("调整成功", arrangementService.manualAdjust(eventId, adjustments));
    }

    @DeleteMapping("/events/{eventId}")
    public ApiResponse<Void> clearArrangement(@PathVariable Long eventId) {
        log.info("清除编排: eventId={}", eventId);
        arrangementService.clearArrangement(eventId);
        return ApiResponse.success("清除成功", null);
    }

    @PostMapping("/batch")
    public ApiResponse<?> batchArrange(@RequestBody List<Long> eventIds) {
        log.info("批量编排: eventIds={}", eventIds);
        return ApiResponse.success("批量编排成功", arrangementService.batchArrange(eventIds));
    }

    @PostMapping("/events/{eventId}/rollback")
    public ApiResponse<?> rollback(@PathVariable Long eventId) {
        log.info("回滚编排: eventId={}", eventId);
        return ApiResponse.success("回滚成功", arrangementService.rollback(eventId));
    }

    // ==================== 预赛淘汰（径赛 needHeats） ====================

    /** 生成预赛编排 */
    @PostMapping("/events/{eventId}/preliminary")
    public ApiResponse<?> generatePreliminary(@PathVariable Long eventId,
                                              @RequestBody Map<String, Object> config) {
        String grade = (String) config.get("grade");
        String gender = (String) config.get("gender");
        log.info("生成预赛编排: eventId={}, grade={}, gender={}", eventId, grade, gender);
        return ApiResponse.success("预赛编排成功", arrangementService.generatePreliminary(eventId, grade, gender));
    }

    /** 录入预赛成绩 items: [{athleteId, time}] */
    @PostMapping("/events/{eventId}/prelim-results")
    public ApiResponse<?> savePrelimResults(@PathVariable Long eventId,
                                            @RequestBody Map<String, Object> body) {
        String grade = (String) body.get("grade");
        String gender = (String) body.get("gender");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        log.info("录入预赛成绩: eventId={}, grade={}, gender={}, {}条", eventId, grade, gender,
                items != null ? items.size() : 0);
        return ApiResponse.success("预赛成绩已保存",
                arrangementService.savePrelimResults(eventId, grade, gender,
                        items != null ? items : List.of()));
    }

    /** 预赛淘汰「立刻计算」并生成决赛编排 */
    @PostMapping("/events/{eventId}/qualify")
    public ApiResponse<?> computeQualifiers(@PathVariable Long eventId,
                                            @RequestBody Map<String, Object> body) {
        String grade = (String) body.get("grade");
        String gender = (String) body.get("gender");
        Integer advanceCount = body.get("advanceCount") instanceof Number n
                ? n.intValue() : null;
        log.info("预赛淘汰计算: eventId={}, grade={}, gender={}, advanceCount={}",
                eventId, grade, gender, advanceCount);
        return ApiResponse.success("晋级计算完成",
                arrangementService.computeQualifiers(eventId, grade, gender, advanceCount));
    }

    /** 查看晋级名单 */
    @GetMapping("/events/{eventId}/qualifiers")
    public ApiResponse<?> viewQualifiers(@PathVariable Long eventId,
                                         @RequestParam(required = false) String grade,
                                         @RequestParam(required = false) String gender) {
        log.info("查看晋级名单: eventId={}, grade={}, gender={}", eventId, grade, gender);
        return ApiResponse.success(arrangementService.viewQualifiers(eventId, grade, gender));
    }

    @GetMapping("/events/{eventId}/export")
    public void exportLaneSheet(@PathVariable Long eventId, HttpServletResponse response) throws IOException {
        log.info("导出道次表: eventId={}", eventId);
        arrangementService.exportLaneSheet(eventId, response);
    }
}
