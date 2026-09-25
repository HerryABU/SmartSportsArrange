package com.sports.controller.parade;

import com.sports.common.web.ApiResponse;
import com.sports.service.parade.ParadeScoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 自定义项目得分控制器（班级打分：手动录入 / Excel 导入 / 查询），按 projectCode 归属项目。
 */
@Slf4j
@RestController
@RequestMapping("/api/parade-score")
@RequiredArgsConstructor
public class ParadeScoreController {

    private final ParadeScoreService paradeScoreService;

    /** 查询某项目的班级得分（可按年级过滤），按分数降序 */
    @GetMapping
    public ApiResponse<?> list(@RequestParam(required = false) String projectCode,
                              @RequestParam(required = false) String grade) {
        log.info("查询项目[{}]得分: grade={}", projectCode, grade);
        return ApiResponse.success(paradeScoreService.list(projectCode, grade));
    }

    /** 手动录入（批量 upsert）：[{classId, score, remark}]，归属 projectCode */
    @PostMapping
    public ApiResponse<?> save(@RequestParam(required = false) String projectCode,
                              @RequestBody List<Map<String, Object>> items) {
        log.info("保存项目[{}]得分: {}条", projectCode, items != null ? items.size() : 0);
        return ApiResponse.success("保存成功", paradeScoreService.saveAll(projectCode, items != null ? items : List.of()));
    }

    /** Excel/CSV 导入：班级|得分 或 年级|班级|得分，归属 projectCode */
    @PostMapping("/import")
    public ApiResponse<?> importExcel(@RequestParam(required = false) String projectCode,
                                     @RequestParam MultipartFile file) {
        log.info("导入项目[{}]得分: {}", projectCode, file.getOriginalFilename());
        return ApiResponse.success("导入完成", paradeScoreService.importExcel(projectCode, file));
    }

    /** 删除一条 */
    @DeleteMapping("/{id}")
    public ApiResponse<?> delete(@PathVariable Long id) {
        log.info("删除项目得分: id={}", id);
        paradeScoreService.delete(id);
        return ApiResponse.success("删除成功", null);
    }

    /** 清空（按项目 + 可选年级） */
    @DeleteMapping
    public ApiResponse<?> clear(@RequestParam(required = false) String projectCode,
                               @RequestParam(required = false) String grade) {
        log.info("清空项目[{}]得分: grade={}", projectCode, grade);
        paradeScoreService.clear(projectCode, grade);
        return ApiResponse.success("已清空", null);
    }
}
