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

            if (result.getTotalRank() != null) {
                if (result.getTotalRank() == 1) ts.goldCount++;
                else if (result.getTotalRank() == 2) ts.silverCount++;
                else if (result.getTotalRank() == 3) ts.bronzeCount++;
            }
        }

        List<TeamScore> sorted = new ArrayList<>(map.values());
        // R-1 严密性：四项分解键全同的班级必须按 classId（班级维度）/ className（年级维度）
        // 给出确定性次级排序，否则相对顺序依赖 findAllValid() 返回序，两次请求可能次序漂移。
        // 次级排序放在 reversed() 之后：主排序保持降序，并列项按 classId 升序确定排列（小者在前）。
        Comparator<TeamScore> stable = Comparator
                .comparing((TeamScore ts) -> ts.classId != null ? ts.classId : Long.MAX_VALUE)
                .thenComparing(ts -> ts.className != null ? ts.className : "");
        if (goldFirst) {
            sorted.sort(Comparator
                    .comparingInt(TeamScore::getGoldCount)
                    .thenComparingInt(TeamScore::getSilverCount)
                    .thenComparingInt(TeamScore::getBronzeCount)
                    .thenComparingDouble(TeamScore::getTotalScore)
                    .reversed()
                    .thenComparing(stable));
        } else {
            sorted.sort(Comparator
                    .comparingDouble(TeamScore::getTotalScore)
                    .thenComparingInt(TeamScore::getGoldCount)
                    .thenComparingInt(TeamScore::getSilverCount)
                    .thenComparingInt(TeamScore::getBronzeCount)
                    .reversed()
                    .thenComparing(stable));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        // B12/U18：名次必须与排序口径一致，且四项分解键完全相同才判并列
        // （旧实现恒为 rank++，与 tieRuleNote 宣称的「名次并列」自相矛盾）
        List<String> prevKey = null;
        int rank = 0;
        for (int i = 0; i < sorted.size(); i++) {
            TeamScore ts = sorted.get(i);
            List<String> tieKey = List.of(
                    String.valueOf(ts.goldCount), String.valueOf(ts.silverCount),
                    String.valueOf(ts.bronzeCount), String.valueOf(ts.totalScore));
            boolean tied = prevKey != null && prevKey.equals(tieKey);
            if (!tied) rank = i + 1;   // 标准竞赛排名：并列取首个位次，被占名次不补授
            prevKey = tieKey;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", rank);
            m.put("classId", ts.classId);
            m.put("className", ts.className);
            m.put("grade", ts.grade);
            m.put("totalPoints", Math.round(ts.totalScore * 100.0) / 100.0);
            m.put("goldCount", ts.goldCount);
            m.put("silverCount", ts.silverCount);
            m.put("bronzeCount", ts.bronzeCount);
            // medalCount 是「奖牌数」= 金+银+铜；旧实现按「得分的名次条数」累加，
            // 与字段名不符（同一班多人在同名次得分时会虚高）
            m.put("medalCount", ts.goldCount + ts.silverCount + ts.bronzeCount);
            m.put("tied", tied);
            result.add(m);
        }

        // B12/U18：输出并列（取名次）规则说明，避免“总分相同按金/银/铜排名”不透明
        String tieRuleNote = goldFirst
                ? "排名规则：先比金牌数，金牌相同比银牌数，再比铜牌数，最后比总分；"
                  + "若仍相同则名次并列先后顺序保持，无额外加赛。"
                : "排名规则：先比总分，总分相同比金牌数，再比银牌数，最后比铜牌数；"
                  + "若仍相同则名次并列先后顺序保持，无额外加赛。";

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("records", result);
        resp.put("totalCount", result.size());
        resp.put("tieRuleNote", tieRuleNote);
        resp.put("tieBreakOrder", goldFirst
                ? List.of("goldCount", "silverCount", "bronzeCount", "totalPoints")
                : List.of("totalPoints", "goldCount", "silverCount", "bronzeCount"));
        resp.put("dimension", byGrade ? "grade" : "class");

        log.info("团体总分排名计算完成: 共{}个{}", result.size(), byGrade ? "年级" : "班级");
        return resp;
    }

    /**
     * 合分排行总览：每班/年级内班级 × 男女 × 计分，支持含/去除入场式两种口径。
     *
     * @param grade        按年级过滤（null=全校；传年级=该年级内班级排名）
     * @param includeParade true 则总分口径为 赛事得分 + 入场式得分，否则纯赛事得分
     * @param topN         &gt;0 时返回 top 字段只保留前 N 名
     * @param byGrade      true 时返回按年级聚合的排行（维度=年级）
     * @param gender       男/M 或 女/F 时只统计该性别运动员得分（得出男生榜/女生榜）
     */
    public Map<String, Object> getScoreBoard(String grade, boolean includeParade, int topN,
                                             boolean byGrade, String gender) {
        List<Result> allResults = resultRepository.findAllValid();
        Map<String, Object> rule = systemService.getScoringRule();
        boolean goldFirst = "gold_first".equalsIgnoreCase(
                String.valueOf(rule.getOrDefault("team_score_sort", "total_score")));

        // 性别过滤维度（兼容 男/M、女/F 写法）
        String genderNorm = com.sports.common.GenderUtil.normalize(gender);
        boolean filterMale = "M".equals(genderNorm);
        boolean filterFemale = "F".equals(genderNorm);

        Map<Long, BoardRow> byClass = new LinkedHashMap<>();
        Map<String, GradeRow> byGradeAgg = new LinkedHashMap<>();

        for (Result result : allResults) {
            if (result.getScore() == null || result.getScore() <= 0) continue;
            if (result.getAthlete() == null || result.getAthlete().getClassInfo() == null) continue;
            ClassInfo ci = result.getAthlete().getClassInfo();
            if (grade != null && !grade.isBlank() && !grade.equals(ci.getGrade())) continue;

            String genderRaw = result.getAthlete().getGender();
            String genderKey = com.sports.common.GenderUtil.normalize(genderRaw); // M/F/其他
            boolean isMale = "M".equals(genderKey);
            boolean isFemale = "F".equals(genderKey);
            if (filterMale && !isMale) continue;
            if (filterFemale && !isFemale) continue;

            double score = result.getScore();

            BoardRow row = byClass.computeIfAbsent(ci.getId(), id -> {
                BoardRow r = new BoardRow();
                r.classId = ci.getId();
                r.className = ci.getName();
                r.grade = ci.getGrade();
                return r;
            });
            row.total += score;
            if (isMale) row.male += score;
            else if (isFemale) row.female += score;
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
            if (isMale) gr.male += score;
            else if (isFemale) gr.female += score;
            else gr.other += score;
        }

        // 入场式得分（班-分映射）
        Map<Long, Double> paradeByClass = new HashMap<>();
        for (ParadeScore ps : paradeScoreRepository.findAllActive()) {
            if (ps.getClassInfo() != null) paradeByClass.put(ps.getClassInfo().getId(), ps.getScore());
        }

        List<BoardRow> values = new ArrayList<>(byClass.values());
        // 含入场式口径时，排序基准必须是 赛事得分+入场式得分，否则名次/TOP 与最终总分不一致
        // R-1 严密性：四项分解键全同的行按 classId 给出确定性次级排序（置于 reversed 之后，小者在前），
        // 避免相对次序依赖查询返回序。
        Comparator<BoardRow> stable = Comparator.comparing(
                (BoardRow r) -> r.classId != null ? r.classId : Long.MAX_VALUE);
        Comparator<BoardRow> cmp = goldFirst
                ? Comparator.comparingInt((BoardRow r) -> r.gold)
                        .thenComparingInt(r -> r.silver)
                        .thenComparingInt(r -> r.bronze)
                        .thenComparingDouble(r -> effScore(r, includeParade, paradeByClass))
                        .reversed()
                        .thenComparing(stable)
                : Comparator.comparingDouble((BoardRow r) -> effScore(r, includeParade, paradeByClass))
                        .thenComparingInt(r -> r.gold)
                        .thenComparingInt(r -> r.silver)
                        .thenComparingInt(r -> r.bronze)
                        .reversed()
                        .thenComparing(stable);
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
        // B12/U18：并列（取名次）规则说明
        result.put("tieRuleNote", goldFirst
                ? "排名规则：先比金牌数，金牌相同比银牌数，再比铜牌数，最后比总分；若全同则并列名次保持先后顺序。"
                : "排名规则：先比总分，总分相同比金牌数，再比银牌数，最后比铜牌数；若全同则并列名次保持先后顺序。");
        result.put("filterGrade", grade);
        result.put("filterGender", gender == null || gender.isBlank() ? null
                : ("M".equals(genderNorm) ? "男" : "F".equals(genderNorm) ? "女" : gender.trim()));
        return result;
    }

    /**
     * 合分排行的名次赋值。
     *
     * <p><b>B12/U18 修复</b>：旧实现只拿总分（或含入场式总分）当唯一键去赋名次，
     * <b>完全忽略排序口径</b>。而排序在 {@code team_score_sort=gold_first} 时是按
     * 金→银→铜→总分 排的，于是行序与名次列互相矛盾：
     * 金牌多但总分少的班会排在第 1 行、却显示「第 2 名」。
     * 另外旧实现宣称「名次并列」但恒按位置递增，全键相同的两个班也拿不到同名次。</p>
     *
     * <p>现在用与排序完全相同的<b>四项分解键</b>（金/银/铜/有效总分）判等：
     * 全键相同才并列，并列取首个位次（标准竞赛排名 1,2,2,4）。
     * 由于比较器相等 ⟺ 四项全等，故键的书写顺序与 gold_first 无关。</p>
     */
    private void assignRanks(List<Map<String, Object>> rows, boolean includeParade) {
        String scoreKey = includeParade ? "totalWithParade" : "totalScore";
        List<String> prevKey = null;
        int rank = 0;
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            List<String> tieKey = List.of(
                    String.valueOf(row.get("goldCount")),
                    String.valueOf(row.get("silverCount")),
                    String.valueOf(row.get("bronzeCount")),
                    String.valueOf(((Number) row.get(scoreKey)).doubleValue()));
            boolean tied = prevKey != null && prevKey.equals(tieKey);
            if (!tied) rank = i + 1;
            prevKey = tieKey;
            row.put("rank", rank);
            row.put("tied", tied);
        }
    }

    /** 排行有效分：含入场式口径 = 赛事得分 + 入场式得分（未录入入场式按 0） */
    private static double effScore(BoardRow r, boolean includeParade, Map<Long, Double> paradeByClass) {
        double base = r.total;
        if (includeParade) {
            Double ps = paradeByClass.get(r.classId);
            if (ps != null) base += ps;
        }
        return base;
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

        // B08/U08：个人榜此前恒用 rank++ 顺序赋名次——既不遵循 scoring_rule.tie_handling，
        // 也不输出并列标记，与项目排名/成绩表的并列口径完全脱节：
        // 同一项目里并列得分的两人到了个人榜就变成「第1、第2」，对外口径不一致。
        // 现按与 calculateRanking 相同的口径处理：
        //   same_rank（默认）= 密集排名 1,1,2；sequential = 标准竞赛排名 1,1,3（被占名次不补授）。
        Map<String, Object> rule = systemService.getScoringRule();
        boolean sequential = "sequential".equals(
                String.valueOf(rule.getOrDefault("tie_handling", "same_rank")));

        List<Map<String, Object>> result = new ArrayList<>();
        int i = 0;
        int n = sorted.size();
        int nextRank = 1;
        while (i < n) {
            int j = i;
            while (j + 1 < n && Math.abs(sorted.get(j + 1).getTotalScore()
                    - sorted.get(i).getTotalScore()) < 1e-9) {
                j++;
            }
            int groupSize = j - i + 1;
            int rank = nextRank;
            for (int k = i; k <= j; k++) {
                IndividualScore is = sorted.get(k);
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("rank", rank);
                map.put("tied", groupSize > 1);
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
            nextRank = sequential ? rank + groupSize : rank + 1;
            i = j + 1;
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
        // B08/U08：并列规则随榜单一并输出，避免「得分相同却名次不同」无从解释
        paged.put("tieHandling", sequential ? "sequential" : "same_rank");
        paged.put("tieRuleNote", sequential
                ? "并列规则：并列顺延占位（名次如 1,1,3，被占名次不补授），并列者共享该名次。"
                : "并列规则：同名次并列（名次如 1,1,2），并列者共享该名次，后续名次顺延。");
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

        Map<String, Object> rule = systemService.getScoringRule();
        boolean sequential = "sequential".equals(String.valueOf(rule.getOrDefault("tie_handling", "same_rank")));
        String tieRuleNote = sequential
                ? "并列规则：并列顺延占位（名次如 1,2,2,4，被占名次不补授），并列者共享该名次积分。"
                : "并列规则：同名次并列（名次如 1,2,2,3），并列者共享该名次积分，后续名次顺延。";
        Set<String> tiedRanks = computeTiedRanks(results);

        List<Map<String, Object>> rankings = new ArrayList<>();
        for (Result r : results) {
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
        result.put("tieRuleNote", tieRuleNote);
        result.put("tieHandling", sequential ? "sequential" : "same_rank");
        result.put("totalCount", rankings.size());

        return result;
    }

    /**
     * 计算某项目内「同名次含多人」的并列名次集合（按年级分组统计）。
     *
     * <p><b>B08/U08 修复</b>：名次是按年级独立排的，并列判定必须落在「年级 × 名次」复合键上。
     * 旧实现把各年级的并列名次合并进同一个 {@code Set<Integer>}，用 rank 单键判断，
     * 导致某一年级出现并列时，其它年级同一名次的运动员被误标为并列。</p>
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
