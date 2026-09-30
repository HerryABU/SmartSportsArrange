package com.sports.service.arrange;

import com.alibaba.excel.EasyExcel;
import com.sports.entity.arrange.Arrangement;
import com.sports.entity.athlete.Athlete;
import com.sports.entity.clazz.ClassInfo;
import com.sports.entity.event.Event;
import com.sports.entity.registration.Registration;
import com.sports.repository.arrange.ArrangementRepository;
import com.sports.repository.athlete.AthleteRepository;
import com.sports.repository.event.EventRepository;
import com.sports.repository.registration.RegistrationRepository;
import com.sports.service.audit.AuditService;
import com.sports.service.notification.NotificationService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 兼项冲突消解（取消某人某项目）+ 通知班主任。
 *
 * <p>与 {@link ConflictService#detectConflicts()}（检测）正交：检测负责「找出冲突」，
 * 本服务负责「消解冲突」——把冲突运动员的某个项目取消（退报名 + 同步移出编排），
 * 并按「分流」通知班主任：</p>
 * <ul>
 *   <li><b>自行批量处理</b>：{@link #statistics()} 先统计「哪些项目与哪些项目冲突」，
 *       再 {@link #cancel(List, boolean)} 统一取消；</li>
 *   <li><b>通知班主任</b>：内通知（站内信，定向到运动员班主任账号）+
 *       外通知（{@link #exportClassConflictSheet} 生成按班级汇总的统计表转交）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ConflictResolutionService {

    private final ConflictService conflictService;
    private final RegistrationRepository registrationRepository;
    private final ArrangementRepository arrangementRepository;
    private final AthleteRepository athleteRepository;
    private final EventRepository eventRepository;
    private final NotificationService notificationService;
    private final AuditService auditService;

    /**
     * 统计「哪些项目与哪些项目冲突」（事件对汇总，供「统一取消某些项目」前决策）。
     *
     * @return { pairs:[{eventA, eventB, count, athletes:[{athleteId, name, number}]}], total }
     */
    @Transactional(readOnly = true)
    public Map<String, Object> statistics() {
        List<Map<String, Object>> conflicts = conflictService.detectConflicts();
        Map<String, Map<String, Object>> pairMap = new LinkedHashMap<>();
        for (Map<String, Object> c : conflicts) {
            Map<String, Object> ea = (Map<String, Object>) c.get("eventA");
            Map<String, Object> eb = (Map<String, Object>) c.get("eventB");
            if (ea == null || eb == null) continue;
            Long idA = num(ea.get("id"));
            Long idB = num(eb.get("id"));
            long a = idA == null ? -1 : idA;
            long b = idB == null ? -1 : idB;
            String key = Math.min(a, b) + "|" + Math.max(a, b);
            Map<String, Object> pair = pairMap.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("eventA", a <= b ? ea : eb);
                m.put("eventB", a <= b ? eb : ea);
                m.put("count", 0L);
                m.put("athletes", new ArrayList<Map<String, Object>>());
                return m;
            });
            pair.put("count", ((Number) pair.get("count")).longValue() + 1);
            Map<String, Object> ath = new LinkedHashMap<>();
            ath.put("athleteId", c.get("athleteId"));
            ath.put("name", c.get("athleteName"));
            ath.put("number", c.get("athleteNumber"));
            ((List<Map<String, Object>>) pair.get("athletes")).add(ath);
        }
        List<Map<String, Object>> pairs = new ArrayList<>(pairMap.values());
        pairs.sort((x, y) -> Long.compare(
                ((Number) y.get("count")).longValue(), ((Number) x.get("count")).longValue()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", pairs.size());
        out.put("pairs", pairs);
        return out;
    }

    /**
     * 取消若干 (运动员 × 项目)：退报名 + 同步移出编排 + 通知该运动员班主任（内通知）。
     *
     * @param items         [{athleteId, eventId}]
     * @param notifyTeacher 是否通知班主任（内通知）
     * @return { cancelled, notifiedTeachers, failed }
     */
    public Map<String, Object> cancel(List<Map<String, Object>> items, boolean notifyTeacher) {
        int cancelled = 0;
        int notifiedTeachers = 0;
        List<Map<String, Object>> failed = new ArrayList<>();
        Set<Long> notified = new HashSet<>();

        for (Map<String, Object> item : items) {
            Long athleteId = num(item.get("athleteId"));
            Long eventId = num(item.get("eventId"));
            if (athleteId == null || eventId == null) {
                failed.add(item);
                continue;
            }
            try {
                Athlete athlete = athleteRepository.findById(athleteId).orElse(null);
                String athleteName = athlete != null ? athlete.getName() : ("#" + athleteId);
                String eventName = eventRepository.findById(eventId).map(Event::getName).orElse("#" + eventId);

                // 退报名
                registrationRepository.findByAthleteIdAndEventId(athleteId, eventId).ifPresent(r -> {
                    r.setStatus("withdrawn");
                    r.setUpdatedAt(LocalDateTime.now());
                    r.setRemark((r.getRemark() == null ? "" : r.getRemark() + " ")
                            + "兼项冲突消解取消");
                    registrationRepository.save(r);
                });
                // 同步移出编排（该运动员该项目全部轮次）
                arrangementRepository.deleteByAthleteIdAndEventId(athleteId, eventId);

                // 内通知：定向到运动员班主任账号
                if (notifyTeacher && athlete != null && athlete.getClassInfo() != null
                        && athlete.getClassInfo().getTeacherUser() != null) {
                    Long teacherId = athlete.getClassInfo().getTeacherUser().getId();
                    if (teacherId != null && notified.add(teacherId)) {
                        notificationService.notifyUser(teacherId, "兼项冲突消解：项目已取消",
                                String.format("运动员「%s」的「%s」项目因兼项冲突已取消报名，请知悉。",
                                        athleteName, eventName),
                                "CONFLICT_CANCEL");
                        notifiedTeachers++;
                    }
                }
                cancelled++;
            } catch (Exception e) {
                failed.add(item);
                log.warn("取消兼项冲突失败: athlete={}, event={}, {}", athleteId, eventId, e.getMessage());
            }
        }
        auditService.record("CONFLICT_CANCEL", "REGISTRATION", null,
                "兼项冲突取消 " + cancelled + " 条（退报名+移出编排），失败 " + failed.size() + " 条");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cancelled", cancelled);
        out.put("notifiedTeachers", notifiedTeachers);
        out.put("failed", failed);
        return out;
    }

    /**
     * 外通知：按班级汇总的兼项冲突统计表（Excel），转交班主任。
     * 列：班级 / 班主任 / 运动员 / 号码布 / 冲突项目A / 冲突项目B / 严重度 / 调整建议。
     */
    @Transactional(readOnly = true)
    public void exportClassConflictSheet(HttpServletResponse response) {
        List<Map<String, Object>> conflicts = conflictService.detectConflicts();
        // 运动员 -> 班级 / 班主任（用于按班分组）
        Map<Long, String[]> classOf = new HashMap<>();
        for (Athlete a : athleteRepository.findAll()) {
            ClassInfo c = a.getClassInfo();
            if (c == null) continue;
            classOf.put(a.getId(), new String[]{c.getName(), c.getTeacherName()});
        }

        List<List<String>> data = new ArrayList<>();
        data.add(List.of("班级", "班主任", "运动员", "号码布", "冲突项目A", "冲突项目B", "严重度", "调整建议"));
        List<String[]> rows = new ArrayList<>();
        for (Map<String, Object> c : conflicts) {
            Long aid = num(c.get("athleteId"));
            String[] cc = aid == null ? null : classOf.get(aid);
            Map<String, Object> ea = (Map<String, Object>) c.get("eventA");
            Map<String, Object> eb = (Map<String, Object>) c.get("eventB");
            rows.add(new String[]{
                    cc == null ? "" : cc[0],
                    cc == null ? "" : cc[1],
                    n(c.get("athleteName")),
                    n(c.get("athleteNumber")),
                    ea == null ? "" : n(ea.get("name")),
                    eb == null ? "" : n(eb.get("name")),
                    n(c.get("severity")),
                    n(c.get("suggestion"))
            });
        }
        rows.sort(Comparator.comparing((String[] r) -> r[0]));
        for (String[] r : rows) data.add(Arrays.asList(r));

        try (OutputStream out = response.getOutputStream()) {
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setCharacterEncoding("utf-8");
            String fileName = "兼项冲突统计表_" + com.sports.common.util.ExportNaming.stamp() + ".xlsx";
            String enc = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
            response.setHeader("Content-Disposition",
                    "attachment;filename=" + enc + ";filename*=UTF-8''" + enc);
            EasyExcel.write(out).head(data.get(0).stream().map(List::of)
                            .collect(java.util.stream.Collectors.toList()))
                    .sheet("兼项冲突(按班级)").doWrite(data.subList(1, data.size()));
        } catch (IOException e) {
            throw new RuntimeException("导出兼项冲突统计表失败: " + e.getMessage());
        }
        log.info("导出兼项冲突统计表: 共 {} 处冲突", conflicts.size());
    }

    private static Long num(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return null; }
    }

    private static String n(Object s) {
        return s == null ? "" : String.valueOf(s);
    }
}
