package com.sports.schedule.exact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 匈牙利算法（最小代价分配精确解）的验证。
 *
 * <p>重点：<b>与暴力枚举全排列的结果一致</b>（小规模穷举就是「标准答案」），
 * 证明它是精确解而非近似解；同时验证矩形（运动员 < 道次）情形的扩展。</p>
 */
@DisplayName("匈牙利算法（精确分配）")
class HungarianAssignmentTest {

    @Test
    @DisplayName("简单 3×3 已知代价矩阵得到最优分配")
    void simpleKnownMatrix() {
        long[][] cost = {
                {1, 2, 3},
                {2, 3, 1},
                {3, 1, 2},
        };
        int[] assignment = HungarianAssignment.minCostAssignment(cost);
        assertEquals(3, assignment.length);
        // 该矩阵最优：行0→列0，行1→列2，行2→列1，总代价 1+1+1=3
        assertEquals(0, assignment[0]);
        assertEquals(2, assignment[1]);
        assertEquals(1, assignment[2]);
        assertEquals(3L, totalCost(cost, assignment));
    }

    @Test
    @DisplayName("方阵与暴力枚举全排列一致（穷举 = 标准答案）")
    void matchesBruteForceSquare() {
        Random rnd = new Random(42L);
        for (int n = 2; n <= 6; n++) {
            for (int trial = 0; trial < 8; trial++) {
                long[][] cost = randomCost(rnd, n, n, 5);
                int[] got = HungarianAssignment.minCostAssignment(cost);
                long minCost = bruteForceSquare(cost);
                assertEquals(minCost, totalCost(cost, got),
                        "n=" + n + " trial=" + trial + " 未达最小代价");
            }
        }
    }

    @Test
    @DisplayName("矩形（运动员 < 道次）分配与暴力枚举一致")
    void matchesBruteForceRectangular() {
        Random rnd = new Random(7L);
        for (int trial = 0; trial < 12; trial++) {
            int m = 2 + rnd.nextInt(3);   // 2..4 名运动员
            int k = m + rnd.nextInt(3);   // 道次 ≥ 运动员
            long[][] cost = randomCost(rnd, m, k, 5);
            int[] freeLanes = new int[k];
            for (int i = 0; i < k; i++) freeLanes[i] = i + 1;

            int[] laneNos = HungarianAssignment.assignAthletesToLanes(cost, freeLanes);
            long got = 0;
            for (int i = 0; i < m; i++) got += cost[i][laneNos[i] - 1];

            long minCost = bruteForceRectangular(cost);
            assertEquals(minCost, got, "trial=" + trial + " 未达最小代价");
        }
    }

    @Test
    @DisplayName("分配是合法双射（每行/列各用一次），无重复道次")
    void assignmentIsBijection() {
        Random rnd = new Random(3L);
        int n = 8;
        long[][] cost = randomCost(rnd, n, n, 20);
        int[] assignment = HungarianAssignment.minCostAssignment(cost);
        boolean[] used = new boolean[n];
        for (int col : assignment) {
            assertFalse(used[col], "道次重复：" + col);
            used[col] = true;
        }
        for (boolean u : used) assertTrue(u, "存在未分配道次");
    }

    @Test
    @DisplayName("空矩阵安全返回空")
    void emptyMatrixReturnsEmpty() {
        assertEquals(0, HungarianAssignment.minCostAssignment(new long[0][0]).length);
        assertEquals(0, HungarianAssignment.assignAthletesToLanes(new long[0][0], new int[0]).length);
    }

    // ==================== 工具 ====================

    private static long totalCost(long[][] cost, int[] assignment) {
        long total = 0;
        for (int i = 0; i < assignment.length; i++) total += cost[i][assignment[i]];
        return total;
    }

    private static long[][] randomCost(Random rnd, int rows, int cols, int maxVal) {
        long[][] cost = new long[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                cost[i][j] = rnd.nextInt(maxVal + 1);
            }
        }
        return cost;
    }

    /** 方阵全排列穷举最小代价（n ≤ 6 可行） */
    private static long bruteForceSquare(long[][] cost) {
        int n = cost.length;
        int[] perm = new int[n];
        for (int i = 0; i < n; i++) perm[i] = i;
        return permute(cost, perm, 0);
    }

    /** 矩形穷举：从 k 列里选 m 列并排列 */
    private static long bruteForceRectangular(long[][] cost) {
        int m = cost.length;
        int k = cost[0].length;
        long[] best = {Long.MAX_VALUE};
        int[] chosen = new int[m];
        boolean[] used = new boolean[k];
        dfs(cost, chosen, used, 0, best);
        return best[0];
    }

    private static void dfs(long[][] cost, int[] chosen, boolean[] used, int idx, long[] best) {
        int m = cost.length;
        int k = cost[0].length;
        if (idx == m) {
            long total = 0;
            for (int i = 0; i < m; i++) total += cost[i][chosen[i]];
            best[0] = Math.min(best[0], total);
            return;
        }
        for (int j = 0; j < k; j++) {
            if (used[j]) continue;
            used[j] = true;
            chosen[idx] = j;
            dfs(cost, chosen, used, idx + 1, best);
            used[j] = false;
        }
    }

    private static long permute(long[][] cost, int[] perm, int idx) {
        int n = cost.length;
        if (idx == n) {
            long total = 0;
            for (int i = 0; i < n; i++) total += cost[i][perm[i]];
            return total;
        }
        long best = Long.MAX_VALUE;
        for (int i = idx; i < n; i++) {
            int tmp = perm[idx];
            perm[idx] = perm[i];
            perm[i] = tmp;
            best = Math.min(best, permute(cost, perm, idx + 1));
            perm[i] = perm[idx];
            perm[idx] = tmp;
        }
        return best;
    }
}
