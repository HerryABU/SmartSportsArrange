package com.sports.controller;

import com.sports.common.ApiResponse;
import com.sports.service.RefereeService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 裁判管理控制器（超级管理员专属，路径 /api/system/** 已锁定 ROLE_SUPER_ADMIN）
 */
@Slf4j
@RestController
@RequestMapping("/api/system/referees")
@RequiredArgsConstructor
public class RefereeController {

    private final RefereeService refereeService;

    @GetMapping
    public ApiResponse<?> list() {
        log.info("查询裁判列表");
        return ApiResponse.success(refereeService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<?> getById(@PathVariable Long id) {
        log.info("查询裁判详情: id={}", id);
        return ApiResponse.success(refereeService.getById(id));
    }

    @PostMapping
    public ApiResponse<?> create(@RequestBody Map<String, Object> body) {
        log.info("创建裁判: name={}", body.get("name"));
        return ApiResponse.success("创建成功", refereeService.create(body));
    }

    @PutMapping("/{id}")
    public ApiResponse<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        log.info("更新裁判: id={}", id);
        return ApiResponse.success("更新成功", refereeService.update(id, body));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<?> delete(@PathVariable Long id) {
        log.info("删除裁判: id={}", id);
        refereeService.delete(id);
        return ApiResponse.success("删除成功", null);
    }

    @PostMapping("/import")
    public ApiResponse<?> importReferees(@RequestParam MultipartFile file) {
        log.info("Excel导入裁判: filename={}", file.getOriginalFilename());
        return ApiResponse.success("导入完成", refereeService.importReferees(file));
    }

    @GetMapping("/template")
    public void downloadTemplate(HttpServletResponse response) {
        log.info("下载裁判导入模板");
        refereeService.downloadTemplate(response);
    }

    // ==================== 裁判登录账号（角色 ROLE_REFEREE） ====================

    /** 为某裁判开通登录账号（body 可选 username/password，默认用户名取手机号、密码 123456） */
    @PostMapping("/{id}/account")
    public ApiResponse<?> openAccount(@PathVariable Long id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        log.info("为裁判开通账号: id={}", id);
        return ApiResponse.success("账号已开通", refereeService.openAccount(id, body == null ? Map.of() : body));
    }

    /** 批量为尚未开通账号的裁判开通账号 */
    @PostMapping("/accounts/open-all")
    public ApiResponse<?> openAccountsForAll() {
        log.info("批量开通裁判账号");
        return ApiResponse.success("批量开通完成", refereeService.openAccountsForAll());
    }
}
