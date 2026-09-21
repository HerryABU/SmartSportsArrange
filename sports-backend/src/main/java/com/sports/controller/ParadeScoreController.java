package com.sports.controller;

import com.sports.common.ApiResponse;
import com.sports.service.ParadeScoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 入场式得分控制器（手动录入 / Excel 导入 / 查询）
 */
@Slf4j
@RestController
@RequestMapping("/api/parade-score")
@RequiredArgsConstructor
public class ParadeScoreController {

    private final ParadeScoreService paradeScoreService;

    /** 查询（可按年级过滤），按分数降序 */
    @GetMapping
    public ApiResponse<?> list(@RequestParam(required = false) String grade) {
        log.info("查询入场式得分: grade={}", grade);
        return ApiResponse.success(paradeScoreService.list(grade));
    }

    /** 手动录入（批量 upsert）：[{classId, score, remark}] */
    @PostMapping
    public ApiResponse<?> save(@RequestBody List<Map<String, Object>> items) {
        log.info("保存入场式得分: {}条", items != null ? items.size() : 0);
        return ApiResponse.success("保存成功", paradeScoreService.saveAll(items != null ? items : List.of()));
    }

    /** Excel/CSV 导入：班级|得分 或 年级|班级|得分 */
    @PostMapping("/import")
    public ApiResponse<?> importExcel(@RequestParam MultipartFile file) {
        log.info("导入入场式得分: {}", file.getOriginalFilename());
        return ApiResponse.success("导入完成", paradeScoreService.importExcel(file));
    }

    /** 删除一条 */
    @DeleteMapping("/{id}")
    public ApiResponse<?> delete(@PathVariable Long id) {
        log.info("删除入场式得分: id={}", id);
        paradeScoreService.delete(id);
        return ApiResponse.success("删除成功", null);
    }

    /** 清空（可按年级） */
    @DeleteMapping
    public ApiResponse<?> clear(@RequestParam(required = false) String grade) {
        log.info("清空入场式得分: grade={}", grade);
        paradeScoreService.clear(grade);
        return ApiResponse.success("已清空", null);
    }
}
