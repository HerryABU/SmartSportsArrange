package com.sports.schedule.opt.solver;

import com.sports.schedule.rule.inject.RuleInjectionHolder;
import com.sports.schedule.rule.inject.RuleInjectionService;
import com.sports.schedule.rule.inject.RuleScript;
import com.sports.schedule.rule.inject.RuleScriptStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 规则注入 → Timefold 动态约束 的桥接测试。
 *
 * <p>不启动求解器，直接验证桥接语义：无脚本时零成本短路；有脚本时规则增量正确映射到
 * 硬/中/软三层；上下文按约束流可见字段组装。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("规则注入 → 动态约束桥接")
class RuleDrivenConstraintTest {

    @Mock
    private RuleScriptStore store;

    private ScheduleUnit unit(String key, String pool, int day, int start, int duration) {
        long[] athletes = {1L, 2L};
        Placement p = new Placement(pool, 0, 0, day, "2026-09-20", "上午", "田径场", start, 480, 300);
        ScheduleUnit u = new ScheduleUnit(key, 100L, "100米", "高一年级", true, pool, null,
                10, duration, 10, athletes, List.of(duration), List.of(p));
        u.setPlacement(p);
        u.setDuration(duration);
        return u;
    }

    @Test
    @DisplayName("无启用脚本：桥接非激活、惩罚恒为 0（约束零成本短路）")
    void noScriptsMeansZeroCost() {
        when(store.load()).thenReturn(List.of());
        new RuleInjectionService(store);

        assertFalse(RuleInjectionHolder.active(), "无启用脚本时不应激活注入桥");
        ScheduleUnit u = unit("u1", "TRACK", 1, 480, 60);
        assertEquals(0, ScheduleConstraintProvider.ruleHard(u));
        assertEquals(0, ScheduleConstraintProvider.ruleMedium(u));
        assertEquals(0, ScheduleConstraintProvider.ruleSoft(u));
    }

    @Test
    @DisplayName("有脚本：规则增量正确映射到 hard/medium/soft，veto → 至少 1 硬分")
    void rulePenaltiesMapToScoreLevels() {
        when(store.load()).thenReturn(List.of(
                RuleScript.builtin("r1", "前2道软罚", "when placement.startMinute == 480 then soft += 30"),
                RuleScript.builtin("r2", "晚开赛中罚", "when placement.startMinute >= 600 then medium += 5"),
                RuleScript.builtin("r3", "跨天硬罚", "when placement.day > 1 then hard += 7"),
                RuleScript.builtin("r4", "禁某池", "when poolLabel == \"POOL_BAD\" then veto")));
        new RuleInjectionService(store);
        assertTrue(RuleInjectionHolder.active());

        // 第 1 天 08:00 → 命中 soft=30
        ScheduleUnit u1 = unit("u1", "TRACK", 1, 480, 60);
        assertEquals(30, ScheduleConstraintProvider.ruleSoft(u1));
        assertEquals(0, ScheduleConstraintProvider.ruleHard(u1));

        // 第 2 天 10:00 → 命中跨天 hard=7 与晚开赛 medium=5
        ScheduleUnit u2 = unit("u2", "TRACK", 2, 600, 60);
        assertEquals(7, ScheduleConstraintProvider.ruleHard(u2));
        assertEquals(5, ScheduleConstraintProvider.ruleMedium(u2));

        // 被否决的池 → veto 记至少 1 硬分
        ScheduleUnit u3 = unit("u3", "POOL_BAD", 1, 480, 60);
        assertTrue(ScheduleConstraintProvider.ruleHard(u3) >= 1);
    }

    /** 带事件属性（键与编排路径对齐）的单元——验证规则字段跨路径一致。 */
    private ScheduleUnit unitWithEvent(String key, String category, int day, int start) {
        long[] athletes = {1L, 2L};
        Placement p = new Placement("TRACK", 0, 0, day, "2026-09-20", "上午", "田径场", start, 480, 300);
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("id", 100L);
        attrs.put("name", "100米");
        attrs.put("category", category);
        attrs.put("track", true);
        attrs.put("team", false);
        attrs.put("teamMembers", 1);
        attrs.put("venueCode", "TRACK");
        ScheduleUnit u = new ScheduleUnit(key, 100L, "100米", "高一年级", true, "TRACK", null,
                10, 60, 10, athletes, List.of(60), List.of(p), attrs);
        u.setPlacement(p);
        u.setDuration(60);
        return u;
    }

    @Test
    @DisplayName("跨路径字段一致：求解侧 event.category 同样可判定（回归防护）")
    void solverSideSeesEventCategory() {
        when(store.load()).thenReturn(List.of(
                RuleScript.builtin("r1", "径赛软罚", "when event.category == \"径赛\" then soft += 30"),
                RuleScript.builtin("r2", "田赛否决", "when event.category == \"田赛\" then veto")));
        new RuleInjectionService(store);

        ScheduleUnit track = unitWithEvent("u1", "径赛", 1, 480);
        assertEquals(30, ScheduleConstraintProvider.ruleSoft(track));
        assertEquals(0, ScheduleConstraintProvider.ruleHard(track));

        ScheduleUnit field = unitWithEvent("u2", "田赛", 1, 480);
        assertTrue(ScheduleConstraintProvider.ruleHard(field) >= 1, "田赛应被 veto 记硬分");
    }

    @Test
    @DisplayName("无事件属性时上下文仍可组装（兜底 id/name/track，不抛异常）")
    void contextWorksWithoutEventAttrs() {
        ScheduleUnit u = unit("u1", "TRACK", 3, 555, 45);
        assertEquals(100L, ((Number) ScheduleConstraintProvider.ruleContextOf(u).get("event.id")).longValue());
        assertEquals("100米", ScheduleConstraintProvider.ruleContextOf(u).get("event.name"));
        assertEquals(Boolean.TRUE, ScheduleConstraintProvider.ruleContextOf(u).get("event.track"));
    }

    @Test
    @DisplayName("上下文按约束流可见字段组装（事件/年级/落位）")
    void contextExposesStreamFields() {
        // 上下文组装是纯静态逻辑，无需脚本服务参与
        ScheduleUnit u = unit("u1", "TRACK", 3, 555, 45);
        assertEquals("高一年级", ScheduleConstraintProvider.ruleContextOf(u).get("grade"));
        assertEquals(Boolean.TRUE, ScheduleConstraintProvider.ruleContextOf(u).get("track"));
        assertEquals(3, ((Number) ScheduleConstraintProvider.ruleContextOf(u).get("placement.day")).intValue());
        assertEquals(555, ((Number) ScheduleConstraintProvider.ruleContextOf(u).get("placement.startMinute")).intValue());
        assertEquals("TRACK", ScheduleConstraintProvider.ruleContextOf(u).get("placement.poolLabel"));
    }
}
