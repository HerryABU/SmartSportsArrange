package com.sports.schedule.rule.inject;

import com.sports.schedule.rule.inject.builtin.BuiltinRuleScriptEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内置伪代码规则引擎（形态一 · 规则注入）测试。
 *
 * <p>覆盖：when/if 两种写法、点路径字段、比较与逻辑运算、中文串比较、注释、否决、多动作，
 * 以及「语法错误降级为 error 结果而非抛异常」「JSR-223 引擎缺失时的明确降级」。</p>
 */
@DisplayName("内置规则片段引擎（规则注入）")
class BuiltinRuleScriptEngineTest {

    private final BuiltinRuleScriptEngine engine = new BuiltinRuleScriptEngine();

    private RuleContext ctx(Object... kv) {
        RuleContext.Builder b = RuleContext.builder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            b.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return b.build();
    }

    @Test
    @DisplayName("when-then：条件成立 → 软分累加并记录触发")
    void whenThenFires() {
        RuleScript s = RuleScript.builtin("r1", "前2道软罚",
                "when event.category == \"径赛\" && lane <= 2 then soft += 30");
        RuleOutcome o = engine.evaluate(s,
                ctx("event", Map.of("category", "径赛"), "lane", 2));
        assertFalse(o.hasError(), "不应降级为错误：" + o.error());
        assertEquals(30, o.soft());
        assertEquals(0, o.hard());
        assertEquals(1, o.fired().size());
        assertTrue(o.fired().get(0).contains("前2道软罚"));
    }

    @Test
    @DisplayName("when-then：条件不成立 → 不产生任何增量")
    void whenThenSkips() {
        RuleScript s = RuleScript.builtin("r1", "前2道软罚",
                "when event.category == \"径赛\" && lane <= 2 then soft += 30");
        RuleOutcome o = engine.evaluate(s, ctx("event", Map.of("category", "径赛"), "lane", 3));
        assertEquals(0, o.soft());
        assertTrue(o.fired().isEmpty());
    }

    @Test
    @DisplayName("if 块写法 + 多动作（hard 与 veto）")
    void ifBlockAndMultipleActions() {
        RuleScript s = RuleScript.builtin("r2", "同班同组重罚",
                "if (teamMembers > 1 && heat > 6) { hard += 100; veto }");
        RuleOutcome o = engine.evaluate(s, ctx("teamMembers", 4, "heat", 8));
        assertEquals(100, o.hard());
        assertTrue(o.veto());
        assertEquals(2, o.fired().size());
    }

    @Test
    @DisplayName("点路径字段与中文串比较")
    void dotPathAndChineseString() {
        RuleScript s = RuleScript.builtin("r3", "高一1班偏好",
                "when athlete.className == \"高一1班\" then soft += 5");
        RuleOutcome hit = engine.evaluate(s, ctx("athlete", Map.of("className", "高一1班")));
        assertEquals(5, hit.soft());
        RuleOutcome miss = engine.evaluate(s, ctx("athlete", Map.of("className", "高二3班")));
        assertEquals(0, miss.soft());
    }

    @Test
    @DisplayName("逻辑运算：|| 与 ! 与布尔字面量")
    void logicalOps() {
        RuleScript s = RuleScript.builtin("r4", "非径赛且为团体",
                "when !(track == true) || (team == true && teamMembers >= 4) then medium += 7");
        // track=false → !(false==true)=true → 命中
        assertEquals(7, engine.evaluate(s, ctx("track", false)).medium());
        // track=true、team=true、teamMembers=4 → 右支命中
        assertEquals(7, engine.evaluate(s, ctx("track", true, "team", true, "teamMembers", 4)).medium());
        // track=true、team=false → 都不命中
        assertEquals(0, engine.evaluate(s, ctx("track", true, "team", false, "teamMembers", 4)).medium());
    }

    @Test
    @DisplayName("注释剥离（# 与 //）")
    void commentsStripped() {
        RuleScript s = RuleScript.builtin("r5", "带注释",
                "# 头部说明\nwhen lane == 1 then hard += 1 // 行尾说明\n");
        RuleOutcome o = engine.evaluate(s, ctx("lane", 1));
        assertEquals(1, o.hard());
    }

    @Test
    @DisplayName("多行多条规则各自独立触发")
    void multipleStatements() {
        RuleScript s = RuleScript.builtin("r6", "多条",
                "when lane == 1 then soft += 10\nwhen lane == 1 then soft += 5");
        RuleOutcome o = engine.evaluate(s, ctx("lane", 1));
        assertEquals(15, o.soft());
        assertEquals(2, o.fired().size());
    }

    @Test
    @DisplayName("语法错误/未知动作 → 降级为 error 结果（不抛异常）")
    void syntaxErrorDegrades() {
        RuleOutcome bad = engine.evaluate(
                RuleScript.builtin("e1", "坏条件", "when lane == then soft += 1"), ctx("lane", 1));
        assertTrue(bad.hasError());
        assertTrue(bad.error().contains("内置规则执行失败"));

        RuleOutcome badAction = engine.evaluate(
                RuleScript.builtin("e2", "坏动作", "when lane == 1 then soft -= 5"), ctx("lane", 1));
        assertTrue(badAction.hasError());
        assertTrue(badAction.error().contains("无法识别的动作"));

        RuleOutcome badStmt = engine.evaluate(
                RuleScript.builtin("e3", "坏语句", "foo bar baz"), ctx());
        assertTrue(badStmt.hasError());
    }

    @Test
    @DisplayName("缺失字段不报错（宽松取值 → 条件为假）")
    void missingFieldIsLenient() {
        RuleScript s = RuleScript.builtin("r7", "缺失字段", "when notExist.deep == 1 then hard += 9");
        RuleOutcome o = engine.evaluate(s, ctx());
        assertFalse(o.hasError());
        assertEquals(0, o.hard());
    }

    @Test
    @DisplayName("执行器分派：内置可用；未知引擎与缺失的 JSR-223 引擎均降级为 error")
    void evaluatorDispatch() {
        RuleScriptEvaluator ev = new RuleScriptEvaluator();
        assertEquals("builtin", ev.engineFor(RuleScript.builtin("a", "a", "when 1 == 1 then soft += 1")).name());

        // 未知引擎
        RuleOutcome unknown = ev.evaluate(new RuleScript("u", "u", "ruby", true, "puts 1"), ctx());
        assertTrue(unknown.hasError());
        assertTrue(unknown.error().contains("未注册的脚本引擎"));

        // JSR-223：引擎在场才断言其可执行；不在场（Java 21 默认无 Nashorn）断言明确降级
        RuleScriptEngine js = ev.engineFor(new RuleScript("j", "j", "javascript", true, "soft(1)"));
        assertNotNull(js);
        if (js.available()) {
            assertFalse(ev.evaluate(new RuleScript("j", "j", "javascript", true, "soft(1)"), ctx()).hasError());
        } else {
            RuleOutcome jsOut = ev.evaluate(new RuleScript("j", "j", "javascript", true, "soft(1)"), ctx());
            assertTrue(jsOut.hasError());
            assertTrue(jsOut.error().contains("JSR-223"));
        }
    }
}
