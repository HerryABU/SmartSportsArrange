package com.sports.schedule.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sports.schedule.plan.PredictivePlanner.PlanUnit;
import com.sports.schedule.plan.PredictivePlanner.SlotDays;

/**
 * 加权代价口径的契约测试。
 *
 * <p>这个用例最重要的作用不是「算得对」，而是<b>把权重数字钉住</b>：
 * 权重表必须与 Python 侧 {@code sports_ai/metrics.py::WEIGHTS} 逐位一致，
 * 任何一边被顺手改动都会让双端拿着两把不同的尺子比较，而结果看起来仍然「合理」（最难查）。</p>
 */
class PlanCostTest {

    private static final SlotDays DAYS = slot -> slot / 2;      // 每 2 个槽算一天

    private static PlanUnit u(String key, String venue, int dur, String group, Long... athletes) {
        return new PlanUnit(key, venue, dur, group, List.of(athletes));
    }

    /** 与 Python 侧逐位一致的期望值（改这里等于改口径，必须同步 metrics.py）。 */
    @Test
    @DisplayName("权重表与 Python metrics.py 逐位一致")
    void weightsMatchPython() {
        Map<String, Double> w = PlanCost.weights();
        assertEquals(1000.0, w.get("unplaced"), 1e-9);
        assertEquals(500.0, w.get("athlete_clash"), 1e-9);
        assertEquals(500.0, w.get("capacity_overflow"), 1e-9);
        assertEquals(50.0, w.get("lane_clash"), 1e-9);
        assertEquals(10.0, w.get("frag_blocks"), 1e-9);
        assertEquals(20.0, w.get("days_over"), 1e-9);
        assertEquals(6, w.size(), "分量个数变了必须同步双端");
    }

    @Test
    @DisplayName("空方案：全零、代价 0")
    void emptyPlanHasZeroCost() {
        PlanCost c = PlanCost.of(List.of(), Map.of(), Map.of(), DAYS, 0);
        assertEquals(0, c.unplaced());
        assertEquals(0.0, c.weighted(), 1e-9);
    }

    @Test
    @DisplayName("未排单元按 1000 计价，且不计入块/工期统计")
    void unplacedIsWeightedHeavily() {
        List<PlanUnit> units = List.of(
                u("a", "V0", 60, null),
                u("b", "V0", 60, null));
        Map<String, Integer> slotOf = new LinkedHashMap<>();
        slotOf.put("a", 0);                       // b 未排
        PlanCost c = PlanCost.of(units, slotOf, Map.of(PredictivePlanner.capKey(0, "V0"), 60), DAYS, 0);
        assertEquals(1, c.unplaced());
        assertEquals(1000.0, c.weighted(), 1e-9);
    }

    @Test
    @DisplayName("超占按「超出分钟数」计价：超 10 分钟 = 10×500")
    void overflowCountsMinutesNotEvents() {
        List<PlanUnit> units = List.of(
                u("a", "V0", 60, null),
                u("b", "V0", 60, null));
        Map<String, Integer> slotOf = Map.of("a", 0, "b", 0);
        Map<String, Integer> caps = Map.of(PredictivePlanner.capKey(0, "V0"), 110);  // 超 10
        PlanCost c = PlanCost.of(units, slotOf, caps, DAYS, 0);
        assertEquals(10, c.capacityOverflow());
        assertEquals(5000.0, c.weighted(), 1e-9);
    }

    @Test
    @DisplayName("碎块：同组跨天按 10 计价；同组同天不计")
    void fragBlocksOnlyCountsCrossDayGroups() {
        // 槽 0/1 同属第 0 天；槽 2 属第 1 天
        List<PlanUnit> units = List.of(
                u("g1", "V0", 30, "G"),
                u("g2", "V0", 30, "G"),
                u("g3", "V0", 30, "G"));
        Map<String, Integer> caps = Map.of(
                PredictivePlanner.capKey(0, "V0"), 30,
                PredictivePlanner.capKey(1, "V0"), 30,
                PredictivePlanner.capKey(2, "V0"), 30);
        // 三成员落在第 0、0、1 天（槽 0、1 同属第 0 天，槽 2 属第 1 天）→ 1 处断裂
        PlanCost spread = PlanCost.of(units, Map.of("g1", 0, "g2", 1, "g3", 2), caps, DAYS, 0);
        assertEquals(1, spread.fragBlocks());
        assertEquals(10.0, spread.weighted(), 1e-9);
        // 两个成员同在第 0 天（槽 0、1，各占满 30 分钟）→ 0 处断裂、0 代价
        PlanCost together = PlanCost.of(
                List.of(u("g1", "V0", 30, "G"), u("g2", "V0", 30, "G")),
                Map.of("g1", 0, "g2", 1), caps, DAYS, 0);
        assertEquals(0, together.fragBlocks());
        assertEquals(0.0, together.weighted(), 1e-9);
    }

    @Test
    @DisplayName("兼项撞：同一运动员同一时段出现两次按 500 计价（非法方案的惩罚）")
    void athleteClashCounted() {
        List<PlanUnit> units = List.of(
                u("a", "V0", 30, null, 7L),
                u("b", "V0", 30, null, 7L));
        Map<String, Integer> caps = Map.of(PredictivePlanner.capKey(0, "V0"), 60);
        PlanCost c = PlanCost.of(units, Map.of("a", 0, "b", 0), caps, DAYS, 0);
        assertEquals(1, c.athleteClash(), "同槽同人应被计为 1 次兼项撞");
        assertEquals(500.0, c.weighted(), 1e-9);
    }

    @Test
    @DisplayName("工期三态：fixed 超限才罚；unlimited / minimize 恒不罚")
    void dayLimitThreeStates() {
        assertEquals(0, PlanCost.dayLimitOf("minimize", 3), "尽可能减少不是上限");
        assertEquals(0, PlanCost.dayLimitOf("unlimited", 3), "不限天数没有上限");
        assertEquals(3, PlanCost.dayLimitOf("fixed", 3));
        assertEquals(3, PlanCost.dayLimitOf(null, 3), "旧配置无 dayMode 时 days>0 即限定");
        assertEquals(0, PlanCost.dayLimitOf(null, -1), "旧配置 days=-1 视为不限");
    }

    @Test
    @DisplayName("工期超限按 20/天计价；未超限为 0")
    void daysOverWeighted() {
        List<PlanUnit> units = new ArrayList<>();
        Map<String, Integer> slotOf = new LinkedHashMap<>();
        Map<String, Integer> caps = new LinkedHashMap<>();
        // 4 个槽 → 第 0/0/1/1 天 = 2 天
        for (int i = 0; i < 4; i++) {
            units.add(u("x" + i, "V0", 10, null));
            slotOf.put("x" + i, i);
            caps.put(PredictivePlanner.capKey(i, "V0"), 10);
        }
        PlanCost ok = PlanCost.of(units, slotOf, caps, DAYS, 2);
        assertEquals(0, ok.daysOver());
        PlanCost over = PlanCost.of(units, slotOf, caps, DAYS, 1);
        assertEquals(1, over.daysOver());
        assertEquals(20.0, over.weighted(), 1e-9);
    }

    @Test
    @DisplayName("分项加权贡献之和恒等于总代价（账面必须自洽）")
    void breakdownSumsToWeighted() {
        List<PlanUnit> units = List.of(
                u("a", "V0", 60, "G", 1L),
                u("b", "V0", 60, "G", 2L),
                u("c", "V0", 60, null));
        Map<String, Integer> slotOf = Map.of("a", 0, "b", 2);      // c 未排；G 跨天
        Map<String, Integer> caps = Map.of(
                PredictivePlanner.capKey(0, "V0"), 60,
                PredictivePlanner.capKey(1, "V0"), 60,
                PredictivePlanner.capKey(2, "V0"), 60);
        PlanCost c = PlanCost.of(units, slotOf, caps, DAYS, 1);
        double sum = c.breakdown().values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(c.weighted(), sum, 1e-9);
        assertTrue(c.weighted() > 0);
    }
}
