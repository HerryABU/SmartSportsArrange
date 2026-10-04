package com.sports.schedule.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sports.schedule.plan.PlanAdvice.GroupBreak;
import com.sports.schedule.plan.PlanAdvice.Result;
import com.sports.schedule.plan.PredictivePlanner.PlanUnit;
import com.sports.schedule.plan.PredictivePlanner.SlotDays;

/**
 * 「结构性断裂 → 给某天多配容量」建议的契约测试。
 *
 * <p>要钉住的是**口径**而不是数字：建议值必须是「把其余天的成员并到锚点天还缺多少分钟」，
 * 且同一天多个组时取**最大**缺口（新增容量可共用，求和会把结论说满）。</p>
 */
class PlanAdviceTest {

    /** 每 2 个槽算一天：槽 0/1 = 第 0 天，槽 2/3 = 第 1 天。 */
    private static final SlotDays DAYS = slot -> slot / 2;

    private static PlanUnit u(String key, String venue, int dur, String group) {
        return new PlanUnit(key, venue, dur, group, List.of());
    }

    private static Map<String, Integer> caps(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 3) {
            m.put(PredictivePlanner.capKey((Integer) kv[i], (String) kv[i + 1]), (Integer) kv[i + 2]);
        }
        return m;
    }

    @Test
    @DisplayName("无跨天（或空输入）时不给建议")
    void noBreaksNoAdvice() {
        assertFalse(PlanAdvice.analyze(List.of(), Map.of(), Map.of(), DAYS).hasBreaks());
        List<PlanUnit> units = List.of(u("g1", "V0", 30, "G"), u("g2", "V0", 30, "G"));
        Result r = PlanAdvice.analyze(units, Map.of("g1", 0, "g2", 1),
                caps(0, "V0", 60, 1, "V0", 60), DAYS);
        assertFalse(r.hasBreaks(), "同一天的两个成员不算断裂");
        assertTrue(r.extraMinutesByDay().isEmpty());
    }

    @Test
    @DisplayName("锚点天已满 → 给出「至少还缺多少分钟」的建议")
    void fullAnchorDayYieldsShortfall() {
        // G 的 3 个成员：两个在第 0 天（槽 0/1），一个在第 1 天（槽 2）
        List<PlanUnit> units = List.of(
                u("g1", "V0", 60, "G"),
                u("g2", "V0", 60, "G"),
                u("g3", "V0", 60, "G"));
        Map<String, Integer> caps = caps(0, "V0", 60, 1, "V0", 60, 2, "V0", 60, 3, "V0", 60);
        Result r = PlanAdvice.analyze(units, Map.of("g1", 0, "g2", 1, "g3", 2), caps, DAYS);
        assertTrue(r.hasBreaks());
        assertEquals(1, r.breaks().size());
        GroupBreak b = r.breaks().get(0);
        assertEquals("G", b.groupKey());
        assertEquals(0, b.anchorDay(), "锚点天应是成员最多的第 0 天");
        assertEquals(1, b.offDayUnits());
        assertEquals(60, b.shortfallMinutes(), "第 0 天该场地已满 → 还缺 60 分钟");
        assertEquals(60, r.extraMinutesOf(0));
    }

    @Test
    @DisplayName("锚点天仍有空余 → 断裂如实上报，但不给容量建议（不是容量的问题）")
    void freeAnchorDayYieldsNoShortfall() {
        List<PlanUnit> units = List.of(
                u("g1", "V0", 30, "G"),
                u("g2", "V0", 30, "G"),
                u("g3", "V0", 30, "G"));
        // 第 0 天两个槽各 100 分钟，只用了 60 → 空余 140 ≥ 需求 30
        Map<String, Integer> caps = caps(0, "V0", 100, 1, "V0", 100, 2, "V0", 100);
        Result r = PlanAdvice.analyze(units, Map.of("g1", 0, "g2", 1, "g3", 2), caps, DAYS);
        assertTrue(r.hasBreaks(), "跨天仍然要如实上报");
        assertEquals(0, r.breaks().get(0).shortfallMinutes());
        assertTrue(r.extraMinutesByDay().isEmpty(), "不是容量问题就不该建议加容量");
    }

    @Test
    @DisplayName("同一天多个组：取最大缺口而不是求和（新增容量可共用）")
    void multipleGroupsTakeMaxNotSum() {
        // 两个组都跨到第 1 天，各缺 60 → 建议应为 60（不是 120）
        List<PlanUnit> units = List.of(
                u("a1", "V0", 60, "GA"), u("a2", "V0", 60, "GA"),
                u("b1", "V0", 60, "GB"), u("b2", "V0", 60, "GB"));
        Map<String, Integer> caps = caps(0, "V0", 60, 1, "V0", 60, 2, "V0", 60, 3, "V0", 60);
        Map<String, Integer> slotOf = Map.of("a1", 0, "a2", 2, "b1", 1, "b2", 3);
        Result r = PlanAdvice.analyze(units, slotOf, caps, DAYS);
        assertEquals(2, r.breaks().size());
        assertEquals(60, r.extraMinutesOf(0), "两组的缺口共用同一批新增容量，取最大而非求和");
    }

    /** 同组跨天的处数（与 PredictivePlanner.cost() 同口径）。 */
    private static int breaksOf(List<PlanUnit> units, Map<String, Integer> slotOf, SlotDays days) {
        Map<String, java.util.Set<Integer>> byGroup = new LinkedHashMap<>();
        for (PlanUnit u : units) {
            Integer sid = slotOf.get(u.key());
            if (sid == null || u.groupKey() == null || u.groupKey().isBlank()) continue;
            byGroup.computeIfAbsent(u.groupKey(), k -> new java.util.LinkedHashSet<>())
                    .add(days.dayOf(sid));
        }
        int out = 0;
        for (java.util.Set<Integer> d : byGroup.values()) out += Math.max(0, d.size() - 1);
        return out;
    }

    /**
     * 端到端：**按建议给锚点天加容量之后，结构性断裂真的会消失**。
     *
     * <p>这条用例的价值在于它同时钉住了三件事：① 建议值算得对（缺 60 就说 60）；
     * ② 断裂确实是**容量**造成的（不是算子不好）；③ 建议是**可执行的** ——
     * 把它填进日程配置后重排，断裂从 1 降到 0。
     * 只要有一环对不上（比如建议值偏小、或加错了天），这里就会红。</p>
     */
    @Test
    @DisplayName("端到端：按建议给锚点天加容量 → 断裂消失（建议可执行且有效）")
    void adviceIsActionableAndEffective() {
        // 每 2 槽一天：槽 0/1 = 第 0 天，槽 2/3 = 第 1 天
        List<PlanUnit> units = List.of(
                u("g1", "V0", 60, "G"), u("g2", "V0", 60, "G"), u("g3", "V0", 60, "G"));
        // 第 0 天每个槽只有 60 分钟 → 一天最多容 120，装不下 180 的三兄弟 → 必然跨天
        Map<String, Integer> caps = caps(0, "V0", 60, 1, "V0", 60, 2, "V0", 120, 3, "V0", 120);
        PredictivePlanner.PlanOutcome before = PredictivePlanner.solve(units, caps, DAYS, 3, 20000, 0L,
                PredictivePlanner.DivOperator.ROTATE, true);
        assertTrue(before.feasible());
        int breaksBefore = breaksOf(units, before.slotOf(), DAYS);
        assertEquals(1, breaksBefore, "三兄弟必有一天装不下 → 恰好 1 处结构性断裂");

        Result advice = PlanAdvice.analyze(units, before.slotOf(), caps, DAYS);
        assertEquals(60, advice.extraMinutesOf(0), "锚点天（第 0 天）应报缺 60 分钟");

        // 按建议执行：把 60 分钟加到第 0 天的**每个**时段上（与前端「额外容量」语义一致）
        Map<String, Integer> widened = new LinkedHashMap<>(caps);
        for (int slot = 0; slot < 4; slot++) {
            if (DAYS.dayOf(slot) != 0) continue;
            String key = PredictivePlanner.capKey(slot, "V0");
            widened.put(key, widened.get(key) + advice.extraMinutesOf(0));
        }
        PredictivePlanner.PlanOutcome after = PredictivePlanner.solve(units, widened, DAYS, 3, 20000, 0L,
                PredictivePlanner.DivOperator.ROTATE, true);
        assertTrue(after.feasible());
        assertEquals(0, breaksOf(units, after.slotOf(), DAYS),
                "按建议加容量后，同组应能并到同一天");
    }

    @Test
    @DisplayName("未排单元不参与块统计（它由 blocked 表达，不是断裂）")
    void unplacedUnitsExcluded() {
        List<PlanUnit> units = List.of(u("g1", "V0", 30, "G"), u("g2", "V0", 30, "G"));
        Map<String, Integer> caps = caps(0, "V0", 60, 2, "V0", 60);
        Result r = PlanAdvice.analyze(units, Map.of("g1", 0), caps, DAYS);   // g2 未排
        assertFalse(r.hasBreaks(), "只剩一个已排成员 → 谈不上跨天");
    }
}
