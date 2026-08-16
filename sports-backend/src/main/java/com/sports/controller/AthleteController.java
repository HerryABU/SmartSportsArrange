package com.sports.controller;

import com.sports.common.ApiResponse;
import com.sports.entity.Athlete;
import com.sports.service.AthleteService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 运动员管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/athletes")
@RequiredArgsConstructor
public class AthleteController {

    private final AthleteService athleteService;

    @GetMapping
    public ApiResponse<ApiResponse.PageData<Athlete>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String grade,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) String keyword) {
        log.info("查询运动员列表: page={}, size={}, grade={}, classId={}, keyword={}",
                page, size, grade, classId, keyword);
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<Athlete> result = athleteService.list(pageable, grade, classId, keyword);
        return ApiResponse.page(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<Athlete> getById(@PathVariable Long id) {
        log.info("查询运动员详情: id={}", id);
        return ApiResponse.success(athleteService.getById(id));
    }

    @PostMapping
    public ApiResponse<Athlete> create(@RequestBody @Valid Athlete athlete) {
        log.info("创建运动员: name={}", athlete.getName());
        return ApiResponse.success("创建成功", athleteService.create(athlete));
    }

    @PutMapping("/{id}")
    public ApiResponse<Athlete> update(@PathVariable Long id, @RequestBody @Valid Athlete athlete) {
        log.info("更新运动员: id={}", id);
        return ApiResponse.success("更新成功", athleteService.update(id, athlete));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        log.info("删除运动员: id={}", id);
        athleteService.delete(id);
        return ApiResponse.success("删除成功", null);
    }

    @PostMapping("/import")
    public ApiResponse<?> importAthletes(@RequestParam MultipartFile file) throws IOException {
        log.info("导入运动员: filename={}", file.getOriginalFilename());
        return ApiResponse.success("导入完成", athleteService.importAthletes(file));
    }

    @GetMapping("/export")
    public void export(HttpServletResponse response) throws IOException {
        log.info("导出运动员数据");
        athleteService.export(response);
    }

    @PostMapping("/batch-generate-numbers")
    public ApiResponse<?> batchGenerateNumbers(
            @RequestParam(required = false) String grade,
            @RequestParam(required = false) Long classId) {
        log.info("批量生成号码: grade={}, classId={}", grade, classId);
        return ApiResponse.success("号码生成成功", athleteService.batchGenerateNumbers(grade, classId));
    }

    @GetMapping("/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        log.info("下载运动员导入模板");
        athleteService.downloadTemplate(response);
    }
}
