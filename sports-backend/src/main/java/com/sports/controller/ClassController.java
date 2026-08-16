package com.sports.controller;

import com.sports.common.ApiResponse;
import com.sports.entity.ClassInfo;
import com.sports.service.ClassService;
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
import java.util.Map;

/**
 * 班级管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/classes")
@RequiredArgsConstructor
public class ClassController {

    private final ClassService classService;

    @GetMapping
    public ApiResponse<ApiResponse.PageData<ClassInfo>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String grade) {
        log.info("查询班级列表: page={}, size={}, grade={}", page, size, grade);
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<ClassInfo> result = classService.list(pageable, grade);
        return ApiResponse.page(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<ClassInfo> getById(@PathVariable Long id) {
        log.info("查询班级详情: id={}", id);
        return ApiResponse.success(classService.getById(id));
    }

    @PostMapping
    public ApiResponse<ClassInfo> create(@RequestBody @Valid ClassInfo classInfo) {
        log.info("创建班级: name={}", classInfo.getName());
        return ApiResponse.success("创建成功", classService.create(classInfo));
    }

    @PutMapping("/{id}")
    public ApiResponse<ClassInfo> update(@PathVariable Long id, @RequestBody @Valid ClassInfo classInfo) {
        log.info("更新班级: id={}", id);
        return ApiResponse.success("更新成功", classService.update(id, classInfo));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        log.info("删除班级: id={}", id);
        classService.delete(id);
        return ApiResponse.success("删除成功", null);
    }

    @PostMapping("/import")
    public ApiResponse<?> importClasses(@RequestParam MultipartFile file) throws IOException {
        log.info("导入班级: filename={}", file.getOriginalFilename());
        return ApiResponse.success("导入成功", classService.importClasses(file));
    }

    @GetMapping("/export")
    public void export(HttpServletResponse response) throws IOException {
        log.info("导出班级数据");
        classService.export(response);
    }

    @GetMapping("/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        log.info("下载班级导入模板");
        classService.downloadTemplate(response);
    }

    @PostMapping("/batch")
    public ApiResponse<?> batchCreate(@RequestBody Map<String, Object> body) {
        log.info("批量创建班级: {}", body);
        return ApiResponse.success("批量创建完成", classService.batchCreate(body));
    }

    /** 绑定班主任到班级 */
    @PutMapping("/{id}/bind-teacher")
    public ApiResponse<?> bindTeacher(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String username = (String) body.get("username");
        classService.bindTeacher(id, username);
        return ApiResponse.success("绑定成功", null);
    }
}
