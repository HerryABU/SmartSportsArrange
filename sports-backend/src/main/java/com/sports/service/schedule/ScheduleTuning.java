package com.sports.service.schedule;

/**
 * 精修链（GA / LNS / MNSA / ALNS / Fix-and-Optimize）的调参快照。
 *
 * <p><b>为什么单独抽一个类型：</b>这些参数来自 facade 的 {@code @Value} 字段，而 Spring 的
 * {@code @Value} 是<b>字段注入，发生在构造器执行之后</b>。旧实现在构造器里直接
 * {@code new ScheduleSolveComponent(..., lnsRounds, gaPopulation, mnsaIterations, ...)}，
 * 那一刻这 10 个字段全是 0；而 {@code ScheduleSolveComponent} 对每个算法都以「&gt;0 才启用」
 * 为开关 ⇒ 生产环境整条精修链<b>静默关闭</b>（前端 {@code algorithmPortfolio} 里连
 * {@code ga} / {@code lns} 字段都不会出现，日志上却一切正常）。</p>
 *
 * <p>修法是「<b>不可变快照 + 使用时求值</b>」：组件不在构造期缓存这些值，而是每次求解现取一份，
 * 字段注入完成之后拿到的自然就是配置文件里的真实值（如 {@code lns-rounds: 2}）。</p>
 *
 * <p>⚠️ 新增算法参数时必须同时改三处：facade 的 {@code @Value} 字段、{@link #describe()}、
 * 本记录的构造调用；否则参数「配了不生效」而毫无报错——这就是本类型诞生的原因。</p>
 */
public record ScheduleTuning(
        int lnsRounds,
        long lnsRoundMillis,
        int gaPopulation,
        int gaGenerations,
        double gaMutationRate,
        long gaIndividualMillis,
        int mnsaIterations,
        int alnsRounds,
        int fixoptRounds,
        long fixoptSliceMillis) {

    /** GA 的启用口径与 {@code ScheduleSolveComponent} 内的判据逐字一致（种群 ≥2 且 ≥1 代）。 */
    public boolean gaEnabled() {
        return gaPopulation >= 2 && gaGenerations >= 1;
    }

    public boolean lnsEnabled() {
        return lnsRounds > 0;
    }

    public boolean mnsaEnabled() {
        return mnsaIterations > 0;
    }

    public boolean alnsEnabled() {
        return alnsRounds > 0;
    }

    public boolean fixoptEnabled() {
        return fixoptRounds > 0;
    }

    /** 精修链是否至少有一环被启用——全为 false 说明装配又退回了「配了不生效」。 */
    public boolean refineChainEnabled() {
        return gaEnabled() || lnsEnabled() || mnsaEnabled() || alnsEnabled() || fixoptEnabled();
    }

    /** 启动期与诊断日志共用的一行摘要：开/关与实际取值同时打印，「没跑」与「跑了没改进」才分得清。 */
    public String describe() {
        return String.format(
                "GA=%s(%d种群×%d代,变异%.2f) LNS=%s(%d轮×%dms) MNSA=%s(%d步) ALNS=%s(%d轮) FixOpt=%s(%d轮×%dms)",
                gaEnabled() ? "开" : "关", gaPopulation, gaGenerations, gaMutationRate,
                lnsEnabled() ? "开" : "关", lnsRounds, lnsRoundMillis,
                mnsaEnabled() ? "开" : "关", mnsaIterations,
                alnsEnabled() ? "开" : "关", alnsRounds,
                fixoptEnabled() ? "开" : "关", fixoptRounds, fixoptSliceMillis);
    }
}
