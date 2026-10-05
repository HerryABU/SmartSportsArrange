package com.sports.schedule.ai;

import java.util.Optional;

/**
 * 主 MoE 的**静态桥**：让「手工装配、不在 Spring 容器里」的编排组件也能用到它。
 *
 * <h2>为什么需要静态桥而不是构造注入</h2>
 * {@code ScheduleSolveComponent} 是 {@code new} 出来的（不是 Spring Bean），
 * 且它的构造器被 4 个 {@code @InjectMocks} 测试类依赖 ——
 * 为加一个依赖去改构造签名，会连带改 4 处测试，收益远不抵风险。
 * 本项目对这种「旁路能力」已有既定做法：静态桥
 * （同 {@code RuleInjectionHolder}，因为 Timefold 的 ConstraintProvider
 * 也是反射实例化、同样拿不到容器）。
 *
 * <h2>分层的语义</h2>
 * 这里只承载<b>主 MoE</b>（第一次编排用）。
 * 各微调环节的专项 MoE（组次错开 / 跨时段拆分）由 {@link AiTiers} 直接调用 ——
 * 它们都在 Spring Bean 里，不需要桥。这正是用户要求的
 * 「第一次编排用主、各微调环节用专项」在装配层的落地。
 *
 * <h2>缺失时的行为</h2>
 * {@link #advise} 返回 {@link Optional#empty()}，调用方按原顺序求解（等价于无 AI）。
 * <b>AI 是增强而非交付前提</b>：桥没绑上、模型没加载、推理失败，功能都必须照常可用。
 */
public final class PrimaryMoeBridge {

    private static volatile AiTiers tiers;

    private PrimaryMoeBridge() {
    }

    /** Spring 启动时由 {@link AiTiers} 绑定。 */
    public static void bind(AiTiers t) {
        tiers = t;
    }

    /** 是否已绑上（供可观测判断「第一次编排到底有没有用主 MoE」）。 */
    public static boolean bound() {
        return tiers != null;
    }

    /**
     * 向主 MoE 要「第一次编排」的建议。
     *
     * <p>返回 {@link Optional#empty()} 的三种正常情形：未绑定 / 模型不可用 / 推理失败。
     * 三者都不应该影响编排结果，只影响「有没有 AI 加持」。</p>
     */
    public static Optional<SuperMoeService.Advice> advise(
            SuperScheduleEncoder.Encoded encoded) {
        AiTiers t = tiers;
        if (t == null || encoded == null || encoded.degraded()) {
            return Optional.empty();
        }
        return t.advisePrimary(encoded);
    }
}
