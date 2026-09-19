package com.sports.schedule.rule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 蛇形分组穷举验证（竞品规则层单测纪律：确定性算法必须可穷举验证）。
 *
 * <p>覆盖：经典 24人×3组模式、8/16/32 人穷举、非整除情形、单位与边界、确定性、均衡性。</p>
 */
@DisplayName("SnakeGrouping 蛇形分组")
class SnakeGroupingTest {

    @Test
    @DisplayName("经典模式：24 人 3 组，蛇形往返填充")
    void classicPattern() {
        List<List<Integer>> g = SnakeGrouping.assign(24, 3);
        assertEquals(3, g.size());
        // 第一行：0,5,6,11,12,17,18,23（位置从 0 计，种子从 1 计）
        assertEquals(List.of(0, 5, 6, 11, 12, 17, 18, 23), g.get(0));
        assertEquals(List.of(1, 4, 7, 10, 13, 16, 19, 22), g.get(1));
        assertEquals(List.of(2, 3, 8, 9, 14, 15, 20, 21), g.get(2));
    }

    @Test
    @DisplayName("穷举 8/16/32 人：所有位置都被分配、无重复、组大小差 ≤ 1")
    void exhaustivePartition() {
        for (int n : new int[]{8, 16, 32}) {
            for (int heats = 1; heats <= 8; heats++) {
                List<List<Integer>> g = SnakeGrouping.assign(n, heats);
                assertEquals(heats, g.size(), "组数应等于 heats");
                Set<Integer> seen = new HashSet<>();
                int max = 0, min = Integer.MAX_VALUE;
                for (List<Integer> group : g) {
                    final int fn = n;
                    for (int pos : group) {
                        assertTrue(pos >= 0 && pos < fn, "越界位置 n=" + n + " heats=" + heats + " pos=" + pos);
                        assertTrue(seen.add(pos), "重复位置 pos=" + pos);
                    }
                    max = Math.max(max, group.size());
                    min = Math.min(min, group.size());
                }
                assertEquals(n, seen.size(), "未覆盖全部位置 n=" + n + " heats=" + heats);
                assertTrue(max - min <= 1, "组大小差 > 1：n=" + n + " heats=" + heats);
            }
        }
    }

    @Test
    @DisplayName("组大小为偶数时各组种子和精确相等（平衡性）")
    void balancedSums() {
        // 24 = 3/4/6 组 × 每组 8/6/4 人（偶） → 完美均衡
        for (int heats : new int[]{3, 4, 6}) {
            List<List<Integer>> g = SnakeGrouping.assign(24, heats);
            assertEquals(0.0, SnakeGrouping.balanceScore(g), 1e-9,
                    "24人" + heats + "组（组大小为偶数）应完美均衡");
        }
        // 2 组 × 12 人（偶）→ 完美均衡
        assertEquals(0.0, SnakeGrouping.balanceScore(SnakeGrouping.assign(24, 2)), 1e-9);
        assertEquals(0.0, SnakeGrouping.balanceScore(SnakeGrouping.assign(32, 4)), 1e-9);
        assertEquals(0.0, SnakeGrouping.balanceScore(SnakeGrouping.assign(16, 8)), 1e-9);  // 2人/组=偶
    }

    @Test
    @DisplayName("组大小为奇数时蛇形受限于结构（24 人 8 组：3 人/组，种子和线性递增）")
    void oddGroupSizeStructuralLimit() {
        // 数学事实：蛇形的完美均衡只在「组大小为偶数」时成立。
        // 24人8组：组 h (0-based) 种子和 = 34 + h，标准差 = std(34..41) = 2.291...
        // 本断言固化该结构性上界，防止未来改动悄悄劣化均衡度。
        double score = SnakeGrouping.balanceScore(SnakeGrouping.assign(24, 8));
        assertTrue(score <= 2.3, "24人8组的均衡度不应劣于结构上界，实际 " + score);
    }

    @Test
    @DisplayName("非整除时蛇形仍尽量均衡（10 人 3 组）")
    void nearBalancedWhenNotDivisible() {
        List<List<Integer>> g = SnakeGrouping.assign(10, 3);
        assertEquals(List.of(0, 5, 6), g.get(0));
        assertEquals(List.of(1, 4, 7), g.get(1));
        assertEquals(List.of(2, 3, 8, 9), g.get(2));
        // 组大小差 ≤ 1 的推广断言（尾组允许恰好多 1 人）
        int max = g.stream().mapToInt(List::size).max().orElse(0);
        int min = g.stream().mapToInt(List::size).min().orElse(0);
        assertTrue(max - min <= 1);
    }

    @Test
    @DisplayName("单组 / 每人一组 / n=0 边界")
    void boundaries() {
        assertEquals(List.of(List.of(0, 1, 2)), SnakeGrouping.assign(3, 1));
        assertEquals(List.of(List.of(0), List.of(1)), SnakeGrouping.assign(2, 2));
        assertEquals(List.of(List.of(0), List.of(1), List.of()), SnakeGrouping.assign(2, 3));   // 空尾组
        assertTrue(SnakeGrouping.assign(0, 3).get(0).isEmpty());
        // 单人单组
        assertEquals(List.of(List.of(0)), SnakeGrouping.assign(1, 1));
    }

    @Test
    @DisplayName("确定性：相同输入 1000 次结果完全一致")
    void deterministic() {
        List<List<Integer>> first = SnakeGrouping.assign(64, 6);
        for (int i = 0; i < 1000; i++) {
            assertEquals(first, SnakeGrouping.assign(64, 6));
        }
    }

    @Test
    @DisplayName("heatOf 扁平映射与分组结果一致")
    void heatOfMapping() {
        int[] map = SnakeGrouping.heatOf(24, 3);
        List<List<Integer>> g = SnakeGrouping.assign(24, 3);
        for (int h = 0; h < 3; h++) {
            for (int pos : g.get(h)) assertEquals(h, map[pos]);
        }
    }

    @Test
    @DisplayName("ID 版本：已排序 ID 数组直接分组")
    void assignByAthleteIds() {
        long[] ids = {100, 101, 102, 103, 104, 105};
        List<List<Long>> g = SnakeGrouping.assign(ids, 2);
        assertEquals(List.of(100L, 103L, 104L), g.get(0));
        assertEquals(List.of(101L, 102L, 105L), g.get(1));
        List<Long> all = new ArrayList<>();
        g.forEach(all::addAll);
        assertEquals(List.of(100L, 101L, 102L, 103L, 104L, 105L).size(), all.size());
    }

    @Test
    @DisplayName("非法参数直接抛出")
    void invalidArgs() {
        assertThrows(IllegalArgumentException.class, () -> SnakeGrouping.assign(-1, 2));
        assertThrows(IllegalArgumentException.class, () -> SnakeGrouping.assign(10, 0));
        assertThrows(IllegalArgumentException.class, () -> SnakeGrouping.assign(null, 2));
    }

    @Test
    @DisplayName("大输入毫秒级（10 万运动员 × 16 组）")
    void linearPerformance() {
        long t0 = System.currentTimeMillis();
        List<List<Integer>> g = SnakeGrouping.assign(100_000, 16);
        long ms = System.currentTimeMillis() - t0;
        assertEquals(100_000, g.stream().mapToInt(List::size).sum());
        assertTrue(ms < 500, () -> "10万人分组应在 500ms 内完成，实际 " + ms + "ms");
    }
}
