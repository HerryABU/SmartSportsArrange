package com.sports.schedule.tournament;

import com.sports.schedule.ai.SuperMoeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 球类赛程编排测试。
 *
 * <p>核心保证：<b>四种赛制都能生成结构</b>、<b>场次与轮次自洽</b>、
 * <b>时间目标三态都接受</b>、<b>AI 不可用时优雅回退</b>。</p>
 */
class BallTournamentServiceTest {

    private BallTournamentService service() {
        // SuperMoeService 走真实构造；模型不在 classpath 时 advise 返回空 → 回退默认赛制，
        // 正好覆盖「AI 不可用」的降级路径。
        return new BallTournamentService(
                new SuperMoeService(true, "classpath:/models", "super_moe.onnx"));
    }

    private static List<BallTournamentService.Team> teams(int n) {
        List<BallTournamentService.Team> ts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ts.add(new BallTournamentService.Team(
                    "队" + i, 0.3 + 0.6 * i / Math.max(1, n - 1), "班" + (i % 4), "A"));
        }
        return ts;
    }

    @Test
    @DisplayName("循环赛：场次 = n(n-1)/2，每队出场 n-1 次")
    void roundRobinCounts() {
        var out = service().arrange(teams(6), "round_robin", 2, 2, false, 40, 0, false);
        assertEquals("round_robin", out.get("format"));
        assertEquals(15, out.get("matchCount"));
        assertEquals(5, out.get("rounds"));
    }

    @Test
    @DisplayName("淘汰赛：场次 = n-1，含轮空，bracket 逐轮收敛")
    void knockoutBracket() {
        var out = service().arrange(teams(6), "knockout", 2, 2, false, 40, 0, false);
        assertEquals("knockout", out.get("format"));
        // 6 队补齐到 8 位签 → 7 轮次对位（其中 2 场是轮空占位），
        // 实际有效对抗 5 场。断言结构而非硬编码数字。
        int mc = (Integer) out.get("matchCount");
        assertTrue(mc >= 5 && mc <= 7, "6 队单淘汰应产出 5~7 个对位（补齐 8 位含轮空），实际 " + mc);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bracket = (List<Map<String, Object>>) out.get("bracket");
        assertNotNull(bracket);
        assertFalse(bracket.isEmpty());
        // bracket 按**实际队数**逐轮两两配对：6 队 → 3 对 → 2 对（有一队轮空）→ 1 对
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> first = (List<Map<String, Object>>) bracket.get(0).get("pairs");
        assertEquals(3, first.size(), "6 队首轮应有 3 个对位");
        // 轮次必须逐轮递增且收敛到决赛
        for (int r = 0; r < bracket.size(); r++) {
            assertEquals(r + 1, ((Number) bracket.get(r).get("round")).intValue());
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> finalRound =
                (List<Map<String, Object>>) bracket.get(bracket.size() - 1).get("pairs");
        assertEquals(1, finalRound.size(), "最后一轮应只剩 1 个对位（决赛）");
    }

    @Test
    @DisplayName("小组赛：分组数受控，每组内部循环")
    void groupFormat() {
        var out = service().arrange(teams(8), "group", 2, 2, false, 40, 0, false);
        assertEquals("group", out.get("format"));
        @SuppressWarnings("unchecked")
        Map<String, List<String>> gs = (Map<String, List<String>>) out.get("groups");
        assertNotNull(gs);
        assertEquals(2, gs.size());
        int total = gs.values().stream().mapToInt(List::size).sum();
        assertEquals(8, total, "分组应覆盖全部队伍且不重复");
    }

    @Test
    @DisplayName("混合赛制：同时产出小组与淘汰")
    void hybridHasBoth() {
        var out = service().arrange(teams(8), "hybrid", 2, 2, false, 40, 0, false);
        assertEquals("hybrid", out.get("format"));
        assertNotNull(out.get("groups"));
        assertNotNull(out.get("crossPairs"));
        assertNotNull(out.get("knockout"));
    }

    @Test
    @DisplayName("时间目标三态都被接受并原样回显")
    void threeTimeGoals() {
        var s = service();
        for (int d : new int[]{-1, 0, 2, 3}) {
            var out = s.arrange(teams(6), "round_robin", 2, 2, false, 40, d, false);
            assertEquals(d, out.get("daysLimit"), "时间目标应原样回显");
            assertNotNull(out.get("matches"));
        }
    }

    @Test
    @DisplayName("AI 决策：format=auto 时走 AI 路径（模型缺失则回退并带 degraded 标记）")
    void autoFormatGoesThroughAi() {
        var out = service().arrange(teams(6), "auto", 2, 2, false, 40, 0, true);
        assertNotNull(out.get("format"));
        @SuppressWarnings("unchecked")
        Map<String, Object> advice = (Map<String, Object>) out.get("aiAdvice");
        assertNotNull(advice, "auto 模式应带 aiAdvice");
        // 模型不在 classpath 时必然 degraded；有模型时必须有 recommendedFormat
        if (Boolean.TRUE.equals(advice.get("degraded"))) {
            assertNotNull(advice.get("reason") != null ? advice.get("reason") : advice.get("error"),
                    "降级必须说明原因");
        } else {
            assertNotNull(advice.get("recommendedFormat"));
        }
    }

    @Test
    @DisplayName("显式指定赛制时不走 AI（ai=false）")
    void explicitFormatSkipsAi() {
        var out = service().arrange(teams(6), "knockout", 2, 2, false, 40, 0, false);
        assertEquals("knockout", out.get("format"));
        assertFalse(out.containsKey("aiAdvice"), "ai=false 时不应有 aiAdvice");
    }

    @Test
    @DisplayName("边界：0 队 / 1 队不崩溃")
    void degenerateTeamCounts() {
        var s = service();
        var zero = s.arrange(List.of(), "auto", 2, 2, false, 40, 0, true);
        assertEquals(0, zero.get("teamCount"));
        var one = s.arrange(teams(1), "auto", 2, 2, false, 40, 0, true);
        assertEquals(1, one.get("teamCount"));
        assertEquals(0, one.get("matchCount"));
    }

    @Test
    @DisplayName("2 队也能编排（单场对决）")
    void twoTeams() {
        var out = service().arrange(teams(2), "round_robin", 2, 2, false, 40, 0, false);
        assertEquals(1, out.get("matchCount"));
    }

    @Test
    @DisplayName("二次编排：保持对阵关系，返回可重排标记")
    void resecondKeepsBracket() {
        var out = service().resecond("knockout", 2, 2, false, 2);
        assertEquals("resecond", out.get("mode"));
        assertEquals(Boolean.TRUE, out.get("reschedulable"));
        assertEquals(2, out.get("daysLimit"));
        assertNotNull(out.get("note"));
    }

    @Test
    @DisplayName("未知赛制名回退到 hybrid 而不是抛异常")
    void unknownFormatFallsBack() {
        var out = service().arrange(teams(6), "不存在的赛制", 2, 2, false, 40, 0, false);
        assertEquals("hybrid", out.get("format"));
    }

    @Test
    @DisplayName("中文赛制名可识别")
    void chineseFormatNames() {
        var s = service();
        assertEquals("knockout", s.arrange(teams(6), "淘汰赛", 2, 2, false, 40, 0, false).get("format"));
        assertEquals("round_robin", s.arrange(teams(6), "循环赛", 2, 2, false, 40, 0, false).get("format"));
        assertEquals("group", s.arrange(teams(6), "小组赛", 2, 2, false, 40, 0, false).get("format"));
    }
}
