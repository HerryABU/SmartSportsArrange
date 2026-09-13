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

        Map<String, Object> resp = (Map<String, Object>) rankingService.getTeamScores(null);
        List<Map<String, Object>> teams = (List<Map<String, Object>>) resp.get("records");
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

    // ==================== B12/U18：名次必须与排序口径一致 ====================

    /**
     * 总分相同但奖牌不同 → 名次必须区分。
     * 旧实现只拿总分当唯一键，会把这种「总分并列」直接判为同名次，
     * 丢掉排序已经用过的金/银/铜 tie-break（B12 的高二8班 vs 高二6班正是此形）。
     */
    @Test
    @SuppressWarnings("unchecked")
    void getScoreBoard_rankHonoursMedalTieBreakers() {
        ClassInfo c8 = ClassInfo.builder().id(1L).name("高二8班").grade("高二").build();
        ClassInfo c6 = ClassInfo.builder().id(2L).name("高二6班").grade("高二").build();
        List<Result> all = List.of(
                result(1L, 9.0, 1, c8),    // 高二8班：1 金，总分 9
                result(2L, 9.0, 9, c6));   // 高二6班：0 金，总分 9
        when(resultRepository.findAllValid()).thenReturn(all);
        when(paradeScoreRepository.findAllActive()).thenReturn(List.of());
        when(systemService.getScoringRule()).thenReturn(defaultRule("class", "total_score"));

        Map<String, Object> board = rankingService.getScoreBoard(null, false, 0, false, null);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) board.get("rows");

        assertEquals("高二8班", rows.get(0).get("className"));
        assertEquals("高二6班", rows.get(1).get("className"));
        assertEquals(1, rows.get(0).get("rank"));
        assertEquals(2, rows.get(1).get("rank"), "总分相同但金牌不同，名次不得并列");
        assertEquals(Boolean.FALSE, rows.get(1).get("tied"));
    }

    /** 金/银/铜/有效总分四项全同 → 名次并列（标准竞赛排名，取首个位次） */
    @Test
    @SuppressWarnings("unchecked")
    void getScoreBoard_fullyTiedTeamsShareRank() {
        ClassInfo c1 = ClassInfo.builder().id(1L).name("高一1班").grade("高一").build();
        ClassInfo c2 = ClassInfo.builder().id(2L).name("高一2班").grade("高一").build();
        List<Result> all = List.of(
                result(1L, 9.0, 1, c1),
                result(2L, 9.0, 1, c2));
        when(resultRepository.findAllValid()).thenReturn(all);
        when(paradeScoreRepository.findAllActive()).thenReturn(List.of());
        when(systemService.getScoringRule()).thenReturn(defaultRule("class", "total_score"));

        Map<String, Object> board = rankingService.getScoreBoard(null, false, 0, false, null);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) board.get("rows");

        assertEquals(1, rows.get(0).get("rank"));
        assertEquals(1, rows.get(1).get("rank"), "四项分解键全同应并列同名次");
        assertEquals(Boolean.TRUE, rows.get(1).get("tied"));
    }

    /** 团体总分榜：四项分解键全同的班级并列同名次，与 tieRuleNote 的表述一致 */
    @Test
    @SuppressWarnings("unchecked")
    void getTeamScores_fullyTiedTeamsShareRank() {
        ClassInfo c1 = ClassInfo.builder().id(1L).name("高二8班").grade("高二").build();
        ClassInfo c2 = ClassInfo.builder().id(2L).name("高二6班").grade("高二").build();
        List<Result> all = List.of(
                result(1L, 9.0, 1, c1),
                result(2L, 9.0, 1, c2));
        when(resultRepository.findAllValid()).thenReturn(all);
        when(systemService.getScoringRule()).thenReturn(defaultRule("class", "total_score"));

        Map<String, Object> resp = (Map<String, Object>) rankingService.getTeamScores(null);
        List<Map<String, Object>> teams = (List<Map<String, Object>>) resp.get("records");

        assertEquals(2, teams.size());
        assertEquals(1, teams.get(0).get("rank"));
        assertEquals(1, teams.get(1).get("rank"), "四项分解键全同的班级应并列");
        assertEquals(Boolean.TRUE, teams.get(1).get("tied"));
        // medalCount 语义修正后 = 金+银+铜（各 1 枚金）
        assertEquals(1, teams.get(0).get("medalCount"));
    }

    /**
     * B08/U08 回归：个人积分榜的并列名次。
     * 默认口径 same_rank = 密集排名（1,1,2），并列者同名次并带 tied 标记，
     * 且榜单附带并列规则说明——旧实现恒 rank++，两人同为 9 分会显示「第1、第2」。
     */
    @Test
    @SuppressWarnings("unchecked")
    void getIndividualScores_tiesShareRankDense() {
        ClassInfo ca = ClassInfo.builder().id(1L).name("高一1班").grade("高一").build();
        List<Result> all = List.of(
                result(1L, 9.0, 1, ca),
                result(2L, 9.0, 1, ca),
                result(3L, 5.0, 3, ca));
        when(resultRepository.findAllValid()).thenReturn(all);
        when(systemService.getScoringRule()).thenReturn(defaultRuleWithTie("same_rank"));

        Map<String, Object> page = (Map<String, Object>) rankingService.getIndividualScores(null, null, 1, 10);
        List<Map<String, Object>> records = (List<Map<String, Object>>) page.get("records");

        assertEquals(3, records.size());
        assertEquals(1, records.get(0).get("rank"));
        assertEquals(1, records.get(1).get("rank"), "同为 9 分应并列第 1");
        assertEquals(Boolean.TRUE, records.get(1).get("tied"));
        assertEquals(2, records.get(2).get("rank"), "密集排名下 5 分应接第 2 名");
        assertEquals("same_rank", page.get("tieHandling"));
        assertNotNull(page.get("tieRuleNote"));
    }

    /** B08/U08：tie_handling=sequential 时改为标准竞赛排名（1,1,3，被占名次不补授） */
    @Test
    @SuppressWarnings("unchecked")
    void getIndividualScores_tiesSkipRankWhenSequential() {
        ClassInfo ca = ClassInfo.builder().id(1L).name("高一1班").grade("高一").build();
        List<Result> all = List.of(
                result(1L, 9.0, 1, ca),
                result(2L, 9.0, 1, ca),
                result(3L, 5.0, 3, ca));
        when(resultRepository.findAllValid()).thenReturn(all);
        when(systemService.getScoringRule()).thenReturn(defaultRuleWithTie("sequential"));

        Map<String, Object> page = (Map<String, Object>) rankingService.getIndividualScores(null, null, 1, 10);
        List<Map<String, Object>> records = (List<Map<String, Object>>) page.get("records");

        assertEquals(1, records.get(0).get("rank"));
        assertEquals(1, records.get(1).get("rank"));
        assertEquals(3, records.get(2).get("rank"), "sequential 下并列占位，第三名应接第 3");
        assertEquals("sequential", page.get("tieHandling"));
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

    private Result resultInGrade(Long id, double score, Integer rank, ClassInfo ci, String grade) {
        Athlete a = Athlete.builder().id(id).name("R" + id).grade(grade)
                .gender("男").classInfo(ci).build();
        return Result.builder().id(id).athlete(a).event(Event.builder().id(1L).build())
                .score(score).totalRank(rank).status("valid").build();
    }

    /**
     * B08/U08 回归：并列标记必须按「年级 × 名次」判定。
     * 旧实现把各年级并列名次并进同一个 Set&lt;Integer&gt; 后用 rank 单键判断，
     * 于是高一第2名并列会把高二第2名（唯一）也标成并列。
     */
    @Test
    @SuppressWarnings("unchecked")
    void getEventRanking_tieFlagIsPerGradeNotGlobal() {
        ClassInfo ca = ClassInfo.builder().id(1L).name("高一1班").grade("高一").build();
        ClassInfo cb = ClassInfo.builder().id(2L).name("高二1班").grade("高二").build();
        List<Result> all = List.of(
                resultInGrade(1L, 7.0, 2, ca, "高一"),   // 高一第2名（并列）
                resultInGrade(2L, 7.0, 2, ca, "高一"),   // 高一第2名（并列）
                resultInGrade(3L, 7.0, 2, cb, "高二"));  // 高二第2名（本年级唯一）
        when(resultRepository.findByEventIdOrderByTotalRankAsc(1L)).thenReturn(all);
        when(systemService.getScoringRule()).thenReturn(defaultRule("class", "total_score"));

        Map<String, Object> ranking = rankingService.getEventRanking(1L);
        List<Map<String, Object>> list = (List<Map<String, Object>>) ranking.get("rankings");

        assertEquals(Boolean.TRUE, list.get(0).get("tied"));
        assertEquals(Boolean.TRUE, list.get(1).get("tied"));
        assertEquals(Boolean.FALSE, list.get(2).get("tied"),
                "高一第2名并列不得把高二第2名（唯一）也标成并列");
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

    /**
     * R-1 严密性：四项分解键全同的班级名次并列，但列表顺序必须确定（按 classId 升序），
     * 不随 findAllValid 返回顺序漂移——否则两次请求可能给出不同次序。
     * 故意以「逆序」返回（高一2班 id=2 在前、高一1班 id=1 在后）验证排序与插入序无关。
     */
    @Test
    @SuppressWarnings("unchecked")
    void getTeamScores_fullyTiedTeamsHaveStableOrder() {
        ClassInfo c2 = ClassInfo.builder().id(2L).name("高一2班").grade("高一").build();
        ClassInfo c1 = ClassInfo.builder().id(1L).name("高一1班").grade("高一").build();
        List<Result> reversed = List.of(
                result(2L, 9.0, 1, c2),
                result(1L, 9.0, 1, c1));
        when(resultRepository.findAllValid()).thenReturn(reversed);
        when(systemService.getScoringRule()).thenReturn(defaultRule("class", "total_score"));

        Map<String, Object> resp = (Map<String, Object>) rankingService.getTeamScores(null);
        List<Map<String, Object>> teams = (List<Map<String, Object>>) resp.get("records");
        assertEquals(2, teams.size());
        assertEquals("高一1班", teams.get(0).get("className"), "classId 小者稳定排前，与返回顺序无关");
        assertEquals(1, teams.get(0).get("rank"));
        assertEquals("高一2班", teams.get(1).get("className"));
        assertEquals(1, teams.get(1).get("rank"), "四项分解键全同仍并列同名次");
    }

    /** R-1：合分排行同样在四项键全同时按 classId 确定性排序 */
    @Test
    @SuppressWarnings("unchecked")
    void getScoreBoard_fullyTiedRowsHaveStableOrder() {
        ClassInfo c2 = ClassInfo.builder().id(2L).name("高一2班").grade("高一").build();
        ClassInfo c1 = ClassInfo.builder().id(1L).name("高一1班").grade("高一").build();
        List<Result> reversed = List.of(
                result(2L, 9.0, 1, c2),
                result(1L, 9.0, 1, c1));
        when(resultRepository.findAllValid()).thenReturn(reversed);
        when(paradeScoreRepository.findAllActive()).thenReturn(List.of());
        when(systemService.getScoringRule()).thenReturn(defaultRule("class", "total_score"));

        Map<String, Object> board = rankingService.getScoreBoard(null, false, 0, false, null);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) board.get("rows");
        assertEquals("高一1班", rows.get(0).get("className"), "逆序返回下 classId 小者仍稳定排前");
        assertEquals("高一2班", rows.get(1).get("className"));
    }

    /**
     * R-2 严密性：非完赛者（DNF，status=dnf，totalRank=null）必须排到有效成绩之后，
     * 且携带 statusLabel="DNF"——此前因 totalRank=null 会被 ORDER BY 排到冠军之前。
     * 故意把 DNF 行放在最前，验证排序与返回顺序无关。
     */
    @Test
    @SuppressWarnings("unchecked")
    void getEventRanking_putsNonFinishersLastWithLabel() {
        ClassInfo ca = ClassInfo.builder().id(1L).name("高一1班").grade("高一").build();
        List<Result> all = List.of(
                resultDnf(9L, ca),
                result(1L, 9.0, 1, ca),
                result(2L, 7.0, 2, ca),
                result(3L, 6.0, 3, ca));
        when(resultRepository.findByEventIdOrderByTotalRankAsc(1L)).thenReturn(all);

        Map<String, Object> ranking = rankingService.getEventRanking(1L);
        List<Map<String, Object>> list = (List<Map<String, Object>>) ranking.get("rankings");
        assertEquals(4, list.size());
        assertEquals(1, list.get(0).get("rank"));
        assertEquals(2, list.get(1).get("rank"));
        assertEquals(3, list.get(2).get("rank"));

        // 末位必须是 DNF，且带标签、无名次、不误标并列
        Map<String, Object> last = list.get(3);
        assertEquals("DNF", last.get("statusLabel"));
        assertNull(last.get("rank"));
        assertEquals(Boolean.FALSE, last.get("tied"));
    }

    /** 构造一条非完赛成绩（status 非 valid，totalRank=null） */
    private Result resultDnf(Long id, ClassInfo ci) {
        Athlete a = Athlete.builder().id(id).name("DNF" + id).grade("高一")
                .gender("男").classInfo(ci).build();
        return Result.builder().id(id).athlete(a).event(Event.builder().id(1L).build())
                .status("dnf").totalRank(null).rawTime("DNF").build();
    }

    private Map<String, Object> defaultRule(String type, String sort) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("team_score_type", type);
        rule.put("team_score_sort", sort);
        return rule;
    }

    private Map<String, Object> defaultRuleWithTie(String tieHandling) {
        Map<String, Object> rule = defaultRule("class", "total_score");
        rule.put("tie_handling", tieHandling);
        return rule;
    }
}
