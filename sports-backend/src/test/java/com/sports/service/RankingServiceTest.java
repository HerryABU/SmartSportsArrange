package com.sports.service;

import com.sports.entity.Athlete;
import com.sports.entity.ClassInfo;
import com.sports.entity.Event;
import com.sports.entity.ParadeScore;
import com.sports.entity.Result;
import com.sports.repository.ClassInfoRepository;
import com.sports.repository.ParadeScoreRepository;
import com.sports.repository.ResultRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 排名与积分聚合测试。
 * 覆盖：团体总分聚合与排序、合分排行（男女/入场式口径）、个人积分聚合与分页、单项目排名。
 */
@ExtendWith(MockitoExtension.class)
class RankingServiceTest {

    @Mock private ResultRepository resultRepository;
    @Mock private ClassInfoRepository classInfoRepository;
    @Mock private ParadeScoreRepository paradeScoreRepository;
    @Mock private SystemService systemService;

    @InjectMocks private RankingService rankingService;

    private Result result(Long id, double score, Integer rank, ClassInfo ci) {
        return result(id, score, rank, ci, "男");
    }

    private Result result(Long id, double score, Integer rank, ClassInfo ci, String gender) {
        Athlete a = Athlete.builder().id(id).name("R" + id).grade("高一")
                .gender(gender).classInfo(ci).build();
        return Result.builder().id(id).athlete(a).event(Event.builder().id(1L).build())
                .score(score).totalRank(rank).status("valid").build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void getTeamScores_aggregatesAndSortsByTotal() {
        ClassInfo ca = ClassInfo.builder().id(1L).name("高一1班").grade("高一").build();
        ClassInfo cb = ClassInfo.builder().id(2L).name("高一2班").grade("高一").build();
        List<Result> all = List.of(
                result(1L, 9.0, 1, ca),   // 金牌
                result(2L, 7.0, 2, ca),   // 银牌
                result(3L, 6.0, 3, cb));
        when(resultRepository.findAllValid()).thenReturn(all);
        when(systemService.getScoringRule()).thenReturn(defaultRule("class", "total_score"));

        List<Map<String, Object>> teams = (List<Map<String, Object>>) rankingService.getTeamScores(null);
        assertEquals(2, teams.size());
        // 高一1班总分 16 排第一
        assertEquals(1, teams.get(0).get("rank"));
        assertEquals("高一1班", teams.get(0).get("className"));
        assertEquals(16.0, teams.get(0).get("totalPoints"));
        assertEquals(1, teams.get(0).get("goldCount"));
        // 高一2班总分 6 排第二
        assertEquals(2, teams.get(1).get("rank"));
        assertEquals(6.0, teams.get(1).get("totalPoints"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getIndividualScores_paginates() {
        ClassInfo ca = ClassInfo.builder().id(1L).name("高一1班").grade("高一").build();
        ClassInfo cb = ClassInfo.builder().id(2L).name("高一2班").grade("高一").build();
        // 运动员 A（id=1）三项累计 22.0；运动员 B（id=4）两项累计 9.0 → 共 2 名运动员
        List<Result> all = List.of(
                result(1L, 9.0, 1, ca), result(1L, 7.0, 2, ca), result(1L, 6.0, 3, ca),
                result(4L, 5.0, 4, cb), result(4L, 4.0, 5, cb));
        when(resultRepository.findAllValid()).thenReturn(all);

        Map<String, Object> page = (Map<String, Object>) rankingService.getIndividualScores(null, null, 1, 1);
        assertEquals(2, page.get("total")); // 2 名运动员
        List<Map<String, Object>> records = (List<Map<String, Object>>) page.get("records");
        assertEquals(1, records.size());
        assertEquals(22.0, (Double) records.get(0).get("totalScore"), 0.001); // 9+7+6
    }

    @Test
    @SuppressWarnings("unchecked")
    void getEventRanking_ordersByRank() {
        ClassInfo ca = ClassInfo.builder().id(1L).name("高一1班").grade("高一").build();
        List<Result> all = List.of(result(1L, 9.0, 1, ca), result(2L, 7.0, 2, ca), result(3L, 6.0, 3, ca));
        when(resultRepository.findByEventIdOrderByTotalRankAsc(1L)).thenReturn(all);

        Map<String, Object> ranking = rankingService.getEventRanking(1L);
        List<Map<String, Object>> list = (List<Map<String, Object>>) ranking.get("rankings");
        assertEquals(3, list.size());
        assertEquals(1, list.get(0).get("rank"));
        assertEquals(3, ranking.get("totalCount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getScoreBoard_aggregatesGenderAndParade() {
        ClassInfo ca = ClassInfo.builder().id(1L).name("高一1班").grade("高一").build();
        ClassInfo cb = ClassInfo.builder().id(2L).name("高一2班").grade("高一").build();
        List<Result> all = List.of(
                result(1L, 9.0, 1, ca),               // 男
                result(2L, 7.0, 2, ca, "女"),         // 女
                result(3L, 6.0, 3, cb));              // 男
        when(resultRepository.findAllValid()).thenReturn(all);
        when(paradeScoreRepository.findAllActive()).thenReturn(List.of(
                ParadeScore.builder().id(1L).classInfo(ca).className("高一1班").grade("高一").score(5.0).build()));
        when(systemService.getScoringRule()).thenReturn(defaultRule("class", "total_score"));

        Map<String, Object> board = rankingService.getScoreBoard(null, true, 0, false, null);

        List<Map<String, Object>> rows = (List<Map<String, Object>>) board.get("rows");
        assertEquals(2, rows.size());

        Map<String, Object> a = rows.get(0);
        assertEquals("高一1班", a.get("className"));
        assertEquals(9.0, (Double) a.get("maleScore"), 0.001);
        assertEquals(7.0, (Double) a.get("femaleScore"), 0.001);
        assertEquals(16.0, (Double) a.get("totalScore"), 0.001);
        assertEquals(5.0, (Double) a.get("paradeScore"), 0.001);
        // includeParade=true → totalWithParade = 赛事 16 + 入场式 5 = 21
        assertEquals(21.0, (Double) a.get("totalWithParade"), 0.001);
        assertEquals(1, a.get("rank"));

        // 高一2班未录入入场式 → 不累加入场式分
        Map<String, Object> b = rows.get(1);
        assertEquals(Boolean.FALSE, b.get("hasParade"));
        assertEquals(6.0, (Double) b.get("totalWithParade"), 0.001);
        assertEquals(2, b.get("rank"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getScoreBoard_gradeFilterAndTopN() {
        ClassInfo ca = ClassInfo.builder().id(1L).name("高一1班").grade("高一").build();
        ClassInfo cb = ClassInfo.builder().id(3L).name("高二1班").grade("高二").build();
        List<Result> all = List.of(
                result(1L, 9.0, 1, ca),
                result(2L, 6.0, 2, cb));
        when(resultRepository.findAllValid()).thenReturn(all);
        when(paradeScoreRepository.findAllActive()).thenReturn(List.of());
        when(systemService.getScoringRule()).thenReturn(defaultRule("class", "total_score"));

        // 按年级过滤：只统计高二 → 1 班
        Map<String, Object> board = rankingService.getScoreBoard("高二", false, 0, false, null);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) board.get("rows");
        assertEquals(1, rows.size());
        assertEquals("高二1班", rows.get(0).get("className"));

        // topN=1：只返回第一名
        Map<String, Object> board2 = rankingService.getScoreBoard(null, false, 1, false, null);
        List<Map<String, Object>> top = (List<Map<String, Object>>) board2.get("top");
        assertEquals(1, top.size());
        assertEquals("高一1班", top.get(0).get("className"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getScoreBoard_genderDimensionFiltersMaleAndFemale() {
        ClassInfo ca = ClassInfo.builder().id(1L).name("高一1班").grade("高一").build();
        // 库内真实存法：M / F（及中文字符兼容）
        List<Result> all = List.of(
                result(1L, 9.0, 1, ca, "M"),
                result(2L, 7.0, 2, ca, "F"),
                result(3L, 5.0, 3, ca, "女"));
        when(resultRepository.findAllValid()).thenReturn(all);
        when(paradeScoreRepository.findAllActive()).thenReturn(List.of());
        when(systemService.getScoringRule()).thenReturn(defaultRule("class", "total_score"));

        // 男生榜：只含 M（男）得分 9
        Map<String, Object> maleBoard = rankingService.getScoreBoard(null, false, 0, false, "男");
        List<Map<String, Object>> maleRows = (List<Map<String, Object>>) maleBoard.get("rows");
        assertEquals(1, maleRows.size());
        assertEquals(9.0, (Double) maleRows.get(0).get("totalScore"), 0.001);
        assertEquals(9.0, (Double) maleRows.get(0).get("maleScore"), 0.001);

        // 女生榜：F 7 + 女 5 = 12
        Map<String, Object> femaleBoard = rankingService.getScoreBoard(null, false, 0, false, "女");
        List<Map<String, Object>> femaleRows = (List<Map<String, Object>>) femaleBoard.get("rows");
        assertEquals(1, femaleRows.size());
        assertEquals(12.0, (Double) femaleRows.get(0).get("totalScore"), 0.001);
        assertEquals(12.0, (Double) femaleRows.get(0).get("femaleScore"), 0.001);

        // 不带性别过滤：男 M 9 + 女 F 7 + 5 = 21
        Map<String, Object> allBoard = rankingService.getScoreBoard(null, false, 0, false, null);
        List<Map<String, Object>> allRows = (List<Map<String, Object>>) allBoard.get("rows");
        assertEquals(21.0, (Double) allRows.get(0).get("totalScore"), 0.001);
    }

    private Map<String, Object> defaultRule(String type, String sort) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("team_score_type", type);
        rule.put("team_score_sort", sort);
        return rule;
    }
}
