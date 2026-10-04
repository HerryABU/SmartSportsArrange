package com.sports.schedule.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sports.schedule.plan.PredictivePlanner.PlanDiagnostics;
import com.sports.schedule.plan.PredictivePlanner.PlanOutcome;
import com.sports.schedule.plan.PredictivePlanner.PlanUnit;
import com.sports.schedule.plan.PredictivePlanner.SlotDays;

/**
 * 规划器的契约测试。
 *
 * <p>这些用例逐条对应 Python 侧 {@code tests/test_hybrid_search.py} 的语义，
 * 目的是**钉住双端一致**：两边若有一边改了前向检查或代价口径，这里会立刻失败。</p>
 */
class PredictivePlannerTest {

    /** 槽 → 天：每 3 个槽算一天（仅用于代价里的工期跨度，测试用）。 */
    private static final SlotDays DAYS = slot -> slot / 3;

    private static PlanUnit u(String key, String venue, int dur, String group, Long... athletes) {
        return new PlanUnit(key, venue, dur, group, List.of(athletes));
    }

    private static Map<String, Integer> caps(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 3) {
            m.put(PredictivePlanner.capKey((Integer) kv[i], (String) kv[i + 1]), (Integer) kv[i + 2]);
        }
        return m;
    }

    @Test
    @DisplayName("空输入：可行、零代价、不抛异常")
    void emptyInputIsTriviallyFeasible() {
        PlanOutcome r = PredictivePlanner.solve(List.of(), Map.of(), DAYS);
        assertTrue(r.feasible());
        assertEquals(0.0, r.value(), 1e-9);
        assertEquals(0, r.diagnostics().searchCost());
    }

    @Test
    @DisplayName("兼项：共享运动员的两个单元不得落进同一槽")
    void athleteClashAvoided() {
        List<PlanUnit> units = new ArrayList<>();
        units.add(u("a", "V0", 60, null, 7L));
        units.add(u("b", "V0", 60, null, 7L));
        PlanOutcome r = PredictivePlanner.solve(units, caps(0, "V0", 999, 1, "V0", 999), DAYS);
        assertTrue(r.feasible());
        assertNotEquals(r.slotOf().get("a"), r.slotOf().get("b"),
                "共享运动员的单元不得同槽");
        assertTrue(r.violations().isEmpty(), "不得有任何非法落位");
    }

    @Test
    @DisplayName("容量不足时诚实上报未排，且**不产生**非法落位")
    void honestBlockedWithoutIllegalPlacement() {
        // 两个 60 分钟的单元，但只有一个 60 分钟的槽 → 必然有一个排不下
        List<PlanUnit> units = new ArrayList<>();
        units.add(u("a", "V0", 60, null));
        units.add(u("b", "V0", 60, null));
        PlanOutcome r = PredictivePlanner.solve(units, caps(0, "V0", 60), DAYS);
        assertFalse(r.feasible());
        assertEquals(1, r.blocked().size(), "应恰好有一个排不下");
        assertTrue(r.violations().isEmpty(),
                "排不下只应体现为 blocked，绝不允许出现超容/兼项这类非法落位");
        // 关键：不允许「塞进去然后超容」—— 那才是现场最危险的结果
        assertEquals(1, r.slotOf().size());
    }

    @Test
    @DisplayName("局部回退：先到的单元要让位给只有一条路的单元")
    void ejectionChainFreesTheOnlyPath() {
        // A 有两个候选槽（0/1），装完一样满 → best-fit 并列时先占槽 1；
        // 而 C 只有槽 1 可去。前向检查只看「当前这一步」，看不到这个跨步连锁，
        // 必须靠**局部回退**把 A 改到槽 0，解才存在。
        List<PlanUnit> units = new ArrayList<>();
        units.add(u("A", "V0", 60, null));
        units.add(u("B", "V0", 40, null));
        units.add(u("C", "V0", 60, null));
        Map<String, Integer> capacity = caps(0, "V0", 100, 1, "V0", 100);
        PlanOutcome r = PredictivePlanner.solve(units, capacity, DAYS, 4, 5000, 0L);
        assertTrue(r.feasible(), "回退后应找到可行解：" + r.violations() + " blocked=" + r.blocked());
        assertEquals(1, r.slotOf().get("C"), "C 只能落槽 1");
        assertEquals(0, r.slotOf().get("A"), "A 必须让位到槽 0");
        assertTrue(r.diagnostics().searchCost() > 0, "搜索量必须被计数");
    }

    @Test
    @DisplayName("诊断为三动作 + 3R 全套，且扩展数不为负")
    void diagnosticsAreComplete() {
        List<PlanUnit> units = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            units.add(u("x" + i, "V0", 30, "G1", (long) i));
        }
        PlanOutcome r = PredictivePlanner.solve(units, caps(0, "V0", 200, 1, "V0", 200), DAYS);
        PlanDiagnostics d = r.diagnostics();
        assertTrue(d.expansions() > 0 && d.continues() > 0);
        assertTrue(d.backtracks() >= 0 && d.repairs() >= 0 && d.restarts() >= 0);
        assertTrue(d.searchCost() == d.expansions(), "searchCost 必须等于节点扩展数");
    }

    @Test
    @DisplayName("best-fit：容量刚好放下时不会因为顺序问题报未排")
    void bestFitPacksTightly() {
        List<PlanUnit> units = new ArrayList<>();
        units.add(u("a", "V0", 100, null));
        units.add(u("b", "V0", 100, null));
        // 两个 100 分钟的槽，每个恰好放一个 → 必须全部排下
        PlanOutcome r = PredictivePlanner.solve(units, caps(0, "V0", 100, 1, "V0", 100), DAYS);
        assertTrue(r.feasible());
        assertEquals(2, r.slotOf().size());
        assertNotEquals(r.slotOf().get("a"), r.slotOf().get("b"));
    }

    @Test
    @DisplayName("结果为确定性：同输入同种子必得同解（可复现是硬要求）")
    void deterministicGivenSameSeed() {
        List<PlanUnit> units = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            units.add(u("k" + i, "V0", 25, "G" + (i % 3), (long) (i % 4)));
        }
        Map<String, Integer> capacity = caps(0, "V0", 90, 1, "V0", 90, 2, "V0", 90);
        PlanOutcome r1 = PredictivePlanner.solve(units, capacity, DAYS, 3, 5000, 7L);
        PlanOutcome r2 = PredictivePlanner.solve(units, capacity, DAYS, 3, 5000, 7L);
        assertEquals(r1.slotOf(), r2.slotOf(), "同种子必须同解");
        assertEquals(r1.diagnostics().searchCost(), r2.diagnostics().searchCost());
    }

    @Test
    @DisplayName("块完整性代价：同组跨天会被计价（同一组的单元倾向于被排在同一天）")
    void groupSpanEntersCost() {
        // 同一组两个单元；容量只允许它们落在同一天的槽里（同天两个槽各放一个）
        List<PlanUnit> units = new ArrayList<>();
        units.add(u("g1", "V0", 50, "GRP"));
        units.add(u("g2", "V0", 50, "GRP"));
        // 槽 0/1 同属第 0 天（DAYS = slot/3），容量各 50 → 可同天，代价中的 breaks 为 0
        PlanOutcome r = PredictivePlanner.solve(units, caps(0, "V0", 50, 1, "V0", 50), DAYS);
        assertTrue(r.feasible());
        // 块断裂为 0（同一天），工期跨度 0 → 代价应为 0
        assertEquals(0.0, r.value(), 1e-9);
    }
}
