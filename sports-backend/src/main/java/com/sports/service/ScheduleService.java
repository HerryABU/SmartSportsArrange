package com.sports.service;

import com.sports.common.Grades;
import com.sports.entity.Event;
import com.sports.entity.EventSchedule;
import com.sports.entity.Registration;
import com.sports.repository.EventRepository;
import com.sports.repository.EventScheduleRepository;
import com.sports.repository.RegistrationRepository;
import com.sports.repository.ArrangementRepository;
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
 *   <li><b>并发位数（取代串行/并行开关）</b>：trackSlots / fieldSlots —— 1 = 串行，
 *       n = 同一时刻可同时进行 n 个项目。槽位与场地一一对应（场地不足时复用并告警）；</li>
 *   <li><b>自定义项目顺序</b>：eventOrder（eventId 有序列表，田赛+径赛混排）优先，
 *       未列入的项目按 sortOrder 追加；</li>
 *   <li><b>田赛分组</b>：fieldGroups 中同一组的田赛项目安排在同一时段并行进行；</li>
 *   <li><b>项目内并发</b>：event.concurrency（径赛默认取道次数；田赛默认 1），
 *       时长 = ceil(人数 / 并发) × 单轮用时，并受 maxDurationMinutes 封顶；
 *       项目之间插入 intervalMinutes 间隔。</li>
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
    private final ArrangementRepository arrangementRepository;
    private final ArrangementService arrangementService;
    private final SystemService systemService;

    /** 单个项目最短占用时间（分钟），避免 0 人报名时挤成一团 */
    private static final int MIN_DURATION = 10;

    // ==================== 自动编排 ====================

    /**
     * 自动编排赛程。
     *
     * @param override 临时覆盖参数（不落库），可含 startDate / days / gradeOrder /
     *                 trackSlots / fieldSlots / eventOrder / fieldGroups /
     *                 defaultDurationMinutes / defaultIntervalMinutes / venues
     */
    public Map<String, Object> autoSchedule(Map<String, Object> override) {
        Map<String, Object> cfg = mergeConfig(override);

        List<String> gradeOrder = strList(cfg.get("gradeOrder"));
        // 并发位数：1 = 串行（同一时刻只进行 1 个项目）；n = 同时进行 n 个项目
        int trackSlots = Math.max(1, intVal(cfg.get("trackSlots"), 1));
        int fieldSlots = Math.max(1, intVal(cfg.get("fieldSlots"), 2));
        int defaultDuration = intVal(cfg.get("defaultDurationMinutes"), 30);
        int defaultInterval = intVal(cfg.get("defaultIntervalMinutes"), 5);
        int heatMinutes = intVal(cfg.get("heatMinutes"), 6);
        int fieldPerAthlete = intVal(cfg.get("fieldPerAthleteMinutes"), 3);
        List<String> venues = venueNames(cfg.get("venues"));
        List<Map<String, Object>> venueList = venueListOf(cfg.get("venues"));
        Map<String, String> codeToName = new LinkedHashMap<>();
        for (Map<String, Object> v : venueList) {
            Object code = v.get("code");
            Object name = v.get("name");
            if (code != null && !String.valueOf(code).isBlank()) {
                codeToName.put(String.valueOf(code).trim(),
                        name != null ? String.valueOf(name).trim() : String.valueOf(code).trim());
            }
        }
        List<Long> eventOrder = longList(cfg.get("eventOrder"));
        Map<Long, String> event2Group = parseFieldGroups(cfg.get("fieldGroups"));

        // 时间窗：按 (天, 时段) 顺序铺开
        List<Window> windows = buildWindows(cfg);
        if (windows.isEmpty()) {
            throw new RuntimeException("运动会日程未配置可用时段，请先在「系统设置 → 运动会日程」中配置日期与时段");
        }

        List<Unit> units = buildUnits(gradeOrder, eventOrder);
        estimateDurations(units, defaultDuration, heatMinutes, fieldPerAthlete);

        // 项目自带的「并行捆绑组」（表格2 的字母列）优先于配置页分组：
        // 同字母 → 同组 → 安排在同一时段并行；为空则不受限制，由算法自动安排
        for (Unit u : units) {
            String bg = u.event.getBundleGroup();
            if (bg != null && !bg.isBlank()) {
                event2Group.put(u.event.getId(), "捆绑组" + bg.trim().toUpperCase());
            }
        }

        // 资源池：按类别分配并发位。径赛用主场地；田赛优先用其余场地（不足则复用并告警）。
        // 此外，项目可显式指定「场地编码」(defaultVenueCode) 以绑定独立并发池，从而与其他场地并行
        // （例如游泳作为特殊径赛，指定独立场馆编码后即可在主径赛之外「同排」）。
        String mainVenue = venues.isEmpty() ? "田径场" : venues.get(0);
        String mainVenueCode = venueList.isEmpty() ? null : codeOf(venueList.get(0));
        List<String> fieldVenues = venues.size() > 1
                ? new ArrayList<>(venues.subList(1, venues.size()))
                : new ArrayList<>(List.of(mainVenue));
        Set<String> fieldVenueCodes = new HashSet<>();
        if (venueList.size() > 1) {
            for (int i = 1; i < venueList.size(); i++) {
                String c = codeOf(venueList.get(i));
                if (c != null) fieldVenueCodes.add(c);
            }
        }
        Pool trackPool = new Pool("径赛", trackSlots, new ArrayList<>(List.of(mainVenue)));
        Pool fieldPool = new Pool("田赛", fieldSlots, fieldVenues);
        // 项目级指定场地时按需创建的独立并发池（key=场地编码），与主/田赛池并行
        Map<String, Pool> dedicatedPools = new LinkedHashMap<>();

        List<EventSchedule> saved = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (trackPool.venueShortage) {
            warnings.add(String.format("径赛并数 %d 超过可用场地数 %d，部分槽位将复用同一场地（并数取决于场地数量，请增加场地或调小并数）",
                    trackSlots, venues.size()));
        }
        if (fieldPool.venueShortage) {
            warnings.add(String.format("田赛并数 %d 超过可用田赛场地数 %d，部分槽位将复用同一场地（并数取决于场地数量，请增加场地或调小并数）",
                    fieldSlots, fieldVenues.size()));
        }

        scheduleRepository.deleteAllSchedules();

        int autoArrangeOk = 0;
        List<String> autoArrangeFails = new ArrayList<>();
        int[] orderCounter = {1};
        Set<Integer> done = new HashSet<>();

        for (int i = 0; i < units.size(); i++) {
            if (done.contains(i)) continue;
            Unit u = units.get(i);
            if (u.participants <= 0) {
                warnings.add(String.format("项目「%s」（%s）暂无有效报名，已跳过",
                        u.event.getName(), u.grade == null ? "不分年级" : u.grade));
                done.add(i);
                continue;
            }

            Pool pool = resolvePool(u, trackPool, fieldPool, mainVenueCode,
                    fieldVenueCodes, codeToName, dedicatedPools, trackSlots, fieldSlots);
            String group = u.track ? null : event2Group.get(u.event.getId());

            if (group == null) {
                // 普通单元：占用并发池中最空闲的一个槽位
                placeOne(u, pool, windows, defaultInterval, saved, warnings, orderCounter, autoArrangeFails);
                autoArrangeOk += u.arranged;
                done.add(i);
            } else {
                // 田赛分组：同组 + 同年级 的单元安排在同一时段并行进行
                List<Integer> batch = new ArrayList<>();
                for (int j = i; j < units.size(); j++) {
                    if (done.contains(j)) continue;
                    Unit v = units.get(j);
                    if (v.track || !sameGrade(v.grade, u.grade)) continue;
                    if (!group.equals(event2Group.get(v.event.getId()))) continue;
                    if (v.participants <= 0) {
                        warnings.add(String.format("项目「%s」（%s）暂无有效报名，已跳过",
                                v.event.getName(), v.grade == null ? "不分年级" : v.grade));
                        done.add(j);
                        continue;
                    }
                    batch.add(j);
                }
                if (batch.isEmpty()) { done.add(i); continue; }
                placeBatch(batch, units, fieldPool, windows, defaultInterval, group,
                        saved, warnings, orderCounter, autoArrangeFails);
                for (Integer idx : batch) {
                    autoArrangeOk += units.get(idx).arranged;
                    done.add(idx);
                }
            }
        }

        log.info("赛程自动编排完成: {}个单元, {}天, 径赛{}位并发, 田赛{}位并发, 田赛分组{}组",
                saved.size(), windows.stream().mapToInt(w -> w.day).max().orElse(0),
                trackSlots, fieldSlots, event2Group.values().stream().distinct().count());

        Map<String, Object> result = buildResult();
        result.put("warnings", warnings);
        result.put("configUsed", cfg);
        result.put("autoArrange", Map.of("ok", autoArrangeOk, "failed", autoArrangeFails.size(), "fails", autoArrangeFails));
        return result;
    }

    // ==================== 放置（并发池） ====================

    /** 普通单元：占用池中最空闲的槽位，并登记赛程行（径赛顺带自动道次编排） */
    private void placeOne(Unit u, Pool pool, List<Window> windows, int defaultInterval,
                          List<EventSchedule> saved, List<String> warnings,
                          int[] orderCounter, List<String> autoArrangeFails) {
        int slot = pool.pickSlot();
        int interval = u.event.getIntervalMinutes() != null ? u.event.getIntervalMinutes() : defaultInterval;
        Slot placed = pool.cursors.get(slot).place(windows, u.duration, interval);
        if (placed == null) {
            warnings.add(String.format("项目「%s」（%s）因时段已排满未能安排", u.event.getName(),
                    u.grade == null ? "不分年级" : u.grade));
            return;
        }
        saveSchedule(u, placed, pool.venueOf.get(slot), saved, orderCounter, autoArrangeFails);
    }

    /**
     * 田赛分组 / 并行捆绑组批量放置：同组单元安排在同一时段、**同一时刻同时开始**。
     *
     * <p>实现：先探测各槽位的最早可用位置（不改状态），取其中「最晚的窗口 + 该窗口内最晚的起点」
     * 作为本批共同起点，再把各项目都放到该起点（各占一个并发位）；若个别槽位在该起点放不下
     * （时段容量不足），退化为各自最早位置并记 warning。</p>
     */
    private void placeBatch(List<Integer> batch, List<Unit> units, Pool pool, List<Window> windows,
                            int defaultInterval, String group, List<EventSchedule> saved,
                            List<String> warnings, int[] orderCounter, List<String> autoArrangeFails) {
        int slots = pool.slots;
        for (int from = 0; from < batch.size(); from += slots) {
            List<Integer> wave = batch.subList(from, Math.min(batch.size(), from + slots));

            // 起始窗口下界：各参与槽位当前窗口的最大值（不倒退到已用尽的时段之前）
            int minWindow = 0;
            for (int k = 0; k < wave.size(); k++) {
                minWindow = Math.max(minWindow, pool.cursors.get(k).windowIdx);
            }

            // ① 各槽位最早可用位置（只探测，不推进游标）
            List<Probe> earliest = new ArrayList<>();
            for (int k = 0; k < wave.size(); k++) {
                Unit u = units.get(wave.get(k));
                earliest.add(pool.cursors.get(k).probe(windows, u.duration, intervalOf(u, defaultInterval), minWindow));
            }

            // ② 共同起点 = 最晚的可用窗口 + 该窗口内最晚的可用起点
            int targetWindow = minWindow;
            for (Probe p : earliest) {
                if (p != null) targetWindow = Math.max(targetWindow, p.windowIdx);
            }
            boolean sameStart = true;
            if (targetWindow >= windows.size()) {
                warnings.add(String.format("田赛分组「%s」因时段已排满未能安排", group));
                continue;
            }
            int commonStart = windows.get(targetWindow).startMinute;
            for (Probe p : earliest) {
                if (p != null && p.windowIdx == targetWindow) {
                    commonStart = Math.max(commonStart, p.slot.startMinute);
                }
            }

            // ③ 各项目放到共同起点；放不下则该项退化为各自最早位置（仍推进游标，避免重叠）
            for (int k = 0; k < wave.size(); k++) {
                Unit u = units.get(wave.get(k));
                Cursor cursor = pool.cursors.get(k);
                Probe p = cursor.placeAt(windows, targetWindow, commonStart, u.duration);
                if (p == null) {
                    sameStart = false;
                    Probe fb = earliest.get(k);   // 退化为该槽位各自最早可用位置
                    if (fb != null) {
                        p = cursor.placeAt(windows, fb.windowIdx, fb.slot.startMinute, u.duration);
                    }
                }
                if (p == null) {
                    warnings.add(String.format("田赛分组「%s」中项目「%s」因时段已排满未能安排", group, u.event.getName()));
                    continue;
                }
                saveSchedule(u, p.slot, pool.venueOf.get(k), saved, orderCounter, autoArrangeFails);
            }
            if (!sameStart) {
                warnings.add(String.format("田赛分组「%s」部分项目未能同时开始（并发位或时段容量不足），已按各自最早时段顺延", group));
            }
        }
    }

    private static int intervalOf(Unit u, int defaultInterval) {
        return u.event.getIntervalMinutes() != null ? u.event.getIntervalMinutes() : defaultInterval;
    }

    /** 登记一条赛程：径赛排入后立即复用编排引擎生成决赛道次 */
    private void saveSchedule(Unit u, Slot placed, String venue, List<EventSchedule> saved,
                              int[] orderCounter, List<String> autoArrangeFails) {
        EventSchedule s = EventSchedule.builder()
                .event(u.event)
                .day(placed.window.day)
                .scheduleDate(placed.window.date)
                .grade(u.grade)
                .timeSlot(placed.window.slotName)
                .startTime(fmt(placed.startMinute))
                .endTime(fmt(placed.startMinute + u.duration))
                .venue(venue)
                .sortOrder(orderCounter[0]++)
                .durationMinutes(u.duration)
                .remark(u.duration >= u.rawDuration ? null
                        : String.format("预计%d分钟，已按上限%d分钟压缩", u.rawDuration, u.duration))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        saved.add(scheduleRepository.save(s));

        if (u.track && u.participants > 0) {
            u.arranged = autoArrangeFor(u, autoArrangeFails);
        }
    }

    /**
     * 为单个径赛单元自动生成决赛道次（先清理该 事件×年级×性别 的旧决赛记录，保留预赛晋级流）。
     *
     * @return 成功生成的 性别组 数量（男/女各计 1）
     */
    private int autoArrangeFor(Unit u, List<String> arrFails) {
        Event e = u.event;
        int lanes = concurrencyOf(e);
        List<String> genders = new ArrayList<>();
        String gl = e.getGenderLimit();
        if ("女子组".equals(gl)) {
            genders.add("F");
        } else if ("男子组".equals(gl)) {
            genders.add("M");
        } else { // 混合/未指定：男女各排一版
            genders.add("M");
            genders.add("F");
        }
        int ok = 0;
        for (String g : genders) {
            try {
                arrangementRepository.deleteByEventRoundGradeGender(e.getId(), ArrangementService.ROUND_FINAL, u.grade, g);
                arrangementService.arrange(e.getId(), u.grade, g, lanes, null, ArrangementService.ROUND_FINAL);
                ok++;
            } catch (Exception ex) {
                arrFails.add(String.format("%s（%s %s）：%s", e.getName(), u.grade,
                        "M".equals(g) ? "男子" : "女子",
                        ex.getMessage() == null ? ex.toString() : ex.getMessage()));
            }
        }
        if (ok > 0) {
            log.info("自动道次编排: event={}({}), grade={}, genders={}", e.getName(), e.getId(), u.grade, genders);
        }
        return ok;
    }

    // ==================== 赛程单元构建 ====================

    /**
     * 按「年级顺序 → 项目顺序」展开赛程单元。
     *
     * <p>项目顺序：自定义 eventOrder（eventId 有序列表，田赛+径赛混排）优先；
     * 未列入的项目保持项目排序号（sortOrder）相对顺序追加在后（稳定排序）。</p>
     * 年级顺序来自配置；event.gradeGroup 非空时该项目只属于对应年级。
     */
    private List<Unit> buildUnits(List<String> gradeOrder, List<Long> eventOrder) {
        List<Event> events = orderedEvents(eventOrder);

        List<Unit> units = new ArrayList<>();
        if (gradeOrder.isEmpty()) {
            for (Event e : events) units.add(new Unit(e, null));
            return units;
        }

        for (String grade : gradeOrder) {
            for (Event e : events) {
                String gg = e.getGradeGroup();
                if (gg != null && !gg.isBlank() && !Grades.same(gg, grade)) continue;
                units.add(new Unit(e, grade));
            }
        }
        return units;
    }

    /** 按自定义顺序（eventOrder）重排项目；未列入者按原 sortOrder 保持相对顺序（稳定排序） */
    private List<Event> orderedEvents(List<Long> eventOrder) {
        List<Event> events = eventRepository.findByIsEnabledTrueOrderBySortOrderAsc();
        if (eventOrder == null || eventOrder.isEmpty()) return events;
        Map<Long, Integer> pos = new HashMap<>();
        for (int i = 0; i < eventOrder.size(); i++) pos.put(eventOrder.get(i), i);
        List<Event> list = new ArrayList<>(events);
        list.sort(Comparator.comparingInt(e -> pos.getOrDefault(e.getId(), Integer.MAX_VALUE)));
        return list;
    }

    /** 项目内并发人数：项目显式 concurrency 优先；径赛回退道次数，田赛回退 1 */
    private int concurrencyOf(Event e) {
        Integer c = e.getConcurrency();
        if (c != null && c > 0) return c;
        if (Boolean.FALSE.equals(e.getTrack())) return 1;
        Integer lc = e.getLaneCount();
        if (lc != null && lc > 0) return lc;
        return e.getDefaultLanes() != null && e.getDefaultLanes() > 0 ? e.getDefaultLanes() : 8;
    }

    /** 估算每个单元用时：径赛看组数、田赛看轮次（均按项目内并发折算），并受最大时间封顶 */
    private void estimateDurations(List<Unit> units, int defaultDuration,
                                   int heatMinutes, int fieldPerAthlete) {
        for (Unit u : units) {
            Event e = u.event;
            boolean isTrack = !Boolean.FALSE.equals(e.getTrack());
            int count = countParticipants(e.getId(), u.grade);
            int concurrency = concurrencyOf(e);

            int entrants = count;
            if (Boolean.TRUE.equals(e.getTeam()) && e.getTeamMembers() != null && e.getTeamMembers() > 0) {
                entrants = (int) Math.ceil((double) count / e.getTeamMembers());
            }
            // 轮次 = ceil(参赛单元 / 项目内并发人数)
            int rounds = Math.max(1, (int) Math.ceil((double) entrants / Math.max(1, concurrency)));

            u.track = isTrack;
            u.concurrency = concurrency;
            u.participants = count;
            u.heats = isTrack ? rounds : 0;
            u.rounds = rounds;
            u.rawDuration = Math.max(MIN_DURATION, rounds * (isTrack ? heatMinutes : fieldPerAthlete));

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
                            && Grades.same(grade, r.getAthlete().getGrade()))
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
                    "类别", "是否田径", "道次", "项目内并发", "预计用时(分)"));
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
                        e != null ? String.valueOf(concurrencyOf(e)) : "",
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
            m.put("concurrency", e != null ? concurrencyOf(e) : 0);
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
        boolean track;        // 是否径赛
        int concurrency = 1;  // 项目内并发人数（径赛=道次/每组人数；田赛=工位数）
        int duration;
        int rawDuration;
        int participants;
        int heats;            // 径赛组数
        int rounds;           // 总轮次（径赛=组数、田赛=批次数）
        int arranged;         // 径赛自动道次编排成功的性别组数

        Unit(Event event, String grade) {
            this.event = event;
            this.grade = grade;
        }
    }

    /** 并发位池：slots 个并发槽位，槽位与场地一一对应（场地不足则复用） */
    private static class Pool {
        final String label;
        final int slots;
        final List<Cursor> cursors = new ArrayList<>();
        final List<String> venueOf = new ArrayList<>();
        final boolean venueShortage;

        Pool(String label, int slots, List<String> venueNames) {
            this.label = label;
            this.slots = Math.max(1, slots);
            this.venueShortage = venueNames.size() < this.slots;
            for (int i = 0; i < this.slots; i++) {
                cursors.add(new Cursor());
                venueOf.add(venueNames.isEmpty()
                        ? "田径场"
                        : venueNames.get(i % venueNames.size()));
            }
        }

        /** 选当前推进最靠前（最空闲）的槽位 */
        int pickSlot() {
            int best = 0;
            for (int i = 1; i < cursors.size(); i++) {
                if (cursors.get(i).aheadOf(cursors.get(best))) best = i;
            }
            return best;
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

    /** 探测结果（只读，不修改游标状态）：给出某槽位最早可放位置 */
    private static class Probe {
        final int windowIdx;
        final Slot slot;

        Probe(int windowIdx, Slot slot) {
            this.windowIdx = windowIdx;
            this.slot = slot;
        }
    }

    /** 场地时间游标：在窗口序列上顺序推进 */
    private static class Cursor {
        int windowIdx = 0;
        int used = 0;   // 当前窗口已用分钟数（含间隔）

        /** 把一段时长放进当前游标位置；放不下就顺延到下一时段 */
        Slot place(List<Window> windows, int duration, int interval) {
            return place(windows, duration, interval, 0);
        }

        /**
         * 同上，但起点不早于 minWindowIdx 号窗口 —— 用于田赛分组：让同组项目落在同一时段。
         * 若当前已推进到更靠后的窗口，则以当前窗口为准（不会回退）。
         */
        Slot place(List<Window> windows, int duration, int interval, int minWindowIdx) {
            if (windowIdx < minWindowIdx) {
                windowIdx = minWindowIdx;
                used = 0;
            }
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

        /**
         * 探测最早可放位置（只读，不推进游标）。
         * 起点不早于 max(当前窗口, minWindowIdx)；返回该位置所在的窗口号与具体 Slot。
         */
        Probe probe(List<Window> windows, int duration, int interval, int minWindowIdx) {
            int wi = Math.max(windowIdx, minWindowIdx);
            int u = (wi == windowIdx) ? used : 0;
            while (wi < windows.size()) {
                Window w = windows.get(wi);
                int gap = u == 0 ? 0 : interval;
                if (u + gap + duration <= w.capacity) {
                    return new Probe(wi, new Slot(w, w.startMinute + u + gap));
                }
                wi++;
                u = 0;
            }
            return null;
        }

        /**
         * 在指定窗口、指定起点放置（推进游标）；放不下（超出窗口容量）返回 null。
         * 用于田赛分组/捆绑组：把同组项目强制落到同一 (窗口, 起点) 以实现同时开赛。
         */
        Probe placeAt(List<Window> windows, int windowIdx, int startMinute, int duration) {
            if (windowIdx < 0 || windowIdx >= windows.size()) return null;
            Window w = windows.get(windowIdx);
            int rel = startMinute - w.startMinute;     // 相对窗口起点的偏移
            if (rel < 0) return null;
            if (rel + duration > w.capacity) return null;
            if (windowIdx != this.windowIdx) {          // 跳到新的（更靠后）窗口，从头计量
                this.windowIdx = windowIdx;
                this.used = 0;
            }
            int end = rel + duration;
            if (end > used) used = end;                 // 维持窗口内已用前沿，避免后续重叠
            return new Probe(windowIdx, new Slot(w, startMinute));
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

    /** 两个年级是否同一（空 = 不分年级，视为相同） */
    private static boolean sameGrade(String a, String b) {
        boolean ea = a == null || a.isBlank();
        boolean eb = b == null || b.isBlank();
        if (ea && eb) return true;
        if (ea || eb) return false;
        return Grades.same(a, b);
    }

    /** 解析自定义项目顺序（eventId 列表） */
    private static List<Long> longList(Object v) {
        List<Long> out = new ArrayList<>();
        if (!(v instanceof List<?> list)) return out;
        for (Object o : list) {
            Long id = asLong(o);
            if (id != null) out.add(id);
        }
        return out;
    }

    /** 解析田赛分组 [{name, eventIds:[...]}] → eventId → 组名（同名视为同组） */
    @SuppressWarnings("unchecked")
    private static Map<Long, String> parseFieldGroups(Object v) {
        Map<Long, String> map = new LinkedHashMap<>();
        if (!(v instanceof List<?> list)) return map;
        int idx = 0;
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> g = (Map<String, Object>) o;
            String name = str(g.get("name"), null);
            if (name == null || name.isBlank()) name = "田赛组" + (++idx);
            if (!(g.get("eventIds") instanceof List<?> ids)) continue;
            for (Object idObj : ids) {
                Long id = asLong(idObj);
                if (id != null) map.put(id, name);
            }
        }
        return map;
    }

    /** 解析场地列表：兼容旧字符串数组 ["田径场", …] 与新对象数组 [{name, code}, …] */
    @SuppressWarnings("unchecked")
    private static List<String> venueNames(Object v) {
        List<String> out = new ArrayList<>();
        if (!(v instanceof List<?> list)) return out;
        for (Object o : list) {
            if (o == null) continue;
            if (o instanceof Map<?, ?> m) {
                Object name = ((Map<String, Object>) m).get("name");
                if (name != null && !String.valueOf(name).isBlank()) out.add(String.valueOf(name).trim());
            } else {
                String s = String.valueOf(o).trim();
                if (!s.isEmpty()) out.add(s);
            }
        }
        return out;
    }

    /** 解析场地列表为完整 {name, code} 对象列表（保留编码，用于按项目级场地编码绑定并发池） */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> venueListOf(Object v) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(v instanceof List<?> list)) return out;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> mm = new LinkedHashMap<>();
            for (Map.Entry<?, ?> en : m.entrySet()) mm.put(String.valueOf(en.getKey()), en.getValue());
            boolean hasName = mm.get("name") != null && !String.valueOf(mm.get("name")).isBlank();
            boolean hasCode = mm.get("code") != null && !String.valueOf(mm.get("code")).isBlank();
            if (hasName || hasCode) out.add(mm);
        }
        return out;
    }

    /** 取场地对象的编码（trim 后）；无编码返回 null */
    private static String codeOf(Map<String, Object> v) {
        if (v == null) return null;
        Object code = v.get("code");
        return code != null && !String.valueOf(code).isBlank() ? String.valueOf(code).trim() : null;
    }

    /**
     * 解析单元应使用哪个并发池：
     * <ul>
     *   <li>未指定场地编码 → 按类别回退默认池（径赛主池 / 田赛池）；</li>
     *   <li>指定了编码且与主径赛场地相同 → 主径赛池；</li>
     *   <li>指定了田赛场地编码 → 该田赛场地的独立池（并发位数=fieldSlots）；</li>
     *   <li>指定了其它新场地编码（如游泳馆）→ 建立独立 1 位并发池，与主/田赛池并行（即「同排」）。</li>
     * </ul>
     * 同一编码的专用池只创建一次（缓存于 dedicatedPools）。
     */
    private Pool resolvePool(Unit u, Pool trackPool, Pool fieldPool,
                            String mainVenueCode, Set<String> fieldVenueCodes,
                            Map<String, String> codeToName, Map<String, Pool> dedicatedPools,
                            int trackSlots, int fieldSlots) {
        String code = u.event.getDefaultVenueCode();
        if (code == null || code.isBlank()) return u.track ? trackPool : fieldPool;
        code = code.trim();
        if (dedicatedPools.containsKey(code)) return dedicatedPools.get(code);
        if (code.equals(mainVenueCode)) return trackPool;
        String name = codeToName.getOrDefault(code, code);
        int slots = fieldVenueCodes.contains(code) ? fieldSlots : 1;
        Pool p = new Pool("场地-" + code, slots, java.util.List.of(name));
        dedicatedPools.put(code, p);
        return p;
    }

    private static Long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o != null) {
            try { return Long.parseLong(String.valueOf(o).trim()); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

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
