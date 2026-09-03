package com.sports.controller;

import com.sports.common.ApiResponse;
import com.sports.service.RankingService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 排名查询控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/ranking")
@RequiredArgsConstructor
public class RankingController {

    private final RankingService rankingService;

    @GetMapping("/team-score")
    public ApiResponse<?> getTeamScores(@RequestParam(required = false) String grade) {
        log.info("查询团体总分, grade={}", grade);
        return ApiResponse.success(rankingService.getTeamScores(grade));
    }

    /** 合分排行总览（班级×男女×项目，含/去除入场式；gender=男/女 出单性别榜） */
    @GetMapping("/scoreboard")
    public ApiResponse<?> getScoreBoard(
            @RequestParam(required = false) String grade,
            @RequestParam(defaultValue = "false") boolean includeParade,
            @RequestParam(defaultValue = "0") int topN,
            @RequestParam(defaultValue = "false") boolean byGrade,
            @RequestParam(required = false) String gender) {
        log.info("查询合分排行: grade={}, includeParade={}, topN={}, byGrade={}, gender={}",
                grade, includeParade, topN, byGrade, gender);
        return ApiResponse.success(rankingService.getScoreBoard(grade, includeParade, topN, byGrade, gender));
    }

    @GetMapping("/team-score/breakdown")
    public ApiResponse<?> getTeamBreakdown(
            @RequestParam String className,
            @RequestParam(required = false) String grade) {
        log.info("查询团体分项明细: class={}, grade={}", className, grade);
        return ApiResponse.success(rankingService.getTeamBreakdown(className, grade));
    }

    @GetMapping("/individual-score")
    public ApiResponse<?> getIndividualScores(
            @RequestParam(required = false) String grade,
            @RequestParam(required = false) Long eventId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        log.info("查询个人积分, grade={}, eventId={}", grade, eventId);
        return ApiResponse.success(rankingService.getIndividualScores(grade, eventId, page, size));
    }

    @GetMapping("/records")
    public ApiResponse<?> getRecords(
            @RequestParam(required = false) String grade,
            @RequestParam(required = false) Long eventId) {
        log.info("查询破纪录情况, grade={}, eventId={}", grade, eventId);
        return ApiResponse.success(rankingService.getRecords(grade, eventId));
    }

    @GetMapping("/events/{eventId}")
    public ApiResponse<?> getEventRanking(@PathVariable Long eventId) {
        log.info("查询项目排名: eventId={}", eventId);
        return ApiResponse.success(rankingService.getEventRanking(eventId));
    }

    @GetMapping("/individual-score/export")
    public void exportIndividualScore(HttpServletResponse response) throws IOException {
        log.info("导出个人积分排名");
        exportExcel(response, rankingService.getIndividualScores(null, null, 1, 9999), "个人积分排名");
    }

    @GetMapping("/team-score/export")
    public void exportTeamScore(HttpServletResponse response) throws IOException {
        log.info("导出团体总分排名");
        exportExcel(response, rankingService.getTeamScores(null), "团体总分排名");
    }

    @GetMapping("/records/export")
    public void exportRecords(HttpServletResponse response) throws IOException {
        log.info("导出破纪录榜");
        exportExcel(response, rankingService.getRecords(null, null), "破纪录榜");
    }

    @SuppressWarnings("unchecked")
    private void exportExcel(HttpServletResponse response, Object data, String sheetName) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = sheetName + "_" + java.time.LocalDateTime.now().toString().replace(":", "-") + ".xlsx";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20")
                + ";filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20"));

        List<Map<String, Object>> rows;
        if (data instanceof List) {
            rows = (List<Map<String, Object>>) data;
        } else {
            rows = List.of();
        }

        try (var out = response.getOutputStream()) {
            if (!rows.isEmpty()) {
                List<String> headers = List.copyOf(rows.get(0).keySet());
                List<List<String>> headCols = headers.stream()
                        .map(List::of)
                        .collect(java.util.stream.Collectors.toList());
                List<List<String>> dataRows = rows.stream()
                        .map(row -> headers.stream().map(h -> String.valueOf(row.getOrDefault(h, ""))).collect(java.util.stream.Collectors.toList()))
                        .collect(java.util.stream.Collectors.toList());
                com.alibaba.excel.EasyExcel.write(out)
                        .head(headCols)
                        .sheet(sheetName)
                        .doWrite(dataRows);
            }
        }
    }
}
