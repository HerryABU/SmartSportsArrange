package com.sports.schedule.opt;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicPhaseConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchType;
import ai.timefold.solver.core.config.localsearch.decider.acceptor.AcceptorType;
import ai.timefold.solver.core.config.localsearch.decider.acceptor.LocalSearchAcceptorConfig;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import com.sports.schedule.opt.portfolio.AlgorithmPortfolio;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
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
        return solve(plan, budget, LocalSearchType.TABU_SEARCH, RANDOM_SEED);
    }

    /**
     * 按指定元启发式与随机种子求解（供算法组合层逐个跑候选算法）。
     *
     * @param type 局部搜索算法：禁忌搜索会记路避免循环；模拟退火允许暂时变差以跳出局部最优；
     *             迟接受接受与历史最优持平的解——三者适应不同的实例特征
     */
    public Optional<SchedulePlan> solve(SchedulePlan plan, Duration budget, LocalSearchType type, long seed) {
        if (plan == null || plan.getUnits() == null || plan.getUnits().isEmpty()
                || plan.getPlacements() == null || plan.getPlacements().isEmpty()) {
            return Optional.empty();
        }
        try {
            Solver<SchedulePlan> solver =
                    SolverFactory.<SchedulePlan>create(config(budget, type, seed)).buildSolver();
            SchedulePlan solved = solver.solve(plan);
            if (solved != null && solved.getScore() != null) {
                log.info("约束求解完成[{}]: score={}（未分配 {} 个、兼项冲突 {} 处）",
                        type, solved.getScore(), countUnassigned(solved), countClashes(solved));
            }
            return Optional.ofNullable(solved);
        } catch (Exception ex) {
            log.warn("约束求解未完成（算法 {}），本轮回退贪心编排: {}", type, ex.toString());
            return Optional.empty();
        }
    }

    /**
     * <b>波次求解（算法组合调度）</b>：按实例特征选出多个候选算法，各自独立跑一遍，取评分最优者。
     *
     * <p>这是「多起点 + 多种元启发式」的落地：单个算法从一个起点出发，容易陷进与其邻域结构
     * 相性差的局部最优；而多个不同算法/不同种子的解<b>并行探索</b>后取优，
     * 能系统性抵消单一起点的偏差——等价于一个极简的「种群 + 选择」。</p>
     *
     * <p>失败是安全的：某个候选算法抛异常只会被跳过，不影响其余候选。</p>
     */
    public Optional<SchedulePlan> solveWithPortfolio(SchedulePlan plan, AlgorithmPortfolio.Features features) {
        List<AlgorithmPortfolio.Plan> plans =
                AlgorithmPortfolio.planFor(features, defaultBudget.toMillis());
        SchedulePlan best = null;
        String winner = null;
        for (AlgorithmPortfolio.Plan p : plans) {
            SchedulePlan r = solve(plan, Duration.ofMillis(p.budgetMillis()), p.type(), p.seed()).orElse(null);
            if (r == null || r.getScore() == null) continue;
            if (best == null || r.getScore().compareTo(best.getScore()) > 0) {
                best = r;
                winner = p.name();
            }
        }
        if (best != null) {
            log.info("算法组合调度: 候选 {} 个，胜出「{}」，score={}", plans.size(), winner, best.getScore());
        } else {
            log.warn("算法组合调度: {} 个候选算法全部未产出结果，将回退贪心编排", plans.size());
        }
        return Optional.ofNullable(best);
    }

    /** 默认配置：禁忌搜索 + 固定种子（单算法路径，保留给测试与降级使用） */
    private SolverConfig config(Duration budget) {
        return config(budget, LocalSearchType.TABU_SEARCH, RANDOM_SEED);
    }

    /** 按元启发式类型与随机种子构造求解配置——算法选择层的执行入口 */
    private SolverConfig config(Duration budget, LocalSearchType type, long seed) {
        return new SolverConfig()
                .withSolutionClass(SchedulePlan.class)
                .withEntityClasses(ScheduleUnit.class)
                .withConstraintProviderClass(ScheduleConstraintProvider.class)
                // NO_ASSERT：生产模式，跳过逐阶段断言（PHASE_ASSERT 只用于开发期自检）。
                // 结果可复现性由「固定随机种子 + 单线程求解」保证，不依赖断言模式。
                .withEnvironmentMode(EnvironmentMode.NO_ASSERT)
                .withRandomSeed(seed)
                .withPhases(
                        // 构造启发式：先得到一个合法可行解（不做任何改进）
                        new ConstructionHeuristicPhaseConfig(),
                        // 局部搜索：算法由算法选择层决定（禁忌搜索 / 模拟退火 / 迟接受 …）
                        localSearchPhase(type))
                .withTerminationConfig(new TerminationConfig().withSpentLimit(budget));
    }

    /**
     * 按元启发式类型构造局部搜索阶段，并补齐各接受器<b>必需的参数</b>。
     *
     * <p>Timefold 的 {@code withLocalSearchType(...)} 只设置「用哪种接受器」，不会替你把
     * 该接受器的关键参数填好——模拟退火缺起始温度、迟接受缺窗口大小都会在求解启动期抛
     * {@code IllegalArgumentException}，导致该候选算法<b>静默失败并降级回贪心</b>（症状隐蔽）。
     * 所以这里显式补齐。</p>
     *
     * <p>注意 Timefold 规定 {@code localSearchType} 与 {@code acceptorConfig} <b>二选一</b>：
     * 一旦显式给了 acceptorConfig，就不能再设 localSearchType（会直接报「must not be
     * configured together」）。因此需要自定义参数的算法走 acceptorConfig 分支（在
     * acceptorTypeList 里声明接受器类型），其余走 localSearchType 分支。</p>
     *
     * <p>模拟退火起始温度取 {@code 1000soft}（非负的「能容忍多差」的幅度）：只允许「软分变差」
     * 的移动（约等于一次移动可能造成的软分损失），硬分与中分绝不放松——既跳得出局部最优，
     * 又不会接受一个不可行或新增兼项冲突的解。</p>
     */
    private LocalSearchPhaseConfig localSearchPhase(LocalSearchType type) {
        switch (type) {
            case SIMULATED_ANNEALING:
                return new LocalSearchPhaseConfig().withAcceptorConfig(
                        new LocalSearchAcceptorConfig()
                                .withAcceptorTypeList(List.of(AcceptorType.SIMULATED_ANNEALING))
                                .withSimulatedAnnealingStartingTemperature("0hard/0medium/1000soft"));
            case LATE_ACCEPTANCE:
                return new LocalSearchPhaseConfig().withAcceptorConfig(
                        new LocalSearchAcceptorConfig()
                                .withAcceptorTypeList(List.of(AcceptorType.LATE_ACCEPTANCE))
                                .withLateAcceptanceSize(400));
            case DIVERSIFIED_LATE_ACCEPTANCE:
                return new LocalSearchPhaseConfig().withAcceptorConfig(
                        new LocalSearchAcceptorConfig()
                                .withAcceptorTypeList(List.of(AcceptorType.DIVERSIFIED_LATE_ACCEPTANCE))
                                .withLateAcceptanceSize(400));
            default:
                // 禁忌搜索 / 爬山等：withLocalSearchType 已生成可用的默认接受器配置，无需额外参数
                return new LocalSearchPhaseConfig().withLocalSearchType(type);
        }
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

    /**
     * 评分器：<b>不求解、只对给定方案计算评分</b>（供遗传算法在交叉/变异后快速评估个体）。
     *
     * <p>它与 {@link #solve} 的区别：solve 是「构造 + 局部搜索」的完整流程，代价高；
     * 而 GA 每一代要评估几十个个体，逐个跑完整求解会爆炸。评分器只跑一遍约束流，
     * 直接得到该个体的 {@link HardMediumSoftScore}，把算力留给真正的搜索。</p>
     *
     * <p>懒加载 + 缓存：评分器背后的 ScoreDirectorFactory 创建一次后线程安全复用。</p>
     */
    public SolutionManager<SchedulePlan, HardMediumSoftScore> solutionManager() {
        if (solutionManager == null) {
            solutionManager = SolutionManager.create(SolverFactory.create(config(defaultBudget)));
        }
        return solutionManager;
    }

    private volatile SolutionManager<SchedulePlan, HardMediumSoftScore> solutionManager;
}
