package com.sports.service;

import com.sports.entity.ClassInfo;
import com.sports.entity.Result;
import com.sports.repository.ClassInfoRepository;
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

    /**
     * 获取团体总分排名（按班级聚合）
     */
    public Object getTeamScores(String grade) {
        List<Result> allResults = resultRepository.findAllValid();

        // 按班级ID聚合分数
        Map<Long, TeamScore> classScores = new LinkedHashMap<>();

        for (Result result : allResults) {
            if (result.getScore() == null || result.getScore() <= 0) continue;

            ClassInfo classInfo = result.getAthlete().getClassInfo();
            if (classInfo == null) continue;
            if (grade != null && !grade.isEmpty() && !grade.equals(classInfo.getGrade())) continue;

            Long classId = classInfo.getId();
            TeamScore ts = classScores.computeIfAbsent(classId, id -> {
                TeamScore t = new TeamScore();
                t.classId = classId;
                t.className = classInfo.getName();
                t.grade = classInfo.getGrade();
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

        List<TeamScore> sorted = classScores.values().stream()
                .sorted(Comparator
                        .comparingDouble(TeamScore::getTotalScore).reversed()
                        .thenComparingInt(TeamScore::getGoldCount).reversed()
                        .thenComparingInt(TeamScore::getSilverCount).reversed()
                        .thenComparingInt(TeamScore::getBronzeCount).reversed())
                .collect(Collectors.toList());

        List<Map<String, Object>> result = new ArrayList<>();
        int rank = 1;
        for (TeamScore ts : sorted) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("rank", rank++);
            map.put("classId", ts.classId);
            map.put("className", ts.className);
            map.put("grade", ts.grade);
            map.put("totalPoints", Math.round(ts.totalScore * 100.0) / 100.0);
            map.put("goldCount", ts.goldCount);
            map.put("silverCount", ts.silverCount);
            map.put("bronzeCount", ts.bronzeCount);
            map.put("medalCount", ts.medalCount);
            result.add(map);
        }

        log.info("团体总分排名计算完成: 共{}个班级", result.size());
        return result;
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
