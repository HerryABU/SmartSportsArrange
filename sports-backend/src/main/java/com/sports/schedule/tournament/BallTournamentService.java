package com.sports.schedule.tournament;

import com.sports.schedule.ai.SuperMoeService;
import com.sports.schedule.ai.SuperScheduleEncoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 球类赛程编排服务：<b>赛制选择交给 AI</b>，结构生成仍走既有规则引擎。
 *
 * <p>分工很明确——规则引擎擅长「给定赛制后把对阵图排出来」（这是纯组合计算，
 * 用规则最可靠）；AI 擅长「该选哪个赛制、种子怎么排、几轮之后怎么二次编排」
 * （这些需要看约束结构与历史模式）。两者互补而不是互相替代。</p>
 *
 * <h3>赛制枚举（与训练侧 super_scenarios.FMT_* 顺序一致）</h3>
 * <pre>
 * 0 group        小组赛（分组循环）
 * 1 round_robin  循环赛
 * 2 knockout     淘汰赛（含晋级链）
 * 3 hybrid       混合（小组 + 淘汰）
 * </pre>
 */
@Slf4j
@Service
public class BallTournamentService {

    private final SuperMoeService superMoe;

    public BallTournamentService(SuperMoeService superMoe) {
        this.superMoe = superMoe;
    }

    public static final int FMT_GROUP = 0;
    public static final int FMT_ROUND_ROBIN = 1;
    public static final int FMT_KNOCKOUT = 2;
    public static final int FMT_HYBRID = 3;

    private static final String[] FMT_NAMES = {"group", "round_robin", "knockout", "hybrid"};

    /** 一支参赛队。 */
    public record Team(String name, Double strength, String unit, String venuePref) {
    }

    /**
     * 编排入口。
     *
     * @param teams        参赛队
     * @param format       赛制；{@code null} / {@code "auto"} 时由 AI 决定
     * @param groups       小组数（group / hybrid 用）
     * @param advancePerGroup 每组晋级名额（hybrid 用）
     * @param doubleLeg    循环赛是否双回合
     * @param minutesPerMatch 单场耗时
     * @param daysLimit    时间目标：{@code >=1} 硬约束 / {@code 0} 不限 / {@code -1} 最小化
     * @param ai           是否允许 AI 决策赛制
     */
    public Map<String, Object> arrange(List<Team> teams, String format, int groups,
                                       int advancePerGroup, boolean doubleLeg,
                                       int minutesPerMatch, int daysLimit, boolean ai) {
        List<String> names = teams == null ? List.of() : teams.stream().map(Team::name).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("teamCount", names.size());

        if (names.size() < 2) {
            out.put("format", FMT_NAMES[FMT_GROUP]);
            out.put("matches", List.of());
            out.put("matchCount", 0);
            out.put("note", "参赛队不足 2 支，无法编排");
            return out;
        }

        // ---- 赛制选择：AI 或人工指定 ----
        int fmt = FMT_HYBRID;
        int effGroups = groups <= 0 ? 2 : groups;
        int effAdvance = advancePerGroup <= 0 ? 2 : advancePerGroup;
        Map<String, Object> aiAdvice = new LinkedHashMap<>();
        if (ai && (format == null || format.isBlank() || "auto".equalsIgnoreCase(format))) {
            aiAdvice = recommendByAi(teams, names, minutesPerMatch, daysLimit);
            Object rec = aiAdvice.get("recommendedFormat");
            if (rec instanceof String sv) {
                fmt = indexOf(sv);
            }
            // 混合/小组赛制的分组参数一并由 AI 定（人工只给上限区间）
            Object g = aiAdvice.get("suggestedGroups");
            if (g instanceof Number gn && gn.intValue() >= 2) {
                effGroups = gn.intValue();
                aiAdvice.put("appliedGroups", effGroups);
            }
            Object ad = aiAdvice.get("suggestedAdvance");
            if (ad instanceof Number an && an.intValue() >= 1) {
                effAdvance = an.intValue();
                aiAdvice.put("appliedAdvance", effAdvance);
            }
        } else if (format != null && !format.isBlank()) {
            fmt = indexOf(format);
        }
        out.put("format", FMT_NAMES[fmt]);
        if (!aiAdvice.isEmpty()) {
            out.put("aiAdvice", aiAdvice);
        }

        // ---- 结构生成（规则引擎） ----
        switch (fmt) {
            case FMT_ROUND_ROBIN -> {
                List<Map<String, Object>> ms = new ArrayList<>();
                for (RoundRobinGenerator.Match m : RoundRobinGenerator.generate(names, doubleLeg, true)) {
                    ms.add(matchMap(m.round(), m.home(), m.away(), m.leg(), null));
                }
                out.put("matches", ms);
                out.put("matchCount", ms.size());
                out.put("rounds", maxRound(ms));
            }
            case FMT_KNOCKOUT -> {
                List<Map<String, Object>> ms = new ArrayList<>();
                for (EliminationGenerator.Match m : EliminationGenerator.single(names)) {
                    ms.add(matchMap(m.round(), m.home(), m.away(), 1, null));
                }
                out.put("matches", ms);
                out.put("matchCount", ms.size());
                out.put("rounds", maxRound(ms));
                out.put("bracket", knockoutBracket(names));
            }
            case FMT_GROUP -> {
                int g = Math.max(2, Math.min(effGroups, names.size()));
                Map<String, List<String>> gs = HybridGenerator.snakeGroup(names, g);
                Map<String, List<RoundRobinGenerator.Match>> gm =
                        RoundRobinGenerator.groupRoundRobin(gs, doubleLeg);
                List<Map<String, Object>> ms = new ArrayList<>();
                for (Map.Entry<String, List<RoundRobinGenerator.Match>> e : gm.entrySet()) {
                    for (RoundRobinGenerator.Match m : e.getValue()) {
                        ms.add(matchMap(m.round(), m.home(), m.away(), m.leg(), e.getKey()));
                    }
                }
                out.put("groups", gs);
                out.put("matches", ms);
                out.put("matchCount", ms.size());
                out.put("rounds", maxRound(ms));
            }
            default -> {
                int g = Math.max(2, Math.min(effGroups, names.size()));
                HybridGenerator.Plan plan =
                        HybridGenerator.plan(names, g, Math.max(1, effAdvance), doubleLeg);
                List<Map<String, Object>> ms = new ArrayList<>();
                for (Map.Entry<String, List<RoundRobinGenerator.Match>> e
                        : plan.groupMatches().entrySet()) {
                    for (RoundRobinGenerator.Match m : e.getValue()) {
                        ms.add(matchMap(m.round(), m.home(), m.away(), m.leg(), e.getKey()));
                    }
                }
                out.put("groups", plan.groups());
                out.put("crossPairs", plan.crossPairs());
                out.put("knockout", plan.knockout());
                out.put("matches", ms);
                out.put("matchCount", ms.size());
            }
        }

        out.put("minutesPerMatch", minutesPerMatch);
        out.put("daysLimit", daysLimit);
        out.put("appliedGroups", effGroups);
        out.put("appliedAdvance", effAdvance);
        out.put("algorithm", algorithmNote(fmt));
        out.put("estimatedMinutes", out.get("matchCount") instanceof Integer c ? c * minutesPerMatch : null);
        log.info("球类编排: format={}, 队伍 {} 支, 场次 {}", out.get("format"), names.size(),
                out.get("matchCount"));
        return out;
    }

    /**
     * 淘汰赛后的二次编排：在已有对阵结构上重排时间槽/场地。
     *
     * <p>对应训练场景里的 {@code TASK_RESECOND}——淘汰赛结束后，
     * 有些队伍临时缺人/场地冲突，需要在<b>保持对阵关系不变</b>的前提下重排。</p>
     */
    public Map<String, Object> resecond(String format, int groups, int advancePerGroup,
                                        boolean doubleLeg, int daysLimit) {
        // 二次编排的语义：结构不变，只重排槽位。这里返回结构 + 「可重排」标记，
        // 真正的槽位分配由超级编排模型的 slot_logits 给出（见 arrangeReschedule）。
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "resecond");
        out.put("note", "保持对阵关系不变，仅重排时间槽与场地");
        out.put("format", format == null ? FMT_NAMES[FMT_KNOCKOUT] : format);
        out.put("daysLimit", daysLimit);
        out.put("reschedulable", true);
        return out;
    }

    // ------------------------------------------------------------------
    /** 让超级模型决定赛制与种子顺序。 */
    private Map<String, Object> recommendByAi(List<Team> teams, List<String> names,
                                              int minutesPerMatch, int daysLimit) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            List<SuperScheduleEncoder.Unit> units = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                Team t = teams.get(i);
                double st = t.strength() == null ? 0.5 : t.strength();
                // 队级单元：每支「队」当成一个编排单元，task=KNOCKOUT（需要晋级编排）
                units.add(new SuperScheduleEncoder.Unit("t" + i, names.get(i),
                        SuperScheduleEncoder.TASK_KNOCKOUT, false, true, null,
                        null, t.venuePref() == null ? "场" : t.venuePref(), "P1",
                        t.unit() == null ? "" : t.unit(), minutesPerMatch, 0, 0,
                        List.of((long) i), null, null, "main"));
            }
            // 窗口：按「能装下多少场」估算
            int nMatches = names.size() * (names.size() - 1) / 2;
            int perWindow = Math.max(1, 240 / Math.max(10, minutesPerMatch));
            int windows = Math.max(2, (int) Math.ceil(nMatches / (double) perWindow));
            List<SuperScheduleEncoder.Window> ws = new ArrayList<>();
            for (int d = 1; d <= Math.max(1, daysLimit <= 0 ? 2 : daysLimit); d++) {
                for (int w = 0; w < 3; w++) {
                    ws.add(new SuperScheduleEncoder.Window(d, w, 240, "场", "P1"));
                }
            }
            SuperScheduleEncoder.Encoded enc = superMoe.getEncoder()
                    .encode(units, ws, daysLimit, names.size());
            if (enc.degraded()) {
                out.put("degraded", true);
                out.put("reason", enc.degradeReason());
                return out;
            }
            var advice = superMoe.advise(enc);
            if (advice.isEmpty()) {
                out.put("degraded", true);
                out.put("error", superMoe.modelInfo().get("error"));
                return out;
            }
            var a = advice.get();
            out.put("recommendedFormat", a.recommendedFormat());
            Map<String, Object> probs = new LinkedHashMap<>();
            for (int i = 0; i < FMT_NAMES.length && i < a.formatLogits().length; i++) {
                probs.put(FMT_NAMES[i], round3(a.formatLogits()[i]));
            }
            out.put("formatLogits", probs);
            // 种子顺序：按 priority 降序
            int[] order = a.orderByPriority();
            List<String> seeded = new ArrayList<>();
            for (int i : order) {
                if (i >= 0 && i < names.size()) {
                    seeded.add(names.get(i));
                }
            }
            out.put("seedOrder", seeded);
            out.put("daysEstimate", round3(a.daysEstimate()));

            // ---- 分组参数建议：让 AI 一并决定「分几组、每组晋级几支」 ----
            // 依据：混合/小组赛制下，组内循环场次 = g * (m-1)/2（m=每组队数）。
            // 目标是把总场次压到「可用时段 × 每时段场次」以内，同时组数不小于 2。
            int n = names.size();
            int matchesPerWindow = Math.max(1, 240 / Math.max(10, minutesPerMatch));
            int capacity = Math.max(1, (daysLimit >= 1 ? daysLimit : 2) * 3) * matchesPerWindow;
            int bestG = 2;
            double bestCost = Double.MAX_VALUE;
            for (int g = 2; g <= Math.min(8, n / 2); g++) {
                int m = (int) Math.ceil((double) n / g);
                double cost = g * (m - 1) / 2.0;      // 组内循环场次
                if (cost > capacity) {
                    cost += 1000;                       // 超容量重罚
                }
                if (cost < bestCost) {
                    bestCost = cost;
                    bestG = g;
                }
            }
            out.put("suggestedGroups", bestG);
            out.put("suggestedAdvance", Math.max(1, Math.min(4, n / bestG / 2)));
            out.put("matchCapacityPerDay", capacity);
            // 九类任务权重（让调用方看到模型在关注什么）
            Map<String, Object> taskW = new LinkedHashMap<>();
            String[] names9 = SuperScheduleEncoder.taskNames();
            for (int i = 0; i < names9.length && i < a.taskProbs().length; i++) {
                taskW.put(names9[i], round3(a.taskProbs()[i]));
            }
            out.put("taskWeights", taskW);
        } catch (RuntimeException e) {
            log.warn("AI 赛制推荐失败，回退默认赛制: {}", e.toString());
            out.put("degraded", true);
            out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return out;
    }

    /** 淘汰赛对阵树（用于前端画 bracket）。 */
    private List<Map<String, Object>> knockoutBracket(List<String> teams) {
        List<Map<String, Object>> rounds = new ArrayList<>();
        List<String> cur = new ArrayList<>(teams);
        int r = 1;
        int size = Seeding.nextPow2(teams.size());
        while (cur.size() > 1) {
            List<Map<String, Object>> pairs = new ArrayList<>();
            List<String> next = new ArrayList<>();
            for (int i = 0; i < cur.size(); i += 2) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("home", cur.get(i));
                p.put("away", i + 1 < cur.size() ? cur.get(i + 1) : null);
                p.put("round", r);
                p.put("bye", i + 1 >= cur.size());
                pairs.add(p);
                next.add("胜者" + (i / 2 + 1));
            }
            Map<String, Object> rd = new LinkedHashMap<>();
            rd.put("round", r);
            rd.put("pairs", pairs);
            rounds.add(rd);
            cur = next;
            r++;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("rounds", rounds);
        meta.put("bracketSize", size);
        return rounds;
    }

    private static Map<String, Object> matchMap(int round, String home, String away,
                                                 int leg, String group) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("round", round);
        m.put("home", home);
        m.put("away", away);
        m.put("leg", leg);
        if (group != null) {
            m.put("group", group);
        }
        return m;
    }

    private static int maxRound(List<Map<String, Object>> ms) {
        int mx = 0;
        for (Map<String, Object> m : ms) {
            if (m.get("round") instanceof Integer r) {
                mx = Math.max(mx, r);
            }
        }
        return mx;
    }

    private static int indexOf(String s) {
        for (int i = 0; i < FMT_NAMES.length; i++) {
            if (FMT_NAMES[i].equalsIgnoreCase(s)) {
                return i;
            }
        }
        return switch (s == null ? "" : s.toLowerCase()) {
            case "rr", "roundrobin", "round-robin", "循环赛" -> FMT_ROUND_ROBIN;
            case "ko", "knockout", "elimination", "淘汰赛" -> FMT_KNOCKOUT;
            case "mixed", "hybrid", "混合" -> FMT_HYBRID;
            case "group", "小组赛", "分组" -> FMT_GROUP;
            default -> FMT_HYBRID;
        };
    }

    /** 赛制算法说明（透明化：用户应知道系统用哪种排法）。 */
    private static Map<String, Object> algorithmNote(int fmt) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (fmt) {
            case FMT_ROUND_ROBIN -> {
                m.put("name", "循环赛（标准轮转）");
                m.put("grouping", "Berger 表轮转法，保证各队轮次分布均衡");
                m.put("homeAway", "主客场轮换分配，尽量平衡");
                m.put("note", "场次随队数平方增长，队数多时优先考虑分组或淘汰");
            }
            case FMT_KNOCKOUT -> {
                m.put("name", "单淘汰（标准种子排位）");
                m.put("seeding", "递归种子序：1/2 号种子分居不同半区、只可能在决赛相遇");
                m.put("bye", "队数非 2 的幂时高位种子轮空");
                m.put("note", "淘汰赛需打「胜者/负者」等后续轮次，编排时可做二次编排");
            }
            case FMT_GROUP -> {
                m.put("name", "小组赛（蛇形分组 + 组内循环）");
                m.put("seeding", "蛇形分配 1→G、G→1 往复，强队分散到不同组");
                m.put("note", "组数与每组队数决定总场次");
            }
            default -> {
                m.put("name", "混合赛制（小组循环 + 交叉淘汰）");
                m.put("seeding", "先蛇形分组，组内循环取前 N 名进入交叉淘汰");
                m.put("note", "运动会最常用：既能打满场次又有淘汰看点");
            }
        }
        return m;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
