package com.sports.service;

import com.sports.entity.*;
import com.sports.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ResultService {

    private final ResultRepository resultRepository;
    private final ArrangementRepository arrangementRepository;
    private final EventRepository eventRepository;
    private final SystemConfigRepository systemConfigRepository;
    private final ExcelService excelService;
    private final SystemService systemService;

    /**
     * 录入成绩
     */
    public List<Result> enterResults(Long eventId, Integer heat, List<ResultInput> inputs) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("项目不存在: " + eventId));

        List<Result> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (ResultInput input : inputs) {
            try {
                // 验证运动员存在且已编排
                Arrangement arrangement = arrangementRepository
                        .findByEventIdAndAthleteId(eventId, input.getAthleteId())
                        .orElse(null);

                if (arrangement == null) {
                    errors.add("运动员ID=" + input.getAthleteId() + " 未编排到此项目");
                    continue;
                }

                // 检查是否已有成绩
                if (resultRepository.existsByEventIdAndAthleteId(eventId, input.getAthleteId())) {
                    errors.add("运动员ID=" + input.getAthleteId() + " 已有成绩记录");
                    continue;
                }

                // R-2：DNF/DNS/DSQ 等非完赛标记——命中则标记为非 valid 状态而非静默当有效成绩。
                String nonFinish = normalizeNonFinishStatus(input.getRawTime());
                Double timeSeconds = parseTime(input.getRawTime());

                Result result = Result.builder()
                        .event(event)
                        .athlete(arrangement.getAthlete())
                        .heat(heat != null ? heat : arrangement.getHeat())
                        .lane(arrangement.getLane())
                        .rawTime(input.getRawTime())
                        .timeSeconds(nonFinish != null ? null : timeSeconds)
                        .windSpeed(input.getWindSpeed())
                        .status(nonFinish != null ? nonFinish : "valid")
                        .remark(input.getRemark())
                        .enteredAt(LocalDateTime.now())
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();

                results.add(resultRepository.save(result));
            } catch (Exception e) {
                errors.add("运动员ID=" + input.getAthleteId() + ": " + e.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            log.warn("录入成绩部分失败: {}", String.join("; ", errors));
        }

        log.info("录入成绩完成: eventId={}, heat={}, 成功{}条, 失败{}条",
                eventId, heat, results.size(), errors.size());
        return results;
    }

    /**
     * 计算排名（读取 scoring_rule 配置，支持并列处理/破纪录/参与分/接力加倍）
     */
    public List<Result> calculateRanking(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("项目不存在: " + eventId));

        // 获取所有有效成绩排序：径赛按时间升序（小者优）；田赛（track=false）按距离/高度降序（大者优）
        boolean higherBetter = Boolean.FALSE.equals(event.getTrack());
        Comparator<Result> byResult = higherBetter
                ? Comparator.comparing(Result::getTimeSeconds, Comparator.nullsLast(Comparator.reverseOrder()))
                : Comparator.comparing(Result::getTimeSeconds, Comparator.nullsLast(Double::compareTo));
        List<Result> validResults = resultRepository.findValidByEventId(eventId).stream()
                .filter(r -> r.getTimeSeconds() != null)
                .sorted(byResult)
                .collect(Collectors.toList());

        if (validResults.isEmpty()) {
            log.warn("没有有效成绩可供排名: eventId={}", eventId);
            return List.of();
        }

        // 读取积分规则配置（完全自定义）
        Map<String, Object> rule = systemService.getScoringRule();
        Map<Integer, Double> scoringTable = parseRankScores(rule.get("rank_scores"));
        boolean sequential = "sequential".equals(String.valueOf(rule.getOrDefault("tie_handling", "same_rank")));
        boolean recordBonusEnabled = boolVal(rule.get("record_bonus_enabled"), false);
        int recordBonus = intVal(rule.get("record_bonus"), 10);
        boolean participationEnabled = boolVal(rule.get("participation_score_enabled"), false);
        int participationScore = intVal(rule.get("participation_score"), 1);
        double relayMultiplier = doubleVal(rule.get("relay_multiplier"), 2.0);
        boolean isRelay = "接力".equals(event.getCategory())
                || (event.getName() != null && event.getName().contains("接力"));

        // Bug6 修复：排名按「项目+年级」分组——各年级独立排序、独立名次、独立得分
        Map<String, List<Result>> byGrade = new LinkedHashMap<>();
        for (Result r : validResults) {
            byGrade.computeIfAbsent(r.getAthlete().getGrade() == null ? "未知组别" : r.getAthlete().getGrade(),
                    g -> new ArrayList<>()).add(r);
        }

        for (Map.Entry<String, List<Result>> gentry : byGrade.entrySet()) {
            List<Result> gradeResults = gentry.getValue(); // 已按成绩排好序
            int rank = 1;
            int i = 0;
            int n = gradeResults.size();
            while (i < n) {
                int j = i;
                while (j + 1 < n
                        && Math.abs(gradeResults.get(j + 1).getTimeSeconds()
                                - gradeResults.get(i).getTimeSeconds()) < 0.001) {
                    j++;
                }
                int groupSize = j - i + 1;
                for (int k = i; k <= j; k++) {
                    Result result = gradeResults.get(k);
                    result.setTotalRank(rank);

                    double score = scoringTable.getOrDefault(rank, 0.0);
                    result.setScore(score);

                    // 破纪录加分
                    if (recordBonusEnabled && event.getRecord() != null) {
                        try {
                            double recordTime = parseTimeToSeconds(event.getRecord());
                            if (result.getTimeSeconds() < recordTime) {
                                result.setIsRecord(true);
                                result.setScore(result.getScore() + recordBonus);
                            }
                        } catch (NumberFormatException ignored) {
                            // 记录格式无法解析，跳过
                        }
                    }

                    // 参与分（未进入积分名次者给基础分）
                    if (participationEnabled && result.getScore() <= 0) {
                        result.setScore(result.getScore() + participationScore);
                    }

                    // 接力项目积分加倍
                    if (isRelay) {
                        result.setScore(result.getScore() * relayMultiplier);
                    }

                    result.setUpdatedAt(LocalDateTime.now());
                    resultRepository.save(result);
                }
                rank += sequential ? groupSize : 1;
                i = j + 1;
            }
        }

        // 计算热次排名
        Map<Integer, List<Result>> byHeat = validResults.stream()
                .filter(r -> r.getHeat() != null)
                .collect(Collectors.groupingBy(Result::getHeat));

        for (Map.Entry<Integer, List<Result>> entry : byHeat.entrySet()) {
            final List<Result> heatResults = entry.getValue().stream()
                    .sorted(byResult)
                    .collect(Collectors.toList());

            int heatRank = 1;
            for (Result r : heatResults) {
                r.setHeatRank(heatRank++);
                resultRepository.save(r);
            }
        }

        log.info("计算排名完成: eventId={}, 共{}名运动员", eventId, validResults.size());
        return validResults;
    }

    /**
     * 获取项目排名
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getRanking(Long eventId) {
        List<Result> results = resultRepository.findByEventIdOrderByTotalRankAsc(eventId);
        Map<String, Object> rule = systemService.getScoringRule();
        boolean sequential = "sequential".equals(String.valueOf(rule.getOrDefault("tie_handling", "same_rank")));
        String tieRuleNote = sequential
                ? "并列规则：并列顺延占位（名次如 1,2,2,4，被占名次不补授），并列者共享该名次积分。"
                : "并列规则：同名次并列（名次如 1,2,2,3），并列者共享该名次积分，后续名次顺延。";
        Set<String> tiedRanks = computeTiedRanks(results);

        List<Map<String, Object>> list = results.stream().map(r -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("rank", r.getTotalRank());
            map.put("tied", r.getTotalRank() != null
                    && tiedRanks.contains(tieKey(gradeOf(r), r.getTotalRank())));
            map.put("gradeRankLabel", r.getTotalRank() != null && r.getAthlete().getGrade() != null
                    ? r.getAthlete().getGrade() + "第" + r.getTotalRank() + "名" : null);
            map.put("athleteId", r.getAthlete().getId());
            map.put("athleteName", r.getAthlete().getName());
            map.put("number", r.getAthlete().getNumber());
            map.put("className", r.getAthlete().getClassInfo() != null
                    ? r.getAthlete().getClassInfo().getName() : "未知");
            map.put("grade", r.getAthlete().getGrade());
            map.put("rawTime", r.getRawTime());
            map.put("timeSeconds", r.getTimeSeconds());
            map.put("score", r.getScore());
            map.put("heat", r.getHeat());
            map.put("lane", r.getLane());
            map.put("heatRank", r.getHeatRank());
            map.put("isRecord", r.getIsRecord());
            return map;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("tieRuleNote", tieRuleNote);
        result.put("tieHandling", sequential ? "sequential" : "same_rank");
        return result;
    }

    /**
     * 计算某项目内「同名次含多人」的并列名次集合（按年级分组统计）。
     *
     * <p><b>B08/U08 修复</b>：名次是按年级独立排的（见 computeRanking 的 byGrade 分组），
     * 因此并列判定必须落在「年级 × 名次」这一复合键上。旧实现把所有年级的并列名次
     * 塞进同一个 {@code Set<Integer>}，再用 rank 单键去判断，于是
     * 「高一第2名并列」会把高二、高三的第2名（各自其实唯一）也标成并列——
     * 成绩表导出随之给它们打上「=」与「并列」列，对外发布凭空多出并列。
     * 现改为 {@code Set<String>}，键 = {@code 年级|名次}。</p>
     */
    private Set<String> computeTiedRanks(List<Result> results) {
        Map<String, Map<Integer, Integer>> cnt = new LinkedHashMap<>();
        for (Result r : results) {
            if (r.getTotalRank() == null) continue;
            cnt.computeIfAbsent(gradeOf(r), k -> new LinkedHashMap<>())
                    .merge(r.getTotalRank(), 1, Integer::sum);
        }
        Set<String> tied = new HashSet<>();
        for (Map.Entry<String, Map<Integer, Integer>> g : cnt.entrySet()) {
            for (Map.Entry<Integer, Integer> e : g.getValue().entrySet()) {
                if (e.getValue() > 1) tied.add(tieKey(g.getKey(), e.getKey()));
            }
        }
        return tied;
    }

    /** 并列判定键：年级 × 名次（名次是年级内名次，跨年级同名次不构成并列） */
    private static String tieKey(String grade, Integer rank) {
        return (grade == null || grade.isBlank() ? "未知" : grade) + "|" + rank;
    }

    /** 成绩所属年级（缺失归为「未知」，与分组口径保持一致） */
    private static String gradeOf(Result r) {
        return r.getAthlete() != null && r.getAthlete().getGrade() != null
                ? r.getAthlete().getGrade() : "未知";
    }

    /**
     * 从Excel导入成绩
     */
    public Map<String, Object> importFromExcel(MultipartFile file) {
        log.info("从Excel导入成绩: {}", file.getOriginalFilename());
        return excelService.importScores(file);
    }

    /**
     * 获取运动员的所有成绩
     */
    @Transactional(readOnly = true)
    public List<Result> findByAthleteId(Long athleteId) {
        return resultRepository.findByAthleteId(athleteId);
    }

    /**
     * 按项目查询成绩
     */
    @Transactional(readOnly = true)
    public List<Result> findByEventId(Long eventId) {
        return resultRepository.findByEventId(eventId);
    }

    /**
     * 修改成绩
     */
    public Result updateResult(Long id, String rawTime, String status, String remark) {
        Result result = resultRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("成绩记录不存在: " + id));

        if (rawTime != null) {
            result.setRawTime(rawTime);
            // R-2：若 rawTime 为非完赛标记且未显式指定状态，则标记为非 valid 状态
            String nonFinish = normalizeNonFinishStatus(rawTime);
            result.setTimeSeconds(nonFinish != null ? null : parseTime(rawTime));
            if (nonFinish != null && status == null) result.setStatus(nonFinish);
        }
        if (status != null) result.setStatus(status);
        if (remark != null) result.setRemark(remark);

        result.setUpdatedAt(LocalDateTime.now());
        Result saved = resultRepository.save(result);
        log.info("更新成绩成功: ID={}", id);
        return saved;
    }

    /**
     * 删除成绩
     */
    public void deleteResult(Long id) {
        Result result = resultRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("成绩记录不存在: " + id));
        result.setStatus("deleted");
        result.setUpdatedAt(LocalDateTime.now());
        resultRepository.save(result);
        log.info("删除成绩成功: ID={}", id);
    }

    // ============ Controller 兼容方法 ============

    /** Controller: list —— 返回安全扁平 VO（避免 open-in-view=false 下懒加载序列化异常） */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Long eventId, Integer heat) {
        List<Result> base;
        if (eventId != null && heat != null) {
            base = resultRepository.findByEventIdAndHeat(eventId, heat);
        } else if (eventId != null) {
            base = resultRepository.findByEventId(eventId);
        } else {
            base = resultRepository.findAll();
        }
        return base.stream().map(ResultService::toVo).collect(Collectors.toList());
    }

    /** 成绩 → 扁平 VO */
    static Map<String, Object> toVo(Result r) {
        Athlete a = r.getAthlete();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("eventId", r.getEvent() != null ? r.getEvent().getId() : null);
        m.put("athleteId", a != null ? a.getId() : null);
        m.put("athleteName", a != null ? a.getName() : null);
        m.put("number", a != null ? a.getNumber() : null);
        m.put("className", a != null && a.getClassInfo() != null ? a.getClassInfo().getName() : null);
        m.put("grade", a != null ? a.getGrade() : null);
        m.put("round", r.getRound());
        m.put("heat", r.getHeat());
        m.put("lane", r.getLane());
        m.put("laneNumber", r.getLane());
        m.put("rawTime", r.getRawTime());
        m.put("timeSeconds", r.getTimeSeconds());
        m.put("heatRank", r.getHeatRank());
        m.put("rank", r.getTotalRank());
        m.put("totalRank", r.getTotalRank());
        m.put("score", r.getScore());
        m.put("points", r.getScore());
        m.put("status", r.getStatus());
        m.put("remark", r.getRemark());
        m.put("enteredAt", r.getEnteredAt());
        return m;
    }

    /** Controller: enterScore (单条) */
    public Result enterScore(Map<String, Object> resultInput) {
        Object eventIdObj = resultInput.get("eventId");
        Object athleteIdObj = resultInput.get("athleteId");
        if (eventIdObj == null || athleteIdObj == null) {
            throw new RuntimeException("缺少eventId或athleteId");
        }
        Long eventId = eventIdObj instanceof Number n ? n.longValue() : Long.parseLong(eventIdObj.toString());
        Long athleteId = athleteIdObj instanceof Number n ? n.longValue() : Long.parseLong(athleteIdObj.toString());
        String rawTime = (String) resultInput.get("rawTime");
        Integer heat = resultInput.containsKey("heat") && resultInput.get("heat") != null
                ? ((Number) resultInput.get("heat")).intValue() : null;
        Double windSpeed = resultInput.containsKey("windSpeed") && resultInput.get("windSpeed") != null
                ? ((Number) resultInput.get("windSpeed")).doubleValue() : null;
        String remark = (String) resultInput.get("remark");

        ResultInput input = new ResultInput();
        input.setAthleteId(athleteId);
        input.setRawTime(rawTime);
        input.setWindSpeed(windSpeed);
        input.setRemark(remark);

        List<Result> results = enterResults(eventId, heat, List.of(input));
        if (results.isEmpty()) {
            throw new RuntimeException("成绩录入失败，请检查运动员是否已编排到此项目");
        }
        return results.get(0);
    }

    /** Controller: modify */
    public Result modify(Long id, Map<String, Object> resultInput) {
        String rawTime = (String) resultInput.get("rawTime");
        String status = (String) resultInput.get("status");
        String remark = (String) resultInput.get("remark");
        return updateResult(id, rawTime, status, remark);
    }

    /** Controller: delete (别名) */
    public void delete(Long id) {
        deleteResult(id);
    }

    /** Controller: importResults */
    public Map<String, Object> importResults(MultipartFile file) {
        return importFromExcel(file);
    }

    /** Controller: viewRanking (别名) */
    @Transactional(readOnly = true)
    public Map<String, Object> viewRanking(Long eventId) {
        return getRanking(eventId);
    }

    /** Controller: exportResults */
    public void exportResults(Long eventId, HttpServletResponse response) throws IOException {
        List<Result> results = resultRepository.findByEventIdOrderByTotalRankAsc(eventId);
        Event event = eventRepository.findById(eventId).orElse(null);
        String name = event != null ? event.getName() : "成绩";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = name + "_成绩表_v" + com.sports.common.ExportNaming.appVersion()
                + "_" + com.sports.common.ExportNaming.stamp() + ".xlsx";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20")
                + ";filename*=UTF-8''" + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20"));
        try (OutputStream out = response.getOutputStream()) {
            Map<String, Object> rule = systemService.getScoringRule();
            boolean sequential = "sequential".equals(String.valueOf(rule.getOrDefault("tie_handling", "same_rank")));
            String tieRuleNote = sequential
                    ? "并列规则：并列顺延占位（名次如 1,2,2,4，被占名次不补授），并列者共享该名次积分。"
                    : "并列规则：同名次并列（名次如 1,2,2,3），并列者共享该名次积分，后续名次顺延。";
            Set<String> tiedRanks = computeTiedRanks(results);

            java.util.List<java.util.List<String>> data = new java.util.ArrayList<>();
            data.add(java.util.List.of("排名", "年级组名次", "运动员", "号码簿", "班级", "年级", "成绩", "得分", "破纪录", "并列"));
            for (Result r : results) {
                Athlete a = r.getAthlete();
                String grade = a != null && a.getGrade() != null ? a.getGrade() : "";
                // B08/U08：并列按「年级 × 名次」判定，避免跨年级同名次被误标
                boolean tied = r.getTotalRank() != null
                        && tiedRanks.contains(tieKey(gradeOf(r), r.getTotalRank()));
                String rankDisp = r.getTotalRank() != null
                        ? (tied ? r.getTotalRank() + "=" : String.valueOf(r.getTotalRank())) : "-";
                // B11/U17：年级组名次（如「高一第1名」），避免各年级多个「第1名」被误读为总冠军
                String gradeRank = r.getTotalRank() != null && !grade.isEmpty()
                        ? grade + "第" + r.getTotalRank() + "名" : "";
                data.add(java.util.List.of(
                    rankDisp,
                    gradeRank,
                    a != null ? (a.getName() != null ? a.getName() : "") : "",
                    a != null ? athleteRef(a) : "",
                    a != null && a.getClassInfo() != null ? a.getClassInfo().getName() : "",
                    grade,
                    r.getRawTime() != null ? r.getRawTime() : "",
                    r.getScore() != null ? String.valueOf(r.getScore()) : "",
                    Boolean.TRUE.equals(r.getIsRecord()) ? "是" : "",
                    tied ? "并列" : ""
                ));
            }
            // 并列规则说明行（置于表格末尾，便于打印/核对）
            data.add(java.util.List.of("说明", tieRuleNote, "", "", "", "", "", "", "", ""));

            java.util.List<java.util.List<String>> headCols = data.get(0).stream()
                    .map(java.util.List::of).collect(java.util.stream.Collectors.toList());
            com.alibaba.excel.EasyExcel.write(out)
                .head(headCols)
                .sheet("成绩表").doWrite(data.subList(1, data.size()));
            log.info("导出成绩: eventId={}, 共{}条", eventId, results.size());
        }
    }

    /**
     * B02/U02：成绩导出中的「运动员号码」列取值——优先号码布，号码布未生成时回退学号。
     *
     * <p>背景：号码布是「系统按号码簿规则生成」的（见导入模板「填写说明」），
     * 尚未执行生成时 {@code athlete.number} 全为 null。此时若导出仍只写 number，
     * 该列会整列为空，回导只能靠姓名匹配——而重名运动员（本库 1047 人中 348 个重名）
     * 会全部失败，「导出 → 回导」的闭环直接断裂（实测 456 条成绩里 388 条回导失败）。</p>
     *
     * <p>导入端按「号码布或学号」匹配（模板「填写说明」已如此约定），
     * 因此导出侧同步回退学号即可让文件真正可回导。</p>
     */
    private static String athleteRef(Athlete a) {
        if (a == null) return "";
        if (a.getNumber() != null && !a.getNumber().isBlank()) return a.getNumber();
        return a.getStudentId() != null ? a.getStudentId() : "";
    }

    /**
     * B02 / U02：全量导出已录成绩，格式与 /api/excel/import/scores 完全对齐（项目编码/运动员号码/运动员姓名/成绩/组别/道次/风速/备注），
     * 覆盖全部项目（不限于单项目），可直接再次导入做一致性校验。
     */
    @Transactional(readOnly = true)
    public void exportAllResults(HttpServletResponse response) throws IOException {
        List<Result> results = resultRepository.findAll();
        results.sort((a, b) -> {
            String ca = a.getEvent() != null && a.getEvent().getCode() != null ? a.getEvent().getCode() : "";
            String cb = b.getEvent() != null && b.getEvent().getCode() != null ? b.getEvent().getCode() : "";
            return ca.compareTo(cb);
        });
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = "成绩全量导入_v" + com.sports.common.ExportNaming.appVersion()
                + "_" + com.sports.common.ExportNaming.stamp() + ".xlsx";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20")
                        + ";filename*=UTF-8''" + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20"));
        try (OutputStream out = response.getOutputStream()) {
            java.util.List<java.util.List<String>> data = new java.util.ArrayList<>();
            data.add(java.util.List.of("项目编码", "运动员号码", "运动员姓名", "成绩", "组别", "道次", "风速", "备注"));
            int count = 0;
            for (Result r : results) {
                Event e = r.getEvent();
                Athlete a = r.getAthlete();
                if (e == null || a == null) continue;
                data.add(java.util.List.of(
                        e.getCode() != null ? e.getCode() : "",
                        athleteRef(a),
                        a.getName() != null ? a.getName() : "",
                        r.getRawTime() != null ? r.getRawTime() : "",
                        // 组别 = 该成绩所在组次（Result.heat）。
                        // 旧实现写的是 event.gradeGroup（项目所属年级组），既语义错位、
                        // 又与导入端 ScoreExcelModel.heat(Integer) 的类型不符——组次信息丢失。
                        r.getHeat() != null ? String.valueOf(r.getHeat()) : "",
                        r.getLane() != null ? String.valueOf(r.getLane()) : "",
                        r.getWindSpeed() != null ? String.valueOf(r.getWindSpeed()) : "",
                        r.getRemark() != null ? r.getRemark() : ""));
                count++;
            }
            java.util.List<java.util.List<String>> headCols = data.get(0).stream()
                    .map(java.util.List::of).collect(java.util.stream.Collectors.toList());
            com.alibaba.excel.EasyExcel.write(out)
                    .head(headCols)
                    .sheet("成绩全量").doWrite(data.subList(1, data.size()));
            long projects = results.stream()
                    .map(x -> x.getEvent() != null ? x.getEvent().getCode() : "")
                    .filter(s -> !s.isEmpty()).distinct().count();
            log.info("全量导出成绩: 共{}条, 覆盖项目数={}", count, projects);
        }
    }

    /**
     * U13：按项目分 Sheet 导出成绩——每个项目一个 Sheet，避免单 Sheet 混杂；
     * 与 importScores（读全部 Sheet）配合，可直接再导入。
     */
    @Transactional(readOnly = true)
    public void exportAllResultsByProject(HttpServletResponse response) throws IOException {
        List<Result> results = resultRepository.findAll();
        // 按项目编码排序、分组
        Map<String, List<Result>> byEvent = new LinkedHashMap<>();
        results.stream()
                .filter(r -> r.getEvent() != null && r.getEvent().getCode() != null)
                .sorted(Comparator.comparing(r -> r.getEvent().getCode()))
                .forEach(r -> byEvent.computeIfAbsent(r.getEvent().getCode(), k -> new ArrayList<>()).add(r));

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = "成绩导入_按项目分Sheet_v" + com.sports.common.ExportNaming.appVersion()
                + "_" + com.sports.common.ExportNaming.stamp() + ".xlsx";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20")
                        + ";filename*=UTF-8''" + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20"));

        java.util.List<java.util.List<String>> head = java.util.List.of(
                "项目编码", "运动员号码", "运动员姓名", "成绩", "组别", "道次", "风速", "备注")
                .stream().map(java.util.List::of).collect(java.util.stream.Collectors.toList());

        try (OutputStream out = response.getOutputStream();
             com.alibaba.excel.ExcelWriter writer = com.alibaba.excel.EasyExcel.write(out).build()) {
            int si = 0;
            for (Map.Entry<String, List<Result>> en : byEvent.entrySet()) {
                java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
                for (Result r : en.getValue()) {
                    Event e = r.getEvent();
                    Athlete a = r.getAthlete();
                    if (e == null || a == null) continue;
                    rows.add(java.util.List.of(
                            e.getCode() != null ? e.getCode() : "",
                            athleteRef(a),
                            a.getName() != null ? a.getName() : "",
                            r.getRawTime() != null ? r.getRawTime() : "",
                            r.getHeat() != null ? String.valueOf(r.getHeat()) : "",
                            r.getLane() != null ? String.valueOf(r.getLane()) : "",
                            r.getWindSpeed() != null ? String.valueOf(r.getWindSpeed()) : "",
                            r.getRemark() != null ? r.getRemark() : ""));
                }
                com.alibaba.excel.write.metadata.WriteSheet ws = com.alibaba.excel.EasyExcel
                        .writerSheet(si++, en.getKey().length() > 28 ? en.getKey().substring(0, 28) : en.getKey())
                        .head(head).build();
                writer.write(rows, ws);
            }
            log.info("按项目分Sheet导出成绩: {} 个项目", byEvent.size());
        }
    }

    // ============ 辅助方法 ============

    /**
     * 解析时间字符串为秒数
     * 支持格式: "12.34", "1:23.45", "1:02:03.45"
     */
    private Double parseTime(String rawTime) {
        if (rawTime == null || rawTime.isBlank()) return null;

        try {
            rawTime = rawTime.trim();
            if (rawTime.contains(":")) {
                String[] parts = rawTime.split(":");
                if (parts.length == 2) {
                    int minutes = Integer.parseInt(parts[0]);
                    double seconds = Double.parseDouble(parts[1]);
                    return minutes * 60.0 + seconds;
                } else if (parts.length == 3) {
                    int hours = Integer.parseInt(parts[0]);
                    int minutes = Integer.parseInt(parts[1]);
                    double seconds = Double.parseDouble(parts[2]);
                    return hours * 3600.0 + minutes * 60.0 + seconds;
                }
            }
            return Double.parseDouble(rawTime);
        } catch (NumberFormatException e) {
            log.warn("无法解析时间格式: {}", rawTime);
            return null;
        }
    }

    private double parseTimeToSeconds(String time) {
        Double result = parseTime(time);
        return result != null ? result : Double.MAX_VALUE;
    }

    /**
     * R-2 严密性：识别非完赛标记（DNF/DNS/DSQ，模板填写说明承诺支持）。
     * 命中则返回对应的持久化状态（小写），供成绩落库时标记为非 valid，
     * 从而自动退出团队/个人计分（findAllValid 只取 valid）并在项目排名中排到末尾。
     * 未命中返回 null（视为正常成绩，继续数值解析）。集中在此处避免重复识别逻辑。
     */
    public static String normalizeNonFinishStatus(String rawTime) {
        if (rawTime == null) return null;
        switch (rawTime.trim().toUpperCase()) {
            case "DNF": return "dnf";
            case "DNS": return "dns";
            case "DSQ": return "dsq";
            default: return null;
        }
    }

    /**
     * 解析积分规则中的 rank_scores（支持 {"1":9,"2":7,...} 或 {"1":9.0,...}）
     */
    @SuppressWarnings("unchecked")
    private Map<Integer, Double> parseRankScores(Object rankScoresObj) {
        Map<Integer, Double> defaultScores = new LinkedHashMap<>();
        defaultScores.put(1, 9.0);
        defaultScores.put(2, 7.0);
        defaultScores.put(3, 6.0);
        defaultScores.put(4, 5.0);
        defaultScores.put(5, 4.0);
        defaultScores.put(6, 3.0);
        defaultScores.put(7, 2.0);
        defaultScores.put(8, 1.0);

        if (!(rankScoresObj instanceof Map)) {
            return defaultScores;
        }
        Map<Integer, Double> table = new LinkedHashMap<>();
        Map<Object, Object> map = (Map<Object, Object>) rankScoresObj;
        for (Map.Entry<Object, Object> e : map.entrySet()) {
            try {
                int rank = Integer.parseInt(String.valueOf(e.getKey()).trim());
                double score = Double.parseDouble(String.valueOf(e.getValue()).trim());
                table.put(rank, score);
            } catch (NumberFormatException ignored) {
                // 跳过非法项
            }
        }
        return table.isEmpty() ? defaultScores : table;
    }

    private boolean boolVal(Object v, boolean def) {
        if (v instanceof Boolean b) return b;
        if (v != null) {
            String s = String.valueOf(v).trim().toLowerCase();
            if ("true".equals(s) || "1".equals(s) || "yes".equals(s)) return true;
            if ("false".equals(s) || "0".equals(s) || "no".equals(s)) return false;
        }
        return def;
    }

    private int intVal(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try { return Integer.parseInt(String.valueOf(v).trim()); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private double doubleVal(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        if (v != null) {
            try { return Double.parseDouble(String.valueOf(v).trim()); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    /**
     * 成绩输入DTO
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ResultInput {
        private Long athleteId;
        private String rawTime;
        private Double windSpeed;
        private String remark;
    }
}