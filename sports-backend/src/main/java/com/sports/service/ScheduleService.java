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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 项目赛程编排服务（项目编排）
 *
 * <p>严格依据 {@code meet_schedule} 配置生成赛程，全部参数可配置、无硬编码：</p>
 * <ul>
 *   <li><b>日期</b>：startDate + days 推出 xD-yD，每天的时段（AM/PM）起止可各不相同；</li>
 *   <li><b>年级顺序</b>：取自 gradeOrder（缺省按 grades 的 sortOrder 升序），
 *       默认高一→高二→高三仅是可修改的默认值，不写死在代码里；</li>
 *   <li><b>串行 / 并行</b>：径赛默认串行（独占跑道依次进行），田赛默认并行（多场地同时开赛），
 *       可按项目或全局切换；</li>
 *   <li><b>时长</b>：径赛按「组数 × 单组用时」、田赛按「人数 × 每人次用时」估算，
 *       并受 maxDurationMinutes 封顶；项目之间插入 intervalMinutes 间隔。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ScheduleService {

    private final EventScheduleRepository scheduleRepository;
    private final EventRepository eventRepository;
    private final RegistrationRepository registrationRepository;
    private final SystemService systemService;

    /** 单个项目最短占用时间（分钟），避免 0 人报名时挤成一团 */
    private static final int MIN_DURATION = 10;

    // ==================== 自动编排 ====================

    /**
     * 自动编排赛程。
     *
     * @param override 临时覆盖参数（不落库），可含 startDate / days / gradeOrder /
     *                 trackMode / fieldMode / defaultDurationMinutes / defaultIntervalMinutes / venues
     */
    public Map<String, Object> autoSchedule(Map<String, Object> override) {
        Map<String, Object> cfg = mergeConfig(override);

        List<String> gradeOrder = strList(cfg.get("gradeOrder"));
        String trackMode = str(cfg.get("trackMode"), "serial");
        String fieldMode = str(cfg.get("fieldMode"), "parallel");
        int defaultDuration = intVal(cfg.get("defaultDurationMinutes"), 30);
        int defaultInterval = intVal(cfg.get("defaultIntervalMinutes"), 5);
        int heatMinutes = intVal(cfg.get("heatMinutes"), 6);
        int fieldPerAthlete = intVal(cfg.get("fieldPerAthleteMinutes"), 3);
        List<String> venues = strList(cfg.get("venues"));

        // 时间窗：按 (天, 时段) 顺序铺开
        List<Window> windows = buildWindows(cfg);
        if (windows.isEmpty()) {
            throw new RuntimeException("运动会日程未配置可用时段，请先在「系统设置 → 运动会日程」中配置日期与时段");
        }

        List<Unit> units = buildUnits(gradeOrder, trackMode, fieldMode);
        estimateDurations(units, defaultDuration, heatMinutes, fieldPerAthlete);

        // 场地：串行项目用主场地，并行项目在其余场地间并行铺开
        String mainVenue = venues.isEmpty() ? "田径场" : venues.get(0);
        List<String> parallelVenues = venues.size() > 1
                ? new ArrayList<>(venues.subList(1, venues.size()))
                : new ArrayList<>(List.of(mainVenue));

        Map<String, Cursor> cursors = new LinkedHashMap<>();
        cursors.put("__serial__", new Cursor());
        for (String v : parallelVenues) cursors.put(v, new Cursor());

        scheduleRepository.deleteAllSchedules();

        List<EventSchedule> saved = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int order = 1;

        for (Unit u : units) {
            boolean serial = "serial".equalsIgnoreCase(u.mode);
            String venue;
            Cursor cursor;

            if (serial) {
                venue = mainVenue;
                cursor = cursors.get("__serial__");
            } else {
                // 并行：挑当前推进最靠前（最空闲）的场地，让田赛尽量同时开赛
                venue = parallelVenues.get(0);
                Cursor best = cursors.get(venue);
                for (String v : parallelVenues) {
                    Cursor c = cursors.computeIfAbsent(v, k -> new Cursor());
                    if (best == null || c.aheadOf(best)) {
                        venue = v;
                        best = c;
                    }
                }
                cursor = best;
            }

            int interval = u.event.getIntervalMinutes() != null ? u.event.getIntervalMinutes() : defaultInterval;
            Slot placed = cursor.place(windows, u.duration, interval);
            if (placed == null) {
                warnings.add(String.format("项目「%s」（%s）因时段已排满未能安排", u.event.getName(),
                        u.grade == null ? "不分年级" : u.grade));
                continue;
            }

            EventSchedule s = EventSchedule.builder()
                    .event(u.event)
                    .day(placed.window.day)
                    .scheduleDate(placed.window.date)
                    .grade(u.grade)
                    .timeSlot(placed.window.slotName)
                    .startTime(fmt(placed.startMinute))
                    .endTime(fmt(placed.startMinute + u.duration))
                    .venue(venue)
                    .sortOrder(order++)
                    .durationMinutes(u.duration)
                    .remark(u.duration >= u.rawDuration ? null
                            : String.format("预计%d分钟，已按上限%d分钟压缩", u.rawDuration, u.duration))
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            saved.add(scheduleRepository.save(s));
        }

        log.info("赛程自动编排完成: {}个单元, {}天, 径赛{}, 田赛{}",
                saved.size(), windows.stream().mapToInt(w -> w.day).max().orElse(0), trackMode, fieldMode);

        Map<String, Object> result = buildResult();
        result.put("warnings", warnings);
        result.put("configUsed", cfg);
        return result;
    }

    // ==================== 赛程单元构建 ====================

    /**
     * 按「年级顺序 → 项目顺序」展开赛程单元。
     * 年级顺序来自配置；event.gradeGroup 非空时该项目只属于对应年级。
     */
    private List<Unit> buildUnits(List<String> gradeOrder, String trackMode, String fieldMode) {
        List<Event> events = eventRepository.findByIsEnabledTrueOrderBySortOrderAsc();

        List<Unit> units = new ArrayList<>();
        if (gradeOrder.isEmpty()) {
            for (Event e : events) units.add(new Unit(e, null, resolveMode(e, trackMode, fieldMode)));
            return units;
        }

        for (String grade : gradeOrder) {
            for (Event e : events) {
                String gg = e.getGradeGroup();
                if (gg != null && !gg.isBlank() && !gg.equals(grade)) continue;
                units.add(new Unit(e, grade, resolveMode(e, trackMode, fieldMode)));
            }
        }
        return units;
    }

    /** 项目显式 scheduleMode 优先，否则按 径赛/田赛 取全局模式 */
    private String resolveMode(Event e, String trackMode, String fieldMode) {
        String m = e.getScheduleMode();
        if (m != null && !m.isBlank()) return m;
        return Boolean.FALSE.equals(e.getTrack()) ? fieldMode : trackMode;
    }

    /** 估算每个单元用时：径赛看组数，田赛看人次，并受最大时间封顶 */
    private void estimateDurations(List<Unit> units, int defaultDuration,
                                   int heatMinutes, int fieldPerAthlete) {
        for (Unit u : units) {
            Event e = u.event;
            boolean isTrack = !Boolean.FALSE.equals(e.getTrack());
            int count = countParticipants(e.getId(), u.grade);

            if (isTrack) {
                int lanes = e.getLaneCount() != null && e.getLaneCount() > 0 ? e.getLaneCount() : 8;
                int entrants = count;
                if (Boolean.TRUE.equals(e.getTeam()) && e.getTeamMembers() != null && e.getTeamMembers() > 0) {
                    entrants = (int) Math.ceil((double) count / e.getTeamMembers());
                }
                int heats = Math.max(1, (int) Math.ceil((double) entrants / lanes));
                u.rawDuration = Math.max(MIN_DURATION, heats * heatMinutes);
                u.participants = count;
                u.heats = heats;
            } else {
                u.rawDuration = Math.max(MIN_DURATION, count * fieldPerAthlete);
                u.participants = count;
                u.heats = 0;
            }

            int cap = e.getMaxDurationMinutes() != null ? e.getMaxDurationMinutes() : defaultDuration;
            u.duration = cap > 0 ? Math.min(u.rawDuration, cap) : u.rawDuration;
        }
    }

    private int countParticipants(Long eventId, String grade) {
        try {
            List<Registration> regs = registrationRepository.findApprovedByEventId(eventId);
            if (grade == null || grade.isBlank()) return regs.size();
            return (int) regs.stream()
                    .filter(r -> r.getAthlete() != null
                            && r.getAthlete().getGrade() != null
                            && grade.equals(r.getAthlete().getGrade()))
                    .count();
        } catch (Exception ex) {
            return 0;
        }
    }

    // ==================== 时间窗 ====================

    /** 把 dayConfigs 铺开成有序的时间窗列表 */
    private List<Window> buildWindows(Map<String, Object> cfg) {
        String startDate = str(cfg.get("startDate"), null);
        List<Map<String, Object>> dayConfigs = castList(cfg.get("dayConfigs"));
        List<Window> windows = new ArrayList<>();

        for (Map<String, Object> dc : dayConfigs) {
            int day = intVal(dc.get("day"), windows.size() + 1);
            String date = str(dc.get("date"), null);
            if ((date == null || date.isBlank()) && startDate != null && !startDate.isBlank()) {
                date = shiftDate(startDate, day - 1);
            }
            List<Map<String, Object>> slots = castList(dc.get("slots"));
            if (slots.isEmpty()) continue;

            for (Map<String, Object> sl : slots) {
                String start = str(sl.get("start"), "08:00");
                String end = str(sl.get("end"), "11:30");
                int s = parseHhMm(start);
                int e = parseHhMm(end);
                if (e <= s) continue;
                windows.add(new Window(day, date, str(sl.get("name"), str(sl.get("key"), "上午")), s, e - s));
            }
        }
        windows.sort(Comparator.comparingInt(w -> w.day));
        return windows;
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
                    .scheduleDate(str(item.get("scheduleDate"), null))
                    .grade(str(item.get("grade"), null))
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
            data.add(List.of("第几天", "日期", "时段", "开始", "结束", "场地", "年级", "项目名称", "项目编码",
                    "类别", "是否田径", "道次", "调度", "预计用时(分)"));
            for (EventSchedule s : schedules) {
                Event e = s.getEvent();
                boolean isTrack = e == null || !Boolean.FALSE.equals(e.getTrack());
                data.add(List.of(
                        "第" + s.getDay() + "天",
                        n(s.getScheduleDate()), n(s.getTimeSlot()),
                        n(s.getStartTime()), n(s.getEndTime()), n(s.getVenue()),
                        n(s.getGrade()),
                        e != null ? n(e.getName()) : "",
                        e != null ? n(e.getCode()) : "",
                        e != null ? n(e.getCategory()) : "",
                        isTrack ? "是" : "否",
                        e != null && e.getLaneCount() != null ? String.valueOf(e.getLaneCount()) : "0",
                        e != null ? n(e.getScheduleMode()) : "",
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

    // ==================== 结果组装 ====================

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
            m.put("isTrack", e == null || !Boolean.FALSE.equals(e.getTrack()));
            m.put("laneCount", e != null ? e.getLaneCount() : 0);
            m.put("isTeam", e != null && Boolean.TRUE.equals(e.getTeam()));
            m.put("teamSize", e != null ? e.getTeamMembers() : 0);
            m.put("day", s.getDay());
            m.put("scheduleDate", s.getScheduleDate());
            m.put("grade", s.getGrade());
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
        result.put("days", items.stream().mapToInt(i -> intVal(i.get("day"), 0)).max().orElse(0));

        // 按天分组，方便前端渲染甘特/时间表
        Map<Integer, List<Map<String, Object>>> byDay = items.stream()
                .collect(Collectors.groupingBy(i -> intVal(i.get("day"), 0), TreeMap::new, Collectors.toList()));
        result.put("byDay", byDay);
        return result;
    }

    // ==================== 配置合并 ====================

    /** 以已保存的 meet_schedule 为底，用请求参数覆盖（不落库） */
    private Map<String, Object> mergeConfig(Map<String, Object> override) {
        Map<String, Object> cfg = new LinkedHashMap<>(systemService.getMeetSchedule());
        if (override != null) {
            for (Map.Entry<String, Object> e : override.entrySet()) {
                if (e.getValue() != null) cfg.put(e.getKey(), e.getValue());
            }
        }
        if (cfg.get("gradeOrder") == null || strList(cfg.get("gradeOrder")).isEmpty()) {
            cfg.put("gradeOrder", systemService.getGradeOrder());
        }
        // 日期跟随 startDate 重算，避免手工改动后日期错位
        String startDate = str(cfg.get("startDate"), null);
        if (startDate != null && !startDate.isBlank()) {
            List<Map<String, Object>> dayConfigs = castList(cfg.get("dayConfigs"));
            for (Map<String, Object> dc : dayConfigs) {
                dc.put("date", shiftDate(startDate, intVal(dc.get("day"), 1) - 1));
            }
            cfg.put("dayConfigs", dayConfigs);
        }
        return cfg;
    }

    // ==================== 内部类 ====================

    /** 一个赛程单元 = 项目 × 年级 */
    private static class Unit {
        Event event;
        String grade;
        String mode;
        int duration;
        int rawDuration;
        int participants;
        int heats;

        Unit(Event event, String grade, String mode) {
            this.event = event;
            this.grade = grade;
            this.mode = mode;
        }
    }

    /** 一个时段窗口（第几天 + 该天的某个时段） */
    private static class Window {
        int day;
        String date;
        String slotName;
        int startMinute;   // 该时段起点（分钟，自 00:00 起算）
        int capacity;      // 该时段可用分钟数

        Window(int day, String date, String slotName, int startMinute, int capacity) {
            this.day = day;
            this.date = date;
            this.slotName = slotName;
            this.startMinute = startMinute;
            this.capacity = capacity;
        }
    }

    /** 放置结果 */
    private static class Slot {
        Window window;
        int startMinute;

        Slot(Window window, int startMinute) {
            this.window = window;
            this.startMinute = startMinute;
        }
    }

    /** 场地时间游标：在窗口序列上顺序推进 */
    private static class Cursor {
        int windowIdx = 0;
        int used = 0;   // 当前窗口已用分钟数（含间隔）

        /** 把一段时长放进当前游标位置；放不下就顺延到下一时段 */
        Slot place(List<Window> windows, int duration, int interval) {
            while (windowIdx < windows.size()) {
                Window w = windows.get(windowIdx);
                // 段前间隔：除窗口起点外，项目之间留出间隔
                int gap = used == 0 ? 0 : interval;
                if (used + gap + duration <= w.capacity) {
                    int start = w.startMinute + used + gap;
                    used += gap + duration;
                    return new Slot(w, start);
                }
                windowIdx++;
                used = 0;
            }
            return null;
        }

        /** 比较推进程度：窗口更靠前、或同窗口已用时间更短的更"空闲" */
        boolean aheadOf(Cursor other) {
            if (windowIdx != other.windowIdx) return windowIdx < other.windowIdx;
            return used < other.used;
        }
    }

    // ==================== 工具方法 ====================

    private static int parseHhMm(String hhmm) {
        if (hhmm == null || !hhmm.contains(":")) return 0;
        try {
            String[] p = hhmm.trim().split(":");
            return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String fmt(int minuteOfDay) {
        int m = ((minuteOfDay % 1440) + 1440) % 1440;
        return String.format("%02d:%02d", m / 60, m % 60);
    }

    private static String shiftDate(String startDate, int offset) {
        try {
            return LocalDate.parse(startDate).plusDays(offset).toString();
        } catch (Exception e) {
            return startDate;
        }
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
    private static List<Map<String, Object>> castList(Object v) {
        if (!(v instanceof List<?> list)) return new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map) out.add(new LinkedHashMap<>((Map<String, Object>) o));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o == null) continue;
                String s = String.valueOf(o).trim();
                if (!s.isEmpty()) out.add(s);
            }
            return out;
        }
        if (v instanceof String s && !s.isBlank()) {
            for (String part : s.split("[,，]")) {
                if (!part.trim().isEmpty()) out.add(part.trim());
            }
        }
        return out;
    }
}
