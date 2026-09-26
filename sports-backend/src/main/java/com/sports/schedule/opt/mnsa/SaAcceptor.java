package com.sports.schedule.opt.mnsa;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;

import java.util.Random;

/**
 * 模拟退火的<b>接受准则</b>：比当前好必接受；比当前差时按
 * {@code exp(-变差幅度 / 温度)} 的概率接受，温度随步数几何下降。
 *
 * <h2>为什么精修阶段也需要「允许暂时变差」</h2>
 * 既有 LNS 用「只接受更好」——那是从组合层已经很好的解上做<b>深层重构</b>时的正确选择；
 * 而 MNSA 是<b>单步浅层移动</b>：从局部最优走出深谷往往要先上一段坡，
 * 「只接受更好」会在谷底一步都不敢动。SA 的退火曲线给了「早期敢爬坡、后期稳收敛」的
 * 时间表：温度高时几乎来者不拒（充分探索），温度衰减到接近 0 时退化成贪心（稳定收尾）。
 *
 * <p>三层评分的「变差幅度」按字典序放大成标量：
 * {@code 标量 = 硬×10¹² + 中×10⁶ + 软}——任何一层的退化都压倒下层的改进，
 * 与 {@link HardMediumSoftScore#compareTo} 的语义严格一致。</p>
 */
public final class SaAcceptor {

    /** 起始温度：软分层一次「中等幅度变差」（约几十分软分）早期可被接受 */
    private static final double INITIAL_TEMPERATURE = 40.0;
    /** 退火终点温度：低于它的变差实际上不再被接受（≈ 贪心收尾） */
    private static final double FINAL_TEMPERATURE = 0.05;

    private final double coolingRate;
    private double temperature;

    private SaAcceptor(double initialTemperature, double coolingRate) {
        this.temperature = initialTemperature;
        this.coolingRate = coolingRate;
    }

    /**
     * 按迭代步数构造几何退火计划：温度从 {@value INITIAL_TEMPERATURE} 衰减到
     * {@value FINAL_TEMPERATURE}，使得整轮搜索「前段探索、后段收敛」。
     *
     * @param iterations 计划施加的移动步数（≥1）
     */
    public static SaAcceptor geometric(int iterations) {
        int steps = Math.max(1, iterations);
        double rate = Math.pow(FINAL_TEMPERATURE / INITIAL_TEMPERATURE, 1.0 / steps);
        return new SaAcceptor(INITIAL_TEMPERATURE, rate);
    }

    /** 显式指定初温与衰减率（测试用） */
    public static SaAcceptor of(double initialTemperature, double coolingRate) {
        return new SaAcceptor(initialTemperature, coolingRate);
    }

    /**
     * 判断候选解是否被接受。
     *
     * <p>注意：无论接受与否，<b>温度都衰减一步</b>——退火计划是按步数推进的，
     * 与结果无关，保证「同迭代数 ⇒ 同温度曲线 ⇒ 同随机种子 ⇒ 同结果」的可复现性。</p>
     */
    public boolean accept(HardMediumSoftScore current, HardMediumSoftScore candidate, Random rnd) {
        boolean ok;
        int cmp = candidate.compareTo(current);
        if (cmp >= 0) {
            ok = true;
        } else {
            double worse = scalar(current) - scalar(candidate);
            ok = rnd.nextDouble() < Math.exp(-worse / Math.max(temperature, 1e-9));
        }
        temperature = Math.max(temperature * coolingRate, 1e-6);
        return ok;
    }

    public double temperature() {
        return temperature;
    }

    /** 三层评分的字典序标量化（见类注释） */
    static double scalar(HardMediumSoftScore s) {
        return s.hardScore() * 1e12 + s.mediumScore() * 1e6 + s.softScore();
    }
}
