package com.sports.service.arrange.alloc;

import com.sports.entity.athlete.Athlete;
import com.sports.entity.event.Event;
import com.sports.schedule.rule.inject.RuleContext;
import com.sports.schedule.rule.inject.RuleInjectionService;
import com.sports.schedule.rule.inject.RuleOutcome;

import java.util.*;

/**
 * 规则注入打分（2026-10-06 从 {@code ArrangementService} 抽出）。
 *
 * <p>把用户写的规则片段折算成「对某个 (运动员, 候选组) 的加权惩罚」，供各款型在
 * <b>同分候选</b>之间做二次择优。<b>无脚本时恒为 0</b> —— 既有编排行为完全不变，
 * 这是「规则注入」能随时开关的前提。</p>
 *
 * <p>权重 hard×1000 + medium×10 + soft：只要存在一条 hard，它的量级必然压过任意多条
 * medium/soft 之和，避免「多条软规则联手推翻一条硬规则」。</p>
 *
 * <p>⚠️ {@code veto} 用哨兵值而不是「直接排除」：优选时避开，但<b>无其它可选时仍可落位</b>
 * —— 否则一条过严的规则会让运动员直接排不下（可解性优先于规则满意度）。</p>
 */
public class RuleInjectionScorer {

    private final RuleInjectionService ruleInjectionService;

    public RuleInjectionScorer(RuleInjectionService ruleInjectionService) {
        this.ruleInjectionService = ruleInjectionService;
    }

    /** 规则注入：否决哨兵惩罚（优选时避开，但无其它可选仍可落位，避免运动员排不下）。 */
    private static final long RULE_VETO_PENALTY = Long.MAX_VALUE / 4;

    /**
     * 规则注入惩罚：(运动员, 候选组) 评估用户规则片段 → 加权惩罚。
     * hard 权重最高；veto 返回哨兵值（优选时避开）。**无脚本时恒为 0 → 既有编排行为完全不变**。
     */
    public long injectPenalty(Event event, Athlete athlete, int heat1, int heats) {
        if (ruleInjectionService == null || athlete == null || event == null) {
            return 0L;
        }
        RuleOutcome o = ruleInjectionService.assess(ruleContextOf(event, athlete, heat1, null, heats));
        if (o == null) {
            return 0L;
        }
        if (o.veto()) {
            return RULE_VETO_PENALTY;
        }
        return o.hard() * 1000L + o.medium() * 10L + o.soft();
    }


    /**
     * 蛇形款型：在有空位的组中选规则惩罚最小者；平局取离蛇形目标组最近者（保持蛇形语义）。
     * 被否决（veto）的组仅在无其它可选时兜底，避免运动员排不下。
     */
    public int pickHeatByInjection(Event event, Athlete athlete, int target, int heats, int lanes, int[] occupancy) {
        int best = -1;
        long bestPenalty = Long.MAX_VALUE;
        int bestDist = Integer.MAX_VALUE;
        int fallback = -1;
        long fallbackPenalty = Long.MAX_VALUE;
        for (int k = 0; k < heats; k++) {
            int h = (target + k) % heats;
            if (occupancy[h] >= lanes) {
                continue;
            }
            long p = injectPenalty(event, athlete, h + 1, heats);
            if (p >= RULE_VETO_PENALTY) {
                if (fallback < 0 || p < fallbackPenalty) {
                    fallback = h;
                    fallbackPenalty = p;
                }
                continue;
            }
            if (p < bestPenalty || (p == bestPenalty && k < bestDist)) {
                best = h;
                bestPenalty = p;
                bestDist = k;
            }
        }
        return best >= 0 ? best : fallback;
    }


    /** 班级均衡款型：在「人数最少」的候选组里再取规则惩罚最小者；完全平局才随机（保留对抗式重排）。 */
    public int pickAmongCandidates(Event event, Athlete athlete, List<Integer> candidates, int heats, Random rnd) {
        if (candidates.isEmpty()) {
            return -1;
        }
        long bestPenalty = Long.MAX_VALUE;
        List<Integer> top = new ArrayList<>();
        for (int h : candidates) {
            long p = injectPenalty(event, athlete, h + 1, heats);
            if (p < bestPenalty) {
                bestPenalty = p;
                top.clear();
                top.add(h);
            } else if (p == bestPenalty) {
                top.add(h);
            }
        }
        return (rnd != null && top.size() > 1) ? top.get(rnd.nextInt(top.size())) : top.get(0);
    }


    /**
     * 组装「规则注入」上下文（供用户规则片段读取 event / athlete / heat / lane 等字段）。
     *
     * <p>⚠️ {@code event.*} 的键与求解侧
     * {@code ScheduleConstraintProvider#ruleContextOf} <b>保持同集</b>（category/track/team/teamMembers/
     * concurrency/venueCode/gradeGroup + id/name）——两条路径字段名一旦分叉，同一份规则会出现
     * 「编排时生效、求解时静默失效」。新增字段请同时改两处。</p>
     */
    public RuleContext ruleContextOf(Event event, Athlete athlete, Integer heat, Integer lane, int heats) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("id", event.getId());
        ev.put("name", event.getName());
        ev.put("category", event.getCategory());
        ev.put("track", event.getTrack());
        ev.put("team", event.getTeam());
        ev.put("teamMembers", event.getTeamMembers());
        ev.put("concurrency", event.getConcurrency());
        ev.put("venueCode", event.getDefaultVenueCode());
        ev.put("gradeGroup", event.getGradeGroup());
        Map<String, Object> ath = new LinkedHashMap<>();
        if (athlete != null) {
            ath.put("id", athlete.getId());
            ath.put("grade", athlete.getGrade());
            ath.put("gender", athlete.getGender());
            ath.put("className", AthleteKeys.classKeyOf(athlete));
        }
        return RuleContext.builder()
                .put("event", ev)
                .put("athlete", ath)
                .put("heat", heat)
                .put("lane", lane)
                .put("heats", heats)
                .build();
    }

}
