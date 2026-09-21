package com.sports.schedule.exact;

import java.util.Arrays;

/**
 * 匈牙利算法（Kuhn–Munkres）：最小代价完全匹配的<b>精确解</b>。
 *
 * <h2>用在哪儿</h2>
 * 道次编排（组内分道）是一个「分配问题」：把一组里的每个运动员派到不同道次，
 * 使「同班挤在同一个道次」的总代价最小。规模 = 道次数（通常 8），用 O(n³) 的匈牙利算法
 * 直接求<b>精确最优</b>，而不是用启发式硬凑——这正是「对规模可控的子问题求精确解」的落地：
 * 把省下的算力留给真正 NP 难的项目编排（Timefold/SA/GA/LNS），道次这种小分配题绝不靠猜。
 *
 * <p>复杂度 O(n³)，n = 矩阵边长（= 道次数），8 道只需几百次运算，可忽略不计。</p>
 */
public final class HungarianAssignment {

    private HungarianAssignment() {
    }

    /**
     * 最小代价完全匹配（方阵）。
     *
     * @param cost n×n 代价矩阵，cost[i][j] = 把行 i 分配给列 j 的代价
     * @return 对每一行 i，其被分配的列号 assignment[i]（0 起），使总代价最小
     */
    public static int[] minCostAssignment(long[][] cost) {
        int n = cost.length;
        if (n == 0) return new int[0];

        // 经典 O(n^3) 匈牙利（1 起下标，u/v 为行/列势，p[j]=匹配到列 j 的行，way 记录增广路径）
        long[] u = new long[n + 1];
        long[] v = new long[n + 1];
        int[] p = new int[n + 1];
        int[] way = new int[n + 1];

        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            long[] minv = new long[n + 1];
            boolean[] used = new boolean[n + 1];
            Arrays.fill(minv, Long.MAX_VALUE);
            do {
                used[j0] = true;
                int i0 = p[j0];
                int j1 = -1;
                long delta = Long.MAX_VALUE;
                for (int j = 1; j <= n; j++) {
                    if (!used[j]) {
                        long cur = cost[i0 - 1][j - 1] - u[i0] - v[j];
                        if (cur < minv[j]) {
                            minv[j] = cur;
                            way[j] = j0;
                        }
                        if (minv[j] < delta) {
                            delta = minv[j];
                            j1 = j;
                        }
                    }
                }
                for (int j = 0; j <= n; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);

            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }

        int[] assignment = new int[n];
        for (int j = 1; j <= n; j++) {
            assignment[p[j] - 1] = j - 1;
        }
        return assignment;
    }

    /**
     * 把 m 个运动员派到 k 条道次（m ≤ k），每个运动员各占一条不同道次，使总代价最小。
     *
     * <p>这是「非方阵」情形：运动员数 ≤ 道次数（组未满）。补 m 行「哑运动员」（代价 0）
     * 把矩阵扩成 k×k 方阵后套用方阵匈牙利，哑运动员只用来填满空道，不影响真实分配的最优性。</p>
     *
     * @param cost     m×k 代价矩阵，cost[i][j] = 把运动员 i 放到道次 freeLanes[j] 的代价
     * @param freeLanes 可用的道次号（升序，长度 k ≥ m）
     * @return 对每个运动员 i，其被分配的道次号（真实道次号，非下标）
     */
    public static int[] assignAthletesToLanes(long[][] cost, int[] freeLanes) {
        int m = cost.length;
        int k = freeLanes.length;
        if (m == 0) return new int[0];
        if (m > k) {
            throw new IllegalArgumentException("运动员数 " + m + " 不能超过可用道次数 " + k);
        }

        long[][] padded = new long[k][k];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < k; j++) {
                padded[i][j] = cost[i][j];
            }
        }
        // 哑运动员行保持 0 代价（默认），填充剩余道次

        int[] assignment = minCostAssignment(padded);
        int[] result = new int[m];
        for (int i = 0; i < m; i++) {
            result[i] = freeLanes[assignment[i]];
        }
        return result;
    }
}
