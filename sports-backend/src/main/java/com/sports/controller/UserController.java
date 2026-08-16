package com.sports.controller;

import com.sports.common.ApiResponse;
import com.sports.entity.User;
import com.sports.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 用户管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/system/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    public ApiResponse<?> list() {
        log.info("查询用户列表");
        return ApiResponse.success(userService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<?> getById(@PathVariable Long id) {
        log.info("查询用户详情: id={}", id);
        return ApiResponse.success(userService.getById(id));
    }

    @PostMapping
    public ApiResponse<?> create(@RequestBody Map<String, Object> body) {
        log.info("创建用户: username={}", body.get("username"));
        return ApiResponse.success("创建成功", userService.create(body));
    }

    @PutMapping("/{id}")
    public ApiResponse<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        log.info("更新用户: id={}", id);
        return ApiResponse.success("更新成功", userService.update(id, body));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<?> delete(@PathVariable Long id) {
        log.info("删除用户: id={}", id);
        userService.delete(id);
        return ApiResponse.success("删除成功", null);
    }

    @PutMapping("/{id}/reset-password")
    public ApiResponse<?> resetPassword(@PathVariable Long id) {
        log.info("重置密码: id={}", id);
        userService.resetPassword(id);
        return ApiResponse.success("密码已重置为默认密码", null);
    }

    @PostMapping("/import")
    public ApiResponse<?> importUsers(@RequestParam MultipartFile file) {
        log.info("Excel导入用户: filename={}", file.getOriginalFilename());
        return ApiResponse.success("导入完成", userService.importUsers(file));
    }

    @GetMapping("/template")
    public void downloadTemplate(HttpServletResponse response) {
        log.info("下载用户导入模板");
        userService.downloadTemplate(response);
    }

    @PostMapping("/batch")
    public ApiResponse<?> batchCreate(@RequestBody Map<String, Object> body) {
        log.info("批量创建用户: {}", body);
        return ApiResponse.success("批量创建完成", userService.batchCreate(body));
    }
}
