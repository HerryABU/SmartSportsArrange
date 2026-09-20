package com.sports.schedule.rule.inject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 规则脚本执行器：按脚本声明的引擎分派，带<b>超时保护</b>，失败一律降级为「带错误的结果」。
 *
 * <p>「两条路径」在此汇合：{@code builtin}（内置伪代码）与 {@code js}/{@code groovy}（JSR-223 注入）
 * 共用同一执行/降级口径——这样上层（编排注入点）无需关心脚本用哪种语言写的。</p>
 */
public class RuleScriptEvaluator {

    /** 默认超时：脚本属于编排热路径，超时必须短，避免拖垮编排。 */
    public static final long DEFAULT_TIMEOUT_MILLIS = 200L;

    private final Map<String, RuleScriptEngine> engines = new LinkedHashMap<>();
    private final long timeoutMillis;

    /**
     * 共享执行池（守护线程）：脚本评估会进入编排热路径（每条落位一次），
     * 因此<b>必须复用线程池</b>——每次新建池的开销会让注入拖垮编排。
     */
    private final java.util.concurrent.ExecutorService pool =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "rule-script-eval");
                t.setDaemon(true);
                return t;
            });

    public RuleScriptEvaluator() {
        this(DEFAULT_TIMEOUT_MILLIS);
    }

    public RuleScriptEvaluator(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis > 0 ? timeoutMillis : DEFAULT_TIMEOUT_MILLIS;
        // 默认注册内置引擎 + 常见 JSR-223 引擎（缺失时 evaluate 会给出明确错误）
        register(new com.sports.schedule.rule.inject.builtin.BuiltinRuleScriptEngine());
        register(new Jsr223RuleScriptEngine("groovy"));
        register(new Jsr223RuleScriptEngine("javascript"));
    }

    public RuleScriptEvaluator register(RuleScriptEngine engine) {
        engines.put(engine.name().toLowerCase(), engine);
        return this;
    }

    public RuleScriptEngine engineFor(RuleScript script) {
        String key = (script == null || script.engine() == null || script.engine().isBlank())
                ? RuleScript.ENGINE_BUILTIN : script.engine().trim().toLowerCase();
        return engines.get(key);
    }

    public long timeoutMillis() {
        return timeoutMillis;
    }

    /**
     * 执行规则片段。绝不抛异常：未注册引擎 / 引擎不可用 / 超时 / 运行异常 都返回带 error 的结果。
     */
    public RuleOutcome evaluate(RuleScript script, RuleContext context) {
        if (script == null) {
            return RuleOutcome.empty();
        }
        RuleScriptEngine engine = engineFor(script);
        if (engine == null) {
            return RuleOutcome.error("未注册的脚本引擎: " + script.engine());
        }
        if (!engine.available()) {
            // 让引擎自己给出「引擎缺失」的明确错误（如 JSR-223 未引入依赖）
            return engine.evaluate(script, context);
        }
        Future<RuleOutcome> future = pool.submit(() -> engine.evaluate(script, context));
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return RuleOutcome.error("脚本执行超时（>" + timeoutMillis + "ms），已中止");
        } catch (ExecutionException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            return RuleOutcome.error("脚本执行异常: " + c.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RuleOutcome.error("脚本执行被中断");
        }
    }
}
