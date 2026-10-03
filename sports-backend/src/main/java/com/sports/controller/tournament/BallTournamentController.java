package com.sports.controller.tournament;

import com.sports.common.web.ApiResponse;
import com.sports.schedule.tournament.BallTournamentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 球类赛程编排接口（赛制选择 / 种子 / 二次编排）。
 *
 * <p>与 {@code TournamentController} 的区别：后者是「给定赛制生成结构」的底层接口，
 * 本接口是<b>编排入口</b>——赛制可以交给 AI 选（{@code format=auto}），
 * 并支持淘汰赛后的二次编排。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/ball")
@Tag(name = "球类赛程编排", description = "赛制选择（AI/人工）、种子排序、淘汰赛二次编排")
public class BallTournamentController {

    private final BallTournamentService service;

    public BallTournamentController(BallTournamentService service) {
        this.service = service;
    }

    /** 一支参赛队（请求用）。 */
    public record TeamReq(String name, Double strength, String unit, String venuePref) {
    }

    /** 编排请求。 */
    public record ArrangeReq(List<TeamReq> teams,
                             String format,          // null / "auto" → AI 选
                             Integer groups,
                             Integer advancePerGroup,
                             Boolean doubleLeg,
                             Integer minutesPerMatch,
                             Integer daysLimit,      // >=1 硬约束 / 0 不限 / -1 最小化
                             Boolean ai) {
    }

    @PostMapping("/arrange")
    @Operation(summary = "球类赛程编排（format=auto 时由 AI 决定赛制）")
    public ApiResponse<Map<String, Object>> arrange(@RequestBody ArrangeReq req) {
        List<BallTournamentService.Team> teams = new ArrayList<>();
        if (req.teams() != null) {
            for (TeamReq t : req.teams()) {
                teams.add(new BallTournamentService.Team(
                        t.name(), t.strength(), t.unit(), t.venuePref()));
            }
        }
        return ApiResponse.success(service.arrange(
                teams,
                req.format(),
                req.groups() == null ? 2 : req.groups(),
                req.advancePerGroup() == null ? 2 : req.advancePerGroup(),
                Boolean.TRUE.equals(req.doubleLeg()),
                req.minutesPerMatch() == null ? 40 : req.minutesPerMatch(),
                req.daysLimit() == null ? 0 : req.daysLimit(),
                !Boolean.FALSE.equals(req.ai())));
    }

    /** 二次编排请求。 */
    public record ResecondReq(String format, Integer groups, Integer advancePerGroup,
                               Boolean doubleLeg, Integer daysLimit) {
    }

    @PostMapping("/resecond")
    @Operation(summary = "淘汰赛等赛制之后的二次编排（保持对阵关系，重排时间槽与场地）")
    public ApiResponse<Map<String, Object>> resecond(@RequestBody ResecondReq req) {
        return ApiResponse.success(service.resecond(
                req.format(),
                req.groups() == null ? 2 : req.groups(),
                req.advancePerGroup() == null ? 2 : req.advancePerGroup(),
                Boolean.TRUE.equals(req.doubleLeg()),
                req.daysLimit() == null ? 0 : req.daysLimit()));
    }

    /** 赛制枚举（供前端下拉）。 */
    public record FormatInfo(int id, String code, String label, String description) {
    }

    @PostMapping("/formats")
    @Operation(summary = "支持的赛制列表")
    public ApiResponse<List<FormatInfo>> formats() {
        return ApiResponse.success(List.of(
                new FormatInfo(0, "group", "小组赛", "分组循环，各组内部单循环，适合人数较多时"),
                new FormatInfo(1, "round_robin", "循环赛", "所有队伍两两交手，场次随队伍数平方增长"),
                new FormatInfo(2, "knockout", "淘汰赛", "含晋级链与轮空，适合队伍数不太多时"),
                new FormatInfo(3, "hybrid", "混合赛制", "小组赛 + 淘汰赛，运动会最常用")));
    }
}
