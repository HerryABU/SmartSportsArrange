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

                Double timeSeconds = parseTime(input.getRawTime());

                Result result = Result.builder()
                        .event(event)
                        .athlete(arrangement.getAthlete())
                        .heat(heat != null ? heat : arrangement.getHeat())
                        .lane(arrangement.getLane())
                        .rawTime(input.getRawTime())
                        .timeSeconds(timeSeconds)
                        .windSpeed(input.getWindSpeed())
                        .status("valid")
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

        // 获取所有有效成绩，按时间升序排列
        List<Result> validResults = resultRepository.findValidByEventId(eventId).stream()
                .filter(r -> r.getTimeSeconds() != null)
                .sorted(Comparator.comparing(Result::getTimeSeconds, Comparator.nullsLast(Double::compareTo)))
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

        // 分组处理并列排名：same_rank（同名次并列）/ sequential（顺延）
        int rank = 1;
        int i = 0;
        int n = validResults.size();
        while (i < n) {
            int j = i;
            while (j + 1 < n
                    && Math.abs(validResults.get(j + 1).getTimeSeconds()
                            - validResults.get(i).getTimeSeconds()) < 0.001) {
                j++;
            }
            int groupSize = j - i + 1;
            for (int k = i; k <= j; k++) {
                Result result = validResults.get(k);
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

        // 计算热次排名
        Map<Integer, List<Result>> byHeat = validResults.stream()
                .filter(r -> r.getHeat() != null)
                .collect(Collectors.groupingBy(Result::getHeat));

        for (Map.Entry<Integer, List<Result>> entry : byHeat.entrySet()) {
            List<Result> heatResults = entry.getValue().stream()
                    .sorted(Comparator.comparing(Result::getTimeSeconds, Comparator.nullsLast(Double::compareTo)))
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
    public List<Map<String, Object>> getRanking(Long eventId) {
        List<Result> results = resultRepository.findByEventIdOrderByTotalRankAsc(eventId);

        return results.stream().map(r -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("rank", r.getTotalRank());
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
            result.setTimeSeconds(parseTime(rawTime));
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
    public List<Map<String, Object>> viewRanking(Long eventId) {
        return getRanking(eventId);
    }

    /** Controller: exportResults */
    public void exportResults(Long eventId, HttpServletResponse response) throws IOException {
        List<Result> results = resultRepository.findByEventIdOrderByTotalRankAsc(eventId);
        Event event = eventRepository.findById(eventId).orElse(null);
        String name = event != null ? event.getName() : "成绩";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = name + "_成绩表_" + LocalDateTime.now().toString().replace(":", "-") + ".xlsx";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20")
                + ";filename*=UTF-8''" + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20"));
        try (OutputStream out = response.getOutputStream()) {
            java.util.List<java.util.List<String>> data = new java.util.ArrayList<>();
            data.add(java.util.List.of("排名", "运动员", "号码簿", "班级", "年级", "成绩", "得分", "破纪录"));
            for (Result r : results) {
                Athlete a = r.getAthlete();
                data.add(java.util.List.of(
                    r.getTotalRank() != null ? String.valueOf(r.getTotalRank()) : "-",
                    a != null ? (a.getName() != null ? a.getName() : "") : "",
                    a != null ? (a.getNumber() != null ? a.getNumber() : "") : "",
                    a != null && a.getClassInfo() != null ? a.getClassInfo().getName() : "",
                    a != null ? (a.getGrade() != null ? a.getGrade() : "") : "",
                    r.getRawTime() != null ? r.getRawTime() : "",
                    r.getScore() != null ? String.valueOf(r.getScore()) : "",
                    Boolean.TRUE.equals(r.getIsRecord()) ? "是" : ""
                ));
            }
            java.util.List<java.util.List<String>> headCols = data.get(0).stream()
                    .map(java.util.List::of).collect(java.util.stream.Collectors.toList());
            com.alibaba.excel.EasyExcel.write(out)
                .head(headCols)
                .sheet("成绩表").doWrite(data.subList(1, data.size()));
            log.info("导出成绩: eventId={}, 共{}条", eventId, results.size());
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