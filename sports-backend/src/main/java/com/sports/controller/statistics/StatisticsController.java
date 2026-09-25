package com.sports.controller.statistics;

import com.sports.common.web.ApiResponse;
import com.sports.service.statistics.StatisticsService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

/**
 * 统计报表控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/statistics")
@RequiredArgsConstructor
public class StatisticsController {

    private final StatisticsService statisticsService;

    @GetMapping("/todo")
    public ApiResponse<?> todoStats() {
        log.info("查询待办统计");
        return ApiResponse.success(statisticsService.getTodoStats());
    }

    @GetMapping("/registration-progress")
    public ApiResponse<?> registrationProgress() {
        log.info("查询报名进度");
        return ApiResponse.success(statisticsService.getRegistrationProgress());
    }

    @GetMapping("/today-schedule")
    public ApiResponse<?> todaySchedule() {
        log.info("查询今日赛程");
        return ApiResponse.success(statisticsService.getTodaySchedule());
    }

    @GetMapping("/registration")
    public ApiResponse<?> registrationStats() {
        log.info("查询报名统计");
        return ApiResponse.success(statisticsService.registrationStats());
    }

    @GetMapping("/score")
    public ApiResponse<?> scoreStats() {
        log.info("查询成绩统计");
        return ApiResponse.success(statisticsService.scoreStats());
    }

    /**
     * 跨届进步榜：同一学生跨多届同一项目的名次进步。
     * eventId 指定项目（单项目榜）；留空则汇总全部项目（综合进步最大者）。
     */
    @GetMapping("/progress")
    public ApiResponse<?> progressLeaderboard(@RequestParam(required = false) Long eventId,
                                             @RequestParam(required = false) Integer limit) {
        log.info("查询跨届进步榜: eventId={}, limit={}", eventId, limit);
        return ApiResponse.success(statisticsService.getProgressLeaderboard(eventId, limit));
    }

    @PostMapping("/order-book")
    public ApiResponse<?> generateOrderBook(@RequestBody(required = false) Map<String, Object> body) {
        String grade = body != null && body.get("grade") != null ? body.get("grade").toString() : null;
        log.info("生成秩序册: grade={}", grade);
        return ApiResponse.success("秩序册生成成功", statisticsService.generateOrderBook(grade));
    }

    @PostMapping("/result-book")
    public ApiResponse<?> generateResultBook() {
        log.info("生成成绩册");
        return ApiResponse.success("成绩册生成成功", statisticsService.generateResultBook());
    }

    @GetMapping("/export")
    public void export(HttpServletResponse response) throws IOException {
        log.info("导出统计数据");
        statisticsService.export(response);
    }
}
