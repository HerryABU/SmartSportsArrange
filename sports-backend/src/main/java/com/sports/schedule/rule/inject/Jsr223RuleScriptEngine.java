package com.sports.schedule.rule.inject;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.SimpleBindings;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * JSR-223 脚本注入引擎（形态一 · 命令式路径）。
 *
 * <p><b>Java 21 现实</b>：JDK 15 起已移除内置 Nashorn，{@code "javascript"} 引擎默认不存在，
 * 因此本类在引擎缺失时：{@link #available()} 返回 false、{@link #evaluate} 返回
 * <b>带明确错误的结果</b>（不抛异常）。这样系统在无引擎环境下仍可正常运行，只如实提示
 * 「请引入 Groovy（{@code org.codehaus.groovy:groovy-jsr223}）或 GraalJS 依赖」。</p>
 *
 * <p><b>沙箱（阶段性）</b>：仅注入受控绑定——只读上下文 {@code ctx} 与四个动作助手
 * {@code hard(n) / medium(n) / soft(n) / veto()}，不暴露类加载器与反射；
 * 执行超时由 {@link RuleScriptEvaluator} 统一控制。更严格的类/方法白名单需引擎级配置（后续阶段）。</p>
 */
public class Jsr223RuleScriptEngine implements RuleScriptEngine {

    private final String engineName;

    public Jsr223RuleScriptEngine(String engineName) {
        this.engineName = engineName;
    }

    @Override
    public String name() {
        return engineName;
    }

    @Override
    public boolean available() {
        return new ScriptEngineManager().getEngineByName(engineName) != null;
    }

    @Override
    public RuleOutcome evaluate(RuleScript script, RuleContext ctx) {
        ScriptEngine engine = new ScriptEngineManager().getEngineByName(engineName);
        if (engine == null) {
            return RuleOutcome.error("未检测到 JSR-223 引擎「" + engineName + "」：Java 21 已移除内置 Nashorn，"
                    + "请引入 Groovy(org.codehaus.groovy:groovy-jsr223) 或 GraalJS 依赖后重试");
        }
        RuleOutcome out = RuleOutcome.empty();
        Bindings bindings = new SimpleBindings();
        bindings.put("ctx", ctx == null ? Map.of() : ctx.asMap());
        bindings.put("hard", (LongConsumer) out::addHard);
        bindings.put("medium", (LongConsumer) out::addMedium);
        bindings.put("soft", (LongConsumer) out::addSoft);
        bindings.put("veto", (Runnable) out::markVeto);
        try {
            engine.eval(script == null ? "" : script.source(), bindings);
        } catch (Exception e) {
            return RuleOutcome.error("脚本执行失败（" + engineName + "）: " + e.getMessage());
        }
        return out;
    }
}
