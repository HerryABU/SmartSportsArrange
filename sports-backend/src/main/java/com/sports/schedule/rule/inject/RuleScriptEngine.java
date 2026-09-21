package com.sports.schedule.rule.inject;

/**
 * 规则脚本执行引擎抽象。
 *
 * <p>「两条路径」共用此接口：</p>
 * <ul>
 *   <li>{@code builtin} —— 内置伪代码引擎（零依赖、按构造即沙箱、必可用）；</li>
 *   <li>{@code js} / {@code groovy} —— JSR-223 脚本注入（命令式，需引擎在 classpath；
 *       Java 21 已移除内置 Nashorn，需自行引入 Groovy/GraalJS）。</li>
 * </ul>
 *
 * <p>实现约定：{@link #evaluate} <b>不抛异常</b>——失败一律返回 {@link RuleOutcome#error}，
 * 由上层如实上报，保证「脚本坏掉不拖垮编排」。</p>
 */
public interface RuleScriptEngine {

    /** 引擎标识（builtin / js / groovy / …），小写。 */
    String name();

    /** 引擎当前是否可用（JSR-223 引擎缺失时返回 false）。 */
    boolean available();

    /**
     * 引擎是否<b>可信</b>（不会跑飞）。
     *
     * <p>可信引擎（内置伪代码：无循环/无 IO/无反射，求值是纯函数）可<b>直接在当前线程求值</b>，
     * 无需线程池 + 超时保护。这一点对热路径至关重要：超时是「等 N 毫秒还没返回」的判定，
     * 一旦把微秒级的内置求值也塞进线程池，高并发下排队/调度抖动会让它偶发「伪超时」，
     * 表现为<b>规则莫名其妙不生效</b>（被降级成 error），且伴随大量线程创建。</p>
     *
     * <p>不可信引擎（JSR-223：用户脚本可能死循环）必须走线程池 + 超时。</p>
     */
    default boolean trusted() {
        return false;
    }

    /** 执行规则片段，返回分数增量/否决/触发说明；失败返回带 error 的结果。 */
    RuleOutcome evaluate(RuleScript script, RuleContext context);
}
