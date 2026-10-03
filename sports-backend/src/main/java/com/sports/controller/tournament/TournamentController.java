package com.sports.controller.tournament;

import com.sports.common.web.ApiResponse;
import com.sports.schedule.tournament.EliminationGenerator;
import com.sports.schedule.tournament.HybridGenerator;
import com.sports.schedule.tournament.RoundRobinGenerator;
import com.sports.schedule.ai.TournamentAiService;
import com.sports.schedule.ai.TournamentGnnEncoder;
import com.sports.schedule.tournament.VolleyballTournament;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 球赛赛制生成接口（循环 / 淘汰 / 混合；排球示例）。
 *
 * <p>输入参赛队伍 → 输出赛制结构（对阵图），供「赛程编排」在结构之上分配时间槽与场地。
 * 与田径编排（时间槽着色）是两个独立的问题，故独立成控制器。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/tournament")
@Tag(name = "球赛赛制生成", description = "循环赛 / 淘汰赛 / 混合赛制 / 排球赛制结构生成")
public class TournamentController {

    private final TournamentAiService tournamentAi;

    public TournamentController(TournamentAiService tournamentAi) {
        this.tournamentAi = tournamentAi;
    }

    /**
     * 请求体：参赛队伍 + 赛制参数。
     *
     * @param format       null / "auto" 时交给 AI 选赛制（此前只能人工指定）
     * @param strengths    每支队伍的实力 0..1（1 最强），与 teams 同序；缺省按中等实力处理
     * @param units        每支队伍所属单位（班），用于「同班应错开」约束；可缺省
     * @param venuePrefs   每支队伍偏好场地；可缺省
     */
    public record TournamentRequest(List<String> teams, String format, Integer groups,
                                    Integer advancePerGroup, Boolean doubleLeg, Integer bestOf,
                                    List<Double> strengths, List<String> units,
                                    List<String> venuePrefs, Integer minutesPerMatch,
                                    Integer availableSlots, Integer days) {
    }

    @PostMapping("/generate")
    @Operation(summary = "生成赛制结构（round_robin / elimination / hybrid / volleyball）")
    public ApiResponse<Map<String, Object>> generate(@RequestBody TournamentRequest req) {
        List<String> teams = req.teams() == null ? List.of() : req.teams();
        // 🔴 format 为 null/空/auto 时交给 AI 选；此前这里硬编码 "hybrid"，
        //    4 队打 12 场循环这种明显不合适的场景也照样走混合赛制。
        String requested = req.format() == null ? "auto" : req.format().toLowerCase();
        Map<String, Object> aiAdvice = new LinkedHashMap<>();
        String aiFormat = null;
        if (requested.isBlank() || "auto".equals(requested)) {
            aiAdvice = recommendByAi(req, teams);
            aiFormat = (String) aiAdvice.get("recommendedFormat");
        }
        String format = aiFormat != null ? aiFormat
                : (requested.isBlank() ? "hybrid" : requested);
        int groups = req.groups() == null ? 2 : req.groups();
        int advance = req.advancePerGroup() == null ? 2 : req.advancePerGroup();
        boolean doubleLeg = Boolean.TRUE.equals(req.doubleLeg());
        int bestOf = req.bestOf() == null ? 5 : req.bestOf();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("teamCount", teams.size());
        out.put("format", format);

        switch (format) {
            case "round_robin", "roundrobin", "round-robin" -> {
                List<RoundRobinGenerator.Match> ms = RoundRobinGenerator.generate(teams, doubleLeg, true);
                out.put("matches", ms);
                out.put("matchCount", ms.size());
            }
            case "elimination", "knockout" -> {
                List<EliminationGenerator.Match> ms = EliminationGenerator.single(teams);
                out.put("matches", ms);
                out.put("matchCount", ms.size());
            }
            case "volleyball" -> {
                out.putAll(VolleyballTournament.plan(teams, groups, advance, bestOf));
            }
            default -> {
                HybridGenerator.Plan p = HybridGenerator.plan(teams, groups, advance, doubleLeg);
                out.put("groups", p.groups());
                out.put("groupMatches", p.groupMatches());
                out.put("crossPairs", p.crossPairs());
                out.put("knockout", p.knockout());
            }
        }
        if (!aiAdvice.isEmpty()) {
            out.put("aiAdvice", aiAdvice);
        }
        log.info("赛制生成: format={}, 队伍 {} 支, ai={}",
                format, teams.size(), aiFormat != null);
        return ApiResponse.success(out);
    }

    /**
     * 让 AI 决定赛制。
     *
     * <p>返回一个 map 至少含 {@code recommendedFormat}；模型不可用或规模超限时
     * 返回空 map（此时上层用默认 hybrid），<b>不抛异常</b>。</p>
     */
    private Map<String, Object> recommendByAi(TournamentRequest req, List<String> teams) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            int n = teams.size();
            if (n < 3) {
                return out;
            }
            List<TournamentGnnEncoder.Team> ts = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                double st = (req.strengths() != null && i < req.strengths().size()
                        && req.strengths().get(i) != null) ? req.strengths().get(i) : 0.5;
                String unit = (req.units() != null && i < req.units().size()
                        && req.units().get(i) != null) ? req.units().get(i) : "";
                String pref = (req.venuePrefs() != null && i < req.venuePrefs().size()
                        && req.venuePrefs().get(i) != null) ? req.venuePrefs().get(i) : "A";
                ts.add(new TournamentGnnEncoder.Team(teams.get(i), st, unit, pref));
            }
            int minutes = req.minutesPerMatch() == null ? 40 : req.minutesPerMatch();
            int slots = req.availableSlots() == null ? 0 : req.availableSlots();
            int days = req.days() == null ? 1 : req.days();
            TournamentGnnEncoder.Encoded enc =
                    tournamentAi.getEncoder().encode(ts, List.of("A", "B", "C"), minutes, slots, days);
            if (enc.degraded()) {
                out.put("degraded", true);
                out.put("reason", enc.degradeReason());
                return out;
            }
            var advice = tournamentAi.advise(enc);
            if (advice.isEmpty()) {
                out.put("degraded", true);
                out.put("reason", tournamentAi.modelInfo().get("error") == null
                        ? "模型不可用" : String.valueOf(tournamentAi.modelInfo().get("error")));
                return out;
            }
            TournamentAiService.Advice a = advice.get();
            double[] probs = a.formatProbabilities();
            out.put("recommendedFormat", a.recommendedFormat());
            out.put("formatProbabilities", java.util.Map.of(
                    TournamentAiService.FORMAT_NAMES[0], probs[0],
                    TournamentAiService.FORMAT_NAMES[1], probs[Math.min(1, probs.length - 1)],
                    TournamentAiService.FORMAT_NAMES[2], probs[Math.min(2, probs.length - 1)]));
            // 种子排序分 → 按分数降序的队伍名（AI 建议的签位顺序）
            Integer[] idx = new Integer[a.seedScores().length];
            for (int i = 0; i < idx.length; i++) {
                idx[i] = i;
            }
            java.util.Arrays.sort(idx, (x, y) -> Double.compare(a.seedScores()[y], a.seedScores()[x]));
            java.util.List<String> seeded = new java.util.ArrayList<>();
            for (int i : idx) {
                seeded.add(teams.get(i));
            }
            out.put("seedOrder", seeded);
            double avgFair = java.util.Arrays.stream(a.fairnessCost()).average().orElse(0.0);
            out.put("avgFairnessCost", avgFair);
            out.put("teamCount", a.n());
        } catch (RuntimeException e) {
            log.warn("AI 赛制推荐失败，回退默认赛制: {}", e.toString());
            out.put("degraded", true);
            out.put("reason", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return out;
    }
}
