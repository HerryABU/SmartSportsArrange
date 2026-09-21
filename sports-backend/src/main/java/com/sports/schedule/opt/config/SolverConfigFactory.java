package com.sports.schedule.opt.config;

import ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicPhaseConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchType;
import ai.timefold.solver.core.config.localsearch.decider.acceptor.AcceptorType;
import ai.timefold.solver.core.config.localsearch.decider.acceptor.LocalSearchAcceptorConfig;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import com.sports.schedule.opt.ScheduleConstraintProvider;
import com.sports.schedule.opt.SchedulePlan;
import com.sports.schedule.opt.ScheduleUnit;

import java.time.Duration;
import java.util.List;

/**
 * 求解器配置工厂（算法系：算法配置）。
 *
 * <p>把「元启发式类型 + 随机种子 + 时间预算」翻译成 Timefold {@link SolverConfig}，
 * 是算法选择层与求解器之间的执行入口。纯静态、无 Spring 依赖，可独立单测。</p>
 */
public final class SolverConfigFactory {

    private SolverConfigFactory() {
    }

    /**
     * 按元启发式类型与随机种子构造求解配置——算法选择层的执行入口。
     *
     * @param budget 时间预算（终止条件）
     * @param type   局部搜索算法（禁忌搜索 / 模拟退火 / 迟接受 …）
     * @param seed   随机种子（固定以保证可复现）
     */
    public static SolverConfig config(Duration budget, LocalSearchType type, long seed) {
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
    private static LocalSearchPhaseConfig localSearchPhase(LocalSearchType type) {
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
}
