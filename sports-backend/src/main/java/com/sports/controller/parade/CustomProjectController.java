package com.sports.controller.parade;

import com.sports.common.web.ApiResponse;
import com.sports.service.parade.CustomProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 自定义项目（自定义项目区）控制器：项目增删改查。
 */
@Slf4j
@RestController
@RequestMapping("/api/custom-project")
@RequiredArgsConstructor
public class CustomProjectController {

    private final CustomProjectService customProjectService;

    @GetMapping
    public ApiResponse<?> list() {
        return ApiResponse.success(customProjectService.list());
    }

    /** 创建 / 更新项目：{id?, code?, name, type?, sortOrder?, countInTotal?} */
    @PostMapping
    public ApiResponse<?> save(@RequestBody Map<String, Object> item) {
        return ApiResponse.success("保存成功", customProjectService.save(item));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<?> delete(@PathVariable Long id) {
        customProjectService.delete(id);
        return ApiResponse.success("删除成功", null);
    }
}
