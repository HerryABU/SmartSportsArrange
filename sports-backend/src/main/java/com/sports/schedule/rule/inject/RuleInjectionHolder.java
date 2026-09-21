package com.sports.schedule.rule.inject;

/**
 * 规则注入服务的全局持有者（静态桥）。
 *
 * <p><b>为什么需要它</b>：Timefold 的 {@code ConstraintProvider} 由求解器用<b>反射</b>实例化
 * （见 {@code SolverConfigFactory} 的 {@code withConstraintProviderClass}），不经过 Spring，
 * 因此无法构造注入。这里用一个极薄的静态桥把 Spring 管理的 {@link RuleInjectionService}
 * 暴露给约束流；由 {@link RuleInjectionService} 构造时自动绑定，无需额外启动代码。</p>
 */
public final class RuleInjectionHolder {

    private static volatile RuleInjectionService instance;

    private RuleInjectionHolder() {
    }

    public static void bind(RuleInjectionService service) {
        instance = service;
    }

    public static RuleInjectionService get() {
        return instance;
    }

    /** 是否已绑定<b>且存在启用脚本</b>——供约束流零成本短路（未配规则时注入约束不产生任何开销）。 */
    public static boolean active() {
        RuleInjectionService s = instance;
        return s != null && s.hasEnabledScripts();
    }
}
