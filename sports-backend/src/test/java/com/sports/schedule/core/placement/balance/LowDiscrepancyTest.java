package com.sports.schedule.core.placement.balance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link LowDiscrepancy} 的穷举与随机化验证。
 *
 * <p><b>为什么这个测试是本模块的主体</b>：低差异构造的全部价值就是那个「任意前缀偏差 ≤ 1」
 * 的上界。如果只测「输出长度正确」「计数正确」，这个类就退化成一个普通轮转器，
 * 而我们付出的复杂度没有换来任何东西。所以这里<b>穷举</b>小规模全部配额组合、
 * 并对每个组合遍历全部轮转起点，用<b>真实计数反算</b>偏差来断言上界。</p>
 *
 * <p>另外刻意独立实现偏差度量（{@link LowDiscrepancy#maxDeviation} 从序列反算，
 * 不复用构造过程的内部量）—— 否则测试与实现会共享同一个错误前提，等于没测。</p>
 */
@DisplayName("LowDiscrepancy 低差异构造（Holroyd–Propp / Tijdeman）")
class LowDiscrepancyTest {

    private static final double EPS = 1e-9;

    // ------------------------------------------------------------------
    // ① 穷举：所有配额组合 × 所有轮转起点，上界必须 ≤ 1
    // ------------------------------------------------------------------

    @Test
    @DisplayName("穷举 2 目标全部配额（0~8）——计数精确且任意前缀偏差 ≤ 1")
    void exhaustiveTwoBuckets() {
        for (int a = 0; a <= 8; a++) {
            for (int b = 0; b <= 8; b++) {
                long[] quotas = {a, b};
                if (a + b == 0) {
                    assertEquals(0, LowDiscrepancy.sequence(quotas).length);
                    continue;
                }
                int[] seq = LowDiscrepancy.sequence(quotas);
                assertCountsExact(seq, quotas, "quotas=" + Arrays.toString(quotas));
                assertBound(seq, quotas, "quotas=" + Arrays.toString(quotas));
            }
        }
    }

    @Test
    @DisplayName("穷举 3 目标全部配额（0~5）——计数精确且任意前缀偏差 ≤ 1")
    void exhaustiveThreeBuckets() {
        for (int a = 0; a <= 5; a++) {
            for (int b = 0; b <= 5; b++) {
                for (int c = 0; c <= 5; c++) {
                    long[] quotas = {a, b, c};
                    if (a + b + c == 0) {
                        continue;
                    }
                    int[] seq = LowDiscrepancy.sequence(quotas);
                    String tag = "quotas=" + Arrays.toString(quotas);
                    assertCountsExact(seq, quotas, tag);
                    assertBound(seq, quotas, tag);
                }
            }
        }
    }

    @Test
    @DisplayName("穷举 4 目标全部配额（0~3）——计数精确且任意前缀偏差 ≤ 1")
    void exhaustiveFourBuckets() {
        for (int a = 0; a <= 3; a++) {
            for (int b = 0; b <= 3; b++) {
                for (int c = 0; c <= 3; c++) {
                    for (int d = 0; d <= 3; d++) {
                        long[] quotas = {a, b, c, d};
                        if (a + b + c + d == 0) {
                            continue;
                        }
                        int[] seq = LowDiscrepancy.sequence(quotas);
                        String tag = "quotas=" + Arrays.toString(quotas);
                        assertCountsExact(seq, quotas, tag);
                        assertBound(seq, quotas, tag);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("穷举：非均分配额（10:5:3:2 …）——上界仍是 1，不因倾斜而放大")
    void skewedQuotas() {
        long[][] cases = {
                {10, 5, 3, 2}, {7, 1}, {1, 7}, {9, 1, 1}, {1, 1, 9},
                {13, 4, 4, 1}, {20, 1, 1, 1, 1}, {6, 6, 6, 6, 6, 6, 7},
        };
        for (long[] quotas : cases) {
            int[] seq = LowDiscrepancy.sequence(quotas);
            String tag = "quotas=" + Arrays.toString(quotas);
            assertCountsExact(seq, quotas, tag);
            assertBound(seq, quotas, tag);
        }
    }

    // ------------------------------------------------------------------
    // ② rotate：并列破法不得放大偏差（这是「不同班从不同道起」能安全使用的前提）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("rotate：遍历全部起点，上界仍 ≤ 1（并列项亏欠相同 ⇒ 破并列不影响上界）")
    void rotateKeepsBound() {
        long[][] cases = {{6, 6, 6}, {7, 5, 3}, {12, 4}, {5, 5, 5, 5, 5}, {11, 7, 3, 1}};
        for (long[] quotas : cases) {
            for (int rotate = 0; rotate < 12; rotate++) {
                int[] seq = LowDiscrepancy.sequence(quotas, rotate);
                String tag = "quotas=" + Arrays.toString(quotas) + " rotate=" + rotate;
                assertCountsExact(seq, quotas, tag);
                assertBound(seq, quotas, tag);
            }
        }
    }

    @Test
    @DisplayName("rotate 确实改变了起点分布（否则它就是个没用上的参数）")
    void rotateActuallyShiftsStart() {
        long[] quotas = {8, 8, 8};
        int[] r0 = LowDiscrepancy.sequence(quotas, 0);
        int[] r1 = LowDiscrepancy.sequence(quotas, 1);
        assertEquals(0, r0[0], "rotate=0 应从目标 0 起");
        assertEquals(1, r1[0], "rotate=1 应从目标 1 起");
        // 计数与偏差不受影响
        assertCountsExact(r1, quotas, "rotate=1");
        assertBound(r1, quotas, "rotate=1");
    }

    // ------------------------------------------------------------------
    // ③ uniform：均匀配额
    // ------------------------------------------------------------------

    @Test
    @DisplayName("uniform：任意 total ≤ 40、buckets ≤ 8 —— 计数差 ≤ 1 且前缀偏差 ≤ 1")
    void uniformAll() {
        for (int buckets = 1; buckets <= 8; buckets++) {
            for (int total = 0; total <= 40; total++) {
                int[] seq = LowDiscrepancy.uniform(total, buckets);
                assertEquals(total, seq.length, "长度应为 total");
                int[] count = new int[buckets];
                for (int v : seq) {
                    assertTrue(v >= 0 && v < buckets, "越界: " + v);
                    count[v]++;
                }
                int min = Integer.MAX_VALUE;
                int max = Integer.MIN_VALUE;
                for (int c : count) {
                    min = Math.min(min, c);
                    max = Math.max(max, c);
                }
                assertTrue(max - min <= 1,
                        "均匀配额下计数差应 ≤1：total=" + total + " buckets=" + buckets
                                + " 计数=" + Arrays.toString(count));
                if (total > 0) {
                    long[] quotas = new long[buckets];
                    int base = total / buckets;
                    int extra = total % buckets;
                    for (int b = 0; b < buckets; b++) {
                        quotas[b] = base + (b < extra ? 1 : 0);
                    }
                    assertBound(seq, quotas, "uniform total=" + total + " buckets=" + buckets);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // ④ 前缀计数表与偏差度量自身
    // ------------------------------------------------------------------

    @Test
    @DisplayName("prefixCounts：前缀表与逐项累计一致，且表首行全 0")
    void prefixCountsConsistent() {
        int[] seq = LowDiscrepancy.uniform(23, 5);
        int[][] pre = LowDiscrepancy.prefixCounts(seq, 5);
        assertEquals(seq.length + 1, pre.length);
        for (int b = 0; b < 5; b++) {
            assertEquals(0, pre[0][b], "第 0 项之前计数应为 0");
        }
        int[] running = new int[5];
        for (int k = 0; k < seq.length; k++) {
            running[seq[k]]++;
            assertArrayEquals(running, pre[k + 1], "前缀表第 " + (k + 1) + " 行不一致");
        }
    }

    @Test
    @DisplayName("maxDeviation：能如实报出坏序列的偏差（度量本身不能恒为 0）")
    void maxDeviationDetectsBadSequence() {
        long[] quotas = {5, 5};
        // 先全给 0 再全给 1：最终计数正确（5:5），但中途严重偏离。
        // k=5 时计数 [5,0]，理想各 5×5/10 = 2.5 ⇒ 偏差 2.5。
        // 注：这条断言最初被我手算成 4.5 而失败 —— 正好说明偏差度量是独立算出来的，
        //     没有跟着实现的内部量走（否则会一起错）。
        int[] bad = {0, 0, 0, 0, 0, 1, 1, 1, 1, 1};
        assertEquals(2.5, LowDiscrepancy.maxDeviation(bad, quotas), 1e-9);
        assertTrue(LowDiscrepancy.maxDeviation(bad, quotas) > 1.0,
                "度量必须能识别坏序列，否则上界断言毫无意义");
    }

    // ------------------------------------------------------------------
    // ⑤ 确定性与边界
    // ------------------------------------------------------------------

    @Test
    @DisplayName("确定性：同输入同输出（算法链要求可复现）")
    void deterministic() {
        long[] quotas = {7, 3, 9, 1};
        assertArrayEquals(LowDiscrepancy.sequence(quotas, 2), LowDiscrepancy.sequence(quotas, 2));
        assertArrayEquals(LowDiscrepancy.uniform(31, 6), LowDiscrepancy.uniform(31, 6));
    }

    @Test
    @DisplayName("边界：总量 0、单目标、含 0 配额、非法入参")
    void edges() {
        assertEquals(0, LowDiscrepancy.sequence(new long[]{0, 0, 0}).length);
        assertArrayEquals(new int[]{0, 0, 0}, LowDiscrepancy.sequence(new long[]{3}));
        assertEquals(0, LowDiscrepancy.uniform(0, 4).length);
        // 0 配额的目标不得出现
        int[] seq = LowDiscrepancy.sequence(new long[]{0, 4, 0});
        for (int v : seq) {
            assertEquals(1, v, "0 配额目标不应被选中");
        }
        assertThrows(IllegalArgumentException.class, () -> LowDiscrepancy.sequence(new long[]{-1, 2}));
        assertThrows(IllegalArgumentException.class, () -> LowDiscrepancy.uniform(5, 0));
        assertThrows(IllegalArgumentException.class, () -> LowDiscrepancy.uniform(-1, 3));
    }

    @Test
    @DisplayName("随机化：500 组随机配额（3~9 目标、总量 ≤ 120）——上界恒 ≤ 1")
    void randomized() {
        Random rnd = new Random(20261006L);
        for (int t = 0; t < 500; t++) {
            int buckets = 3 + rnd.nextInt(7);
            long[] quotas = new long[buckets];
            long total = 0;
            for (int b = 0; b < buckets; b++) {
                quotas[b] = rnd.nextInt(25);
                total += quotas[b];
            }
            if (total == 0 || total > 120) {
                continue;
            }
            int rotate = rnd.nextInt(buckets);
            int[] seq = LowDiscrepancy.sequence(quotas, rotate);
            String tag = "quotas=" + Arrays.toString(quotas) + " rotate=" + rotate;
            assertCountsExact(seq, quotas, tag);
            assertBound(seq, quotas, tag);
        }
    }

    // ------------------------------------------------------------------
    // 断言工具
    // ------------------------------------------------------------------

    private static void assertCountsExact(int[] seq, long[] quotas, String tag) {
        int total = 0;
        for (long q : quotas) {
            total += (int) q;
        }
        assertEquals(total, seq.length, "长度应等于配额之和 ｜ " + tag);
        int[] count = new int[quotas.length];
        for (int v : seq) {
            count[v]++;
        }
        for (int b = 0; b < quotas.length; b++) {
            assertEquals((int) quotas[b], count[b], "目标 " + b + " 的次数应等于配额 ｜ " + tag);
        }
    }

    private static void assertBound(int[] seq, long[] quotas, String tag) {
        double dev = LowDiscrepancy.maxDeviation(seq, quotas);
        assertTrue(dev <= 1.0 + EPS,
                "任意前缀偏差应 ≤ 1，实测 " + dev + " ｜ " + tag);
    }
}
