package com.sports.service;

import com.sports.entity.Event;
import com.sports.entity.EventSchedule;
import com.sports.entity.Registration;
import com.sports.repository.EventRepository;
import com.sports.repository.EventScheduleRepository;
import com.sports.repository.RegistrationRepository;
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
import java.util.stream.Collectors;

/**
 * 项目赛程编排服务（项目编排）
 * 将比赛项目自动调度到「天 × 时段 × 场地」的时间表中，支持手动调整与导出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ScheduleService {

    private final EventScheduleRepository scheduleRepository;
    private final EventRepository eventRepository;
    private final RegistrationRepository registrationRepository;

    // ==================== 自动编排 ====================

    /**
     * 自动编排赛程
     * config 支持：days(天数) / time_slots(时段) / venues(场地) /
     *              slot_minutes(每时段分钟) / per_event_minutes(默认项目用时)
     */
    public Map<String, Object> autoSchedule(Map<String, Object> config) {
        int days = intVal(config != null ? config.get("days") : null, 2);
        List<String> slots = strList(config != null ? config.get("time_slots") : null,
                List.of("上午", "下午"));
        List<String> venues = strList(config != null ? config.get("venues") : null,
                List.of("田径场"));
        int slotMinutes = intVal(config != null ? config.get("slot_minutes") : null, 180);
        int perEventMinutes = intVal(config != null ? config.get("per_event_minutes") : null, 30);

        List<Event> events = eventRepository.findByIsEnabledTrueOrderBySortOrderAsc();

        scheduleRepository.deleteAllSchedules();

        // 每个 (day, slot, venue) 的已占用分钟数
        Map<String, Integer> load = new LinkedHashMap<>();
        List<EventSchedule> saved = new ArrayList<>();

        int order = 1;
        for (Event e : events) {
            int duration = estimateDuration(e, perEventMinutes);

            // 选择负载最小的 (day, slot, venue)
            String bestKey = null;
            int bestLoad = Integer.MAX_VALUE;
            for (int d = 1; d <= days; d++) {
                for (String slot : slots) {
                    for (String venue : venues) {
                        String key = key(d, slot, venue);
                        int l = load.getOrDefault(key, 0);
                        if (l < bestLoad) {
                            bestLoad = l;
                            bestKey = key;
                        }
                    }
                }
            }
            if (bestKey == null) bestKey = key(1, slots.get(0), venues.get(0));

            int[] parts = parseKey(bestKey);
            int d = parts[0];
            String slot = slots.get(parts[1]);
            String venue = venues.get(parts[2]);

            String start = addMinutes(slotStart(slot), bestLoad);
            String end = addMinutes(slotStart(slot), bestLoad + duration);

            EventSchedule s = EventSchedule.builder()
                    .event(e)
                    .day(d)
                    .timeSlot(slot)
                    .startTime(start)
                    .endTime(end)
                    .venue(venue)
                    .sortOrder(order++)
                    .durationMinutes(duration)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            saved.add(scheduleRepository.save(s));
            load.put(bestKey, bestLoad + duration);
        }

        log.info("项目赛程自动编排完成: 共{}个项目, {}天", saved.size(), days);
        return buildResult();
    }

    // ==================== 查询 / 手动调整 / 清空 ====================

    @Transactional(readOnly = true)
    public Map<String, Object> list() {
        return buildResult();
    }

    /** 手动保存调整后的赛程（替换全部） */
    public Map<String, Object> save(List<Map<String, Object>> items) {
        scheduleRepository.deleteAllSchedules();
        int order = 1;
        for (Map<String, Object> item : items) {
            Long eventId = item.get("eventId") != null
                    ? ((Number) item.get("eventId")).longValue() : null;
            if (eventId == null) continue;
            Event event = eventRepository.findById(eventId).orElse(null);
            if (event == null) continue;

            EventSchedule s = EventSchedule.builder()
                    .event(event)
                    .day(intVal(item.get("day"), 1))
                    .timeSlot(str(item.get("timeSlot"), "上午"))
                    .startTime(str(item.get("startTime"), null))
                    .endTime(str(item.get("endTime"), null))
                    .venue(str(item.get("venue"), "田径场"))
                    .sortOrder(order++)
                    .durationMinutes(intVal(item.get("durationMinutes"), 30))
                    .remark(str(item.get("remark"), null))
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            scheduleRepository.save(s);
        }
        log.info("手动保存赛程: 共{}条", order - 1);
        return buildResult();
    }

    public void clear() {
        scheduleRepository.deleteAllSchedules();
        log.info("清空项目赛程");
    }

    // ==================== 导出 ====================

    public void export(HttpServletResponse response) {
        List<EventSchedule> schedules = scheduleRepository.findByOrderByDayAscSortOrderAscStartTimeAsc();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = "项目赛程表_" + LocalDateTime.now().toString().replace(":", "-") + ".xlsx";
        String enc = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment;filename=" + enc + ";filename*=UTF-8''" + enc);

        try (OutputStream out = response.getOutputStream()) {
            List<List<String>> data = new ArrayList<>();
            data.add(List.of("天", "时段", "开始时间", "结束时间", "场地", "项目名称", "项目编码", "类别", "预计用时(分)"));
            for (EventSchedule s : schedules) {
                Event e = s.getEvent();
                data.add(List.of(
                        "第" + s.getDay() + "天",
                        n(s.getTimeSlot()), n(s.getStartTime()), n(s.getEndTime()), n(s.getVenue()),
                        e != null ? n(e.getName()) : "",
                        e != null ? n(e.getCode()) : "",
                        e != null ? n(e.getCategory()) : "",
                        s.getDurationMinutes() != null ? String.valueOf(s.getDurationMinutes()) : ""));
            }
            List<List<String>> head = data.get(0).stream().map(List::of).collect(Collectors.toList());
            com.alibaba.excel.EasyExcel.write(out).head(head).sheet("项目赛程")
                    .doWrite(data.subList(1, data.size()));
        } catch (IOException e) {
            throw new RuntimeException("导出赛程失败: " + e.getMessage());
        }
        log.info("导出项目赛程: 共{}条", schedules.size());
    }

    // ==================== 辅助 ====================

    private Map<String, Object> buildResult() {
        List<EventSchedule> schedules = scheduleRepository.findByOrderByDayAscSortOrderAscStartTimeAsc();

        List<Map<String, Object>> items = schedules.stream().map(s -> {
            Event e = s.getEvent();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("eventId", e != null ? e.getId() : null);
            m.put("eventName", e != null ? e.getName() : "");
            m.put("eventCode", e != null ? e.getCode() : "");
            m.put("category", e != null ? e.getCategory() : "");
            m.put("genderLimit", e != null ? e.getGenderLimit() : "");
            m.put("day", s.getDay());
            m.put("timeSlot", s.getTimeSlot());
            m.put("startTime", s.getStartTime());
            m.put("endTime", s.getEndTime());
            m.put("venue", s.getVenue());
            m.put("sortOrder", s.getSortOrder());
            m.put("durationMinutes", s.getDurationMinutes());
            m.put("remark", s.getRemark());
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", items.size());
        result.put("days", items.stream().mapToInt(i -> ((Number) i.get("day")).intValue()).max().orElse(0));
        return result;
    }

    /** 估算项目用时（分钟） */
    private int estimateDuration(Event e, int perEventMinutes) {
        try {
            List<Registration> regs = registrationRepository.findApprovedByEventId(e.getId());
            int count = regs.size();
            int lanes = e.getDefaultLanes() != null ? Math.max(1, e.getDefaultLanes()) : 8;
            if ("田赛".equals(e.getCategory())) {
                return Math.max(20, Math.min(90, count * 4));
            }
            if (Boolean.TRUE.equals(e.getNeedHeats()) && count > 0) {
                int heats = (int) Math.ceil((double) count / lanes);
                return Math.max(15, Math.min(120, heats * 6));
            }
        } catch (Exception ignored) { /* fallthrough */ }
        return perEventMinutes;
    }

    private String key(int day, String slot, String venue) {
        return day + "|" + slot + "|" + venue;
    }

    private int[] parseKey(String key) {
        String[] p = key.split("\\|");
        return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])};
    }

    private String slotStart(String slot) {
        return switch (slot) {
            case "下午" -> "14:00";
            case "晚上" -> "19:00";
            default -> "08:30";
        };
    }

    private String addMinutes(String hhmm, int minutes) {
        String[] p = hhmm.split(":");
        int h = Integer.parseInt(p[0]);
        int m = Integer.parseInt(p[1]);
        int total = h * 60 + m + minutes;
        return String.format("%02d:%02d", total / 60, total % 60);
    }

    private static int intVal(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try { return Integer.parseInt(String.valueOf(v).trim()); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private static String str(Object v, String def) {
        return v != null && !String.valueOf(v).isBlank() ? String.valueOf(v) : def;
    }

    private static String n(String s) { return s != null ? s : ""; }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object v, List<String> def) {
        if (v instanceof List) {
            List<String> out = new ArrayList<>();
            for (Object o : (List<Object>) v) out.add(String.valueOf(o));
            return out.isEmpty() ? def : out;
        }
        if (v instanceof String && !((String) v).isBlank()) {
            return Arrays.stream(((String) v).split("[,，]"))
                    .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        }
        return def;
    }
}
