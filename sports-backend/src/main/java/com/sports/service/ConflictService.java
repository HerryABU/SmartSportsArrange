package com.sports.service;

import com.sports.entity.Arrangement;
import com.sports.entity.Event;
import com.sports.entity.EventSchedule;
import com.sports.repository.ArrangementRepository;
import com.sports.repository.AthleteRepository;
import com.sports.repository.EventScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * B06 / U05：兼项冲突检测。
 *
 * <p>同一运动员报名了多个项目，若其中两个项目的赛程时间窗在「同一天且重叠（或间隔过小）」，
 * 则判定为兼项冲突——该运动员可能被同时叫到两个场地。检测结果为冲突清单，供编排后告警与人工调整。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConflictService {

    private final ArrangementRepository arrangementRepository;
    private final EventScheduleRepository eventScheduleRepository;
    private final AthleteRepository athleteRepository;

    /** 相邻项目「结束-开始」间隔小于该值（分钟）也视为冲突 */
    private static final int CONFLICT_BUFFER_MIN = 15;

    /**
     * 检测全部运动员的兼项冲突。
     *
     * @return 冲突清单，元素含 athleteId/athleteName/athleteNumber/eventA/eventB/windowA/windowB/suggestion
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> detectConflicts() {
        // 1) 项目 -> 赛程时间窗（合并多轮）
        Map<Long, List<EventSchedule>> eventSchedules = new HashMap<>();
        for (EventSchedule s : eventScheduleRepository.findAll()) {
            if (s.getEvent() == null || s.getStartTime() == null || s.getEndTime() == null) continue;
            eventSchedules.computeIfAbsent(s.getEvent().getId(), k -> new ArrayList<>()).add(s);
        }

        // 2) 运动员 -> 参赛项目集合
        Map<Long, Set<Long>> athleteEvents = new HashMap<>();
        for (Arrangement a : arrangementRepository.findAll()) {
            if (a.getAthlete() == null || a.getEvent() == null) continue;
            athleteEvents.computeIfAbsent(a.getAthlete().getId(), k -> new HashSet<>()).add(a.getEvent().getId());
        }

        // 3) 两两比较同一运动员的不同项目时间窗
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Map.Entry<Long, Set<Long>> entry : athleteEvents.entrySet()) {
            Long aid = entry.getKey();
            List<Long> evs = new ArrayList<>(entry.getValue());
            if (evs.size() < 2) continue;
            Map<String, Object> ath = athleteNameNumber(aid);
            for (int i = 0; i < evs.size(); i++) {
                for (int j = i + 1; j < evs.size(); j++) {
                    List<EventSchedule> sa = eventSchedules.get(evs.get(i));
                    List<EventSchedule> sb = eventSchedules.get(evs.get(j));
                    if (sa == null || sb == null) continue;
                    for (EventSchedule x : sa) {
                        for (EventSchedule y : sb) {
                            if (overlap(x, y)) {
                                conflicts.add(buildConflict(aid, ath, x, y));
                            }
                        }
                    }
                }
            }
        }

        conflicts.sort(Comparator.comparing(c -> String.valueOf(c.get("athleteName"))));
        log.info("兼项冲突检测完成: 共 {} 处", conflicts.size());
        return conflicts;
    }

    private boolean overlap(EventSchedule x, EventSchedule y) {
        int dx = x.getDay() == null ? 1 : x.getDay();
        int dy = y.getDay() == null ? 1 : y.getDay();
        if (dx != dy) return false; // 不同天
        int xs = toMin(x.getStartTime()), xe = toMin(x.getEndTime());
        int ys = toMin(y.getStartTime()), ye = toMin(y.getEndTime());
        boolean overlapping = xs < ye && ys < xe;                      // 时间窗重叠
        boolean tooClose = Math.abs(ys - xe) < CONFLICT_BUFFER_MIN;   // 相邻且间隔过小
        return overlapping || tooClose;
    }

    private Map<String, Object> buildConflict(Long aid, Map<String, Object> ath,
                                              EventSchedule x, EventSchedule y) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("athleteId", aid);
        m.put("athleteName", ath.get("name"));
        m.put("athleteNumber", ath.get("number"));
        m.put("eventA", eventSummary(x));
        m.put("eventB", eventSummary(y));
        m.put("windowA", windowStr(x));
        m.put("windowB", windowStr(y));
        m.put("suggestion", "建议错开运动员「" + ath.get("name") + "」的两个项目时间，或调整其中一项赛程至不同时段");
        return m;
    }

    private Map<String, Object> eventSummary(EventSchedule s) {
        Event e = s.getEvent();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", e != null && e.getCode() != null ? e.getCode() : "");
        m.put("name", e != null && e.getName() != null ? e.getName() : "");
        m.put("venue", s.getVenue() != null ? s.getVenue() : "");
        m.put("round", s.getRound() != null ? s.getRound() : "final");
        return m;
    }

    private String windowStr(EventSchedule s) {
        int day = s.getDay() == null ? 1 : s.getDay();
        return "第" + day + "天 " + s.getStartTime() + "~" + s.getEndTime()
                + (s.getVenue() != null ? " @" + s.getVenue() : "");
    }

    private Map<String, Object> athleteNameNumber(Long aid) {
        Map<String, Object> m = new LinkedHashMap<>();
        athleteRepository.findById(aid).ifPresent(a -> {
            m.put("name", a.getName());
            m.put("number", a.getNumber());
        });
        if (m.isEmpty()) {
            m.put("name", "未知");
            m.put("number", "");
        }
        return m;
    }

    private int toMin(String hhmm) {
        if (hhmm == null || hhmm.isBlank()) return 0;
        String[] p = hhmm.split(":");
        try {
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) {
            return 0;
        }
    }
}
