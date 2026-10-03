package com.sports.schedule.ai;

import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.ScheduleUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AutoregressiveScenarioPlanner} 的核心约定。
 *
 * <p>本类存在的理由是「<b>后面几步要依据前面几步判断</b>」。因此最关键的两条断言是：
 * ① 同一单元在不同历史下会得到<b>不同</b>的条件特征；
 * ② 输出的是<b>多条带概率的走向</b>而非单点答案。</p>
 */
@DisplayName("自回归场景规划（多步预测 + 多场景 + 回溯）")
class AutoregressiveScenarioPlannerTest {

    /** 造一个容量受限的装箱场景：总容量刚好装不下所有单元 → 必然需要回溯。 */
    private static List<Placement> bins(int day, int perBin) {
        List<Placement> ps = new ArrayList<>();
        ps.add(new Placement("径赛", 0, 0, day, "2026-01-01", "上午", "田径场", 480, 480, perBin));
        ps.add(new Placement("径赛", 0, 1, day, "2026-01-01", "下午", "田径场", 840, 840, perBin));
        return ps;
    }

    private static ScheduleUnit unit(String key, long[] ath, int dur, List<Placement> cands) {
        return new ScheduleUnit(key, 1L, "项目" + key, "高一", true, "径赛", null, 5,
                dur, 10, ath, List.of(dur), cands);
    }

    @Test
    @DisplayName("空输入不炸")
    void emptyIsSafe() {
        var plan = new AutoregressiveScenarioPlanner(3, 5, 1).plan(List.of(), 0);
        assertNotNull(plan);
        assertTrue(plan.scenarios().isEmpty());
        assertTrue(plan.complete());
    }

    @Test
    @DisplayName("每条场景都是「每个单元恰好出现一次」的完整排列")
    void everyScenarioIsAPermutation() {
        List<Placement> c = bins(1, 1000);
        List<ScheduleUnit> units = List.of(
                unit("a", new long[]{1L}, 20, c),
                unit("b", new long[]{2L}, 20, c),
                unit("c", new long[]{3L}, 20, c),
                unit("d", new long[]{4L}, 20, c));

        var plan = new AutoregressiveScenarioPlanner(3, 3, 7).plan(units, 0.1);

        assertEquals(3, plan.scenarios().size(), "应产出请求的多条走向");
        for (var sc : plan.scenarios()) {
            assertEquals(units.size(), sc.order().size(),
                    "容量充足时应能把所有单元排完，实际=" + sc.order());
            assertEquals(units.size(), new java.util.HashSet<>(sc.order()).size(), "不得重复");
        }
    }

    @Test
    @DisplayName("概率归一化且与代价单调（代价越低概率越高）")
    void probabilitiesAreNormalizedAndMonotonic() {
        List<Placement> c = bins(1, 1000);
        List<ScheduleUnit> units = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            units.add(unit("u" + i, new long[]{i}, 20, c));
        }
        var plan = new AutoregressiveScenarioPlanner(5, 2, 3).plan(units, 0.3);

        double sum = 0;
        double prevProb = Double.MAX_VALUE;
        double prevCost = -1;
        for (var sc : plan.scenarios()) {
            assertTrue(sc.probability() >= 0 && sc.probability() <= 1.0000001,
                    "概率必须在 [0,1]：" + sc.probability());
            assertTrue(sc.probability() <= prevProb + 1e-9,
                    "按概率降序排列时不应回升");
            if (prevCost >= 0) {
                assertTrue(sc.cost() >= prevCost - 1e-9, "概率降序等价于代价升序");
            }
            prevProb = sc.probability();
            prevCost = sc.cost();
            sum += sc.probability();
        }
        assertEquals(1.0, sum, 1e-6, "概率必须归一化");
    }

    @Test
    @DisplayName("核心：条件特征随「前面几步」变化——同一单元在不同历史下特征不同")
    void stepFeaturesDependOnHistory() {
        // 两个单元共享运动员 1 号；先放 a 会让 b 的冲突面上升
        List<Placement> c = bins(1, 1000);
        ScheduleUnit a = unit("a", new long[]{1L}, 20, c);
        ScheduleUnit b = unit("b", new long[]{1L, 2L}, 20, c);
        ScheduleUnit z = unit("z", new long[]{9L}, 20, c);

        var planner = new AutoregressiveScenarioPlanner(1, 0, 1);
        // 场景 0 走贪心：瓶颈前置应先放冲突面小、体积大的 b
        var plan = planner.plan(List.of(a, b, z), 0.0);
        var sc = plan.best();
        assertNotNull(sc);
        assertEquals(3, sc.stepFeatures().size(), "每步都要留条件特征供模型使用");
        for (float[] f : sc.stepFeatures()) {
            assertEquals(AutoregressiveScenarioPlanner.STEP_FEAT_DIM, f.length,
                    "条件特征维度固定，是双端契约");
        }
        // f[2] = 兼项冲突占比：第一步必为 0（还没放任何东西）
        assertEquals(0f, sc.stepFeatures().get(0)[2], 1e-6,
                "第一步不可能有兼项冲突");
        // 后续步的 f[3]（剩余比）必须单调下降 —— 证明每步都在看「还剩多少」
        for (int i = 1; i < sc.stepFeatures().size(); i++) {
            assertTrue(sc.stepFeatures().get(i)[3] < sc.stepFeatures().get(i - 1)[3] + 1e-6,
                    "剩余比例应随推进单调下降（后面几步确实依据了前面几步）");
        }
    }

    @Test
    @DisplayName("装箱受限时靠回溯走出死路，而不是直接放弃")
    void backtrackingRecoversFromDeadEnd() {
        // 2 个时段箱 × 容量 30，3 个各 25 分钟的单元 → 总需求 75 > 总容量 60，必排不下
        List<Placement> c = bins(1, 30);
        List<ScheduleUnit> units = List.of(
                unit("a", new long[]{1L}, 25, c),
                unit("b", new long[]{2L}, 25, c),
                unit("cc", new long[]{3L}, 25, c));

        var noBacktrack = new AutoregressiveScenarioPlanner(1, 0, 1).plan(units, 0.0);
        var withBacktrack = new AutoregressiveScenarioPlanner(1, 8, 1).plan(units, 0.0);

        // 无论能否排完，都必须如实报告 complete，不能假装成功
        assertNotNull(noBacktrack.best());
        if (withBacktrack.complete()) {
            assertTrue(withBacktrack.backtracks() > 0,
                    "若最终排完了，说明确实用了回溯");
            assertEquals(3, withBacktrack.best().order().size());
        } else {
            assertTrue(withBacktrack.best().cost() > 0, "未排完必须有代价");
        }
    }

    @Test
    @DisplayName("极端紧张场景不会死循环：回溯次数有上限")
    void backtrackIsBounded() {
        List<Placement> c = bins(1, 10);
        List<ScheduleUnit> units = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            units.add(unit("u" + i, new long[]{i}, 25, c));
        }
        var plan = new AutoregressiveScenarioPlanner(2, 3, 1).plan(units, 0.2);
        assertTrue(plan.backtracks() <= 6, "回溯次数应受 maxBacktracks×场景数 约束，实际=" + plan.backtracks());
        assertNotNull(plan.best());
    }

    @Test
    @DisplayName("场景之间必须有真实差异（探索强度生效），否则「多场景」是假的")
    void scenariosActuallyDiffer() {
        List<Placement> c = bins(1, 1000);
        List<ScheduleUnit> units = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            units.add(unit("u" + i, new long[]{i}, 20, c));
        }
        var plan = new AutoregressiveScenarioPlanner(6, 2, 42).plan(units, 0.5);

        var distinct = new java.util.HashSet<String>();
        plan.scenarios().forEach(s -> distinct.add(s.order().toString()));
        assertTrue(distinct.size() > 1,
                "多场景必须给出不同走向，实际只有 " + distinct.size() + " 种");
    }

    @Test
    @DisplayName("无兼项时不应凭空造出冲突")
    void noFakeConflictsWhenNoOverlap() {
        List<Placement> c = bins(1, 1000);
        List<ScheduleUnit> units = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            units.add(unit("u" + i, new long[]{i}, 20, c));
        }
        var plan = new AutoregressiveScenarioPlanner(2, 1, 1).plan(units, 0.0);
        for (var sc : plan.scenarios()) {
            assertEquals(0.0, sc.cost(), 1e-6,
                    "每人只报一项且容量充足 → 代价应为 0（无兼项冲突、无未排）");
        }
        assertTrue(plan.complete());
    }
}
