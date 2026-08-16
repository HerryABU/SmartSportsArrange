package com.sports.controller;

import com.sports.common.ApiResponse;
import com.sports.service.StatisticsService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

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

    @PostMapping("/order-book")
    public ApiResponse<?> generateOrderBook() {
        log.info("生成秩序册");
        return ApiResponse.success("秩序册生成成功", statisticsService.generateOrderBook());
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
