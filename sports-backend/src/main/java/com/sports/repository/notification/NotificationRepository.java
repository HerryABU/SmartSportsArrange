package com.sports.repository.notification;

import com.sports.entity.notification.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByRecipientUserIdOrderByCreatedAtDesc(Long userId);

    List<Notification> findByRecipientRoleOrderByCreatedAtDesc(String role);

    long countByRecipientUserIdAndIsReadFalse(Long userId);

    long countByRecipientRoleAndIsReadFalse(String role);
}
