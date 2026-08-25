package com.sports.service;

import com.sports.entity.Athlete;
import com.sports.entity.Event;
import com.sports.entity.Result;
import com.sports.repository.ArrangementRepository;
import com.sports.repository.EventRepository;
import com.sports.repository.ResultRepository;
import com.sports.repository.SystemConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 成绩服务测试。
 * 覆盖：时间字符串解析（秒/分:秒/时:分:秒/非法）、排名与积分计算、并列处理。
 */
@ExtendWith(MockitoExtension.class)
class ResultServiceTest {

    @Mock private ResultRepository resultRepository;
    @Mock private ArrangementRepository arrangementRepository;
    @Mock private EventRepository eventRepository;
    @Mock private SystemConfigRepository systemConfigRepository;
    @Mock private ExcelService excelService;
    @Mock private SystemService systemService;

    @InjectMocks private ResultService resultService;

    private Event trackEvent() {
        return Event.builder().id(100L).name("100m").category("径赛").record(null).build();
    }

    private Result result(Long id, double seconds, String raw) {
        Athlete a = Athlete.builder().id(id).name("R" + id).grade("高一").gender("男").build();
        return Result.builder().id(id).athlete(a).event(trackEvent())
                .rawTime(raw).timeSeconds(seconds).status("valid").build();
    }

    @Test
    void parseTime_variousFormats() throws Exception {
        Method m = ResultService.class.getDeclaredMethod("parseTime", String.class);
        m.setAccessible(true);

        assertEquals(12.34, (Double) m.invoke(resultService, "12.34"), 1e-9);
        assertEquals(83.45, (Double) m.invoke(resultService, "1:23.45"), 1e-9);
        assertEquals(3723.45, (Double) m.invoke(resultService, "1:02:03.45"), 1e-9);
        assertNull(m.invoke(resultService, (Object) null));
        assertNull(m.invoke(resultService, ""));
        assertNull(m.invoke(resultService, "bad"));
    }

    @Test
    void calculateRanking_assignsRanksAndScores() {
        List<Result> results = List.of(result(1L, 12.0, "12.00"), result(2L, 11.0, "11.00"), result(3L, 10.0, "10.00"));
        when(resultRepository.findValidByEventId(100L)).thenReturn(results);
        when(eventRepository.findById(100L)).thenReturn(Optional.of(trackEvent()));
        when(systemService.getScoringRule()).thenReturn(defaultScoringRule());
        when(resultRepository.save(any(Result.class))).thenAnswer(inv -> inv.getArgument(0));

        List<Result> ranked = resultService.calculateRanking(100L);

        // 已按时间升序排序：10.00(第1), 11.00(第2), 12.00(第3)
        assertEquals(1, ranked.get(0).getTotalRank());
        assertEquals(9.0, ranked.get(0).getScore());
        assertEquals(2, ranked.get(1).getTotalRank());
        assertEquals(7.0, ranked.get(1).getScore());
        assertEquals(3, ranked.get(2).getTotalRank());
        assertEquals(6.0, ranked.get(2).getScore());
    }

    @Test
    void calculateRanking_sameRankTieHandling() {
        // 两人并列第一（相同时间），第三人单独第二
        List<Result> results = List.of(result(1L, 10.0, "10.00"), result(2L, 10.0, "10.00"), result(3L, 12.0, "12.00"));
        when(resultRepository.findValidByEventId(100L)).thenReturn(results);
        when(eventRepository.findById(100L)).thenReturn(Optional.of(trackEvent()));
        when(systemService.getScoringRule()).thenReturn(defaultScoringRule());
        when(resultRepository.save(any(Result.class))).thenAnswer(inv -> inv.getArgument(0));

        List<Result> ranked = resultService.calculateRanking(100L);
        assertEquals(1, ranked.get(0).getTotalRank());
        assertEquals(1, ranked.get(1).getTotalRank());
        assertEquals(9.0, ranked.get(0).getScore());
        assertEquals(2, ranked.get(2).getTotalRank());
        assertEquals(7.0, ranked.get(2).getScore());
    }

    private Map<String, Object> defaultScoringRule() {
        Map<String, Object> rule = new LinkedHashMap<>();
        Map<String, Object> rankScores = new LinkedHashMap<>();
        rankScores.put("1", 9); rankScores.put("2", 7); rankScores.put("3", 6);
        rankScores.put("4", 5); rankScores.put("5", 4); rankScores.put("6", 3);
        rankScores.put("7", 2); rankScores.put("8", 1);
        rule.put("rank_scores", rankScores);
        rule.put("tie_handling", "same_rank");
        rule.put("record_bonus_enabled", false);
        rule.put("participation_score_enabled", false);
        rule.put("relay_multiplier", 2.0);
        return rule;
    }
}
