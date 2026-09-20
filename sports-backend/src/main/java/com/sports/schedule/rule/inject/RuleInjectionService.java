package com.sports.schedule.rule.inject;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规则注入服务（L1「自定义规则」层 · 形态一）。
 *
 * <p>把用户写的「规则片段」评估为分数增量（hard/medium/soft）与否决标记：</p>
 * <ul>
 *   <li>编排流程据此对候选落位施加额外硬否决 / 软惩罚（注入约束）；</li>
 *   <li>前端可「试运行」单条脚本，实时看到命中/增量/错误。</li>
 * </ul>
 *
 * <p>「两条路径」由 {@link RuleScriptEvaluator} 统一分派：内置伪代码（{@code builtin}）与
 * JSR-223 脚本注入（{@code groovy} / {@code javascript}）。</p>
 */
@Service
public class RuleInjectionService {

    private final RuleScriptStore store;
    private final RuleScriptEvaluator evaluator;

    /**
     * 脚本缓存：编排热路径会高频调用 {@link #assess}，若每次都查库会拖垮编排。
     * 保存时失效（{@link #save}），因此读多写少的场景下等价于「修改即生效、读取零查库」。
     */
    private volatile List<RuleScript> cache;

    /** 仅启用脚本的缓存（assess 只用到启用的）。 */
    private volatile List<RuleScript> enabledCache;

    /** 求解热路径的记忆表（key = 单元|落位），上限后整体清空（防无界增长）。 */
    private final Map<String, RuleOutcome> memo = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MEMO_MAX = 200_000;

    public RuleInjectionService(RuleScriptStore store) {
        this.store = store;
        this.evaluator = new RuleScriptEvaluator();
        // 绑定静态桥：供 Timefold 约束流（反射实例化、无法构造注入）取用
        RuleInjectionHolder.bind(this);
    }

    public RuleScriptEvaluator evaluator() {
        return evaluator;
    }

    /** 是否存在启用脚本（供约束流零成本短路）。 */
    public boolean hasEnabledScripts() {
        if (enabledCache == null) {
            list();
        }
        return enabledCache != null && !enabledCache.isEmpty();
    }

    /**
     * 带记忆的评估：Timefold 求解热路径会反复评估同一 (单元, 落位) 组合，
     * 故按调用方给出的 cacheKey 记忆结果；脚本变更（save）时整体失效。
     */
    public RuleOutcome assessCached(String cacheKey, RuleContext context) {
        if (!hasEnabledScripts() || cacheKey == null) {
            return assess(context);
        }
        RuleOutcome hit = memo.get(cacheKey);
        if (hit != null) {
            return hit;
        }
        RuleOutcome o = assess(context);
        if (memo.size() > MEMO_MAX) {
            memo.clear();
        }
        memo.put(cacheKey, o);
        return o;
    }

    /** 全部规则脚本（带缓存）。 */
    public List<RuleScript> list() {
        List<RuleScript> c = cache;
        if (c == null) {
            c = store.load();
            cache = c;
            List<RuleScript> en = new ArrayList<>();
            for (RuleScript s : c) {
                if (s != null && s.enabled()) {
                    en.add(s);
                }
            }
            enabledCache = en;
        }
        return c;
    }

    /** 覆盖保存（校验 id 非空且唯一），保存后使缓存失效。 */
    public List<RuleScript> save(List<RuleScript> scripts) {
        if (scripts != null) {
            Set<String> ids = new HashSet<>();
            for (RuleScript s : scripts) {
                if (s == null || s.id() == null || s.id().isBlank()) {
                    throw new IllegalArgumentException("规则脚本 id 不能为空");
                }
                if (!ids.add(s.id())) {
                    throw new IllegalArgumentException("规则脚本 id 重复: " + s.id());
                }
            }
        }
        store.save(scripts);
        cache = null;
        enabledCache = null;
        memo.clear();   // 脚本变更 → 记忆结果失效
        return list();
    }

    /** 评估全部「启用」脚本并聚合（供编排注入点调用）。 */
    public RuleOutcome assess(RuleContext context) {
        if (enabledCache == null && cache == null) {
            list();     // 触发缓存加载
        }
        RuleOutcome total = RuleOutcome.empty();
        List<RuleScript> enabled = enabledCache == null ? List.of() : enabledCache;
        for (RuleScript s : enabled) {
            total.merge(evaluator.evaluate(s, context));
        }
        return total;
    }

    /** 试运行：单条脚本 + 给定上下文 → 结果明细（供前端「试运行」面板）。 */
    public Map<String, Object> test(RuleScript script, RuleContext context) {
        RuleOutcome o = evaluator.evaluate(script, context);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", !o.hasError());
        m.put("error", o.error());
        m.put("hard", o.hard());
        m.put("medium", o.medium());
        m.put("soft", o.soft());
        m.put("veto", o.veto());
        m.put("fired", o.fired());
        return m;
    }

    /** 各引擎可用性（供前端提示：JSR-223 引擎缺失时需引入依赖）。 */
    public List<Map<String, Object>> engines() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String name : List.of(RuleScript.ENGINE_BUILTIN, "groovy", "javascript")) {
            RuleScriptEngine e = evaluator.engineFor(new RuleScript("_probe", "_probe", name, true, ""));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("available", e != null && e.available());
            m.put("builtin", RuleScript.ENGINE_BUILTIN.equalsIgnoreCase(name));
            list.add(m);
        }
        return list;
    }
}
