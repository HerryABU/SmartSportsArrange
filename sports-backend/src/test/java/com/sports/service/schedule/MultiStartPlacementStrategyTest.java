package com.sports.service.schedule;

import com.sports.entity.event.Event;
import com.sports.schedule.core.primitive.Unit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多起点放置策略族：守住「确定性、固定种子」这条红线。
 *
 * <p>为什么值得单测：这族函数是「多起点自适应重试」的全部定义处，而<b>可复现</b>是它能被
 * 核对与回归的前提 —— 「同一份数据跑两次结果不同」会让所有对比失去意义，
 * 却不会报任何错误。这类契约只能靠断言守。</p>
 */
class MultiStartPlacementStrategyTest {

    private static Unit unit(int participants, long... athletes) {
        Unit u = new Unit(new Event(), "高一");
        u.participants = participants;
        for (long a : athletes) {
            u.athleteIds.add(a);
        }
        return u;
    }

    private static List<Unit> sample() {
        List<Unit> units = new ArrayList<>();
        units.add(unit(10, 1L, 2L));
        units.add(unit(50, 1L, 2L, 3L, 4L));
        units.add(unit(30, 9L));
        units.add(unit(20, 1L, 5L, 6L));
        return units;
    }

    @Test
    @DisplayName("第 0 条策略 = 原始顺序")
    void strategyZeroIsIdentity() {
        List<Unit> units = sample();
        assertEquals(List.of(0, 1, 2, 3), MultiStartPlacementStrategy.strategyAt(units, 0));
    }

    @Test
    @DisplayName("第 1 条策略 = 按参与人数降序（并列时按原下标稳定）")
    void strategyOneIsByParticipantsDesc() {
        List<Unit> units = sample();
        // 人数 50 / 30 / 20 / 10 → 下标 1,2,3,0
        assertEquals(List.of(1, 2, 3, 0), MultiStartPlacementStrategy.strategyAt(units, 1));
    }

    @Test
    @DisplayName("第 2 条策略 = 按兼项度降序（该运动员在本项目集合里出现 ≥2 次才算）")
    void strategyTwoIsByDegreeDesc() {
        List<Unit> units = sample();
        // 兼项度：u0 有 1、2 各出现 2 次 → 2；u1 有 1、2 → 2；u3 有 1 → 1；u2 的 9 只出现 1 次 → 0
        Integer[] got = MultiStartPlacementStrategy.strategyAt(units, 2).toArray(new Integer[0]);
        assertEquals(4, got.length);
        // 前两名必须是兼项度 2 的（u0/u1），最后一名必须是兼项度 0 的 u2
        assertTrue(got[0] == 0 || got[0] == 1, "首位应是兼项度最高者，实测下标 " + got[0]);
        assertTrue(got[1] == 0 || got[1] == 1, "次位应是兼项度最高者，实测下标 " + got[1]);
        assertEquals(2, got[3], "末位应是兼项度 0 的单元");
    }

    @Test
    @DisplayName("随机扰动策略：同序号必须给出同一顺序（固定种子的红线）")
    void shuffleStrategiesAreReproducible() {
        List<Unit> units = sample();
        for (int p = 3; p <= 8; p++) {
            assertEquals(MultiStartPlacementStrategy.strategyAt(units, p),
                    MultiStartPlacementStrategy.strategyAt(units, p),
                    "第 " + p + " 条策略两次调用结果不一致 —— 固定种子被破坏了");
        }
    }

    @Test
    @DisplayName("不同序号的扰动应给出不同顺序（否则「多起点」退化成重复同一趟，白烧算力）")
    void differentPassesExploreDifferentOrders() {
        List<Unit> units = sample();
        java.util.Set<List<Integer>> seen = new java.util.HashSet<>();
        for (int p = 3; p <= 12; p++) {
            seen.add(MultiStartPlacementStrategy.strategyAt(units, p));
        }
        assertTrue(seen.size() > 1, "10 个扰动起点只产出 1 种顺序 —— 多起点没有真正分散");
    }

    @Test
    @DisplayName("策略命名可读且与序号一一对应")
    void strategyNames() {
        assertEquals("original", MultiStartPlacementStrategy.strategyNameAt(0));
        assertEquals("byParticipantsDesc", MultiStartPlacementStrategy.strategyNameAt(1));
        assertEquals("byDegreeDesc", MultiStartPlacementStrategy.strategyNameAt(2));
        // 扰动档从 1 开始编号（第 1 个扰动 = 序号 3），便于日志里读「第几个扰动」
        assertEquals("shuffle#1", MultiStartPlacementStrategy.strategyNameAt(3));
        assertEquals("shuffle#3", MultiStartPlacementStrategy.strategyNameAt(5));
        assertNotEquals(MultiStartPlacementStrategy.strategyNameAt(3),
                MultiStartPlacementStrategy.strategyNameAt(4));
    }

    @Test
    @DisplayName("择优判据：先比兼项冲突数（少者优），再比已排数（多者优），最后比放置失败数（少者优）")
    void passComparisonOrdering() {
        PlacementPassResult a = new PlacementPassResult();
        a.conflictStat[1] = 1;
        a.conflictStat[0] = 100;
        PlacementPassResult b = new PlacementPassResult();
        b.conflictStat[1] = 2;          // 兼项冲突更多
        b.conflictStat[0] = 999;        // 即便已排数更多，也应输
        assertTrue(MultiStartPlacementStrategy.passIsBetter(a, b),
                "兼项冲突少的应获胜 —— 它是第一判据");

        PlacementPassResult c = new PlacementPassResult();
        c.conflictStat[1] = 1;
        c.conflictStat[0] = 50;         // 兼项冲突相同、已排数更少
        assertTrue(MultiStartPlacementStrategy.passIsBetter(a, c),
                "兼项冲突相同时，已排数多的应获胜");

        PlacementPassResult d = new PlacementPassResult();
        d.conflictStat[1] = 1;
        d.conflictStat[0] = 100;
        d.autoArrangeFails.add("x");
        assertTrue(MultiStartPlacementStrategy.passIsBetter(a, d),
                "前两项相同时，放置失败数少的应获胜");
    }
}
