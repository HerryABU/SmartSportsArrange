package com.sports.schedule.rule.inject;

/**
 * 编排规则脚本（L1「自定义规则」层 · 形态一 · 规则注入）。
 *
 * <p>L1 名义上就是「自定义规则」层：除了内置分组款型（班级均衡 / 蛇形排布 / 种子蛇形），
 * 用户还可用「规则片段」表达自定义约束——这正是「伪代码」的落点。</p>
 *
 * @param id      唯一标识
 * @param name    显示名
 * @param engine  执行引擎：{@code builtin}（内置伪代码，零依赖，天然沙箱）/ {@code js} / {@code groovy}
 *                （JSR-223 脚本注入，需相应引擎在 classpath）
 * @param enabled 是否启用
 * @param source  规则片段源码
 */
public record RuleScript(String id, String name, String engine, boolean enabled, String source) {

    /** 内置伪代码引擎标识（默认）。 */
    public static final String ENGINE_BUILTIN = "builtin";

    /** 是否走内置伪代码引擎（engine 为空视为内置）。 */
    public boolean builtin() {
        return engine == null || engine.isBlank() || ENGINE_BUILTIN.equalsIgnoreCase(engine.trim());
    }

    public static RuleScript builtin(String id, String name, String source) {
        return new RuleScript(id, name, ENGINE_BUILTIN, true, source);
    }
}
