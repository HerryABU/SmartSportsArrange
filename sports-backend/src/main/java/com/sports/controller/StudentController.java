package com.sports.controller;

import com.sports.common.ApiResponse;
import com.sports.entity.*;
import com.sports.repository.*;
import com.sports.security.JwtUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 学生端控制器 — 首页/项目浏览/我的赛程/我的成绩
 */
@Slf4j
@RestController
@RequestMapping("/api/student")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StudentController {

    private final RegistrationRepository registrationRepository;
    private final ArrangementRepository arrangementRepository;
    private final ResultRepository resultRepository;
    private final EventRepository eventRepository;
    private final AthleteRepository athleteRepository;

    // ===== 首页 =====
    @GetMapping("/home")
    public ApiResponse<?> home() {
        Long athleteId = getCurrentAthleteId();
        List<Registration> regs = registrationRepository.findByAthleteId(athleteId);
        long approvedCount = regs.stream().filter(r -> "approved".equals(r.getStatus())).count();
        List<Result> results = resultRepository.findByAthleteId(athleteId);
        long awardCount = results.stream().filter(r -> r.getTotalRank() != null && r.getTotalRank() <= 3).count();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("registrationCount", (long) regs.size());
        stats.put("approvedCount", approvedCount);
        stats.put("awardCount", awardCount);

        List<Map<String, Object>> myRegs = regs.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("eventName", r.getEvent().getName());
            m.put("eventType", r.getEvent().getCategory());
            m.put("gender", r.getEvent().getGenderLimit());
            m.put("status", r.getStatus());
            return m;
        }).collect(Collectors.toList());

        List<Registration> approvedRegs = regs.stream()
                .filter(r -> "approved".equals(r.getStatus())).toList();
        List<Long> eventIds = approvedRegs.stream().map(r -> r.getEvent().getId()).distinct().toList();
        Map<Long, String> eventNames = approvedRegs.stream()
                .collect(Collectors.toMap(r -> r.getEvent().getId(), r -> r.getEvent().getName(), (a, b) -> a));

        List<Map<String, Object>> schedules = new ArrayList<>();
        if (!eventIds.isEmpty()) {
            List<Arrangement> arrs = arrangementRepository.findByEventIdsAndAthleteId(eventIds, athleteId);
            for (Arrangement arr : arrs) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", arr.getId());
                m.put("eventName", eventNames.getOrDefault(arr.getEvent().getId(), arr.getEvent().getName()));
                m.put("heat", arr.getHeat());
                m.put("laneNumber", arr.getLane());
                m.put("time", null);
                m.put("location", null);
                schedules.add(m);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stats", stats);
        result.put("registrations", myRegs);
        result.put("schedules", schedules);
        return ApiResponse.success(result);
    }

    // ===== 项目浏览 =====
    @GetMapping("/events")
    public ApiResponse<?> events() {
        Long athleteId = getCurrentAthleteId();
        List<Registration> myRegs = registrationRepository.findByAthleteId(athleteId);
        Set<Long> myEventIds = myRegs.stream().map(r -> r.getEvent().getId()).collect(Collectors.toSet());

        List<Event> events = eventRepository.findByIsEnabledTrueOrderBySortOrderAsc();
        List<Map<String, Object>> list = events.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("name", e.getName());
            m.put("code", e.getCode());
            m.put("category", e.getCategory());
            m.put("genderLimit", e.getGenderLimit());
            m.put("record", e.getRecord());
            m.put("isRegistered", myEventIds.contains(e.getId()));
            return m;
        }).collect(Collectors.toList());

        return ApiResponse.success(list);
    }

    // ===== 我的赛程 =====
    @GetMapping("/schedule")
    public ApiResponse<?> schedule() {
        Long athleteId = getCurrentAthleteId();
        List<Registration> regs = registrationRepository.findByAthleteId(athleteId);

        List<Registration> approvedRegs = regs.stream()
                .filter(r -> "approved".equals(r.getStatus())).toList();
        List<Long> eventIds = approvedRegs.stream().map(r -> r.getEvent().getId()).distinct().toList();
        Map<Long, String> eventNames = approvedRegs.stream()
                .collect(Collectors.toMap(r -> r.getEvent().getId(), r -> r.getEvent().getName(), (a, b) -> a));

        List<Map<String, Object>> list = new ArrayList<>();
        if (!eventIds.isEmpty()) {
            List<Arrangement> arrs = arrangementRepository.findByEventIdsAndAthleteId(eventIds, athleteId);
            for (Arrangement arr : arrs) {
                Registration matchedReg = approvedRegs.stream()
                        .filter(r -> r.getEvent().getId().equals(arr.getEvent().getId()))
                        .findFirst().orElse(null);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", arr.getId());
                m.put("eventName", eventNames.getOrDefault(arr.getEvent().getId(), arr.getEvent().getName()));
                m.put("eventType", matchedReg != null ? matchedReg.getEvent().getCategory() : "");
                m.put("gender", matchedReg != null ? matchedReg.getEvent().getGenderLimit() : "");
                m.put("heat", arr.getHeat());
                m.put("laneNumber", arr.getLane());
                m.put("time", null);
                m.put("location", null);
                list.add(m);
            }
        }
        return ApiResponse.success(list);
    }

    // ===== 我的成绩 =====
    @GetMapping("/results")
    public ApiResponse<?> results() {
        Long athleteId = getCurrentAthleteId();
        List<Result> results = resultRepository.findByAthleteId(athleteId);

        List<Map<String, Object>> list = results.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("eventName", r.getEvent().getName());
            m.put("category", r.getEvent().getCategory());
            m.put("rawTime", r.getRawTime());
            m.put("rank", r.getTotalRank());
            m.put("points", r.getScore());
            m.put("isRecord", Boolean.TRUE.equals(r.getIsRecord()));
            m.put("heatRank", r.getHeatRank());
            m.put("heat", r.getHeat());
            m.put("lane", r.getLane());
            return m;
        }).collect(Collectors.toList());

        return ApiResponse.success(list);
    }

    private Long getCurrentAthleteId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof JwtUserDetails userDetails) {
            String username = userDetails.getUsername();
            // 用学号(用户名)直接查运动员
            Athlete a = athleteRepository.findByStudentId(username).orElse(null);
            if (a != null) return a.getId();
            throw new IllegalArgumentException("当前用户未关联运动员(学号=" + username + ")");
        }
        throw new IllegalArgumentException("未登录");
    }
}
