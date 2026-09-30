package com.sports.repository.protection;

import com.sports.entity.protection.AdminTimeProtection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AdminTimeProtectionRepository extends JpaRepository<AdminTimeProtection, Long> {

    List<AdminTimeProtection> findByEnabledTrueOrderByDayAscStartTimeAsc();

    List<AdminTimeProtection> findAllByOrderByDayAscStartTimeAsc();

    List<AdminTimeProtection> findByTargetType(String targetType);

    List<AdminTimeProtection> findByTargetTypeAndTargetId(String targetType, Long targetId);
}
