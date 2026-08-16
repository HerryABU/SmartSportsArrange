package com.sports.controller;

import com.sports.common.ApiResponse;
import com.sports.entity.Result;
import com.sports.service.ResultService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 成绩管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/results")
@RequiredArgsConstructor
public class ResultController {

    private final ResultService resultService;

    @GetMapping
    public ApiResponse<List<Result>> list(
            @RequestParam(required = false) Long eventId,
            @RequestParam(required = false) Integer heat) {
        log.info("查询成绩列表: eventId={}, heat={}", eventId, heat);
        return ApiResponse.success(resultService.list(eventId, heat));
    }

    @PostMapping
    public ApiResponse<Result> enterScore(@RequestBody @Valid Map<String, Object> resultInput) {
        log.info("录入成绩: {}", resultInput);
        return ApiResponse.success("录入成功", resultService.enterScore(resultInput));
    }

    @PutMapping("/{id}")
    public ApiResponse<Result> modify(@PathVariable Long id, @RequestBody @Valid Map<String, Object> resultInput) {
        log.info("修改成绩: id={}", id);
        return ApiResponse.success("修改成功", resultService.modify(id, resultInput));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        log.info("删除成绩: id={}", id);
        resultService.delete(id);
        return ApiResponse.success("删除成功", null);
    }

    @PostMapping("/import")
    public ApiResponse<?> importResults(@RequestParam MultipartFile file) throws IOException {
        log.info("导入成绩: filename={}", file.getOriginalFilename());
        return ApiResponse.success("导入成功", resultService.importResults(file));
    }

    @PostMapping("/events/{eventId}/calculate-ranking")
    public ApiResponse<?> calculateRanking(@PathVariable Long eventId) {
        log.info("计算排名: eventId={}", eventId);
        return ApiResponse.success("排名计算完成", resultService.calculateRanking(eventId));
    }

    @GetMapping("/events/{eventId}/ranking")
    public ApiResponse<?> viewRanking(@PathVariable Long eventId) {
        log.info("查看排名: eventId={}", eventId);
        return ApiResponse.success(resultService.viewRanking(eventId));
    }

    @GetMapping("/events/{eventId}/export")
    public void exportResults(@PathVariable Long eventId, HttpServletResponse response) throws IOException {
        log.info("导出成绩: eventId={}", eventId);
        resultService.exportResults(eventId, response);
    }
}
