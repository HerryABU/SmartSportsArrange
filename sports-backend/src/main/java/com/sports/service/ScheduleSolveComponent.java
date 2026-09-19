package com.sports.service;

import com.sports.schedule.core.Pool;
import com.sports.schedule.core.Unit;
import com.sports.schedule.core.Window;
import com.sports.schedule.opt.Placement;
import com.sports.schedule.opt.ScheduleOptimizer;
import com.sports.schedule.opt.SchedulePlan;
import com.sports.schedule.opt.ScheduleUnit;
import com.sports.schedule.opt.ga.GeneticAlgorithm;
import com.sports.schedule.opt.lns.LnsImprover;
import com.sports.schedule.opt.portfolio.AlgorithmPortfolio;
import com.sports.schedule.rule.RuleBasedScheduler;
import com.sports.schedule.rule.RuleScheduleConfig;
import com.sports.schedule.rule.RuleUnit;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.sports.schedule.support.ScheduleSupport.*;
import com.sports.schedule.core.*;

/**
 * 赛程求解组件（从 {@code ScheduleService} 抽出）：负责把「每个项目的落位 + 时长」
 * 整体求出来——规则模式（确定性 first-fit）或优化模式（Timefold 组合求解 + GA + LNS）。
 *
 * <p>求解结果只写回传入的 {@code solvedPlacement}（并把求解器选定的时长写回 {@code unit.duration}），
 * 真正的落库仍由主循环统一完成——「求解」与「持久化」解耦，求解失败时零副作用回退贪心。</p>
 *
 * <p>持有两个算法服务与规则编排器，并复用 {@link ScheduleBuildComponent} 的场地池解析，
 * 全部为 Spring 无关的纯装配逻辑（仅持有注入的依赖）。</p>
 */
@Slf4j
public class ScheduleSolveComponent {

    private final ScheduleOptimizer scheduleOptimizer;
    private final RuleBasedScheduler ruleBasedScheduler;
    private final GeneticAlgorithm geneticAlgorithm;
    private final LnsImprover lnsImprover;
    private final ScheduleBuildComponent buildComponent;

    // 算法调参（与 facade 的 @Value 同源，由构造器注入；仅 fillSolvedFromSolver 使用）
    private final int lnsRounds;
    private final long lnsRoundMillis;
    private final int gaPopulation;
    private final int gaGenerations;
    private final double gaMutationRate;
    private final long gaIndividualMillis;

    public ScheduleSolveComponent(ScheduleOptimizer scheduleOptimizer,
                                  RuleBasedScheduler ruleBasedScheduler,
                                  GeneticAlgorithm geneticAlgorithm,
                                  LnsImprover lnsImprover,
                                  ScheduleBuildComponent buildComponent,
                                  int lnsRounds,
                                  long lnsRoundMillis,
                                  int gaPopulation,
                                  int gaGenerations,
                                  double gaMutationRate,
                                  long gaIndividualMillis) {
        this.scheduleOptimizer = scheduleOptimizer;
        this.ruleBasedScheduler = ruleBasedScheduler;
        this.geneticAlgorithm = geneticAlgorithm;
        this.lnsImprover = lnsImprover;
        this.buildComponent = buildComponent;
        this.lnsRounds = lnsRounds;
        this.lnsRoundMillis = lnsRoundMillis;
        this.gaPopulation = gaPopulation;
        this.gaGenerations = gaGenerations;
        this.gaMutationRate = gaMutationRate;
        this.gaIndividualMillis = gaIndividualMillis;
    }

    // ==================== 以下方法由 scripts/refactor_extract_solve_component.py 从 ScheduleService 迁入 ====================

    /**
     * 规则模式编排（U39/B36）：把单元适配成 {@link RuleUnit}，交给确定性规则编排器出方案。
     *
     * <p>与 {@link #fillSolvedFromSolver} 完全对称：同样只写回 {@code solvedPlacement}
     * （及 {@code unit.duration}），真正的落库仍由主循环统一完成——「规则」与「持久化」解耦，
     * 规则排不下的单元由主循环贪心兜底，接口在任何情况下都能给出方案。</p>
     *
     * <p>组次/道次级规则（蛇形分组、固定分道）在 {@code saveSchedule → autoArrangeFor} 的既有管线
     * 中生效，本方法只负责「时间线」级别（哪个项目、哪天、哪个时段、哪刻开始、多长时间）。</p>
     */
    public void fillSolvedFromRules(List<Unit> units, Pool trackPool, Pool fieldPool,
                                     Map<String, Pool> dedicatedPools, String mainVenueCode,
                                     Set<String> fieldVenueCodes, Map<String, String> codeToName,
                                     Map<String, Integer> codeToParallelMax,
                                     int trackSlots, int fieldSlots, List<Window> windows,
                                     Map<Long, String> event2Group, int unitInterval,
                                     RuleScheduleConfig ruleConfig,
                                     Map<Unit, Placement> solvedPlacement,
                                     Map<String, Object> portfolioInfo) {
        // ① 与求解器完全相同的池解析/候选栅格口径（保证规则解与求解解可互替）
        Map<Unit, Pool> unitPool = new IdentityHashMap<>();
        for (Unit u : units) {
            if (u.participants <= 0) continue;
            unitPool.put(u, buildComponent.resolvePool(u, trackPool, fieldPool, mainVenueCode, fieldVenueCodes,
                    codeToName, codeToParallelMax, dedicatedPools, trackSlots, fieldSlots));
        }
        if (unitPool.isEmpty()) return;

        Map<String, List<Placement>> rangeByPool = new LinkedHashMap<>();
        for (Pool p : unitPool.values()) {
            rangeByPool.computeIfAbsent(p.label, k -> SchedulePlacementMath.placementsOf(p, windows, unitInterval));
        }

        // ② 适配为规则层公共 DTO（不让 ScheduleService 的私有内部类泄漏出服务层）
        List<RuleUnit> ruleUnits = new ArrayList<>();
        Map<String, Unit> unitByKey = new LinkedHashMap<>();
        for (int i = 0; i < units.size(); i++) {
            Unit u = units.get(i);
            Pool pool = unitPool.get(u);
            if (pool == null) continue;
            List<Placement> range = rangeByPool.get(pool.label);
            List<Placement> cands = new ArrayList<>();
            int floor = SchedulePlacementMath.minDurationOf(u);
            for (Placement p : range) {
                if (p.getMaxDuration() >= floor) cands.add(p);
            }
            if (cands.isEmpty()) continue;   // 该池整块放不下 → 交给贪心如实报「排不下」
            String key = "u" + i;
            unitByKey.put(key, u);
            ruleUnits.add(new RuleUnit(key, u.event.getId(), u.event.getName(), u.grade, u.track,
                    pool.label, u.track ? null : event2Group.get(u.event.getId()),
                    Math.max(SchedulePlacementMath.intervalOf(u, unitInterval), 1), u.rawDuration, floor,
                    SchedulePlacementMath.sortedAthletes(u), cands));
        }
        if (ruleUnits.isEmpty()) return;

        // ③ 规则编排（确定性、毫秒级）
        RuleBasedScheduler.RulePlan plan = ruleBasedScheduler.plan(ruleUnits, ruleConfig);
        for (Map.Entry<String, RuleBasedScheduler.Assignment> e : plan.assignments().entrySet()) {
            Unit u = unitByKey.get(e.getKey());
            if (u == null) continue;
            solvedPlacement.put(u, e.getValue().placement());
            u.duration = e.getValue().duration();
        }
        if (portfolioInfo != null) {
            portfolioInfo.put("rule", Map.of(
                    "placed", plan.placedCount(),
                    "unplaced", plan.unplacedCount(),
                    "residualConflicts", plan.residualConflicts(),
                    "elapsedMillis", plan.elapsedMillis(),
                    "lanePolicy", ruleConfig.lanePolicy().name(),
                    "advanceCount", ruleConfig.advanceCount(),
                    "conflictBufferMinutes", ruleConfig.conflictBufferMinutes()));
            portfolioInfo.put("basis", "规则模式：确定性 first-fit（蛇形分组 + 固定分道 + 时间栅格），"
                    + "毫秒级可复现；自检与下界评估照常执行");
        }
        log.info("规则编排: 排入 {}/{} 个单元, 残余兼项冲突 {} 处, 耗时 {}ms",
                plan.placedCount(), ruleUnits.size(), plan.residualConflicts(), plan.elapsedMillis());
    }


    /**
     * 用约束求解器求出全局编排方案（每个项目的落位 + 时长）。
     *
     * <p>求解结果只写回 {@code solvedPlacement}（并把求解器选定的时长写回 {@code unit.duration}），
     * 真正的落库仍由主循环统一完成——「求解」与「持久化」解耦，求解失败时零副作用回退贪心。</p>
     *
     * @param solverStat 出参：[0] = 采用解的项目数，[1] = 求解后的残余兼项冲突数
     */
    public void fillSolvedFromSolver(List<Unit> units, Pool trackPool, Pool fieldPool,
                                      Map<String, Pool> dedicatedPools, String mainVenueCode,
                                      Set<String> fieldVenueCodes, Map<String, String> codeToName,
                                      Map<String, Integer> codeToParallelMax,
                                      int trackSlots, int fieldSlots, List<Window> windows,
                                      Map<Long, String> event2Group, int unitInterval,
                                      Map<Unit, Placement> solvedPlacement, int[] solverStat,
                                      Map<String, Object> portfolioInfo) {
        if (scheduleOptimizer == null) return;

        // ① 预解析每个单元所属的并发池。与主循环调用同一个方法、同一顺序，
        //    因此主循环再次解析必然得到同一个池，不会出现「解落在别的池」。
        Map<Unit, Pool> unitPool = new IdentityHashMap<>();
        for (Unit u : units) {
            if (u.participants <= 0) continue;
            unitPool.put(u, buildComponent.resolvePool(u, trackPool, fieldPool, mainVenueCode, fieldVenueCodes,
                    codeToName, codeToParallelMax, dedicatedPools, trackSlots, fieldSlots));
        }
        if (unitPool.isEmpty()) return;

        // ② 位置值域按池生成一次并复用（同池单元共享同一批候选位置）
        Map<String, List<Placement>> rangeByPool = new LinkedHashMap<>();
        for (Pool p : unitPool.values()) {
            rangeByPool.computeIfAbsent(p.label, k -> SchedulePlacementMath.placementsOf(p, windows, unitInterval));
        }

        // ③ 组装计划实体：每个单元只暴露「自己池里、且放得下其时长下限」的位置
        List<ScheduleUnit> optUnits = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            Unit u = units.get(i);
            Pool pool = unitPool.get(u);
            if (pool == null) continue;
            int floor = SchedulePlacementMath.minDurationOf(u);
            List<Placement> cands = new ArrayList<>();
            for (Placement p : rangeByPool.get(pool.label)) {
                if (p.getMaxDuration() >= floor) cands.add(p);
            }
            if (cands.isEmpty()) continue;   // 该池整块放不下 → 交给贪心如实报「排不下」
            optUnits.add(new ScheduleUnit("u" + i, u.event.getId(), u.event.getName(), u.grade, u.track,
                    pool.label, u.track ? null : event2Group.get(u.event.getId()),
                    unitInterval, u.rawDuration, floor, SchedulePlacementMath.sortedAthletes(u), SchedulePlacementMath.durationChoicesOf(u), cands));
        }
        if (optUnits.isEmpty()) return;

        List<Placement> allPlacements = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<Placement> list : rangeByPool.values()) {
            for (Placement p : list) {
                if (seen.add(p.getId())) allPlacements.add(p);
            }
        }

        // ④ 求解：先提取实例特征，再由算法组合层选出候选算法，波次跑完取最优。
        //    容量紧张 → 模拟退火/迟接受（允许暂时变差才跳得出「用压缩换时间」的深坑）；
        //    容量宽裕 → 禁忌搜索（记住走过的路，避免循环）；兼项密集 → 多样化迟接受（多邻域）。
        SchedulePlan problem = new SchedulePlan(allPlacements, optUnits);
        AlgorithmPortfolio.Features features = AlgorithmPortfolio.extract(optUnits, allPlacements);
        if (portfolioInfo != null) {
            portfolioInfo.put("features", features.toMap());
            List<String> names = new ArrayList<>();
            for (AlgorithmPortfolio.Plan p : AlgorithmPortfolio.planFor(features, 1)) {
                names.add(p.name());
            }
            portfolioInfo.put("candidates", names);
            portfolioInfo.put("basis", "按实例特征（紧张度 / 兼项密度）动态选择；"
                    + "多算法并行探索后取评分最优者");
        }
        SchedulePlan solvedPlan = scheduleOptimizer.solveWithPortfolio(problem, features).orElse(null);
        if (solvedPlan == null) return;

        // ④b **遗传算法（GA）**：种群 + 交叉 + 变异，全局并行探索。
        //     与多起点波次的区别：波次是「各跑各的、跑完比大小」，GA 让好解之间繁殖——
        //     好的落位模式被交叉重组、坏的被变异扰动，能组合出任何单个起点都到不了的结构。
        try {
            if (gaPopulation >= 2 && gaGenerations >= 1) {
                SchedulePlan before = solvedPlan;
                Optional<SchedulePlan> evolved = geneticAlgorithm.evolve(
                        before, gaPopulation, gaGenerations,
                        java.time.Duration.ofMillis(Math.max(200, gaIndividualMillis)),
                        gaMutationRate);
                if (evolved.isPresent()) {
                    solvedPlan = evolved.get();
                    if (portfolioInfo != null) {
                        portfolioInfo.put("ga", "已启用：种群 " + gaPopulation + "、" + gaGenerations + " 代");
                        portfolioInfo.put("gaScore", String.format("%s → %s",
                                before.getScore(), solvedPlan.getScore()));
                    }
                } else if (portfolioInfo != null) {
                    portfolioInfo.put("ga", "种群进化后无改进（初始解已足够好）");
                }
            }
        } catch (Exception ex) {
            log.warn("遗传算法失败（保留算法组合层的结果）: {}", ex.toString());
        }

        // ④c **大邻域搜索精修（LNS）**：破坏一块 → 只重建这一块 → 只接受更好的。
        //     与上游的区别在粒度：局部搜索一次动一个项目，LNS 一次拔出「一个年级 / 一个时段窗口 /
        //     某个最忙运动员的全部项目」，在子空间里重新优化，其余部分用 @PlanningPin 锁定不动。
        //     因此每轮代价很小，能在同样预算里做很多次真正触到"根"的调整。
        try {
            if (lnsRounds > 0) {
                int rounds = Math.min(lnsRounds, 20);
                long perRound = Math.max(200, lnsRoundMillis);
                SchedulePlan before = solvedPlan;
                Optional<SchedulePlan> improved =
                        lnsImprover.improve(before, rounds, java.time.Duration.ofMillis(perRound));
                if (improved.isPresent()) {
                    solvedPlan = improved.get();
                    if (portfolioInfo != null) {
                        portfolioInfo.put("lns", "已启用：破坏-重建 " + rounds + " 轮，每轮 " + perRound + "ms");
                        portfolioInfo.put("lnsScore", String.format("%s → %s",
                                before.getScore(), solvedPlan.getScore()));
                    }
                } else if (portfolioInfo != null) {
                    portfolioInfo.put("lns", rounds + " 轮未改进（当前解已局部稳定）");
                }
            }
        } catch (Exception ex) {
            log.warn("LNS 精修失败（保留算法组合层的结果）: {}", ex.toString());
        }

        // ⑤ 回填：位置与时长一起生效。时长写回 u.duration 之后，compressionReport 反映的就是
        //    真正落地的时长，而不是「预计要压多少」。
        int applied = 0;
        for (ScheduleUnit su : solvedPlan.getUnits()) {
            if (!su.isPlaced()) continue;
            int idx = Integer.parseInt(su.getKey().substring(1));
            Unit u = units.get(idx);
            solvedPlacement.put(u, su.getPlacement());
            u.duration = su.getDuration();
            applied++;
        }
        solverStat[0] = applied;
        solverStat[1] = SchedulePlacementMath.countResidualClashes(solvedPlacement);
    }
}
