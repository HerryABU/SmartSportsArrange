package com.sports.schedule.rule.inject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 规则注入服务测试：启用脚本聚合、停用脚本跳过、试运行明细、引擎可用性、保存校验。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("规则注入服务（形态一）")
class RuleInjectionServiceTest {

    @Mock
    private RuleScriptStore store;

    private RuleContext ctx() {
        return RuleContext.builder()
                .put("event", Map.of("category", "径赛"))
                .put("lane", 1)
                .build();
    }

    @Test
    @DisplayName("assess：聚合全部启用脚本、跳过停用脚本")
    void assessAggregatesEnabledOnly() {
        when(store.load()).thenReturn(List.of(
                new RuleScript("a", "启用1", "builtin", true, "when lane == 1 then soft += 10"),
                new RuleScript("b", "停用", "builtin", false, "when lane == 1 then hard += 99"),
                new RuleScript("c", "启用2", "builtin", true,
                        "when event.category == \"径赛\" then soft += 5")));
        RuleOutcome o = new RuleInjectionService(store).assess(ctx());
        assertEquals(15, o.soft());
        assertEquals(0, o.hard());
        assertFalse(o.hasError());
    }

    @Test
    @DisplayName("test：返回命中明细；坏脚本返回 ok=false + error")
    void testReturnsDetailAndError() {
        RuleInjectionService svc = new RuleInjectionService(store);
        Map<String, Object> ok = svc.test(
                new RuleScript("a", "a", "builtin", true, "when lane == 1 then soft += 3"), ctx());
        assertEquals(Boolean.TRUE, ok.get("ok"));
        assertEquals(3L, ok.get("soft"));
        assertEquals(1, ((List<?>) ok.get("fired")).size());

        Map<String, Object> bad = svc.test(new RuleScript("b", "b", "builtin", true, "oops"), ctx());
        assertEquals(Boolean.FALSE, bad.get("ok"));
        assertNotNull(bad.get("error"));
    }

    @Test
    @DisplayName("engines：内置伪代码引擎必然可用")
    void enginesReportBuiltinAvailable() {
        List<Map<String, Object>> es = new RuleInjectionService(store).engines();
        assertTrue(es.stream().anyMatch(m -> "builtin".equals(m.get("name"))
                && Boolean.TRUE.equals(m.get("available"))));
    }

    @Test
    @DisplayName("save：id 为空/重复 → 报错；正常保存返回最新列表")
    void saveValidatesIds() {
        RuleInjectionService svc = new RuleInjectionService(store);
        assertThrows(IllegalArgumentException.class,
                () -> svc.save(List.of(new RuleScript("", "x", "builtin", true, ""))));
        assertThrows(IllegalArgumentException.class, () -> svc.save(List.of(
                new RuleScript("dup", "x", "builtin", true, ""),
                new RuleScript("dup", "y", "builtin", true, ""))));

        when(store.load()).thenReturn(List.of(new RuleScript("ok", "o", "builtin", true, "")));
        assertEquals(1, svc.save(List.of(new RuleScript("ok", "o", "builtin", true, ""))).size());
    }
}
