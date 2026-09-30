package com.sports.service.notification;

import com.sports.entity.notification.Notification;
import com.sports.repository.notification.NotificationRepository;
import com.sports.security.jwt.JwtUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 站内通知服务（内通知）。
 *
 * <p>编排 / 兼项冲突消解等动作在此产生消息，定向投递给具体用户或整类角色；
 * 前端通知中心按当前登录用户拉取（按 userId 精确 + 按 role 广播两类都命中）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationService {

    private final NotificationRepository notificationRepository;

    /** 定向通知某用户 */
    public Notification notifyUser(Long userId, String title, String content, String type) {
        Notification n = Notification.builder()
                .recipientUserId(userId)
                .title(title)
                .content(content)
                .type(type)
                .build();
        return notificationRepository.save(n);
    }

    /** 广播通知某角色（如 CLASS_TEACHER / REFEREE） */
    public Notification notifyRole(String role, String title, String content, String type) {
        Notification n = Notification.builder()
                .recipientRole(role)
                .title(title)
                .content(content)
                .type(type)
                .build();
        return notificationRepository.save(n);
    }

    /** 当前登录用户（userId + role）的通知列表（最新在前） */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listMine() {
        CurrentUser cu = currentUser();
        Set<Notification> set = new LinkedHashSet<>();
        if (cu.userId != null) {
            set.addAll(notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(cu.userId));
        }
        if (cu.role != null) {
            set.addAll(notificationRepository.findByRecipientRoleOrderByCreatedAtDesc(cu.role));
        }
        List<Notification> all = new ArrayList<>(set);
        all.sort(Comparator.comparing(Notification::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return all.stream().map(this::toMap).collect(Collectors.toList());
    }

    /** 当前用户未读数 */
    @Transactional(readOnly = true)
    public long unreadCount() {
        CurrentUser cu = currentUser();
        long n = 0;
        if (cu.userId != null) n += notificationRepository.countByRecipientUserIdAndIsReadFalse(cu.userId);
        if (cu.role != null) n += notificationRepository.countByRecipientRoleAndIsReadFalse(cu.role);
        return n;
    }

    /** 标记单条已读 */
    public void markRead(Long id) {
        notificationRepository.findById(id).ifPresent(n -> {
            n.setIsRead(true);
            notificationRepository.save(n);
        });
    }

    /** 当前用户全部标记已读 */
    public void markAllRead() {
        CurrentUser cu = currentUser();
        if (cu.userId != null) {
            for (Notification n : notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(cu.userId)) {
                if (!Boolean.TRUE.equals(n.getIsRead())) {
                    n.setIsRead(true);
                    notificationRepository.save(n);
                }
            }
        }
        if (cu.role != null) {
            for (Notification n : notificationRepository.findByRecipientRoleOrderByCreatedAtDesc(cu.role)) {
                if (!Boolean.TRUE.equals(n.getIsRead())) {
                    n.setIsRead(true);
                    notificationRepository.save(n);
                }
            }
        }
    }

    private Map<String, Object> toMap(Notification n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.getId());
        m.put("title", n.getTitle());
        m.put("content", n.getContent());
        m.put("type", n.getType());
        m.put("read", Boolean.TRUE.equals(n.getIsRead()));
        m.put("createdAt", n.getCreatedAt());
        return m;
    }

    private CurrentUser currentUser() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof JwtUserDetails u) {
                return new CurrentUser(u.getUserId(), u.getRole());
            }
        } catch (Exception ignored) {
        }
        return new CurrentUser(null, null);
    }

    private record CurrentUser(Long userId, String role) {
    }
}
