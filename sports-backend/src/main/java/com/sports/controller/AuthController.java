package com.sports.controller;

import com.sports.common.ApiResponse;
import com.sports.common.BusinessException;
import com.sports.dto.LoginRequest;
import com.sports.dto.LoginResponse;
import com.sports.security.JwtUserDetails;
import com.sports.security.JwtUtil;
import com.sports.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtUtil jwtUtil;

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody @Valid LoginRequest request) {
        log.info("用户登录: {}", request.getUsername());
        LoginResponse response = authService.login(request);
        return ApiResponse.success("登录成功", response);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        String token = extractToken(request);
        log.info("用户登出");
        authService.logout(token);
        return ApiResponse.success("登出成功", null);
    }

    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(@RequestParam String refreshToken) {
        log.info("刷新令牌");
        LoginResponse response = authService.refresh(refreshToken);
        return ApiResponse.success("令牌刷新成功", response);
    }

    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@RequestBody @Valid Map<String, String> request) {
        Long userId = getCurrentUserId();
        String oldPassword = request.get("oldPassword");
        String newPassword = request.get("newPassword");
        if (oldPassword == null || newPassword == null) {
            throw BusinessException.badRequest("旧密码和新密码不能为空");
        }
        log.info("用户修改密码: userId={}", userId);
        authService.changePassword(userId, oldPassword, newPassword);
        return ApiResponse.success("密码修改成功", null);
    }

    @GetMapping("/profile")
    public ApiResponse<?> getProfile() {
        Long userId = getCurrentUserId();
        log.info("获取用户信息: userId={}", userId);
        return ApiResponse.success(authService.getProfile(userId));
    }

    @PutMapping("/profile")
    public ApiResponse<?> updateProfile(@RequestBody Map<String, Object> profile) {
        Long userId = getCurrentUserId();
        log.info("更新用户信息: userId={}", userId);
        return ApiResponse.success(authService.updateProfile(userId, profile));
    }

    private Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof JwtUserDetails userDetails) {
            return userDetails.getUserId();
        }
        throw BusinessException.unauthorized("未登录或登录已过期");
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
