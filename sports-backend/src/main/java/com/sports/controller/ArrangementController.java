package com.sports.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sports.common.ApiResponse;
import com.sports.service.ArrangementService;
import com.sports.service.ConflictService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDateTime;
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
    private final ConflictService conflictService;
    private final ObjectMapper objectMapper;

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

    /**
     * 全量编排导出（含预赛与决赛），作为 arrange_result.json 的程序化来源与统一数据源（B01/U01/B17）。
     * 二次编排后调用本接口即可得到含决赛的完整编排 JSON。
     */
    @GetMapping("/export-all")
    public void exportAllArrangement(HttpServletResponse response) throws IOException {
        log.info("导出全量编排(JSON,含决赛)");
        Map<String, Object> data = arrangementService.exportAllArrangement();
        response.setContentType("application/json");
        response.setCharacterEncoding("utf-8");
        // U11/B13：文件名带「阶段 + 版本 + 生成时间」；含决赛即二次编排后
        boolean afterSecond = data.get("finalRoundCount") instanceof Number n && n.intValue() > 0;
        String fileName = "arrange_result_" + com.sports.common.ExportNaming.stage(afterSecond)
                + "_v" + com.sports.common.ExportNaming.appVersion()
                + "_" + com.sports.common.ExportNaming.stamp() + ".json";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
                        + ";filename*=UTF-8''" + java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20"));
        objectMapper.writeValue(response.getWriter(), data);
    }

    /**
     * B06 / U05：兼项冲突检测。返回同一运动员在相近时间参加的不同项目冲突清单。
     */
    @GetMapping("/conflicts")
    public ApiResponse<?> conflicts() {
        log.info("查询兼项冲突");
        return ApiResponse.success("兼项冲突检测完成", conflictService.detectConflicts());
    }
}
