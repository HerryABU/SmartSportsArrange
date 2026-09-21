package com.sports.schedule.rule;

import com.sports.schedule.rule.FixedLaneAssignment.Policy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 固定分道穷举验证：确定性映射必须逐道断言（规则可被精确实现，也可被精确验证）。
 */
@DisplayName("FixedLaneAssignment 固定分道")
class FixedLaneAssignmentTest {

    @Test
    @DisplayName("REGISTRATION：报名序依次占 1..n 道")
    void registrationOrder() {
        assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6, 7, 8},
                FixedLaneAssignment.assign(8, 8, Policy.REGISTRATION));
        assertArrayEquals(new int[]{1, 2, 3}, FixedLaneAssignment.assign(3, 8, Policy.REGISTRATION));
        assertArrayEquals(new int[]{}, FixedLaneAssignment.assign(0, 8, Policy.REGISTRATION));
    }

    @Test
    @DisplayName("PERFORMANCE：8 道经典「中间向两翼」4,5,3,6,2,7,1,8")
    void performanceEightLanesClassic() {
        assertArrayEquals(new int[]{4, 5, 3, 6, 2, 7, 1, 8},
                FixedLaneAssignment.assign(8, 8, Policy.PERFORMANCE));
    }

    @Test
    @DisplayName("PERFORMANCE：不满员时从中间向外紧凑填充（不跳道）")
    void performancePartialFillDensely() {
        // 3 人决赛：4,5,3（种子前三进中三道），不会出现 1 道空着、排 3/4/5 的稀疏情形
        assertArrayEquals(new int[]{4, 5, 3}, FixedLaneAssignment.assign(3, 8, Policy.PERFORMANCE));
        // 5 人：4,5,3,6,2
        assertArrayEquals(new int[]{4, 5, 3, 6, 2}, FixedLaneAssignment.assign(5, 8, Policy.PERFORMANCE));
    }

    @Test
    @DisplayName("PERFORMANCE：6 道推导序 3,4,2,5,1,6（平局小道优先，与 8 道惯例一致）")
    void performanceDerivedSixLanes() {
        assertArrayEquals(new int[]{3, 4, 2, 5, 1, 6},
                FixedLaneAssignment.assign(6, 6, Policy.PERFORMANCE));
    }

    @Test
    @DisplayName("穷举 1..10 道 × 1..满员：无重复、无越界、确定性")
    void exhaustiveAllLanes() {
        for (int lanes = 1; lanes <= 10; lanes++) {
            for (int n = 0; n <= lanes; n++) {
                for (Policy p : Policy.values()) {
                    int[] a = FixedLaneAssignment.assign(n, lanes, p);
                    assertEquals(n, a.length);
                    Set<Integer> used = new HashSet<>();
                    for (int lane : a) {
                        assertTrue(lane >= 1 && lane <= lanes,
                                "越界道次 lanes=" + lanes + " n=" + n + " lane=" + lane);
                        assertTrue(used.add(lane), "重复道次 lane=" + lane);
                    }
                    // 确定性：重复调用完全一致
                    assertArrayEquals(a, FixedLaneAssignment.assign(n, lanes, p));
                }
            }
        }
    }

    @Test
    @DisplayName("锁定项避让：固定分配自动跳过人工占用的道次，仍保持策略序")
    void avoidOccupiedLanes() {
        // 8 道，1、4 道已被人工锁定 → PERFORMANCE 空闲序为 5,3,6,2,7,8
        int[] a = FixedLaneAssignment.assignAvoiding(4, 8, Policy.PERFORMANCE, List.of(1, 4));
        assertArrayEquals(new int[]{5, 3, 6, 2}, a);
        // REGISTRATION：从 1 道起跳过已占的 2 道 → 1,3,4,5
        int[] b = FixedLaneAssignment.assignAvoiding(4, 8, Policy.REGISTRATION, List.of(2));
        assertArrayEquals(new int[]{1, 3, 4, 5}, b);
    }

    @Test
    @DisplayName("锁定项避让：空闲道位不足时抛可解释异常")
    void avoidOccupiedInsufficientThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> FixedLaneAssignment.assignAvoiding(2, 4, Policy.REGISTRATION, List.of(1, 2, 4)));
        assertTrue(ex.getMessage().contains("剩余空闲道位"));
    }

    @Test
    @DisplayName("非法参数：人数超道位数 / 道位数 < 1 / 负数")
    void invalidArgs() {
        assertThrows(IllegalArgumentException.class,
                () -> FixedLaneAssignment.assign(9, 8, Policy.REGISTRATION));
        assertThrows(IllegalArgumentException.class,
                () -> FixedLaneAssignment.assign(3, 0, Policy.REGISTRATION));
        assertThrows(IllegalArgumentException.class,
                () -> FixedLaneAssignment.assign(-1, 8, Policy.REGISTRATION));
    }
}
