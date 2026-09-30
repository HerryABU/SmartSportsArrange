package com.sports.controller.notification;

import com.sports.common.web.ApiResponse;
import com.sports.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 站内通知（内通知）控制器。
 */
@Slf4j
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ApiResponse<?> list() {
        return ApiResponse.success(notificationService.listMine());
    }

    @GetMapping("/unread-count")
    public ApiResponse<?> unreadCount() {
        return ApiResponse.success(java.util.Map.of("count", notificationService.unreadCount()));
    }

    @PutMapping("/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable Long id) {
        notificationService.markRead(id);
        return ApiResponse.success("已读", null);
    }

    @PutMapping("/read-all")
    public ApiResponse<Void> markAllRead() {
        notificationService.markAllRead();
        return ApiResponse.success("全部已读", null);
    }
}
