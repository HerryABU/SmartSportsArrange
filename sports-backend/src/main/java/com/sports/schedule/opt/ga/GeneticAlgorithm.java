package com.sports.schedule.opt.ga;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.config.localsearch.LocalSearchType;
import com.sports.schedule.opt.ScheduleOptimizer;
import com.sports.schedule.opt.SchedulePlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * 遗传算法（GA）：种群 + 选择 + 交叉 + 变异，并行探索多个局部最优。
 *
 * <h2>它和「多起点波次」的区别</h2>
 * 算法组合层（{@code AlgorithmPortfolio}）是「各自独立跑、跑完比大小」——多个起点<b>互不交流</b>。
 * 遗传算法则让这些解<b>繁殖</b>：好的局部模式通过交叉被重组，坏的通过变异被扰动，
 * 一代代进化。这是「并行探索」里更主动的一种，能组合出任何单个起点都到不了的结构。
 *
 * <h2>混合策略定位</h2>
 * 与学术文献「GA（全局）+ 局部搜索（精修）」一致：本类只做全局种群探索，
 * 交叉/变异后的个体用 {@link ScheduleOptimizer#solutionManager()} 直接评分（不跑完整求解，
 * 省下算力）；个体多样性的来源是初始种群用多个随机种子各求解一次。
 * 之后的「精修」由大邻域搜索（LNS）承担。
 *
 * <p><b>只接受更好</b>：精英保留保证最优个体不退化，返回结果仅当严格优于输入种子解。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeneticAlgorithm {

    private final ScheduleOptimizer optimizer;

    /** 锦标赛选择中参与比较的候选数（注释曾写「随机挑 k 个」，此处明确 k 的取值） */
    private static final int TOURNAMENT_SIZE = 2;

    /** 一次进化的过程与结果，用于对外解释「种群如何演化」 */
    public record Report(boolean used, int populationSize, int generations, String initialScore,
                         String finalScore, int crossovers, int mutations, List<String> trace) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("used", used);
            m.put("populationSize", populationSize);
            m.put("generations", generations);
            m.put("initialScore", initialScore);
            m.put("finalScore", finalScore);
            m.put("crossovers", crossovers);
            m.put("mutations", mutations);
            m.put("trace", trace);
            return m;
        }
    }

    /**
     * 进化一个种群。
     *
     * @param seed                上游（算法组合层）给出的种子解
     * @param populationSize      种群大小（2..40）
     * @param generations         代数（1..10）
     * @param perIndividualBudget 初始种群里每个「多样化个体」的求解时间预算
     * @param mutationRate        变异概率（0..1）
     */
    public Optional<SchedulePlan> evolve(SchedulePlan seed, int populationSize, int generations,
                                         Duration perIndividualBudget, double mutationRate) {
        return evolve(seed, populationSize, generations, perIndividualBudget, mutationRate,
                ScheduleOptimizer.RANDOM_SEED);
    }

    /** 显式指定随机种子（测试用） */
    public Optional<SchedulePlan> evolve(SchedulePlan seed, int populationSize, int generations,
                                         Duration perIndividualBudget, double mutationRate, long seedBase) {
        if (seed == null || seed.getUnits() == null || seed.getUnits().isEmpty() || seed.getScore() == null
                || populationSize < 2 || generations < 1) {
            return Optional.empty();
        }
        int pop = Math.min(Math.max(2, populationSize), 40);
        int gens = Math.min(generations, 10);

        // ① 初始种群：种子解 + (pop-1) 个不同随机种子的多样化个体。
        //    用模拟退火（允许暂时变差）产生多样性——不同种子会落到不同的局部最优附近。
        List<SchedulePlan> population = new ArrayList<>();
        population.add(seed.deepCopy());
        for (int i = 1; i < pop; i++) {
            optimizer.solve(seed, perIndividualBudget, LocalSearchType.SIMULATED_ANNEALING, seedBase + i * 101L)
                    .ifPresent(population::add);
        }
        if (population.size() < 2) {
            return Optional.empty();   // 多样化个体一个都没跑出来，种群退化成单点，直接放弃
        }

        SolutionManager<SchedulePlan, HardMediumSoftScore> scorer = optimizer.solutionManager();
        for (SchedulePlan p : population) {
            scorer.update(p);
        }

        SchedulePlan best = bestOf(population);
        List<String> trace = new ArrayList<>();
        trace.add("初始种群 " + population.size() + " 个体，最优 " + best.getScore());
        int crossovers = 0;
        int mutations = 0;

        // ② 逐代进化：精英保留 + 锦标赛选择 + 均匀交叉 + 变异
        for (int g = 0; g < gens; g++) {
            List<SchedulePlan> next = new ArrayList<>();
            next.add(best.deepCopy());                       // 精英保留：最优个体原样进入下一代
            while (next.size() < pop) {
                SchedulePlan pa = tournament(population, seedBase + g * 1000L + next.size());
                SchedulePlan pb = tournament(population, seedBase + g * 1000L + next.size() + 7L);
                SchedulePlan child = GeneticOperators.uniformCrossover(pa, pb,
                        seedBase + g * 1000L + next.size() + 13L);
                crossovers++;
                child = GeneticOperators.mutate(child, mutationRate,
                        seedBase + g * 1000L + next.size() + 29L);
                mutations++;
                scorer.update(child);
                next.add(child);
            }
            population = next;
            SchedulePlan genBest = bestOf(population);
            if (genBest.getScore().compareTo(best.getScore()) > 0) {
                best = genBest;
            }
            trace.add("第" + (g + 1) + "代 最优 " + genBest.getScore());
        }

        // ③ 只接受更好：精英保留保证 best ≥ seed，但只有严格更优才算「改进」
        if (best.getScore().compareTo(seed.getScore()) <= 0) {
            log.info("遗传算法: 种群 {}、{} 代后无改进（初始解已足够好）", pop, gens);
            return Optional.empty();
        }
        log.info("遗传算法: 种群 {}、{} 代，{} 次交叉 / {} 次变异，{} → {}",
                pop, gens, crossovers, mutations, seed.getScore(), best.getScore());
        return Optional.of(best);
    }

    /** 锦标赛选择：随机挑 k 个，取评分最高者（兼顾选择压力与多样性） */
    private static SchedulePlan tournament(List<SchedulePlan> population, long seed) {
        Random rnd = new Random(seed);
        SchedulePlan best = population.get(rnd.nextInt(population.size()));
        for (int i = 1; i < TOURNAMENT_SIZE; i++) {
            SchedulePlan c = population.get(rnd.nextInt(population.size()));
            if (c.getScore().compareTo(best.getScore()) > 0) {
                best = c;
            }
        }
        return best;
    }

    private static SchedulePlan bestOf(List<SchedulePlan> population) {
        SchedulePlan best = population.get(0);
        for (int i = 1; i < population.size(); i++) {
            if (population.get(i).getScore().compareTo(best.getScore()) > 0) {
                best = population.get(i);
            }
        }
        return best;
    }
}
