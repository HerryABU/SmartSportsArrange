package com.sports.schedule.tournament;

/**
 * 种子分配：标准淘汰赛对阵图的种子排位。
 *
 * <p>标准递归 seed 排位保证 1/2 号种子分居不同半区、只可能在决赛相遇，高种子优先获得轮空。
 * 例如 {@code seedOrder(8)} = {@code [1,8,4,5,2,7,3,6]}（对阵 1v8、4v5、2v7、3v6）。</p>
 *
 * <p>对应架构文档「球赛赛制生成 → 种子队分配」。</p>
 */
public final class Seeding {

    private Seeding() {
    }

    /** 下一个不小于 n 的 2 的幂。 */
    public static int nextPow2(int n) {
        int p = 1;
        while (p < n) p <<= 1;
        return p;
    }

    /** n（须为 2 的幂）个位置上的标准种子号序列。 */
    public static int[] seedOrder(int n) {
        if (n <= 1) return new int[]{1};
        int[] prev = seedOrder(n / 2);
        int[] out = new int[n];
        int k = 0;
        for (int s : prev) {
            out[k++] = s;
            out[k++] = n + 1 - s;
        }
        return out;
    }

    /** 参赛队数补齐到 2 的幂后的标准种子序列；种子号 &gt; nTeams 的位置即「轮空位」。 */
    public static int[] seedPositions(int nTeams) {
        return seedOrder(nextPow2(Math.max(1, nTeams)));
    }
}
