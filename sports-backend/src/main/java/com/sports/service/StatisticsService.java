package com.sports.service;

import com.sports.entity.*;
import com.sports.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StatisticsService {

    private final RegistrationRepository registrationRepository;
    private final ArrangementRepository arrangementRepository;
    private final ResultRepository resultRepository;
    private final EventRepository eventRepository;
    private final ClassInfoRepository classInfoRepository;
    private final AthleteRepository athleteRepository;

    /**
     * 获取报名统计
     */
    public Map<String, Object> getRegistrationStats() {
        List<Registration> allRegs = registrationRepository.findAll();
        List<Event> events = eventRepository.findAll();
        List<ClassInfo> classes = classInfoRepository.findAll();

        // 按项目统计
        Map<String, Map<String, Long>> byEvent = new LinkedHashMap<>();
        for (Event event : events) {
            Map<String, Long> counts = new LinkedHashMap<>();
            long approved = registrationRepository.countApprovedByEventId(event.getId());
            List<Registration> eventRegs = registrationRepository.findByEventId(event.getId());
            long pending = eventRegs.stream().filter(r -> "pending".equals(r.getStatus())).count();
            long rejected = eventRegs.stream().filter(r -> "rejected".equals(r.getStatus())).count();
            long cancelled = eventRegs.stream().filter(r -> "cancelled".equals(r.getStatus())).count();

            counts.put("total", (long) eventRegs.size());
            counts.put("approved", approved);
            counts.put("pending", pending);
            counts.put("rejected", rejected);
            counts.put("cancelled", cancelled);
            counts.put("capacity", event.getMaxParticipants() != null
                    ? event.getMaxParticipants().longValue() : 0L);
            byEvent.put(event.getName(), counts);
        }

        // 按班级统计
        Map<String, Long> byClass = new LinkedHashMap<>();
        for (ClassInfo ci : classes) {
            List<Registration> classRegs = allRegs.stream()
                    .filter(r -> r.getAthlete().getClassInfo() != null
                            && r.getAthlete().getClassInfo().getId().equals(ci.getId()))
                    .collect(Collectors.toList());
            byClass.put(ci.getName(), (long) classRegs.size());
        }

        // 按年级统计（供报表「各年级参赛人数」）
        Map<String, Long> byGrade = new LinkedHashMap<>();
        for (ClassInfo ci : classes) {
            String g = ci.getGrade() != null ? ci.getGrade() : "未分年级";
            long count = allRegs.stream()
                    .filter(r -> r.getAthlete().getClassInfo() != null
                            && r.getAthlete().getClassInfo().getId().equals(ci.getId()))
                    .count();
            byGrade.merge(g, count, Long::sum);
        }

        // 按状态统计
        long totalPending = allRegs.stream().filter(r -> "pending".equals(r.getStatus())).count();
        long totalApproved = allRegs.stream().filter(r -> "approved".equals(r.getStatus())).count();
        long totalRejected = allRegs.stream().filter(r -> "rejected".equals(r.getStatus())).count();
        long totalCancelled = allRegs.stream().filter(r -> "cancelled".equals(r.getStatus())).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalRegistrations", allRegs.size());
        result.put("totalClasses", classes.size());
        result.put("totalAthletes", athleteRepository.count());
        result.put("totalEvents", events.size());
        result.put("total", allRegs.size());
        result.put("byStatus", Map.of(
                "pending", totalPending,
                "approved", totalApproved,
                "rejected", totalRejected,
                "cancelled", totalCancelled
        ));
        result.put("byEvent", byEvent);
        result.put("byClass", byClass);
        result.put("byGrade", byGrade);

        return result;
    }

    /**
     * 获取成绩统计
     */
    public Map<String, Object> getScoreStats() {
        List<Result> allResults = resultRepository.findAllValid();
        List<Event> events = eventRepository.findAll();

        // 按项目统计
        Map<String, Map<String, Object>> byEvent = new LinkedHashMap<>();
        for (Event event : events) {
            List<Result> eventResults = allResults.stream()
                    .filter(r -> r.getEvent().getId().equals(event.getId()))
                    .collect(Collectors.toList());

            if (eventResults.isEmpty()) continue;

            Map<String, Object> eventStats = new LinkedHashMap<>();
            eventStats.put("total", eventResults.size());
            eventStats.put("hasRanking", eventResults.stream().anyMatch(r -> r.getTotalRank() != null));

            // 最高分
            eventResults.stream()
                    .filter(r -> r.getScore() != null)
                    .max(Comparator.comparing(Result::getScore))
                    .ifPresent(r -> eventStats.put("maxScore", r.getScore()));

            // 破纪录数
            long records = eventResults.stream().filter(Result::getIsRecord).count();
            eventStats.put("records", records);

            byEvent.put(event.getName(), eventStats);
        }

        // 总分统计
        double totalScore = allResults.stream()
                .filter(r -> r.getScore() != null)
                .mapToDouble(Result::getScore)
                .sum();

        long totalRecords = allResults.stream().filter(Result::getIsRecord).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalResults", allResults.size());
        result.put("totalScore", Math.round(totalScore * 100.0) / 100.0);
        result.put("totalRecords", totalRecords);
        result.put("byEvent", byEvent);

        return result;
    }

    /**
     * 生成秩序册数据
     */
    public Map<String, Object> generateOrderBook() {
        List<Event> events = eventRepository.findByIsEnabledTrueOrderBySortOrderAsc();
        List<ClassInfo> classes = classInfoRepository.findByIsParticipatingTrue();

        // 项目分组
        Map<String, List<Map<String, Object>>> byCategory = new LinkedHashMap<>();
        byCategory.put("径赛", new ArrayList<>());
        byCategory.put("田赛", new ArrayList<>());
        byCategory.put("其他", new ArrayList<>());

        for (Event event : events) {
            Map<String, Object> eventInfo = new LinkedHashMap<>();
            eventInfo.put("id", event.getId());
            eventInfo.put("name", event.getName());
            eventInfo.put("code", event.getCode());
            eventInfo.put("genderLimit", event.getGenderLimit());
            eventInfo.put("distanceType", event.getDistanceType());
            eventInfo.put("registrationStart", event.getRegistrationStart());
            eventInfo.put("registrationEnd", event.getRegistrationEnd());
            eventInfo.put("record", event.getRecord());

            // 获取该项目已编排信息
            List<Arrangement> arrangements = arrangementRepository.findByEventId(event.getId());
            eventInfo.put("arrangedCount", arrangements.size());
            eventInfo.put("heatCount", arrangements.stream()
                    .map(Arrangement::getHeat)
                    .distinct()
                    .count());

            String category = event.getCategory();
            if (category == null) category = "其他";
            byCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(eventInfo);
        }

        // 班级信息
        List<Map<String, Object>> classList = new ArrayList<>();
        for (ClassInfo ci : classes) {
            Map<String, Object> classInfo = new LinkedHashMap<>();
            classInfo.put("id", ci.getId());
            classInfo.put("name", ci.getName());
            classInfo.put("grade", ci.getGrade());
            classInfo.put("teacherName", ci.getTeacherName());
            classInfo.put("studentCount", ci.getStudentCount());
            classList.add(classInfo);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "运动会秩序册");
        result.put("generatedAt", java.time.LocalDateTime.now().toString());
        result.put("events", byCategory);
        result.put("classes", classList);
        result.put("totalEvents", events.size());
        result.put("totalClasses", classes.size());

        return result;
    }

    /**
     * 生成成绩册数据
     */
    public Map<String, Object> generateResultBook() {
        List<Event> events = eventRepository.findByIsEnabledTrueOrderBySortOrderAsc();
        List<Result> allResults = resultRepository.findAllValid();

        List<Map<String, Object>> eventResults = new ArrayList<>();
        Map<String, List<Map<String, Object>>> records = new LinkedHashMap<>();

        for (Event event : events) {
            List<Result> eventResultList = allResults.stream()
                    .filter(r -> r.getEvent().getId().equals(event.getId()))
                    .sorted(Comparator.comparing(Result::getTotalRank,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .collect(Collectors.toList());

            if (eventResultList.isEmpty()) continue;

            Map<String, Object> eventData = new LinkedHashMap<>();
            eventData.put("eventId", event.getId());
            eventData.put("eventName", event.getName());
            eventData.put("category", event.getCategory());
            eventData.put("record", event.getRecord());

            List<Map<String, Object>> rankings = new ArrayList<>();
            for (Result r : eventResultList) {
                Map<String, Object> rankData = new LinkedHashMap<>();
                rankData.put("rank", r.getTotalRank());
                rankData.put("athleteName", r.getAthlete().getName());
                rankData.put("number", r.getAthlete().getNumber());
                rankData.put("className", r.getAthlete().getClassInfo() != null
                        ? r.getAthlete().getClassInfo().getName() : "未知");
                rankData.put("grade", r.getAthlete().getGrade());
                rankData.put("rawTime", r.getRawTime());
                rankData.put("score", r.getScore());
                rankData.put("isRecord", r.getIsRecord());
                rankings.add(rankData);

                // 收集破纪录信息
                if (Boolean.TRUE.equals(r.getIsRecord())) {
                    records.computeIfAbsent(event.getName(), k -> new ArrayList<>()).add(rankData);
                }
            }

            eventData.put("rankings", rankings);
            eventData.put("totalRanked", rankings.size());
            eventResults.add(eventData);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "运动会成绩册");
        result.put("generatedAt", java.time.LocalDateTime.now().toString());
        result.put("eventResults", eventResults);
        result.put("records", records);
        result.put("totalEvents", eventResults.size());

        return result;
    }

    /**
     * 获取待办事项统计（Dashboard用）
     */
    public Map<String, Object> getTodoStats() {
        long pendingRegistrations = registrationRepository.countPending();
        long totalEvents = eventRepository.findByIsEnabledTrue().size();
        long arrangedEvents = arrangementRepository.findDistinctEventCount();
        long unarrangedEvents = totalEvents - arrangedEvents;
        long pendingScores = Math.max(0, arrangedEvents - resultRepository.countDistinctEventWithResults());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pendingRegistrations", pendingRegistrations);
        result.put("unarrangedEvents", unarrangedEvents);
        result.put("pendingScores", pendingScores);
        result.put("completed", totalEvents + pendingRegistrations);
        return result;
    }

    /**
     * 获取报名进度（按年级，Dashboard用）
     */
    public List<Map<String, Object>> getRegistrationProgress() {
        List<ClassInfo> classes = classInfoRepository.findByIsParticipatingTrue();
        Map<String, int[]> byGrade = new LinkedHashMap<>();

        for (ClassInfo ci : classes) {
            String grade = ci.getGrade() != null ? ci.getGrade() : "未知";
            byGrade.computeIfAbsent(grade, k -> new int[]{0, 0});
            byGrade.get(grade)[0] += ci.getStudentCount() != null ? ci.getStudentCount() : 0;
        }

        List<Registration> approved = registrationRepository.findByStatus("approved");
        for (Registration reg : approved) {
            if (reg.getAthlete() != null && reg.getAthlete().getClassInfo() != null) {
                String grade = reg.getAthlete().getClassInfo().getGrade();
                if (grade != null && byGrade.containsKey(grade)) {
                    byGrade.get(grade)[1]++;
                }
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        byGrade.forEach((grade, counts) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", grade);
            item.put("total", counts[0]);
            item.put("registered", counts[1]);
            result.add(item);
        });
        return result;
    }

    /**
     * 获取今日赛程（Dashboard用）
     */
    public List<Map<String, Object>> getTodaySchedule() {
        List<Event> events = eventRepository.findByIsEnabledTrueOrderBySortOrderAsc();
        List<Map<String, Object>> schedule = new ArrayList<>();
        int id = 1;

        for (Event event : events) {
            List<Arrangement> arrangements = arrangementRepository.findByEventId(event.getId());
            if (arrangements.isEmpty()) continue;

            long distinctHeats = arrangements.stream().map(Arrangement::getHeat).distinct().count();

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id++);
            item.put("eventName", event.getName());
            item.put("gender", event.getGenderLimit() != null ? event.getGenderLimit() : "");
            item.put("heat", (int) distinctHeats + " 组");
            item.put("time", event.getRegistrationEnd() != null ?
                event.getRegistrationEnd().toLocalTime().toString().substring(0, 5) : "待定");
            item.put("location", "田径场");
            item.put("statusCode", arrangements.isEmpty() ? "preparing" : "in_progress");
            item.put("status", arrangements.isEmpty() ? "待编排" : "进行中");
            schedule.add(item);
        }

        return schedule;
    }

    // ============ Controller 兼容别名 + export ============

    /** Controller 别名 */
    public Map<String, Object> registrationStats() {
        return getRegistrationStats();
    }

    /** Controller 别名 */
    public Map<String, Object> scoreStats() {
        return getScoreStats();
    }

    /** 导出统计 */
    public void export(jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        Map<String, Object> data = getRegistrationStats();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = "报名统计_" + java.time.LocalDateTime.now().toString().replace(":", "-") + ".xlsx";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode(fileName, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")
                + ";filename*=UTF-8''" + java.net.URLEncoder.encode(fileName, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
        try (java.io.OutputStream out = response.getOutputStream()) {
            java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
            // Sheet1: 按项目统计
            rows.add(java.util.List.of("项目名称", "总报名数", "已通过", "待审核"));
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Long>> byEvent = (Map<String, Map<String, Long>>) data.get("byEvent");
            if (byEvent != null) {
                for (Map.Entry<String, Map<String, Long>> entry : byEvent.entrySet()) {
                    Map<String, Long> counts = entry.getValue();
                    rows.add(java.util.List.of(
                        entry.getKey(),
                        String.valueOf(counts.getOrDefault("total", 0L)),
                        String.valueOf(counts.getOrDefault("approved", 0L)),
                        String.valueOf(counts.getOrDefault("pending", 0L))
                    ));
                }
            }
            java.util.List<java.util.List<String>> headCols = rows.get(0).stream()
                    .map(java.util.List::of).collect(java.util.stream.Collectors.toList());
            com.alibaba.excel.EasyExcel.write(out)
                .head(headCols)
                .sheet("报名统计").doWrite(rows.subList(1, rows.size()));
            log.info("导出统计数据完成");
        }
    }
}
