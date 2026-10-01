package com.sports.schedule.opt.solver;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.localsearch.LocalSearchType;
import com.sports.schedule.ai.AiAdvisory;
import com.sports.schedule.ai.OnnxInferenceService;
import com.sports.schedule.opt.config.SolverConfigFactory;
import com.sports.schedule.opt.portfolio.AlgorithmPortfolio;
import com.sports.schedule.opt.score.PlanMetrics;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 赛程约束求解器（Timefold Solver）——<b>调度层编排入口</b>。
 *
 * <p>本类只负责「编排决策」：选算法、跑求解、组合调度、缓存评分器；
 * 求解器配置构造下沉到 {@code opt.config.SolverConfigFactory}（算法配置系），
 * 方案度量下沉到 {@code opt.score.PlanMetrics}（评分系）。公开 API 不变。</p>
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

    /**
     * AI 推理服务（可选注入）。
     *
     * <p>用 setter + {@code required=false} 注入，保证「纯算法单测里 {@code new ScheduleOptimizer(1)}」
     * 不依赖 Spring、也不依赖模型文件——此时 {@code aiService} 为 null，AI 自动关闭，回退规则编排。
     * Spring 容器里正常注入后，编排优先采纳 AI 建议（见 {@link #solveWithPortfolio}）。</p>
     */
    @Autowired(required = false)
    public void setAiService(OnnxInferenceService aiService) {
        this.aiService = aiService;
    }

    private volatile OnnxInferenceService aiService;

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
                    SolverFactory.<SchedulePlan>create(SolverConfigFactory.config(budget, type, seed)).buildSolver();
            SchedulePlan solved = solver.solve(plan);
            if (solved != null && solved.getScore() != null) {
                log.info("约束求解完成[{}]: score={}（未分配 {} 个、兼项冲突 {} 处）",
                        type, solved.getScore(), PlanMetrics.countUnassigned(solved), PlanMetrics.countClashes(solved));
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
        // 自适应总预算（2026-09-27）：默认 4s 按「小实例」标定，项目数多的真实学校数据
        // 按单元数放大（每单元 ~250ms，上限 10s）——配置的 solver-seconds 是下限，
        // 大实例自动获得更多搜索时间（残留冲突与解质量的主要瓶颈是预算不足，而非算法选择）。
        long total = defaultBudget.toMillis();
        if (features != null && features.unitCount() > 0) {
            total = Math.max(total, Math.min(features.unitCount() * 250L, 10_000L));
        }

        // ① AI 编排建议（模型缺失/加载失败时返回 empty，自动回退规则编排）
        Optional<AiAdvisory> advisory = advise(plan);
        if (advisory.isPresent()) {
            AiAdvisory a = advisory.get();
            log.info("AI 编排建议: 策略={}, 取消概率={}, 置信={}",
                    a.strategy(), String.format("%.2f", a.cancelProbability()), a.confident());
        }

        // ② 按冲突簇 GNN 的着色优先级重排单元：中心冲突簇先着色，
        //    作为构造启发式的初始顺序（Timefold 默认按实体集合顺序构造初始解）。
        SchedulePlan ordered = advisory.map(a -> reorderByPriority(plan, a)).orElse(plan);

        List<AlgorithmPortfolio.Plan> plans =
                AlgorithmPortfolio.planFor(features, total, advisory.orElse(null));
        SchedulePlan best = null;
        String winner = null;
        for (AlgorithmPortfolio.Plan p : plans) {
            SchedulePlan r = solve(ordered, Duration.ofMillis(p.budgetMillis()), p.type(), p.seed()).orElse(null);
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

    /** 向 AI 推理服务请求编排建议（服务缺失时返回 empty）。 */
    private Optional<AiAdvisory> advise(SchedulePlan plan) {
        if (aiService == null) return Optional.empty();
        try {
            return aiService.advise(plan.getUnits(), plan.getPlacements());
        } catch (Exception ex) {
            log.warn("AI 编排建议失败，回退规则编排: {}", ex.toString());
            return Optional.empty();
        }
    }

    /**
     * 按 GNN 着色优先级重排单元（中心冲突簇在前），返回新方案（不动调用方原始方案）。
     *
     * <p>优先级按 {@code units} 原始顺序对齐，稳定排序（同优先级保持原相对顺序），
     * 超出的单元（当单元数 &gt; {@code MAX_NODES} 时被截断）原样追加到末尾。</p>
     */
    private SchedulePlan reorderByPriority(SchedulePlan plan, AiAdvisory advisory) {
        double[] priority = advisory.nodePriority();
        List<ScheduleUnit> units = plan.getUnits();
        if (priority == null || priority.length == 0 || units == null || units.size() < 2) {
            return plan;
        }
        int m = Math.min(priority.length, units.size());
        Integer[] idx = new Integer[m];
        for (int i = 0; i < m; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Double.compare(priority[b], priority[a]));   // 优先级降序
        List<ScheduleUnit> ordered = new ArrayList<>(units.size());
        for (int i = 0; i < m; i++) ordered.add(units.get(idx[i]));
        for (int i = m; i < units.size(); i++) ordered.add(units.get(i));
        SchedulePlan out = new SchedulePlan(plan.getPlacements(), ordered);
        out.setScore(plan.getScore());
        return out;
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
            solutionManager = SolutionManager.create(
                    SolverFactory.create(SolverConfigFactory.config(defaultBudget, LocalSearchType.TABU_SEARCH, RANDOM_SEED)));
        }
        return solutionManager;
    }

    private volatile SolutionManager<SchedulePlan, HardMediumSoftScore> solutionManager;
}
