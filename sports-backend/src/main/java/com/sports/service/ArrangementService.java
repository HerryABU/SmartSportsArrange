package com.sports.service;

import com.sports.common.Grades;
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

/**
 * 编排服务
 *
 * <p>核心能力：</p>
 * <ul>
 *   <li><b>径赛硬约束「同一组不能同班」</b>：预赛/决赛排组时按班级分散，
 *       组数 = max(按道次的组数, 最大单班人数)，保证同一组（排）绝不出现同班；</li>
 *   <li><b>每组同一个年级</b>：编排以 (项目 × 年级 × 性别) 为最小单位分别执行；</li>
 *   <li><b>预赛淘汰「立刻计算」</b>：needHeats 的田径项目先排预赛（round=preliminary），
 *       录入预赛成绩后调用 computeQualifiers 立即按成绩取前 N 名晋级并自动排出决赛。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ArrangementService {

    private final ArrangementRepository arrangementRepository;
    private final RegistrationRepository registrationRepository;
    private final EventRepository eventRepository;
    private final SystemService systemService;
    private final WordOrderBookService wordOrderBookService;

    // 默认算法参数（可被 arrange_rule.algorithm_params 覆盖）
    private static final int OPTIMIZATION_ROUNDS = 3;
    private static final int TIMEOUT_SECONDS = 20;

    public static final String ROUND_PRELIM = "preliminary";
    public static final String ROUND_FINAL = "final";

    // ==================== Controller 适配方法 ====================

    /**
     * 执行编排。config: grade / gender / lanes / round(auto|preliminary|final) / ruleConfig
     * round 缺省 auto：已有预赛编排 → 只排晋级者进决赛；否则 → 直接决赛。
     */
    public Map<String, Object> executeArrangement(Long eventId, Map<String, Object> config) {
        String grade = (String) config.get("grade");
        String gender = (String) config.get("gender");
        int lanes = config.containsKey("lanes") ? ((Number) config.get("lanes")).intValue() : 8;

        @SuppressWarnings("unchecked")
        Map<String, Boolean> ruleConfig = (Map<String, Boolean>) config.get("ruleConfig");
        String round = config.containsKey("round") && config.get("round") != null
                ? String.valueOf(config.get("round")) : null;

        long startTime = System.currentTimeMillis();
        Map<String, Object> result = arrange(eventId, grade, gender, lanes, ruleConfig, round);
        long elapsed = System.currentTimeMillis() - startTime;
        result.put("executionTimeMs", elapsed);
        return result;
    }

    /** 预览编排（不保存） */
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

    /** 查看编排结果 */
    public Map<String, Object> viewArrangement(Long eventId) {
        return getArrangement(eventId);
    }

    /** 手动调整编排 */
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
            if (adj.containsKey("round")) {
                arr.setRound((String) adj.get("round"));
            }
            arrangementList.add(arr);
        }
        return updateArrangement(eventId, arrangementList);
    }

    /** 批量编排 */
    public Map<String, Object> batchArrange(List<Long> eventIds) {
        List<Map<String, Object>> results = new ArrayList<>();
        int success = 0, failed = 0;

        for (Long eventId : eventIds) {
            try {
                Event event = eventRepository.findById(eventId)
                        .orElseThrow(() -> new RuntimeException("项目不存在: " + eventId));

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
                    try {
                        arrange(eventId, grade, gender, lanes, null, null);
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

    /** 导出道次表 */
    public void exportLaneSheet(Long eventId, HttpServletResponse response) {
        exportArrangement(eventId, response);
    }

    // ==================== 预赛淘汰 ====================

    /**
     * 生成预赛编排（round=preliminary）：全体已报名者按「同组不同班 + 同年级同性别」分组。
     * 适用于 needHeats=true 且需要预赛淘汰的田径项目。
     */
    public Map<String, Object> generatePreliminary(Long eventId, String grade, String gender) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("项目不存在: " + eventId));
        int lanes = resolveLanes(event);
        Map<String, Object> result = arrange(eventId, grade, gender, lanes, null, ROUND_PRELIM);
        // 若开启「自动生成秩序册」：预赛编排完成后自动生成 Word 秩序册并落盘
        try {
            if (systemService.isOrderBookAutoGenerate()) {
                wordOrderBookService.generateToDisk();
                log.info("预赛编排后已自动生成秩序册(Word): eventId={}, grade={}", eventId, grade);
            }
        } catch (Exception ex) {
            // 自动生成失败不影响预赛编排主流程
            log.warn("预赛后自动生成秩序册失败（已忽略）: {}", ex.getMessage());
        }
        return result;
    }

    /**
     * 录入预赛成绩（写入编排行的 prelim 字段，不打成绩表）。
     * items: [{athleteId, time}]，time 形如 "12.34" / "1:02.5"。
     */
    public Map<String, Object> savePrelimResults(Long eventId, String grade, String gender,
                                                  List<Map<String, Object>> items) {
        List<Arrangement> prelims = arrangementRepository
                .findByEventRoundGradeGender(eventId, ROUND_PRELIM, grade, gender);
        if (prelims.isEmpty()) {
            throw new RuntimeException("该项目尚未生成预赛编排，请先执行「生成预赛」");
        }

        Map<Long, Arrangement> byAthlete = prelims.stream()
                .collect(Collectors.toMap(a -> a.getAthlete().getId(), a -> a, (x, y) -> x));

        List<String> errors = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Long athleteId = item.get("athleteId") instanceof Number n
                    ? n.longValue() : Long.parseLong(String.valueOf(item.get("athleteId")));
            String time = String.valueOf(item.get("time")).trim();
            Arrangement arr = byAthlete.get(athleteId);
            if (arr == null) {
                errors.add("运动员ID=" + athleteId + " 不在预赛名单中");
                continue;
            }
            arr.setPrelimTime(time);
            arr.setPrelimTimeSeconds(parseTime(time));
            arr.setUpdatedAt(LocalDateTime.now());
            arrangementRepository.save(arr);
        }

        // 组内名次（heatRank 语义：同组按成绩排）
        Map<Integer, List<Arrangement>> byHeat = prelims.stream()
                .filter(a -> a.getPrelimTimeSeconds() != null)
                .collect(Collectors.groupingBy(Arrangement::getHeat, TreeMap::new, Collectors.toList()));
        for (Map.Entry<Integer, List<Arrangement>> e : byHeat.entrySet()) {
            List<Arrangement> sorted = e.getValue().stream()
                    .sorted(Comparator.comparing(Arrangement::getPrelimTimeSeconds,
                            Comparator.nullsLast(Double::compareTo)))
                    .collect(Collectors.toList());
            int r = 1;
            for (Arrangement a : sorted) {
                a.setPrelimRank(r++);
                arrangementRepository.save(a);
            }
        }

        log.info("保存预赛成绩: eventId={}, grade={}, gender={}, {}条, 失败{}条",
                eventId, grade, gender, items.size() - errors.size(), errors.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", items.size());
        result.put("saved", items.size() - errors.size());
        result.put("errors", errors);
        return result;
    }

    /**
     * 预赛淘汰「立刻计算」：按预赛成绩全场取前 advanceCount 名晋级，
     * 标记 qualified/prelimRank 后自动生成决赛编排（round=final）。
     */
    public Map<String, Object> computeQualifiers(Long eventId, String grade, String gender, Integer advanceCount) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("项目不存在: " + eventId));

        List<Arrangement> prelims = arrangementRepository
                .findByEventRoundGradeGender(eventId, ROUND_PRELIM, grade, gender);
        if (prelims.isEmpty()) {
            throw new RuntimeException("该项目没有预赛编排，无需淘汰计算");
        }

        int quota = advanceCount != null && advanceCount > 0
                ? advanceCount : (event.getAdvanceCount() != null ? event.getAdvanceCount() : 8);

        List<Arrangement> timed = prelims.stream()
                .filter(a -> a.getPrelimTimeSeconds() != null)
                .sorted(Comparator.comparing(Arrangement::getPrelimTimeSeconds))
                .collect(Collectors.toList());

        List<Arrangement> untimed = prelims.stream()
                .filter(a -> a.getPrelimTimeSeconds() == null)
                .collect(Collectors.toList());

        List<Arrangement> qualifiers = new ArrayList<>();
        // 全场名次：有成绩的按成绩排序，未录成绩的排最后（成绩缺失默认淘汰，除非名额富余）
        int globalRank = 1;
        List<Arrangement> ordered = new ArrayList<>(timed);
        ordered.addAll(untimed);

        for (Arrangement a : ordered) {
            a.setQualified(false);
            if (globalRank <= quota) {
                a.setQualified(true);
                qualifiers.add(a);
            }
            if (a.getPrelimRank() == null) {
                a.setPrelimRank(globalRank);
            }
            arrangementRepository.save(a);
            globalRank++;
        }

        // 立刻生成决赛编排（仅晋级者）
        List<Athlete> finalPool = qualifiers.stream()
                .map(Arrangement::getAthlete)
                .collect(Collectors.toList());

        // 删掉旧的决赛编排（避免重复），保留预赛
        arrangementRepository.deleteByEventRoundGradeGender(eventId, ROUND_FINAL, grade, gender);

        Map<String, Object> finalResult = arrangePool(event, finalPool, grade, gender,
                resolveLanes(event), null, ROUND_FINAL, qualifiers);

        log.info("预赛淘汰计算完成: eventId={}, grade={}, gender={}, 报名{}人, 晋级{}人 (取前{})",
                eventId, grade, gender, prelims.size(), qualifiers.size(), quota);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", eventId);
        result.put("eventName", event.getName());
        result.put("grade", grade);
        result.put("gender", gender);
        result.put("participants", prelims.size());
        result.put("advanceCount", quota);
        result.put("qualifierCount", qualifiers.size());
        result.put("qualifiers", qualifierView(qualifiers));
        result.put("final", finalResult);
        return result;
    }

    /** 查看晋级名单 */
    public List<Map<String, Object>> viewQualifiers(Long eventId, String grade, String gender) {
        List<Arrangement> prelims = arrangementRepository
                .findByEventRoundGradeGender(eventId, ROUND_PRELIM, grade, gender);
        return prelims.stream()
                .filter(a -> Boolean.TRUE.equals(a.getQualified()))
                .sorted(Comparator.comparingInt(a -> a.getPrelimRank() != null ? a.getPrelimRank() : Integer.MAX_VALUE))
                .map(this::arrangementBrief)
                .collect(Collectors.toList());
    }

    // ==================== 核心编排算法 ====================

    /** 历史兼容入口：自动判断赛次 */
    public Map<String, Object> arrange(Long eventId, String grade, String gender,
                                        int lanes, Map<String, Boolean> ruleConfig) {
        return arrange(eventId, grade, gender, lanes, ruleConfig, null);
    }

    /**
     * 编排入口。
     *
     * @param round null/auto → 该项目已有预赛编排则只排晋级者进决赛，否则直接决赛；
     *              preliminary → 排预赛（全体报名者）；final → 排决赛（仅晋级者或全体）。
     */
    public Map<String, Object> arrange(Long eventId, String grade, String gender,
                                        int lanes, Map<String, Boolean> ruleConfig, String round) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("项目不存在: " + eventId));

        List<Registration> registrations = registrationRepository
                .findApprovedByEventGradeGender(eventId, grade, Grades.shortName(grade), gender);
        if (registrations.isEmpty()) {
            throw new RuntimeException("没有符合条件的已审核报名记录");
        }
        List<Athlete> athletes = registrations.stream()
                .map(Registration::getAthlete)
                .collect(Collectors.toList());

        boolean hasPrelim = arrangementRepository.countPreliminaryByEventId(eventId) > 0;

        String targetRound;
        List<Athlete> pool = athletes;
        List<Arrangement> qualifierRefs = null;
        if (ROUND_PRELIM.equals(round)) {
            targetRound = ROUND_PRELIM;
        } else if (ROUND_FINAL.equals(round)) {
            targetRound = ROUND_FINAL;
            // 明确指定决赛：若已有预赛则只用晋级者
            if (hasPrelim) {
                List<Arrangement> qualified = arrangementRepository
                        .findByEventRoundGradeGender(eventId, ROUND_PRELIM, grade, gender).stream()
                        .filter(a -> Boolean.TRUE.equals(a.getQualified()))
                        .collect(Collectors.toList());
                if (!qualified.isEmpty()) {
                    pool = qualified.stream().map(Arrangement::getAthlete).collect(Collectors.toList());
                    qualifierRefs = qualified;
                }
            }
        } else {
            // auto：已有预赛 → 直接进入决赛编排（仅晋级者）；否则当作直接决赛
            targetRound = ROUND_FINAL;
            if (hasPrelim) {
                List<Arrangement> qualified = arrangementRepository
                        .findByEventRoundGradeGender(eventId, ROUND_PRELIM, grade, gender).stream()
                        .filter(a -> Boolean.TRUE.equals(a.getQualified()))
                        .collect(Collectors.toList());
                if (!qualified.isEmpty()) {
                    pool = qualified.stream().map(Arrangement::getAthlete).collect(Collectors.toList());
                    qualifierRefs = qualified;
                }
            }
        }

        // 重新编排该切片前，先清除该赛次已存在的编排，避免版本堆积造成重复
        arrangementRepository.deleteByEventRoundGradeGender(eventId, targetRound, grade, gender);

        return arrangePool(event, pool, grade, gender, lanes, ruleConfig, targetRound, qualifierRefs);
    }

    /**
     * 把运动员池排入 (round) 的组与道次，保存并返回视图结果。
     * 硬约束：同一组不能同班；组数 = max(按道次所需组数, 最大单班人数)。
     */
    private Map<String, Object> arrangePool(Event event, List<Athlete> pool,
                                            String grade, String gender, int lanes,
                                            Map<String, Boolean> ruleConfig, String round,
                                            List<Arrangement> qualifierRefs) {
        log.info("编排: eventId={}, grade={}, gender={}, lanes={}, round={}, pool={}",
                event.getId(), grade, gender, lanes, round, pool.size());

        int athleteCount = pool.size();
        if (athleteCount == 0) {
            return Map.of("eventId", event.getId(), "eventName", event.getName(),
                    "grade", grade, "gender", gender, "round", round,
                    "heats", List.of(), "statistics", Map.of("totalAthletes", 0, "totalHeats", 0));
        }

        Placement placement = allocate(pool, lanes);
        int heats = placement.heats;

        // 版本号：取该赛次当前最大版本 + 1
        Integer maxVersion = arrangementRepository.findMaxVersionByEventId(event.getId());
        int version = maxVersion != null ? maxVersion + 1 : 1;

        Map<Long, Arrangement> qualifierByAthlete = new HashMap<>();
        if (qualifierRefs != null) {
            for (Arrangement q : qualifierRefs) {
                qualifierByAthlete.put(q.getAthlete().getId(), q);
            }
        }

        List<Arrangement> arrangements = new ArrayList<>();
        for (int h = 0; h < heats; h++) {
            for (Arrangement arr : placement.heatsMatrix.get(h)) {
                Athlete athlete = arr.getAthlete();
                Arrangement qualifier = qualifierByAthlete.get(athlete.getId());
                arrangements.add(Arrangement.builder()
                        .event(event)
                        .athlete(athlete)
                        .grade(grade)
                        .gender(gender)
                        .heat(h + 1)
                        .lane(arr.getLane())
                        .round(round)
                        .qualified(qualifier != null && Boolean.TRUE.equals(qualifier.getQualified()))
                        .prelimRank(qualifier != null ? qualifier.getPrelimRank() : null)
                        .prelimTime(qualifier != null ? qualifier.getPrelimTime() : null)
                        .prelimTimeSeconds(qualifier != null ? qualifier.getPrelimTimeSeconds() : null)
                        .version(version)
                        .isManual(false)
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build());
            }
        }

        arrangementRepository.saveAll(arrangements);
        log.info("编排保存完成: eventId={}, round={}, 共{}组{}名, version={}",
                event.getId(), round, heats, arrangements.size(), version);

        // 保存后统一生成视图（带数据库回填的 id）
        List<Map<String, Object>> heatDetails = new ArrayList<>();
        Map<Integer, List<Arrangement>> byHeat = arrangements.stream()
                .collect(Collectors.groupingBy(Arrangement::getHeat, TreeMap::new, Collectors.toList()));
        for (Map.Entry<Integer, List<Arrangement>> e : byHeat.entrySet()) {
            List<Map<String, Object>> lanesInHeat = new ArrayList<>();
            List<Arrangement> sortedInHeat = e.getValue().stream()
                    .sorted(Comparator.comparing(Arrangement::getLane, Comparator.nullsLast(Integer::compareTo)))
                    .collect(Collectors.toList());
            for (Arrangement saved : sortedInHeat) {
                lanesInHeat.add(laneInfo(saved));
            }
            heatDetails.add(laneBrief(e.getKey(), lanesInHeat));
        }

        log.info("编排保存完成: eventId={}, round={}, 共{}组{}名, version={}",
                event.getId(), round, heats, arrangements.size(), version);

        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("totalAthletes", athleteCount);
        statistics.put("totalHeats", heats);
        statistics.put("lanes", lanes);
        statistics.put("version", version);
        statistics.put("avgPerHeat", heats > 0 ? Math.round(athleteCount * 10.0 / heats) / 10.0 : 0);
        statistics.put("maxPerHeat", heats > 0 ? placement.heatsMatrix.stream()
                .mapToInt(List::size).max().orElse(0) : 0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", event.getId());
        result.put("eventName", event.getName());
        result.put("grade", grade);
        result.put("gender", gender);
        result.put("round", round);
        result.put("heats", heatDetails);
        result.put("statistics", statistics);
        result.put("warnings", placement.warnings);
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
                .findApprovedByEventGradeGender(eventId, grade, Grades.shortName(grade), gender);
        if (registrations.isEmpty()) {
            throw new RuntimeException("没有符合条件的已审核报名记录");
        }

        List<Athlete> athletes = registrations.stream()
                .map(Registration::getAthlete)
                .collect(Collectors.toList());

        Placement placement = allocate(athletes, lanes);

        List<Map<String, Object>> heatDetails = new ArrayList<>();
        for (int h = 0; h < placement.heats; h++) {
            List<Map<String, Object>> lanesInHeat = new ArrayList<>();
            List<Arrangement> sortedInHeat = placement.heatsMatrix.get(h).stream()
                    .sorted(Comparator.comparing(Arrangement::getLane, Comparator.nullsLast(Integer::compareTo)))
                    .collect(Collectors.toList());
            for (Arrangement a : sortedInHeat) {
                Athlete ath = a.getAthlete();
                Map<String, Object> laneInfo = new LinkedHashMap<>();
                laneInfo.put("lane", a.getLane());
                laneInfo.put("athleteId", ath.getId());
                laneInfo.put("athleteName", ath.getName());
                laneInfo.put("number", ath.getNumber());
                laneInfo.put("className", ath.getClassInfo() != null
                        ? ath.getClassInfo().getName() : "未知");
                lanesInHeat.add(laneInfo);
            }
            heatDetails.add(laneBrief(h + 1, lanesInHeat));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", eventId);
        result.put("eventName", event.getName());
        result.put("grade", grade);
        result.put("gender", gender);
        result.put("heats", heatDetails);
        result.put("warnings", placement.warnings);
        result.put("statistics", Map.of(
                "totalAthletes", athletes.size(),
                "totalHeats", placement.heats,
                "lanes", lanes));
        return result;
    }

    /**
     * 获取已编排结果（按赛次聚合；返回 rounds 供前端按预赛/决赛查看）
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getArrangement(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("项目不存在: " + eventId));

        List<Arrangement> arrangements = arrangementRepository.findByEventId(eventId);
        if (arrangements.isEmpty()) {
            return Map.of("eventId", eventId, "eventName", event.getName(),
                    "heats", List.of(), "statistics", Map.of("totalAthletes", 0, "totalHeats", 0),
                    "rounds", List.of());
        }

        // 按赛次分组（历史 NULL 行视为 final）
        Map<String, List<Arrangement>> byRound = arrangements.stream()
                .collect(Collectors.groupingBy(a ->
                        a.getRound() == null || a.getRound().isBlank() ? ROUND_FINAL : a.getRound(),
                        LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> rounds = new ArrayList<>();

        for (Map.Entry<String, List<Arrangement>> entry : byRound.entrySet()) {
            Map<Integer, List<Arrangement>> byHeat = entry.getValue().stream()
                    .sorted(Comparator.comparing(Arrangement::getHeat).thenComparing(Arrangement::getLane))
                    .collect(Collectors.groupingBy(Arrangement::getHeat, TreeMap::new, Collectors.toList()));

            List<Map<String, Object>> heatDetails = new ArrayList<>();
            int total = 0;
            for (Map.Entry<Integer, List<Arrangement>> he : byHeat.entrySet()) {
                List<Map<String, Object>> lanesInHeat = new ArrayList<>();
                for (Arrangement arr : he.getValue()) {
                    lanesInHeat.add(laneInfo(arr));
                    total++;
                }
                heatDetails.add(laneBrief(he.getKey(), lanesInHeat));
            }

            Integer version = entry.getValue().stream()
                    .map(Arrangement::getVersion)
                    .filter(Objects::nonNull)
                    .max(Integer::compareTo)
                    .orElse(1);

            Map<String, Object> roundResult = new LinkedHashMap<>();
            roundResult.put("round", entry.getKey());
            roundResult.put("heats", heatDetails);
            roundResult.put("version", version);
            roundResult.put("statistics", Map.of("totalAthletes", total, "totalHeats", byHeat.size()));
            rounds.add(roundResult);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", eventId);
        result.put("eventName", event.getName());
        result.put("rounds", rounds);
        // 顶层 heats 用于「成绩录入」等按组次落成绩的场景：
        //   单赛次 → 平铺该赛次；多赛次（预赛+决赛）→ 平铺决赛赛次（成绩表只录决赛成绩），
        //   尚无决赛而只有预赛时退化为最后一个赛次（由编排页走预赛成绩流）。
        Map<String, Object> topRound = null;
        if (rounds.size() == 1) {
            topRound = rounds.get(0);
        } else {
            topRound = rounds.stream()
                    .filter(r -> ROUND_FINAL.equals(r.get("round")))
                    .findFirst()
                    .orElse(rounds.get(rounds.size() - 1));
        }
        if (topRound != null) {
            result.put("heats", topRound.get("heats"));
            result.put("version", topRound.get("version"));
            result.put("statistics", topRound.get("statistics"));
            result.put("activeRound", topRound.get("round"));
        } else {
            result.put("heats", List.of());
            result.put("statistics", Map.of("totalAthletes", 0, "totalHeats", 0));
            result.put("activeRound", null);
        }
        return result;
    }

    /**
     * 手动调整编排（替换指定赛次全部）
     */
    public Map<String, Object> updateArrangement(Long eventId, List<Arrangement> arrangements) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("项目不存在: " + eventId));

        String round = arrangements.stream()
                .map(Arrangement::getRound)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(ROUND_FINAL);

        // 先清掉该赛次旧编排（手动保存 = 全量替换）
        arrangementRepository.deleteByEventIdAndRound(eventId, round);

        List<Arrangement> saved = new ArrayList<>();
        for (Arrangement arr : arrangements) {
            arr.setEvent(event);
            arr.setRound(round);
            arr.setIsManual(true);
            arr.setCreatedAt(LocalDateTime.now());
            arr.setUpdatedAt(LocalDateTime.now());
            saved.add(arrangementRepository.save(arr));
        }

        log.info("手动调整编排完成: eventId={}, round={}, 共{}条", eventId, round, saved.size());

        return Map.of("eventId", eventId, "count", saved.size(), "round", round);
    }

    /**
     * 清空编排（全部赛次）
     */
    public void clearArrangement(Long eventId) {
        arrangementRepository.deleteByEventId(eventId);
        log.info("清空编排: eventId={}", eventId);
    }

    /**
     * 回滚到上一版本（删除当前赛次全部编排 = 重置该赛次）
     */
    public Map<String, Object> rollback(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("项目不存在: " + eventId));

        List<Arrangement> all = arrangementRepository.findByEventId(eventId);
        if (all.isEmpty()) {
            throw new RuntimeException("没有可回滚的编排");
        }

        // 删除最新版本，保留更早版本（兼容旧行为）
        Integer maxVersion = arrangementRepository.findMaxVersionByEventId(eventId);
        if (maxVersion == null) {
            throw new RuntimeException("没有可回滚的版本");
        }
        arrangementRepository.deleteByEventIdAndVersion(eventId, maxVersion);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", eventId);
        result.put("rolledBackFrom", maxVersion);
        List<Arrangement> remaining = arrangementRepository.findByEventId(eventId);
        result.put("remaining", remaining.stream()
                .map(Arrangement::getVersion).distinct().count());
        return result;
    }

    /**
     * 导出编排到Excel（按赛次导出，默认 final）
     */
    public void exportArrangement(Long eventId, HttpServletResponse response) {
        List<Arrangement> arrangements = arrangementRepository.findByEventId(eventId);
        Event event = eventRepository.findById(eventId).orElse(null);
        String eventName = event != null ? event.getName() : "未知项目";

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = eventName + "_编排表_" + LocalDateTime.now().toString().replace(":", "-") + ".xlsx";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20")
                + ";filename*=UTF-8''" + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20"));

        try (OutputStream out = response.getOutputStream()) {
            java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
            rows.add(java.util.List.of("赛次", "组号", "道次", "运动员", "号码簿", "班级", "预赛成绩", "晋级"));
            for (Arrangement a : arrangements) {
                Athlete ath = a.getAthlete();
                rows.add(java.util.List.of(
                        roundLabel(a.getRound()),
                        String.valueOf(a.getHeat()),
                        String.valueOf(a.getLane()),
                        ath.getName(),
                        ath.getNumber() != null ? ath.getNumber() : "",
                        ath.getClassInfo() != null ? ath.getClassInfo().getName() : "",
                        a.getPrelimTime() != null ? a.getPrelimTime() : "",
                        Boolean.TRUE.equals(a.getQualified()) ? "✓" : ""));
            }
            java.util.List<java.util.List<String>> headCols = rows.get(0).stream()
                    .map(java.util.List::of).collect(java.util.stream.Collectors.toList());
            com.alibaba.excel.EasyExcel.write(out).head(headCols)
                .sheet(eventName).doWrite(rows.subList(1, rows.size()));
        } catch (IOException e) {
            log.error("导出道次表失败: eventId={}", eventId, e);
            throw new RuntimeException("导出失败: " + e.getMessage());
        }
    }

    // ==================== 同组不同班硬约束分配 ====================

    /**
     * 核心分配：同一组不能同班。
     * 组数 = max(ceil(人数/道数), 最大单班人数)，保证每个班的运动员可分到不同组；
     * 贪心按「当前组人数最少」选择，命中同班已占用的组则跳过。
     */
    private Placement allocate(List<Athlete> athletes, int lanes) {
        int n = athletes.size();
        List<String> warnings = new ArrayList<>();

        // 按班级分组（班级缺失归为 0）
        Map<Long, List<Athlete>> byClass = athletes.stream()
                .collect(Collectors.groupingBy(
                        a -> a.getClassInfo() != null ? a.getClassInfo().getId() : 0L,
                        LinkedHashMap::new,
                        Collectors.toList()));

        int maxClassSize = byClass.values().stream().mapToInt(List::size).max().orElse(0);
        int minHeats = (int) Math.ceil((double) n / Math.max(1, lanes));
        int heats = Math.max(minHeats, maxClassSize);
        // 组数上限保护：若道次很少而班级很大，组数可能过大，放宽「同组不同班」并警告
        if (heats > Math.max(12, minHeats * 2)) {
            warnings.add(String.format("班级人数差异过大（最大班%d人），为满足「同组不同班」需排%d组，建议减少该班报名人数",
                    maxClassSize, heats));
        }
        if (heats <= 0) heats = 1;

        int[] occupancy = new int[heats];
        // 记录每个班已在哪些组出现
        Map<Long, boolean[]> classHeatFlags = new HashMap<>();
        for (Long cid : byClass.keySet()) {
            classHeatFlags.put(cid, new boolean[heats]);
        }
        // 记录每个班在各道次的使用次数（preferDiffLane 软约束）
        Map<Long, int[]> classLaneUse = new HashMap<>();
        for (Long cid : byClass.keySet()) {
            classLaneUse.put(cid, new int[lanes]);
        }

        @SuppressWarnings("unchecked")
        List<Arrangement>[] matrix = new List[heats];
        for (int h = 0; h < heats; h++) matrix[h] = new ArrayList<>();

        List<Map.Entry<Long, List<Athlete>>> sortedClasses = byClass.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue().size(), e1.getValue().size()))
                .collect(Collectors.toList());

        // 阶段一：分班入组（同组不同班）
        for (Map.Entry<Long, List<Athlete>> entry : sortedClasses) {
            Long classId = entry.getKey();
            boolean[] usedHeat = classHeatFlags.get(classId);
            List<Athlete> members = entry.getValue();

            for (Athlete athlete : members) {
                int bestHeat = -1;
                int minOcc = Integer.MAX_VALUE;
                for (int h = 0; h < heats; h++) {
                    if (usedHeat[h]) continue;          // 硬约束：同班同组禁止
                    if (occupancy[h] >= lanes) continue;
                    if (occupancy[h] < minOcc) {
                        minOcc = occupancy[h];
                        bestHeat = h;
                    }
                }
                if (bestHeat < 0) {
                    // 理论不可达（heats>=maxClassSize）；保险兜底：挑人最少的组
                    for (int h = 0; h < heats; h++) {
                        if (occupancy[h] < lanes && (bestHeat < 0 || occupancy[h] < occupancy[bestHeat])) {
                            bestHeat = h;
                        }
                    }
                    if (bestHeat >= 0) {
                        warnings.add(String.format("无法满足「同组不同班」：%s 有同班同学挤在同一组", athlete.getName()));
                    } else {
                        warnings.add("无法为运动员 " + athlete.getName() + " 分配合适的组");
                        continue;
                    }
                }
                Arrangement arr = new Arrangement();
                arr.setAthlete(athlete);
                matrix[bestHeat].add(arr);
                occupancy[bestHeat]++;
                usedHeat[bestHeat] = true;
            }
        }

        // 阶段二：组内分道（软约束：同班在不同组的道次尽量错开）
        for (int h = 0; h < heats; h++) {
            List<Arrangement> inHeat = matrix[h];
            if (inHeat.isEmpty()) continue;

            // 依道次顺序逐个挑选「当前班在该道次占用最少」的选手落位
            int filled = inHeat.size();
            for (int l = 0; l < lanes && filled > 0; l++) {
                // 为当前道次挑一个选手：优先选班-道占用最少的
                Arrangement best = null;
                int bestScore = Integer.MAX_VALUE;
                for (Arrangement cand : inHeat) {
                    if (cand.getLane() != null) continue;
                    Long cid = cand.getAthlete().getClassInfo() != null
                            ? cand.getAthlete().getClassInfo().getId() : 0L;
                    int[] laneUse = classLaneUse.computeIfAbsent(cid, k -> new int[lanes]);
                    if (laneUse[l] < bestScore) {
                        bestScore = laneUse[l];
                        best = cand;
                    }
                }
                if (best != null) {
                    best.setLane(l + 1);
                    Long cid = best.getAthlete().getClassInfo() != null
                            ? best.getAthlete().getClassInfo().getId() : 0L;
                    classLaneUse.get(cid)[l]++;
                    filled--;
                }
            }
        }

        Placement placement = new Placement();
        placement.heats = heats;
        placement.warnings = warnings;
        placement.heatsMatrix = new ArrayList<>(heats);
        for (int h = 0; h < heats; h++) {
            placement.heatsMatrix.add(matrix[h]);
        }
        return placement;
    }

    // ==================== 辅助方法 ====================

    private int resolveLanes(Event e) {
        if (Boolean.FALSE.equals(e.getTrack())) return 1; // 田赛按单人分
        Integer lc = e.getLaneCount();
        if (lc != null && lc > 0) return lc;
        return e.getDefaultLanes() != null ? e.getDefaultLanes() : 8;
    }

    private List<Map<String, Object>> qualifierView(List<Arrangement> qualifiers) {
        return qualifiers.stream()
                .sorted(Comparator.comparingInt(a -> a.getPrelimRank() != null ? a.getPrelimRank() : Integer.MAX_VALUE))
                .map(this::arrangementBrief)
                .collect(Collectors.toList());
    }

    private Map<String, Object> arrangementBrief(Arrangement a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("athleteId", a.getAthlete().getId());
        m.put("athleteName", a.getAthlete().getName());
        m.put("number", a.getAthlete().getNumber());
        m.put("className", a.getAthlete().getClassInfo() != null
                ? a.getAthlete().getClassInfo().getName() : "未知");
        m.put("grade", a.getGrade());
        m.put("gender", a.getGender());
        m.put("heat", a.getHeat());
        m.put("prelimRank", a.getPrelimRank());
        m.put("prelimTime", a.getPrelimTime());
        m.put("qualified", Boolean.TRUE.equals(a.getQualified()));
        return m;
    }

    private Map<String, Object> laneInfo(Arrangement arr) {
        Map<String, Object> laneInfo = new LinkedHashMap<>();
        laneInfo.put("lane", arr.getLane());
        laneInfo.put("athleteId", arr.getAthlete().getId());
        laneInfo.put("athleteName", arr.getAthlete().getName());
        laneInfo.put("number", arr.getAthlete().getNumber());
        laneInfo.put("className", arr.getAthlete().getClassInfo() != null
                ? arr.getAthlete().getClassInfo().getName() : "未知");
        laneInfo.put("arrangementId", arr.getId());
        laneInfo.put("qualified", Boolean.TRUE.equals(arr.getQualified()));
        laneInfo.put("prelimRank", arr.getPrelimRank());
        laneInfo.put("prelimTime", arr.getPrelimTime());
        return laneInfo;
    }

    private Map<String, Object> laneBrief(int heat, List<Map<String, Object>> lanesInHeat) {
        Map<String, Object> heatInfo = new LinkedHashMap<>();
        heatInfo.put("heat", heat);
        heatInfo.put("lanes", lanesInHeat);
        return heatInfo;
    }

    private static String roundLabel(String round) {
        return ROUND_PRELIM.equals(round) ? "预赛" : "决赛";
    }

    /** 解析时间字符串为秒；支持 "12.34"、"1:23.45"、"1:02:03.45" */
    private static Double parseTime(String rawTime) {
        if (rawTime == null || rawTime.isBlank()) return null;
        try {
            String t = rawTime.trim();
            if (t.contains(":")) {
                String[] p = t.split(":");
                if (p.length == 2) return Integer.parseInt(p[0]) * 60.0 + Double.parseDouble(p[1]);
                if (p.length == 3) return Integer.parseInt(p[0]) * 3600.0
                        + Integer.parseInt(p[1]) * 60.0 + Double.parseDouble(p[2]);
            }
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 分配结果 */
    private static class Placement {
        int heats;
        List<List<Arrangement>> heatsMatrix;
        List<String> warnings = new ArrayList<>();
    }
}
