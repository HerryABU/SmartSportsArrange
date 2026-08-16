package com.sports.service;

import com.sports.entity.*;
import com.sports.repository.*;
import com.alibaba.excel.EasyExcel;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RegistrationService {

    private final RegistrationRepository registrationRepository;
    private final AthleteRepository athleteRepository;
    private final EventRepository eventRepository;
    private final SystemConfigRepository systemConfigRepository;

    /** 分页查询报名 */
    @Transactional(readOnly = true)
    public Page<Registration> list(Pageable pageable, Long eventId, Long classId, String status) {
        Specification<Registration> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (eventId != null)
                predicates.add(cb.equal(root.get("event").get("id"), eventId));
            if (classId != null)
                predicates.add(cb.equal(root.get("athlete").get("classInfo").get("id"), classId));
            if (status != null && !status.isBlank())
                predicates.add(cb.equal(root.get("status"), status));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return registrationRepository.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public Registration getById(Long id) {
        return registrationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("报名记录不存在"));
    }

    /** 单个报名 */
    public Registration create(Long athleteId, Long eventId) {
        Athlete athlete = athleteRepository.findById(athleteId)
                .orElseThrow(() -> new IllegalArgumentException("运动员不存在"));
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在"));
        if (registrationRepository.existsByAthleteIdAndEventId(athleteId, eventId))
            throw new IllegalArgumentException("该运动员已报名此项目");
        if (event.getGenderLimit() != null && !"mixed".equals(event.getGenderLimit())
                && !event.getGenderLimit().equalsIgnoreCase(athlete.getGender()))
            throw new IllegalArgumentException("性别不符合项目要求");
        int maxPerClass = getConfig("maxAthletesPerEvent", 3);
        long classCount = registrationRepository.countByClassAndEvent(
                athlete.getClassInfo() != null ? athlete.getClassInfo().getId() : 0L, eventId);
        if (classCount >= maxPerClass)
            throw new IllegalArgumentException("该班级报名此项目已达上限(" + maxPerClass + "人)");
        int maxPerAthlete = getConfig("maxEventsPerAthlete", 3);
        long activeRegCount = registrationRepository.findByAthleteId(athleteId).stream()
                .filter(r -> !"withdrawn".equals(r.getStatus())).count();
        if (activeRegCount >= maxPerAthlete)
            throw new IllegalArgumentException("该运动员报名项目已达上限(" + maxPerAthlete + "项)");
        Registration reg = Registration.builder()
                .athlete(athlete).event(event)
                .status("pending")
                .registrationTime(LocalDateTime.now())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
        Registration saved = registrationRepository.save(reg);
        log.info("报名成功: athleteId={}, eventId={}", athleteId, eventId);
        return saved;
    }

    /** 批量报名 */
    public List<Registration> batchRegister(List<Map<String, Long>> items) {
        List<Registration> results = new ArrayList<>();
        for (Map<String, Long> item : items) {
            try {
                results.add(create(item.get("athleteId"), item.get("eventId")));
            } catch (Exception e) {
                log.warn("批量报名单项失败: {}", e.getMessage());
            }
        }
        return results;
    }

    /** 取消报名 */
    public void cancel(Long id) {
        Registration reg = getById(id);
        reg.setStatus("withdrawn");
        reg.setUpdatedAt(LocalDateTime.now());
        registrationRepository.save(reg);
        log.info("取消报名: id={}", id);
    }

    /** 审核 */
    public Registration approve(Long id, String remark) {
        Registration reg = getById(id);
        reg.setStatus("approved");
        reg.setAuditRemark(remark);
        reg.setAuditTime(LocalDateTime.now());
        reg.setUpdatedAt(LocalDateTime.now());
        Registration saved = registrationRepository.save(reg);
        log.info("审核通过: id={}", id);
        return saved;
    }

    /** 拒绝报名 */
    public Registration reject(Long id) {
        Registration reg = getById(id);
        reg.setStatus("rejected");
        reg.setAuditTime(LocalDateTime.now());
        reg.setUpdatedAt(LocalDateTime.now());
        Registration saved = registrationRepository.save(reg);
        log.info("拒绝报名: id={}", id);
        return saved;
    }

    /** 统计 */
    @Transactional(readOnly = true)
    public Map<String, Object> statistics() {
        List<Registration> all = registrationRepository.findAll();
        long total = all.size();
        long approved = all.stream().filter(r -> "approved".equals(r.getStatus())).count();
        long pending = all.stream().filter(r -> "pending".equals(r.getStatus())).count();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", total);
        stats.put("approved", approved);
        stats.put("pending", pending);
        return stats;
    }

    /** 导出 */
    public void export(HttpServletResponse response) throws IOException {
        List<Registration> list = registrationRepository.findAll();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = "报名信息_" + LocalDateTime.now().toString().replace(":", "-") + ".xlsx";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20")
                + ";filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20"));
        try (OutputStream out = response.getOutputStream()) {
            List<List<String>> data = new ArrayList<>();
            data.add(List.of("运动员姓名", "号码簿", "班级", "报名项目", "状态", "报名时间"));
            for (Registration r : list) {
                Athlete a = r.getAthlete();
                data.add(List.of(
                    a != null ? (a.getName() != null ? a.getName() : "") : "",
                    a != null ? (a.getNumber() != null ? a.getNumber() : "") : "",
                    a != null && a.getClassInfo() != null ? a.getClassInfo().getName() : "",
                    r.getEvent() != null ? r.getEvent().getName() : "",
                    r.getStatus() != null ? r.getStatus() : "",
                    r.getRegistrationTime() != null ? r.getRegistrationTime().toString() : ""
                ));
            }
            java.util.List<java.util.List<String>> headCols = data.get(0).stream()
                    .map(java.util.List::of).collect(java.util.stream.Collectors.toList());
            com.alibaba.excel.EasyExcel.write(out)
                .head(headCols)
                .sheet("报名信息").doWrite(data.subList(1, data.size()));
            log.info("导出报名信息: 共{}条", list.size());
        }
    }

    private int getConfig(String key, int def) {
        return systemConfigRepository.findByConfigKey(key)
                .map(c -> { try { return Integer.parseInt(c.getConfigValue()); } catch (Exception e) { return def; } })
                .orElse(def);
    }
}
