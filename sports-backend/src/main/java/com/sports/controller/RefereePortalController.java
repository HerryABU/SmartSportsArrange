package com.sports.controller;

import com.sports.common.ApiResponse;
import com.sports.security.jwt.JwtUserDetails;
import com.sports.service.ArrangementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 裁判端接口（裁判本人登录后使用，角色 {@code ROLE_REFEREE}）。
 * <p>裁判花名册通过 {@code referee.user_id} 关联登录账号，此处按当前登录用户返回其执裁安排。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/referee")
@RequiredArgsConstructor
public class RefereePortalController {

    private final ArrangementService arrangementService;

    /** 我的执裁安排（按当前登录裁判聚合） */
    @GetMapping("/me")
    public ApiResponse<?> myAssignments() {
        Long userId = currentUserId();
        log.info("裁判端·我的执裁安排: userId={}", userId);
        return ApiResponse.success(arrangementService.getRefereeBoardForUser(userId));
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof JwtUserDetails userDetails) {
            return userDetails.getUserId();
        }
        throw new RuntimeException("未获取到当前登录用户");
    }
}
