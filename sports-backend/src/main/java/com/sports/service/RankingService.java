package com.sports.service;

import com.sports.entity.ClassInfo;
import com.sports.entity.ParadeScore;
import com.sports.entity.Result;
import com.sports.repository.ClassInfoRepository;
import com.sports.repository.ParadeScoreRepository;
import com.sports.repository.ResultRepository;
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
public class RankingService {

    private final ResultRepository resultRepository;
    private final ClassInfoRepository classInfoRepository;
    private final ParadeScoreRepository paradeScoreRepository;
    private final SystemService systemService;

    /**
     * 获取团体总分排名（按班级/年级聚合，排序方式可配置）
     */
    public Object getTeamScores(String grade) {
        List<Result> allResults = resultRepository.findAllValid();

        Map<String, Object> rule = systemService.getScoringRule();
        String teamScoreType = String.valueOf(rule.getOrDefault("team_score_type", "class"));
        String teamScoreSort = String.valueOf(rule.getOrDefault("team_score_sort", "total_score"));
        boolean byGrade = "grade".equalsIgnoreCase(teamScoreType);
        boolean goldFirst = "gold_first".equalsIgnoreCase(teamScoreSort);

        Map<String, TeamScore> map = new LinkedHashMap<>();

        for (Result result : allResults) {
            if (result.getScore() == null || result.getScore() <= 0) continue;

            ClassInfo classInfo = result.getAthlete().getClassInfo();
            if (classInfo == null) continue;
            if (grade != null && !grade.isEmpty() && !grade.equals(classInfo.getGrade())) continue;

            String key = byGrade ? (classInfo.getGrade() != null ? classInfo.getGrade() : "未知")
                                 : String.valueOf(classInfo.getId());
            TeamScore ts = map.computeIfAbsent(key, k -> {
                TeamScore t = new TeamScore();
                if (byGrade) {
                    t.classId = null;
                    t.className = classInfo.getGrade();
                    t.grade = classInfo.getGrade();
                } else {
                    t.classId = classInfo.getId();
                    t.className = classInfo.getName();
                    t.grade = classInfo.getGrade();
                }
                return t;
            });

            ts.totalScore += result.getScore();
            ts.medalCount++;

            if (result.getTotalRank() != null) {
                if (result.getTotalRank() == 1) ts.goldCount++;
                else if (result.getTotalRank() == 2) ts.silverCount++;
                else if (result.getTotalRank() == 3) ts.bronzeCount++;
            }
        }

        List<TeamScore> sorted = new ArrayList<>(map.values());
        if (goldFirst) {
            // 注意：reversed() 必须放在整条链末尾，逐级 reversed() 会互相抵消导致排序方向错误
            sorted.sort(Comparator
                    .comparingInt(TeamScore::getGoldCount)
                    .thenComparingInt(TeamScore::getSilverCount)
                    .thenComparingInt(TeamScore::getBronzeCount)
                    .thenComparingDouble(TeamScore::getTotalScore)
                    .reversed());
        } else {
            sorted.sort(Comparator
                    .comparingDouble(TeamScore::getTotalScore)
                    .thenComparingInt(TeamScore::getGoldCount)
                    .thenComparingInt(TeamScore::getSilverCount)
                    .thenComparingInt(TeamScore::getBronzeCount)
                    .reversed());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        int rank = 1;
        for (TeamScore ts : sorted) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", rank++);
            m.put("classId", ts.classId);
            m.put("className", ts.className);
            m.put("grade", ts.grade);
            m.put("totalPoints", Math.round(ts.totalScore * 100.0) / 100.0);
            m.put("goldCount", ts.goldCount);
            m.put("silverCount", ts.silverCount);
            m.put("bronzeCount", ts.bronzeCount);
            m.put("medalCount", ts.medalCount);
            result.add(m);
        }

        log.info("团体总分排名计算完成: 共{}个{}", result.size(), byGrade ? "年级" : "班级");
        return result;
    }

    /**
     * 合分排行总览：每班/年级内班级 × 男女 × 计分，支持含/去除入场式两种口径。
     *
     * @param grade        按年级过滤（null=全校；传年级=该年级内班级排名）
     * @param includeParade true 则总分口径为 赛事得分 + 入场式得分，否则纯赛事得分
     * @param topN         &gt;0 时返回 top 字段只保留前 N 名
     * @param byGrade      true 时返回按年级聚合的排行（维度=年级）
     */
    public Map<String, Object> getScoreBoard(String grade, boolean includeParade, int topN, boolean byGrade) {
        List<Result> allResults = resultRepository.findAllValid();
        Map<String, Object> rule = systemService.getScoringRule();
        boolean goldFirst = "gold_first".equalsIgnoreCase(
                String.valueOf(rule.getOrDefault("team_score_sort", "total_score")));

        Map<Long, BoardRow> byClass = new LinkedHashMap<>();
        Map<String, GradeRow> byGradeAgg = new LinkedHashMap<>();

        for (Result result : allResults) {
            if (result.getScore() == null || result.getScore() <= 0) continue;
            if (result.getAthlete() == null || result.getAthlete().getClassInfo() == null) continue;
            ClassInfo ci = result.getAthlete().getClassInfo();
            if (grade != null && !grade.isBlank() && !grade.equals(ci.getGrade())) continue;

            double score = result.getScore();
            String gender = result.getAthlete().getGender() == null ? "未知"
                    : result.getAthlete().getGender();

            BoardRow row = byClass.computeIfAbsent(ci.getId(), id -> {
                BoardRow r = new BoardRow();
                r.classId = ci.getId();
                r.className = ci.getName();
                r.grade = ci.getGrade();
                return r;
            });
            row.total += score;
            if ("男".equals(gender)) row.male += score;
            else if ("女".equals(gender)) row.female += score;
            else row.other += score;
            row.scoredEvents++;

            if (result.getTotalRank() != null) {
                if (result.getTotalRank() == 1) row.gold++;
                else if (result.getTotalRank() == 2) row.silver++;
                else if (result.getTotalRank() == 3) row.bronze++;
            }

            String g = ci.getGrade() != null ? ci.getGrade() : "未知";
            GradeRow gr = byGradeAgg.computeIfAbsent(g, k -> new GradeRow());
            gr.total += score;
            if ("男".equals(gender)) gr.male += score;
            else if ("女".equals(gender)) gr.female += score;
            else gr.other += score;
        }

        // 入场式得分（班-分映射）
        Map<Long, Double> paradeByClass = new HashMap<>();
        for (ParadeScore ps : paradeScoreRepository.findAllActive()) {
            if (ps.getClassInfo() != null) paradeByClass.put(ps.getClassInfo().getId(), ps.getScore());
        }

        List<BoardRow> values = new ArrayList<>(byClass.values());
        Comparator<BoardRow> cmp = goldFirst
                ? Comparator.comparingInt((BoardRow r) -> r.gold)
                        .thenComparingInt(r -> r.silver)
                        .thenComparingInt(r -> r.bronze)
                        .thenComparingDouble(r -> r.total)
                        .reversed()
                : Comparator.comparingDouble((BoardRow r) -> r.total)
                        .thenComparingInt(r -> r.gold)
                        .thenComparingInt(r -> r.silver)
                        .thenComparingInt(r -> r.bronze)
                        .reversed();
        values.sort(cmp);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (BoardRow r : values) {
            double paradeScore = paradeByClass.getOrDefault(r.classId, 0.0);
            boolean classHasParade = paradeByClass.containsKey(r.classId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("classId", r.classId);
            m.put("className", r.className);
            m.put("grade", r.grade);
            m.put("maleScore", round2(r.male));
            m.put("femaleScore", round2(r.female));
            m.put("otherScore", round2(r.other));
            m.put("scoredEvents", r.scoredEvents);
            m.put("goldCount", r.gold);
            m.put("silverCount", r.silver);
            m.put("bronzeCount", r.bronze);
            m.put("medalCount", r.gold + r.silver + r.bronze);
            m.put("totalScore", round2(r.total));
            m.put("paradeScore", round2(paradeScore));
            m.put("hasParade", classHasParade);
            m.put("totalWithParade", round2(r.total + (includeParade && classHasParade ? paradeScore : 0)));
            rows.add(m);
        }

        assignRanks(rows, includeParade);

        List<Map<String, Object>> top = topN > 0 && rows.size() > topN
                ? new ArrayList<>(rows.subList(0, topN)) : rows;

        List<Map<String, Object>> gradeSummary = new ArrayList<>();
        for (Map.Entry<String, GradeRow> e : byGradeAgg.entrySet()) {
            GradeRow gr = e.getValue();
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("grade", e.getKey());
            gm.put("maleScore", round2(gr.male));
            gm.put("femaleScore", round2(gr.female));
            gm.put("otherScore", round2(gr.other));
            gm.put("totalScore", round2(gr.total));
            gradeSummary.add(gm);
        }
        gradeSummary.sort(Comparator.comparing(m -> String.valueOf(m.get("grade"))));

        log.info("合分排行计算完成: grade={}, includeParade={}, 共{}班", grade, includeParade, rows.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("top", top);
        result.put("gradeSummary", gradeSummary);
        result.put("includeParade", includeParade);
        result.put("totalClasses", rows.size());
        result.put("dimension", byGrade ? "grade" : "class");
        result.put("filterGrade", grade);
        return result;
    }

    /** 并列排名：分数相同给同名次 */
    private void assignRanks(List<Map<String, Object>> rows, boolean includeParade) {
        String key = includeParade ? "totalWithParade" : "totalScore";
        int rank = 0;
        double prev = Double.NaN;
        for (int i = 0; i < rows.size(); i++) {
            double cur = ((Number) rows.get(i).get(key)).doubleValue();
            if (i == 0 || Math.abs(cur - prev) > 1e-9) {
                rank = i + 1;
                prev = cur;
            }
            rows.get(i).put("rank", rank);
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    @lombok.Data
    private static class BoardRow {
        Long classId;
        String className;
        String grade;
        double male;
        double female;
        double other;
        double total;
        int gold;
        int silver;
        int bronze;
        int scoredEvents;
    }

    @lombok.Data
    private static class GradeRow {
        String grade;
        double male;
        double female;
        double other;
        double total;
    }

    /**
     * 获取个人积分排名
     */
    public Object getIndividualScores(String grade, Long eventId, int page, int size) {
        List<Result> allResults = resultRepository.findAllValid();

        // 按运动员聚合分数
        Map<Long, IndividualScore> athleteScores = new LinkedHashMap<>();

        for (Result result : allResults) {
            if (result.getScore() == null || result.getScore() <= 0) continue;
            if (grade != null && !grade.isEmpty() && !grade.equals(result.getAthlete().getGrade())) continue;
            if (eventId != null && !eventId.equals(result.getEvent().getId())) continue;

            Long athleteId = result.getAthlete().getId();
            IndividualScore is = athleteScores.computeIfAbsent(athleteId, id -> {
                IndividualScore s = new IndividualScore();
                s.athleteId = athleteId;
                s.athleteName = result.getAthlete().getName();
                s.number = result.getAthlete().getNumber();
                s.className = result.getAthlete().getClassInfo() != null
                        ? result.getAthlete().getClassInfo().getName() : "未知";
                s.grade = result.getAthlete().getGrade();
                s.gender = result.getAthlete().getGender();
                return s;
            });

            is.totalScore += result.getScore();
            is.eventCount++;
        }

        List<IndividualScore> sorted = athleteScores.values().stream()
                .sorted(Comparator.comparingDouble(IndividualScore::getTotalScore).reversed())
                .collect(Collectors.toList());

        List<Map<String, Object>> result = new ArrayList<>();
        int rank = 1;
        for (IndividualScore is : sorted) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("rank", rank++);
            map.put("athleteId", is.athleteId);
            map.put("athleteName", is.athleteName);
            map.put("athleteNumber", is.number);
            map.put("number", is.number);
            map.put("className", is.className);
            map.put("grade", is.grade);
            map.put("totalScore", Math.round(is.totalScore * 100.0) / 100.0);
            map.put("eventCount", is.eventCount);
            result.add(map);
        }

        // Paginate
        int total = result.size();
        int from = (page - 1) * size;
        int to = Math.min(from + size, total);
        List<Map<String, Object>> pageData = from < total ? result.subList(from, to) : List.of();

        Map<String, Object> paged = new LinkedHashMap<>();
        paged.put("records", pageData);
        paged.put("total", total);
        paged.put("page", page);
        paged.put("size", size);
        return paged;
    }

    /**
     * 获取破纪录列表
     */
    public List<Map<String, Object>> getRecords(String grade, Long eventId) {
        List<Result> records = resultRepository.findByIsRecordTrue();

        return records.stream()
                .filter(r -> grade == null || grade.isEmpty() || grade.equals(r.getAthlete().getGrade()))
                .filter(r -> eventId == null || eventId.equals(r.getEvent().getId()))
                .map(r -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("athleteId", r.getAthlete().getId());
                    map.put("athleteName", r.getAthlete().getName());
                    map.put("athleteNumber", r.getAthlete().getNumber());
                    map.put("number", r.getAthlete().getNumber());
                    map.put("className", r.getAthlete().getClassInfo() != null
                            ? r.getAthlete().getClassInfo().getName() : "未知");
                    map.put("grade", r.getAthlete().getGrade());
                    map.put("eventId", r.getEvent().getId());
                    map.put("eventName", r.getEvent().getName());
                    map.put("oldRecord", r.getEvent().getRecord());
                    map.put("rawTime", r.getRawTime());
                    map.put("timeSeconds", r.getTimeSeconds());
                    map.put("rank", r.getTotalRank());
                    map.put("score", r.getScore());
                    map.put("recordType", "校纪录");
                    return map;
                }).collect(Collectors.toList());
    }

    /**
     * 获取单个项目的排名详情
     */
    public Map<String, Object> getEventRanking(Long eventId) {
        List<Result> results = resultRepository.findByEventIdOrderByTotalRankAsc(eventId);

        List<Map<String, Object>> rankings = new ArrayList<>();
        for (Result r : results) {
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
            map.put("windSpeed", r.getWindSpeed());
            rankings.add(map);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        if (!results.isEmpty()) {
            Result first = results.get(0);
            result.put("eventId", first.getEvent().getId());
            result.put("eventName", first.getEvent().getName());
            result.put("category", first.getEvent().getCategory());
            result.put("record", first.getEvent().getRecord());
        }
        result.put("rankings", rankings);
        result.put("totalCount", rankings.size());

        return result;
    }

    /**
     * 获取团体某班的分项明细
     */
    public List<Map<String, Object>> getTeamBreakdown(String className, String grade) {
        List<Result> allResults = resultRepository.findAllValid();

        return allResults.stream()
                .filter(r -> r.getScore() != null && r.getScore() > 0)
                .filter(r -> {
                    ClassInfo ci = r.getAthlete().getClassInfo();
                    return ci != null && className.equals(ci.getName())
                        && (grade == null || grade.isEmpty() || grade.equals(ci.getGrade()));
                })
                .map(r -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("eventName", r.getEvent().getName());
                    map.put("rank", r.getTotalRank());
                    map.put("score", r.getScore());
                    map.put("points", r.getScore());
                    return map;
                })
                .sorted(Comparator.comparing(m -> (String) m.get("eventName")))
                .collect(Collectors.toList());
    }

    // ============ 内部类 ============

    @lombok.Data
    private static class TeamScore {
        Long classId;
        String className;
        String grade;
        double totalScore;
        int goldCount;
        int silverCount;
        int bronzeCount;
        int medalCount;
    }

    @lombok.Data
    private static class IndividualScore {
        Long athleteId;
        String athleteName;
        String number;
        String className;
        String grade;
        String gender;
        double totalScore;
        int eventCount;
    }
}
