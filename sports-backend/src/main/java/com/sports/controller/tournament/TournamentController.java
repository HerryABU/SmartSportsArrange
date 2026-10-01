package com.sports.controller.tournament;

import com.sports.common.web.ApiResponse;
import com.sports.schedule.tournament.EliminationGenerator;
import com.sports.schedule.tournament.HybridGenerator;
import com.sports.schedule.tournament.RoundRobinGenerator;
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

    /** 请求体：参赛队伍 + 赛制参数。 */
    public record TournamentRequest(List<String> teams, String format, Integer groups,
                                    Integer advancePerGroup, Boolean doubleLeg, Integer bestOf) {
    }

    @PostMapping("/generate")
    @Operation(summary = "生成赛制结构（round_robin / elimination / hybrid / volleyball）")
    public ApiResponse<Map<String, Object>> generate(@RequestBody TournamentRequest req) {
        List<String> teams = req.teams() == null ? List.of() : req.teams();
        String format = req.format() == null ? "hybrid" : req.format().toLowerCase();
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
        log.info("赛制生成: format={}, 队伍 {} 支", format, teams.size());
        return ApiResponse.success(out);
    }
}
