package com.sports.controller;

import com.sports.common.ApiResponse;
import com.sports.common.BusinessException;
import com.sports.service.SetupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 建站向导控制器（首次运行安装）。
 *
 * 安全：安装完成后，install / check-db 接口一律拒绝（403），无法二次进入；
 * status 接口公开（前端据此判断是否需要引导到安装向导）。
 */
@Slf4j
@RestController
@RequestMapping("/api/setup")
@RequiredArgsConstructor
public class SetupController {

    private final SetupService setupService;

    /** 安装状态（公开） */
    @GetMapping("/status")
    public ApiResponse<?> status() {
        return ApiResponse.success(setupService.getStatus());
    }

    /** 测试数据库连接（仅未安装时可调用） */
    @PostMapping("/check-db")
    public ApiResponse<?> checkDb(@RequestBody Map<String, Object> db) {
        if (setupService.isInstalled()) {
            throw BusinessException.forbidden("系统已安装");
        }
        return ApiResponse.success(setupService.testDb(db));
    }

    /** 执行安装（仅未安装时可调用，安装后锁定） */
    @PostMapping("/install")
    public ApiResponse<?> install(@RequestBody Map<String, Object> body) {
        if (setupService.isInstalled()) {
            throw BusinessException.forbidden("系统已安装，安装向导已锁定");
        }
        log.info("执行建站安装: siteName={}, dbType={}", body.get("siteName"), body.get("dbType"));
        return ApiResponse.success("安装完成", setupService.install(body));
    }
}
