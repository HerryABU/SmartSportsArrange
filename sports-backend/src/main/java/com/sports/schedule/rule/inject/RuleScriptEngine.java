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

    /** 执行规则片段，返回分数增量/否决/触发说明；失败返回带 error 的结果。 */
    RuleOutcome evaluate(RuleScript script, RuleContext context);
}
