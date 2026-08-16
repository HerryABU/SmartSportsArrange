package com.sports.service;

import com.sports.entity.Arrangement;
import com.sports.entity.Athlete;
import com.sports.entity.Event;
import com.sports.entity.Registration;
import com.sports.repository.ArrangementRepository;
import com.sports.repository.EventRepository;
import com.sports.repository.RegistrationRepository;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ArrangementService {

    private final ArrangementRepository arrangementRepository;
    private final RegistrationRepository registrationRepository;
    private final EventRepository eventRepository;

    private static final int OPTIMIZATION_ROUNDS = 5;
    private static final int MAX_SWAP_ATTEMPTS = 500;

    // ==================== 编排适配方法（Controller 调用入口） ====================

    /**
     * 执行编排（Controller 适配方法）
     */
    public Map<String, Object> executeArrangement(Long eventId, Map<String, Object> config) {
        String grade = (String) config.get("grade");
        String gender = (String) config.get("gender");
        int lanes = config.containsKey("lanes") ? ((Number) config.get("lanes")).intValue() : 8;

        @SuppressWarnings("unchecked")
        Map<String, Boolean> ruleConfig = (Map<String, Boolean>) config.get("ruleConfig");
        if (ruleConfig == null) {
            ruleConfig = Map.of(
                "preferDiffHeat", true,
                "preferDiffLane", true,
                "banSameClassSameLane", true
            );
        }

        long startTime = System.currentTimeMillis();
        Map<String, Object> result = arrange(eventId, grade, gender, lanes, ruleConfig);
        long elapsed = System.currentTimeMillis() - startTime;
        result.put("executionTimeMs", elapsed);
        return result;
    }

    /**
     * 预览编排（Controller 适配方法，不保存）
     */
    public Map<String, Object> previewArrangement(Map<String, Object> config) {
        Long eventId = config.containsKey("eventId") ? ((Number) config.get("eventId")).longValue() : null;
        String grade = (String) config.get("grade");
        String gender = (String) config.get("gender");
        int lanes = config.containsKey("lanes") ? ((Number) config.get("lanes")).intValue() : 8;

        if (eventId == null) {
            throw new RuntimeException("预览需要指定 eventId");
        }

        return preview(eventId, grade, gender, lanes);
    }

    /**
     * 查看编排结果（Controller 适配方法）
     */
    public Map<String, Object> viewArrangement(Long eventId) {
        return getArrangement(eventId);
    }

    /**
     * 手动调整编排（Controller 适配方法）
     */
    public Map<String, Object> manualAdjust(Long eventId, List<Map<String, Object>> adjustments) {
        List<Arrangement> arrangementList = new ArrayList<>();
        for (Map<String, Object> adj : adjustments) {
            Arrangement arr = new Arrangement();
            if (adj.containsKey("id") && adj.get("id") != null) {
                arr.setId(((Number) adj.get("id")).longValue());
            }
            if (adj.containsKey("athleteId") && adj.get("athleteId") != null) {
                Athlete athlete = new Athlete();
                athlete.setId(((Number) adj.get("athleteId")).longValue());
                arr.setAthlete(athlete);
            }
            if (adj.containsKey("heat") && adj.get("heat") != null) {
                arr.setHeat(((Number) adj.get("heat")).intValue());
            }
            if (adj.containsKey("lane") && adj.get("lane") != null) {
                arr.setLane(((Number) adj.get("lane")).intValue());
            }
            if (adj.containsKey("grade")) {
                arr.setGrade((String) adj.get("grade"));
            }
            if (adj.containsKey("gender")) {
                arr.setGender((String) adj.get("gender"));
            }
            arrangementList.add(arr);
        }
        return updateArrangement(eventId, arrangementList);
    }

    /**
     * 批量编排（Controller 适配方法）
     */
    public Map<String, Object> batchArrange(List<Long> eventIds) {
        List<Map<String, Object>> results = new ArrayList<>();
        int success = 0, failed = 0;

        for (Long eventId : eventIds) {
            try {
                Event event = eventRepository.findById(eventId)
                        .orElseThrow(() -> new RuntimeException("项目不存在: " + eventId));

                // 获取该项目的所有报名记录，按年级+性别分组
                List<Registration> regs = registrationRepository.findApprovedByEventId(eventId);
                Set<String> gradeGenderPairs = new HashSet<>();
                for (Registration r : regs) {
                    Athlete a = r.getAthlete();
                    if (a.getGrade() != null && a.getGender() != null) {
                        gradeGenderPairs.add(a.getGrade() + "|" + a.getGender());
                    }
                }

                for (String pair : gradeGenderPairs) {
                    String[] parts = pair.split("\\|");
                    String grade = parts[0];
                    String gender = parts[1];
                    int lanes = event.getDefaultLanes() != null ? event.getDefaultLanes() : 8;

                    Map<String, Boolean> ruleConfig = Map.of(
                        "preferDiffHeat", true,
                        "preferDiffLane", true,
                        "banSameClassSameLane", true
                    );

                    try {
                        arrange(eventId, grade, gender, lanes, ruleConfig);
                        success++;
                    } catch (Exception e) {
                        log.warn("批量编排失败: eventId={}, grade={}, gender={}: {}",
                                eventId, grade, gender, e.getMessage());
                        failed++;
                    }
                }
            } catch (Exception e) {
                log.error("批量编排项目 {} 失败: {}", eventId, e.getMessage());
                failed++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", eventIds.size());
        result.put("success", success);
        result.put("failed", failed);
        return result;
    }

    /**
     * 导出道次表（Controller 适配方法）
     */
    public void exportLaneSheet(Long eventId, HttpServletResponse response) {
        exportArrangement(eventId, response);
    }

    // ==================== 核心编排算法 ====================

    /**
     * 智能编排算法 - 核心方法
     */
    public Map<String, Object> arrange(Long eventId, String grade, String gender,
                                        int lanes, Map<String, Boolean> ruleConfig) {
        log.info("开始编排: eventId={}, grade={}, gender={}, lanes={}", eventId, grade, gender, lanes);

        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("项目不存在: " + eventId));

        // 1. 获取已审核的报名记录
        List<Registration> registrations = registrationRepository
                .findApprovedByEventGradeGender(eventId, grade, gender);

        if (registrations.isEmpty()) {
            throw new RuntimeException("没有符合条件的已审核报名记录");
        }

        // 获取运动员列表（带班级信息）
        List<Athlete> athletes = registrations.stream()
                .map(Registration::getAthlete)
                .collect(Collectors.toList());

        int athleteCount = athletes.size();

        // 2. 计算需要的组数
        int heats = (int) Math.ceil((double) athleteCount / lanes);

        // 3. 按班级分组运动员
        Map<Long, List<Athlete>> classAthletes = athletes.stream()
                .collect(Collectors.groupingBy(
                        a -> a.getClassInfo() != null ? a.getClassInfo().getId() : 0L,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        // 按班级人数降序排列
        List<Map.Entry<Long, List<Athlete>>> sortedClasses = classAthletes.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue().size(), e1.getValue().size()))
                .collect(Collectors.toList());

        // 4. 初始化 heats × lanes 矩阵
        Athlete[][] matrix = new Athlete[heats][lanes];

        // 记录每个heat中各班级已分配人数
        Map<Integer, Map<Long, Integer>> heatClassCounts = new HashMap<>();
        for (int h = 0; h < heats; h++) {
            heatClassCounts.put(h, new HashMap<>());
        }

        // 5. 贪心分配：按班级从大到小，每个运动员分配到当前同班人数最少的组
        List<String> warnings = new ArrayList<>();

        for (Map.Entry<Long, List<Athlete>> entry : sortedClasses) {
            Long classId = entry.getKey();
            List<Athlete> classAthleteList = new ArrayList<>(entry.getValue());

            for (Athlete athlete : classAthleteList) {
                int bestHeat = -1;
                int minSameClass = Integer.MAX_VALUE;
                int minTotal = Integer.MAX_VALUE;

                for (int h = 0; h < heats; h++) {
                    int sameClassInHeat = heatClassCounts.get(h).getOrDefault(classId, 0);
                    int totalInHeat = heatClassCounts.get(h).values().stream()
                            .mapToInt(Integer::intValue).sum();

                    if (totalInHeat >= lanes) continue;

                    if (sameClassInHeat < minSameClass ||
                            (sameClassInHeat == minSameClass && totalInHeat < minTotal)) {
                        minSameClass = sameClassInHeat;
                        minTotal = totalInHeat;
                        bestHeat = h;
                    }
                }

                if (bestHeat == -1) {
                    warnings.add("无法为运动员 " + athlete.getName() + " 分配合适的组");
                    continue;
                }

                for (int l = 0; l < lanes; l++) {
                    if (matrix[bestHeat][l] == null) {
                        matrix[bestHeat][l] = athlete;
                        heatClassCounts.get(bestHeat).merge(classId, 1, Integer::sum);
                        break;
                    }
                }
            }
        }

        // 6. 局部优化：尝试交换运动员以改善约束
        if (ruleConfig != null) {
            boolean banSameClassSameLane = ruleConfig.getOrDefault("banSameClassSameLane", true);
            boolean preferDiffHeat = ruleConfig.getOrDefault("preferDiffHeat", true);
            boolean preferDiffLane = ruleConfig.getOrDefault("preferDiffLane", true);

            for (int round = 0; round < OPTIMIZATION_ROUNDS; round++) {
                boolean improved = false;

                for (int attempt = 0; attempt < MAX_SWAP_ATTEMPTS; attempt++) {
                    int h1 = (int) (Math.random() * heats);
                    int h2 = (int) (Math.random() * heats);
                    if (h1 == h2) continue;

                    List<Integer> nonEmptyLanes1 = new ArrayList<>();
                    List<Integer> nonEmptyLanes2 = new ArrayList<>();
                    for (int l = 0; l < lanes; l++) {
                        if (matrix[h1][l] != null) nonEmptyLanes1.add(l);
                        if (matrix[h2][l] != null) nonEmptyLanes2.add(l);
                    }
                    if (nonEmptyLanes1.isEmpty() || nonEmptyLanes2.isEmpty()) continue;

                    int l1 = nonEmptyLanes1.get((int) (Math.random() * nonEmptyLanes1.size()));
                    int l2 = nonEmptyLanes2.get((int) (Math.random() * nonEmptyLanes2.size()));

                    Athlete a1 = matrix[h1][l1];
                    Athlete a2 = matrix[h2][l2];

                    if (a1 == null || a2 == null) continue;

                    Long c1 = a1.getClassInfo() != null ? a1.getClassInfo().getId() : 0L;
                    Long c2 = a2.getClassInfo() != null ? a2.getClassInfo().getId() : 0L;

                    double costBefore = calculateCost(matrix, heatClassCounts, h1, h2, l1, l2,
                            banSameClassSameLane, preferDiffHeat, preferDiffLane);

                    matrix[h1][l1] = a2;
                    matrix[h2][l2] = a1;

                    Map<Integer, Map<Long, Integer>> simulatedCounts = deepCopy(heatClassCounts);
                    simulatedCounts.get(h1).merge(c1, -1, Integer::sum);
                    simulatedCounts.get(h2).merge(c2, -1, Integer::sum);
                    simulatedCounts.get(h1).merge(c2, 1, Integer::sum);
                    simulatedCounts.get(h2).merge(c1, 1, Integer::sum);

                    double costAfter = calculateCost(matrix, simulatedCounts, h1, h2, l1, l2,
                            banSameClassSameLane, preferDiffHeat, preferDiffLane);

                    if (costAfter < costBefore) {
                        heatClassCounts = simulatedCounts;
                        improved = true;
                    } else {
                        matrix[h1][l1] = a1;
                        matrix[h2][l2] = a2;
                    }
                }

                if (!improved) break;
            }
        }

        // 7. 创建Arrangement记录
        int version = 1;
        Integer maxVersion = arrangementRepository.findMaxVersionByEventId(eventId);
        if (maxVersion != null) {
            version = maxVersion + 1;
        }

        List<Arrangement> arrangements = new ArrayList<>();
        List<Map<String, Object>> heatDetails = new ArrayList<>();

        for (int h = 0; h < heats; h++) {
            List<Map<String, Object>> lanes_in_heat = new ArrayList<>();

            for (int l = 0; l < lanes; l++) {
                Athlete athlete = matrix[h][l];

                Map<String, Object> laneInfo = new LinkedHashMap<>();
                laneInfo.put("lane", l + 1);

                if (athlete != null) {
                    Arrangement arrangement = Arrangement.builder()
                            .event(event)
                            .athlete(athlete)
                            .grade(grade)
                            .gender(gender)
                            .heat(h + 1)
                            .lane(l + 1)
                            .version(version)
                            .isManual(false)
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();
                    arrangements.add(arrangement);

                    laneInfo.put("athleteId", athlete.getId());
                    laneInfo.put("athleteName", athlete.getName());
                    laneInfo.put("number", athlete.getNumber());
                    laneInfo.put("className", athlete.getClassInfo() != null
                            ? athlete.getClassInfo().getName() : "未知");
                    laneInfo.put("classId", athlete.getClassInfo() != null
                            ? athlete.getClassInfo().getId() : null);
                } else {
                    laneInfo.put("athleteId", null);
                    laneInfo.put("athleteName", null);
                }

                lanes_in_heat.add(laneInfo);
            }

            Map<String, Object> heatInfo = new LinkedHashMap<>();
            heatInfo.put("heat", h + 1);
            heatInfo.put("lanes", lanes_in_heat);
            heatDetails.add(heatInfo);
        }

        arrangementRepository.saveAll(arrangements);
        log.info("编排完成: eventId={}, 共{}组, {}名运动员, version={}",
                eventId, heats, athleteCount, version);

        // 8. 计算统计信息
        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("totalAthletes", athleteCount);
        statistics.put("totalHeats", heats);
        statistics.put("lanes", lanes);
        statistics.put("version", version);
        statistics.put("avgPerHeat", heats > 0 ? Math.round(athleteCount * 10.0 / heats) / 10.0 : 0);
        int emptyLanes = 0;
        for (int h = 0; h < heats; h++) {
            for (int l = 0; l < lanes; l++) {
                if (matrix[h][l] == null) emptyLanes++;
            }
        }
        statistics.put("emptyLanes", emptyLanes);

        Map<String, List<Integer>> classDistribution = new LinkedHashMap<>();
        for (int h = 0; h < heats; h++) {
            for (int l = 0; l < lanes; l++) {
                Athlete a = matrix[h][l];
                if (a != null && a.getClassInfo() != null) {
                    String cn = a.getClassInfo().getName();
                    classDistribution.computeIfAbsent(cn, k -> new ArrayList<>()).add(h + 1);
                }
            }
        }
        statistics.put("classDistribution", classDistribution);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", eventId);
        result.put("eventName", event.getName());
        result.put("grade", grade);
        result.put("gender", gender);
        result.put("heats", heatDetails);
        result.put("statistics", statistics);
        result.put("warnings", warnings);
        result.put("version", version);

        return result;
    }

    /**
     * 预览编排（不保存）
     */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(Long eventId, String grade, String gender, int lanes) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("项目不存在: " + eventId));

        List<Registration> registrations = registrationRepository
                .findApprovedByEventGradeGender(eventId, grade, gender);

        if (registrations.isEmpty()) {
            throw new RuntimeException("没有符合条件的已审核报名记录");
        }

        List<Athlete> athletes = registrations.stream()
                .map(Registration::getAthlete)
                .collect(Collectors.toList());

        int athleteCount = athletes.size();
        int heats = (int) Math.ceil((double) athleteCount / lanes);

        Map<Long, List<Athlete>> classAthletes = athletes.stream()
                .collect(Collectors.groupingBy(
                        a -> a.getClassInfo() != null ? a.getClassInfo().getId() : 0L,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<Map.Entry<Long, List<Athlete>>> sortedClasses = classAthletes.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue().size(), e1.getValue().size()))
                .collect(Collectors.toList());

        Athlete[][] matrix = new Athlete[heats][lanes];
        Map<Integer, Map<Long, Integer>> heatClassCounts = new HashMap<>();
        for (int h = 0; h < heats; h++) {
            heatClassCounts.put(h, new HashMap<>());
        }

        for (Map.Entry<Long, List<Athlete>> entry : sortedClasses) {
            Long classId = entry.getKey();
            for (Athlete athlete : entry.getValue()) {
                int bestHeat = -1;
                int minSameClass = Integer.MAX_VALUE;
                int minTotal = Integer.MAX_VALUE;

                for (int h = 0; h < heats; h++) {
                    int sameClassInHeat = heatClassCounts.get(h).getOrDefault(classId, 0);
                    int totalInHeat = heatClassCounts.get(h).values().stream()
                            .mapToInt(Integer::intValue).sum();
                    if (totalInHeat >= lanes) continue;
                    if (sameClassInHeat < minSameClass ||
                            (sameClassInHeat == minSameClass && totalInHeat < minTotal)) {
                        minSameClass = sameClassInHeat;
                        minTotal = totalInHeat;
                        bestHeat = h;
                    }
                }

                if (bestHeat >= 0) {
                    for (int l = 0; l < lanes; l++) {
                        if (matrix[bestHeat][l] == null) {
                            matrix[bestHeat][l] = athlete;
                            heatClassCounts.get(bestHeat).merge(classId, 1, Integer::sum);
                            break;
                        }
                    }
                }
            }
        }

        List<Map<String, Object>> heatDetails = new ArrayList<>();
        for (int h = 0; h < heats; h++) {
            List<Map<String, Object>> lanes_in_heat = new ArrayList<>();
            for (int l = 0; l < lanes; l++) {
                Athlete a = matrix[h][l];
                Map<String, Object> laneInfo = new LinkedHashMap<>();
                laneInfo.put("lane", l + 1);
                if (a != null) {
                    laneInfo.put("athleteId", a.getId());
                    laneInfo.put("athleteName", a.getName());
                    laneInfo.put("number", a.getNumber());
                    laneInfo.put("className", a.getClassInfo() != null
                            ? a.getClassInfo().getName() : "未知");
                } else {
                    laneInfo.put("athleteId", null);
                    laneInfo.put("athleteName", null);
                }
                lanes_in_heat.add(laneInfo);
            }
            Map<String, Object> heatInfo = new LinkedHashMap<>();
            heatInfo.put("heat", h + 1);
            heatInfo.put("lanes", lanes_in_heat);
            heatDetails.add(heatInfo);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", eventId);
        result.put("eventName", event.getName());
        result.put("grade", grade);
        result.put("gender", gender);
        result.put("heats", heatDetails);
        result.put("statistics", Map.of("totalAthletes", athleteCount, "totalHeats", heats, "lanes", lanes));

        return result;
    }

    /**
     * 获取已编排结果
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getArrangement(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("项目不存在: " + eventId));

        List<Arrangement> arrangements = arrangementRepository
                .findByEventIdOrderByHeatAscLaneAsc(eventId);

        if (arrangements.isEmpty()) {
            return Map.of(
                    "eventId", eventId,
                    "eventName", event.getName(),
                    "heats", List.of(),
                    "statistics", Map.of("totalAthletes", 0, "totalHeats", 0)
            );
        }

        Map<Integer, List<Arrangement>> byHeat = arrangements.stream()
                .collect(Collectors.groupingBy(Arrangement::getHeat, TreeMap::new, Collectors.toList()));

        Integer version = arrangements.get(0).getVersion();

        List<Map<String, Object>> heatDetails = new ArrayList<>();
        for (Map.Entry<Integer, List<Arrangement>> entry : byHeat.entrySet()) {
            List<Map<String, Object>> lanes_in_heat = new ArrayList<>();
            for (Arrangement arr : entry.getValue()) {
                Map<String, Object> laneInfo = new LinkedHashMap<>();
                laneInfo.put("lane", arr.getLane());
                laneInfo.put("athleteId", arr.getAthlete().getId());
                laneInfo.put("athleteName", arr.getAthlete().getName());
                laneInfo.put("number", arr.getAthlete().getNumber());
                laneInfo.put("className", arr.getAthlete().getClassInfo() != null
                        ? arr.getAthlete().getClassInfo().getName() : "未知");
                laneInfo.put("arrangementId", arr.getId());
                lanes_in_heat.add(laneInfo);
            }
            Map<String, Object> heatInfo = new LinkedHashMap<>();
            heatInfo.put("heat", entry.getKey());
            heatInfo.put("lanes", lanes_in_heat);
            heatDetails.add(heatInfo);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", eventId);
        result.put("eventName", event.getName());
        result.put("heats", heatDetails);
        result.put("version", version);
        result.put("statistics", Map.of(
                "totalAthletes", arrangements.size(),
                "totalHeats", byHeat.size()
        ));

        return result;
    }

    /**
     * 手动调整编排
     */
    public Map<String, Object> updateArrangement(Long eventId, List<Arrangement> arrangements) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("项目不存在: " + eventId));

        Integer maxVersion = arrangementRepository.findMaxVersionByEventId(eventId);
        int newVersion = maxVersion != null ? maxVersion + 1 : 1;

        List<Arrangement> saved = new ArrayList<>();
        for (Arrangement arr : arrangements) {
            arr.setEvent(event);
            arr.setVersion(newVersion);
            arr.setIsManual(true);
            arr.setCreatedAt(LocalDateTime.now());
            arr.setUpdatedAt(LocalDateTime.now());
            saved.add(arr);
        }

        arrangementRepository.saveAll(saved);
        log.info("手动调整编排完成: eventId={}, 共{}条, version={}",
                eventId, saved.size(), newVersion);

        return Map.of(
                "eventId", eventId,
                "count", saved.size(),
                "version", newVersion
        );
    }

    /**
     * 清空编排
     */
    public void clearArrangement(Long eventId) {
        arrangementRepository.deleteByEventId(eventId);
        log.info("清空编排: eventId={}", eventId);
    }

    /**
     * 回滚到上一版本
     */
    public Map<String, Object> rollback(Long eventId) {
        Integer maxVersion = arrangementRepository.findMaxVersionByEventId(eventId);
        if (maxVersion == null || maxVersion <= 1) {
            throw new RuntimeException("没有可回滚的版本");
        }

        arrangementRepository.deleteByEventIdAndVersion(eventId, maxVersion);

        int prevVersion = maxVersion - 1;
        List<Arrangement> prev = arrangementRepository.findByEventIdAndVersion(eventId, prevVersion);

        log.info("回滚编排: eventId={}, 从版本{}回滚到版本{}", eventId, maxVersion, prevVersion);

        return Map.of(
                "eventId", eventId,
                "rolledBackFrom", maxVersion,
                "currentVersion", prevVersion,
                "count", prev.size()
        );
    }

    /**
     * 导出编排到Excel
     */
    public void exportArrangement(Long eventId, HttpServletResponse response) {
        List<Arrangement> arrangements = arrangementRepository
                .findByEventIdOrderByHeatAscLaneAsc(eventId);

        Event event = eventRepository.findById(eventId).orElse(null);
        String eventName = event != null ? event.getName() : "未知项目";

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = eventName + "_编排表_" + LocalDateTime.now().toString().replace(":", "-") + ".xlsx";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20")
                + ";filename*=UTF-8''" + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20"));

        Map<Integer, List<Arrangement>> byHeat = arrangements.stream()
                .collect(Collectors.groupingBy(Arrangement::getHeat, TreeMap::new, Collectors.toList()));

        try (OutputStream out = response.getOutputStream()) {
            java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
            rows.add(java.util.List.of("组号","道次","运动员","号码簿","班级"));
            for (java.util.Map.Entry<Integer, java.util.List<Arrangement>> entry : byHeat.entrySet()) {
                for (Arrangement a : entry.getValue()) {
                    Athlete ath = a.getAthlete();
                    rows.add(java.util.List.of(String.valueOf(a.getHeat()), String.valueOf(a.getLane()),
                        ath.getName(), ath.getNumber() != null ? ath.getNumber() : "",
                        ath.getClassInfo() != null ? ath.getClassInfo().getName() : ""));
                }
            }
            // 转置表头：行式 → 列式
            java.util.List<java.util.List<String>> headCols = rows.get(0).stream()
                    .map(java.util.List::of).collect(java.util.stream.Collectors.toList());
            com.alibaba.excel.EasyExcel.write(out).head(headCols)
                .sheet(eventName).doWrite(rows.subList(1, rows.size()));
        } catch (IOException e) {
            log.error("导出道次表失败: eventId={}", eventId, e);
            throw new RuntimeException("导出失败: " + e.getMessage());
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 计算编排代价（用于局部优化）
     */
    private double calculateCost(Athlete[][] matrix, Map<Integer, Map<Long, Integer>> heatClassCounts,
            int h1, int h2, int l1, int l2,
            boolean banSameClassSameLane, boolean preferDiffHeat, boolean preferDiffLane) {
        double cost = 0;
        int totalHeats = matrix.length;
        int totalLanes = matrix[0].length;

        // 检查涉及的两个heat的约束
        int[] checkHeats = {h1, h2};
        for (int h : checkHeats) {
            for (int l = 0; l < totalLanes; l++) {
                Athlete a = matrix[h][l];
                if (a == null) continue;
                Long classId = a.getClassInfo() != null ? a.getClassInfo().getId() : 0L;

                // preferDiffHeat: 同班在同一heat中的惩罚
                if (preferDiffHeat) {
                    int sameClassCount = heatClassCounts.get(h).getOrDefault(classId, 0);
                    if (sameClassCount > 1) {
                        cost += (sameClassCount - 1) * 10.0;
                    }
                }

                // preferDiffLane: 同班在同一lane的惩罚
                if (preferDiffLane) {
                    for (int otherH = 0; otherH < totalHeats; otherH++) {
                        if (otherH == h) continue;
                        Athlete otherA = matrix[otherH][l];
                        if (otherA != null) {
                            Long otherClassId = otherA.getClassInfo() != null ? otherA.getClassInfo().getId() : 0L;
                            if (classId.equals(otherClassId)) {
                                cost += 5.0;
                            }
                        }
                    }
                }

                // banSameClassSameLane: 同一heat中同班在不同lane的惩罚
                if (banSameClassSameLane) {
                    for (int otherL = 0; otherL < totalLanes; otherL++) {
                        if (otherL == l) continue;
                        Athlete otherA = matrix[h][otherL];
                        if (otherA != null) {
                            Long otherClassId = otherA.getClassInfo() != null ? otherA.getClassInfo().getId() : 0L;
                            if (classId.equals(otherClassId)) {
                                cost += 20.0;
                            }
                        }
                    }
                }
            }
        }

        return cost;
    }

    /**
     * 深拷贝 heatClassCounts
     */
    private Map<Integer, Map<Long, Integer>> deepCopy(Map<Integer, Map<Long, Integer>> source) {
        Map<Integer, Map<Long, Integer>> copy = new HashMap<>();
        for (Map.Entry<Integer, Map<Long, Integer>> entry : source.entrySet()) {
            copy.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }
        return copy;
    }
}
