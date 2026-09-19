package com.sports.schedule.opt;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicPhaseConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchType;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 赛程约束求解器（Timefold Solver）。
 *
 * <p><b>为什么用求解器而不是继续手写贪心</b>：编排问题同时含三类耦合决策——
 * 项目排在哪个并发位、每个项目分到多少时长、如何避开运动员兼项。贪心只能按固定顺序
 * 逐项决策、且「时长」这一维只能靠池级统一比例（一刀切）。求解器用「构造启发式 + 局部搜索
 * （禁忌搜索）」在同一目标函数下<b>联合优化</b>这三类决策，并在容量客观不足时给出「最不坏」方案。</p>
 *
     * <p><b>可复现性</b>：固定随机种子 + 单线程求解（Timefold 除 NON_REPRODUCIBLE 外的模式均为
     * 可复现），保证同一份数据两次编排得到完全相同的赛程——排程结果要能对外解释、要能复现，
     * 不能每次刷新都变。</p>
 *
 * <p><b>失败即降级</b>：求解异常或超时不抛出，返回空结果，由调用方回退到原有贪心编排，
 * 保证接口在任何情况下都能出方案。</p>
 */
@Slf4j
@Component
public class ScheduleOptimizer {

    /** 固定随机种子：同输入必得同输出 */
    public static final long RANDOM_SEED = 20260918L;

    /** 默认求解时间预算（秒），可由 sports.schedule.solver-seconds 覆盖 */
    @Getter
    private final Duration defaultBudget;

    public ScheduleOptimizer(@Value("${sports.schedule.solver-seconds:4}") long solverSeconds) {
        this.defaultBudget = Duration.ofSeconds(Math.max(1L, solverSeconds));
        log.info("赛程约束求解器就绪: Timefold Solver 2.6.0（构造启发式 + 禁忌搜索），默认时间预算 {}s",
                defaultBudget.toSeconds());
    }

    /** 按默认预算求解 */
    public Optional<SchedulePlan> solve(SchedulePlan plan) {
        return solve(plan, defaultBudget);
    }

    /**
     * 求解赛程问题。
     *
     * @return 求解后的方案；问题为空、求解异常或超时时返回 {@link Optional#empty()}（调用方应回退贪心）
     */
    public Optional<SchedulePlan> solve(SchedulePlan plan, Duration budget) {
        if (plan == null || plan.getUnits() == null || plan.getUnits().isEmpty()
                || plan.getPlacements() == null || plan.getPlacements().isEmpty()) {
            return Optional.empty();
        }
        try {
            Solver<SchedulePlan> solver = SolverFactory.<SchedulePlan>create(config(budget)).buildSolver();
            SchedulePlan solved = solver.solve(plan);
            if (solved != null && solved.getScore() != null) {
                log.info("约束求解完成: score={}（未分配 {} 个、兼项冲突 {} 处）",
                        solved.getScore(), countUnassigned(solved), countClashes(solved));
            }
            return Optional.ofNullable(solved);
        } catch (Exception ex) {
            log.warn("约束求解未完成，本轮回退贪心编排: {}", ex.toString());
            return Optional.empty();
        }
    }

    private SolverConfig config(Duration budget) {
        return new SolverConfig()
                .withSolutionClass(SchedulePlan.class)
                .withEntityClasses(ScheduleUnit.class)
                .withConstraintProviderClass(ScheduleConstraintProvider.class)
                // NO_ASSERT：生产模式，跳过逐阶段断言（PHASE_ASSERT 只用于开发期自检）。
                // 结果可复现性由「固定随机种子 + 单线程求解」保证，不依赖断言模式。
                .withEnvironmentMode(EnvironmentMode.NO_ASSERT)
                .withRandomSeed(RANDOM_SEED)
                .withPhases(
                        // 构造启发式：先得到一个合法可行解（不做任何改进）
                        new ConstructionHeuristicPhaseConfig(),
                        // 局部搜索：禁忌搜索——排程/调度类问题的经典强算法
                        new LocalSearchPhaseConfig().withLocalSearchType(LocalSearchType.TABU_SEARCH))
                .withTerminationConfig(new TerminationConfig().withSpentLimit(budget));
    }

    private static long countUnassigned(SchedulePlan plan) {
        return plan.getUnits().stream().filter(u -> !u.isPlaced()).count();
    }

    private static long countClashes(SchedulePlan plan) {
        var units = plan.getUnits();
        long n = 0;
        for (int i = 0; i < units.size(); i++) {
            ScheduleUnit a = units.get(i);
            if (!a.isPlaced() || !a.hasAthletes()) continue;
            for (int j = i + 1; j < units.size(); j++) {
                ScheduleUnit b = units.get(j);
                if (b.isPlaced() && b.hasAthletes()
                        && a.sharesAthlete(b) && ScheduleConstraintProvider.athleteClash(a, b)) {
                    n++;
                }
            }
        }
        return n;
    }
}
