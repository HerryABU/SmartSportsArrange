package com.sports.service.schedule;

import com.sports.common.util.Grades;
import com.sports.entity.event.Event;
import com.sports.entity.registration.Registration;
import com.sports.entity.venue.Venue;
import com.sports.repository.event.EventRepository;
import com.sports.repository.registration.RegistrationRepository;
import com.sports.schedule.core.primitive.Pool;
import com.sports.schedule.core.math.ScheduleAnalysisMath;
import com.sports.schedule.core.math.SchedulePlacementMath;
import com.sports.schedule.core.primitive.Unit;
import com.sports.schedule.core.primitive.Window;
import com.sports.schedule.support.ScheduleSupport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.sports.schedule.support.ScheduleSupport.castList;
import static com.sports.schedule.support.ScheduleSupport.intVal;
import static com.sports.schedule.support.ScheduleSupport.parseHhMm;
import static com.sports.schedule.support.ScheduleSupport.shiftDate;
import static com.sports.schedule.support.ScheduleSupport.str;
import static com.sports.schedule.support.ScheduleSupport.strList;
import com.sports.service.system.SystemService;

/**
 * 赛程编排的「数据构建 / 配置 / 场地池解析」组件（从 {@code ScheduleService} 抽出）。
 *
 * <p>职责：把配置与报名数据装配成编排所需的 {@link Unit} / {@link Window} / {@link Pool}，
 * 并解析每个单元应使用哪个并发池。全部为无 Spring 依赖的纯装配逻辑，仅持有
 * {@code EventRepository} / {@code RegistrationRepository} / {@code SystemService}。</p>
 *
 * <p>被 {@code SchedulePlacementComponent}（道次编排）与 {@code ScheduleSelfCheckComponent}
 * （自检数据装配）复用其报名查询能力。</p>
 */
public class ScheduleBuildComponent {

    private final EventRepository eventRepository;
    private final RegistrationRepository registrationRepository;
    private final SystemService systemService;

    public ScheduleBuildComponent(EventRepository eventRepository,
                                 RegistrationRepository registrationRepository,
                                 SystemService systemService) {
        this.eventRepository = eventRepository;
        this.registrationRepository = registrationRepository;
        this.systemService = systemService;
    }

    /** 该 (项目, 年级) 的「已审核报名」——本组件查询报名的<b>唯一入口</b> */
    public List<Registration> approvedRegs(Long eventId, String grade) {
        try {
            List<Registration> regs = registrationRepository.findApprovedByEventId(eventId);
            if (grade == null || grade.isBlank()) return regs;
            return regs.stream()
                    .filter(r -> r.getAthlete() != null && Grades.same(grade, r.getAthlete().getGrade()))
                    .collect(java.util.stream.Collectors.toList());
        } catch (Exception ex) {
            return List.of();
        }
    }

    public int countParticipants(Long eventId, String grade) {
        return approvedRegs(eventId, grade).size();
    }

    /** 该项目在指定年级下「已审核报名」里实际出现的性别（原值，通常为 M/F），去重后排序 */
    public List<String> approvedGenders(Long eventId, String grade) {
        List<String> out = new ArrayList<>();
        for (Registration r : approvedRegs(eventId, grade)) {
            var a = r.getAthlete();
            if (a == null || a.getGender() == null) continue;
            String g = a.getGender().trim();
            if (!g.isEmpty() && !out.contains(g)) out.add(g);
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /** 按「年级顺序 → 项目顺序」展开赛程单元 */
    public List<Unit> buildUnits(List<String> gradeOrder, List<Long> eventOrder) {
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
    public List<Event> orderedEvents(List<Long> eventOrder) {
        List<Event> events = eventRepository.findByIsEnabledTrueOrderBySortOrderAsc();
        if (eventOrder == null || eventOrder.isEmpty()) return events;
        Map<Long, Integer> pos = new HashMap<>();
        for (int i = 0; i < eventOrder.size(); i++) pos.put(eventOrder.get(i), i);
        List<Event> list = new ArrayList<>(events);
        list.sort(Comparator.comparingInt(e -> pos.getOrDefault(e.getId(), Integer.MAX_VALUE)));
        return list;
    }

    /** 估算每个单元用时：径赛看组数、田赛看轮次（均按项目内并发折算） */
    public void estimateDurations(List<Unit> units,
                                  int heatMinutes, int fieldPerAthlete, int defaultDuration) {
        for (Unit u : units) {
            Event e = u.event;
            boolean trackFlag = !Boolean.FALSE.equals(e.getTrack());
            boolean fieldMethod = !trackFlag
                    || Boolean.TRUE.equals(e.getFunSports())
                    || Boolean.TRUE.equals(e.getOccupiesTrack());
            boolean isTrack = !fieldMethod;
            List<Registration> regs = approvedRegs(e.getId(), u.grade);
            int count = regs.size();
            int concurrency = ScheduleAnalysisMath.concurrencyOf(e);

            int entrants = count;
            if (Boolean.TRUE.equals(e.getTeam()) && e.getTeamMembers() != null && e.getTeamMembers() > 0) {
                entrants = (int) Math.ceil((double) count / e.getTeamMembers());
            }
            int rounds = Math.max(1, (int) Math.ceil((double) entrants / Math.max(1, concurrency)));

            u.track = isTrack;
            u.concurrency = concurrency;
            u.participants = count;
            u.heats = isTrack ? rounds : 0;
            u.rounds = rounds;
            for (Registration r : regs) {
                if (r.getAthlete() != null && r.getAthlete().getId() != null) {
                    u.athleteIds.add(r.getAthlete().getId());
                }
            }
            int perUnit = (e.getPerBatchMinutes() != null && e.getPerBatchMinutes() > 0)
                    ? e.getPerBatchMinutes()
                    : (isTrack ? heatMinutes : fieldPerAthlete);
            int estimated = rounds * perUnit;
            u.rawDuration = Math.max(SchedulePlacementMath.MIN_DURATION, estimated > 0 ? estimated : defaultDuration);

            u.explicitMaxDuration = e.getMaxDurationMinutes() != null && e.getMaxDurationMinutes() > 0
                    ? e.getMaxDurationMinutes() : 0;
            u.duration = u.explicitMaxDuration > 0
                    ? Math.min(u.rawDuration, u.explicitMaxDuration)
                    : u.rawDuration;
        }
    }

    /** 把 dayConfigs 铺开成有序的时间窗列表 */
    public List<Window> buildWindows(Map<String, Object> cfg) {
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

    /** 以已保存的 meet_schedule 为底，用请求参数覆盖（不落库） */
    public Map<String, Object> mergeConfig(Map<String, Object> override) {
        Map<String, Object> cfg = new LinkedHashMap<>(systemService.getMeetSchedule());
        if (override != null) {
            for (Map.Entry<String, Object> e : override.entrySet()) {
                if (e.getValue() != null) cfg.put(e.getKey(), e.getValue());
            }
        }
        if (cfg.get("gradeOrder") == null || strList(cfg.get("gradeOrder")).isEmpty()) {
            cfg.put("gradeOrder", systemService.getGradeOrder());
        }
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

    /**
     * 解析单元应使用哪个并发池
     */
    public Pool resolvePool(Unit u, Pool trackPool, Pool fieldPool,
                            String mainVenueCode, Set<String> fieldVenueCodes,
                            Map<String, String> codeToName, Map<String, Integer> codeToParallelMax,
                            Map<String, Pool> dedicatedPools,
                            int trackSlots, int fieldSlots) {
        String code = u.event.getDefaultVenueCode();
        if (code == null || code.isBlank()) return u.track ? trackPool : fieldPool;
        code = code.trim();
        if (dedicatedPools.containsKey(code)) return dedicatedPools.get(code);
        if (code.equals(mainVenueCode)) return trackPool;
        String name = (u.event.getDefaultVenue() != null && !u.event.getDefaultVenue().isBlank())
                ? u.event.getDefaultVenue().trim()
                : codeToName.getOrDefault(code, code);
        int slots = fieldVenueCodes.contains(code) ? fieldSlots : codeToParallelMax.getOrDefault(code, 1);
        Pool p = new Pool("场地-" + code, slots, java.util.List.of(name));
        dedicatedPools.put(code, p);
        return p;
    }
}
