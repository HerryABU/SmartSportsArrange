package com.sports.service.protection;

import com.sports.entity.athlete.Athlete;
import com.sports.entity.clazz.ClassInfo;
import com.sports.entity.protection.AdminTimeProtection;
import com.sports.entity.registration.Registration;
import com.sports.repository.athlete.AthleteRepository;
import com.sports.repository.clazz.ClassInfoRepository;
import com.sports.repository.protection.AdminTimeProtectionRepository;
import com.sports.repository.referee.RefereeRepository;
import com.sports.repository.registration.RegistrationRepository;
import com.sports.repository.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 行政时间保护（规避时间）服务：CRUD + 供编排 / 裁判编排消费的「按需解析」。
 *
 * <p>三种保护对象（GLOBAL / TEACHER / REFEREE）的消费口径：</p>
 * <ul>
 *   <li>{@link #globalBlocks()}：GLOBAL 避让时段 → 编排端整段不可排；</li>
 *   <li>{@link #teacherEventBlocks()}：TEACHER 个人时段 → 展开为「受影响的 projectId → 保护列表」；</li>
 *   <li>{@link #refereeBlocks()}：REFEREE 个人时段 → 展开为「裁判 id → 保护列表」。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AdminTimeProtectionService {

    private final AdminTimeProtectionRepository protectionRepository;
    private final ClassInfoRepository classInfoRepository;
    private final AthleteRepository athleteRepository;
    private final RegistrationRepository registrationRepository;
    private final RefereeRepository refereeRepository;
    private final UserRepository userRepository;

    // ==================== CRUD ====================

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return protectionRepository.findAllByOrderByDayAscStartTimeAsc().stream()
                .map(this::toMap).collect(Collectors.toList());
    }

    public Map<String, Object> create(Map<String, Object> body) {
        String targetType = str(body.get("targetType"), AdminTimeProtection.TYPE_GLOBAL).toUpperCase();
        if (!Set.of(AdminTimeProtection.TYPE_GLOBAL, AdminTimeProtection.TYPE_TEACHER,
                AdminTimeProtection.TYPE_REFEREE).contains(targetType)) {
            throw new RuntimeException("无效的保护对象类型: " + targetType);
        }
        String startTime = str(body.get("startTime"), null);
        String endTime = str(body.get("endTime"), null);
        if (startTime == null || endTime == null || startTime.isBlank() || endTime.isBlank()) {
            throw new RuntimeException("保护时段起止时间不能为空");
        }
        AdminTimeProtection p = AdminTimeProtection.builder()
                .targetType(targetType)
                .targetId(longOrNull(body.get("targetId")))
                .targetName(resolveTargetName(targetType, longOrNull(body.get("targetId")),
                        str(body.get("targetName"), null)))
                .day(intOrNull(body.get("day")))
                .startTime(startTime.trim())
                .endTime(endTime.trim())
                .reason(str(body.get("reason"), null))
                .enabled(body.get("enabled") == null || Boolean.TRUE.equals(body.get("enabled")))
                .build();
        p = protectionRepository.save(p);
        log.info("新增行政时间保护: type={}, name={}, {}~{}", targetType, p.getTargetName(), startTime, endTime);
        return toMap(p);
    }

    public Map<String, Object> update(Long id, Map<String, Object> body) {
        AdminTimeProtection p = protectionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("行政时间保护不存在: " + id));
        if (body.containsKey("targetType")) {
            p.setTargetType(str(body.get("targetType"), p.getTargetType()).toUpperCase());
        }
        if (body.containsKey("targetId")) p.setTargetId(longOrNull(body.get("targetId")));
        if (body.containsKey("startTime")) p.setStartTime(str(body.get("startTime"), p.getStartTime()));
        if (body.containsKey("endTime")) p.setEndTime(str(body.get("endTime"), p.getEndTime()));
        if (body.containsKey("day")) p.setDay(intOrNull(body.get("day")));
        if (body.containsKey("reason")) p.setReason(str(body.get("reason"), p.getReason()));
        if (body.containsKey("enabled")) p.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        if (body.containsKey("targetName")) {
            p.setTargetName(str(body.get("targetName"), p.getTargetName()));
        } else {
            p.setTargetName(resolveTargetName(p.getTargetType(), p.getTargetId(), p.getTargetName()));
        }
        p = protectionRepository.save(p);
        return toMap(p);
    }

    public void delete(Long id) {
        AdminTimeProtection p = protectionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("行政时间保护不存在: " + id));
        p.setDeletedAt(LocalDateTime.now());
        protectionRepository.save(p);
    }

    // ==================== 编排 / 裁判编排消费 ====================

    /** GLOBAL 避让时段（启用中），供编排端整段不可排 */
    @Transactional(readOnly = true)
    public List<AdminTimeProtection> globalBlocks() {
        return protectionRepository.findByEnabledTrueOrderByDayAscStartTimeAsc().stream()
                .filter(p -> AdminTimeProtection.TYPE_GLOBAL.equals(p.getTargetType()))
                .collect(Collectors.toList());
    }

    /**
     * TEACHER 个人时段 → 受影响的「项目 id → 保护列表」。
     * 教师（用户 id）→ 其班主任班级 → 班级运动员 → 已审核报名 → 项目 id。
     */
    @Transactional(readOnly = true)
    public Map<Long, List<AdminTimeProtection>> teacherEventBlocks() {
        List<AdminTimeProtection> teacherBlocks = protectionRepository.findByEnabledTrueOrderByDayAscStartTimeAsc().stream()
                .filter(p -> AdminTimeProtection.TYPE_TEACHER.equals(p.getTargetType()))
                .collect(Collectors.toList());
        Map<Long, List<AdminTimeProtection>> out = new HashMap<>();
        if (teacherBlocks.isEmpty()) return out;

        // 预取：教师 -> 班级 -> 运动员 -> 已审核报名(项目)
        for (AdminTimeProtection p : teacherBlocks) {
            if (p.getTargetId() == null) continue;
            Set<Long> eventIds = eventIdsOfTeacher(p.getTargetId());
            for (Long eventId : eventIds) {
                out.computeIfAbsent(eventId, k -> new ArrayList<>()).add(p);
            }
        }
        return out;
    }

    /** REFEREE 个人时段 → 「裁判 id → 保护列表」，供裁判编排端跳过受保护裁判 */
    @Transactional(readOnly = true)
    public Map<Long, List<AdminTimeProtection>> refereeBlocks() {
        Map<Long, List<AdminTimeProtection>> out = new HashMap<>();
        for (AdminTimeProtection p : protectionRepository.findByEnabledTrueOrderByDayAscStartTimeAsc()) {
            if (!AdminTimeProtection.TYPE_REFEREE.equals(p.getTargetType()) || p.getTargetId() == null) continue;
            out.computeIfAbsent(p.getTargetId(), k -> new ArrayList<>()).add(p);
        }
        return out;
    }

    /** 教师（用户 id）名下班主任班级的运动员所报（已审核）项目 id 集合 */
    private Set<Long> eventIdsOfTeacher(Long userId) {
        Set<Long> eventIds = new HashSet<>();
        for (ClassInfo c : classInfoRepository.findByTeacherUserId(userId)) {
            List<Athlete> athletes = athleteRepository.findByClassInfoId(c.getId());
            if (athletes.isEmpty()) continue;
            List<Long> athleteIds = athletes.stream().map(Athlete::getId).collect(Collectors.toList());
            for (Registration r : registrationRepository.findActiveByAthleteIdIn(athleteIds)) {
                if ("approved".equals(r.getStatus()) && r.getEvent() != null) {
                    eventIds.add(r.getEvent().getId());
                }
            }
        }
        return eventIds;
    }

    private String resolveTargetName(String type, Long targetId, String provided) {
        if (provided != null && !provided.isBlank()) return provided.trim();
        if (targetId == null) return "全校";
        try {
            if (AdminTimeProtection.TYPE_REFEREE.equals(type)) {
                return refereeRepository.findById(targetId).map(r -> r.getName()).orElse("裁判#" + targetId);
            }
            if (AdminTimeProtection.TYPE_TEACHER.equals(type)) {
                return userRepository.findById(targetId).map(u -> u.getName()).orElse("教师#" + targetId);
            }
        } catch (Exception ignored) {
        }
        return "全校";
    }

    private Map<String, Object> toMap(AdminTimeProtection p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("targetType", p.getTargetType());
        m.put("targetId", p.getTargetId());
        m.put("targetName", p.getTargetName());
        m.put("day", p.getDay());
        m.put("startTime", p.getStartTime());
        m.put("endTime", p.getEndTime());
        m.put("reason", p.getReason());
        m.put("enabled", p.getEnabled());
        m.put("createdAt", p.getCreatedAt());
        m.put("updatedAt", p.getUpdatedAt());
        return m;
    }

    private static String str(Object v, String def) {
        return v == null || String.valueOf(v).isBlank() ? def : String.valueOf(v).trim();
    }

    private static Long longOrNull(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v).trim()); } catch (Exception e) { return null; }
    }

    private static Integer intOrNull(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v).trim()); } catch (Exception e) { return null; }
    }
}
