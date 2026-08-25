package com.sports.service;

import com.sports.entity.Athlete;
import com.sports.entity.ClassInfo;
import com.sports.entity.Event;
import com.sports.entity.Result;
import com.sports.repository.ClassInfoRepository;
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
 * 覆盖：团体总分聚合与排序、个人积分聚合与分页、单项目排名。
 */
@ExtendWith(MockitoExtension.class)
class RankingServiceTest {

    @Mock private ResultRepository resultRepository;
    @Mock private ClassInfoRepository classInfoRepository;
    @Mock private SystemService systemService;

    @InjectMocks private RankingService rankingService;

    private Result result(Long id, double score, Integer rank, ClassInfo ci) {
        Athlete a = Athlete.builder().id(id).name("R" + id).grade("高一")
                .gender("男").classInfo(ci).build();
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

    private Map<String, Object> defaultRule(String type, String sort) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("team_score_type", type);
        rule.put("team_score_sort", sort);
        return rule;
    }
}
