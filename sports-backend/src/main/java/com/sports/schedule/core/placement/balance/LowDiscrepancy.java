package com.sports.schedule.core.placement.balance;

/**
 * 低差异序列构造（Tijdeman / Holroyd–Propp）—— 把「铺匀」从**事后检查**变成**构造保证**。
 *
 * <h2>为什么需要它（换范式，不是换参数）</h2>
 *
 * <p>本项目里所有「均衡」诉求（同班道次错开、裁判/班主任派遣均衡、休息间隔分散）
 * 原先的共性做法是：先构造一个可行解，再用一个**代价**去近似均衡，然后靠搜索去压这个代价。
 * 这条路的固有上限是：<b>代价低 ≠ 真的均衡</b>，而且「多均衡才算够」没有判据 ——
 * 无法回答「现在这个分布离最优还差多远」，只能反复调权重。</p>
 *
 * <p>差异理论给的是另一种东西：<b>一条显式构造，附带一个可证的上界</b>。
 * 经典结论（Holroyd–Propp，用 Hall 婚姻定理证明；Tijdeman 更早有等价结果）：
 * 给定有限集上的有理分布 π，存在无穷序列 s₁,s₂,… 使得对<b>任意</b>前缀长度 k
 * 与任意目标 s，都有</p>
 *
 * <pre>
 *     | N_k(s) − k·π(s) |  ≤  1
 * </pre>
 *
 * <p>即：<b>不仅最终均衡，中途每一步都不偏</b>（这一点比「总量均衡」强得多 ——
 * 赛程进行到一半时各道使用次数同样不能失衡）。</p>
 *
 * <h2>实现要点</h2>
 *
 * <ul>
 *   <li><b>纯整数运算</b>：判据写成 {@code quota[b]·k − N·count[b]} 的整数比较，
 *       全程不出现浮点，因此没有「理论上成立、实现上差 1」的漂移。</li>
 *   <li><b>贪心即最优</b>：每步取「最亏欠」的目标（上式最大者）。
 *       并列时几个候选**亏欠程度完全相同**，因此并列怎么破都不影响上界 ——
 *       这正是 {@code rotate} 参数安全的理由（见下）。</li>
 *   <li><b>rotate（轮转起点）</b>：并列时按从 {@code rotate} 开始的轮转顺序取。
 *       没有它的话，所有班级的「第一次」都会落在 1 道 —— 单看每个班都均衡，
 *       但同一组次里各班的第一个人全挤在 1 道，是**横向**上的新失衡。
 *       用「班级 id 对道次数取模」做起点即可打散。
 *       ⚠️ 并列项的亏欠相同 ⇒ rotate 不放大偏差，上界仍是 1（有穷举测试固化）。</li>
 * </ul>
 *
 * <h2>边界（诚实说明）</h2>
 *
 * <p>差异理论只管「铺匀」，<b>不管可行性</b>。因此它的正确用法是嵌在
 * 「候选已合法」之后那一层（本项目即 {@code candidatePlacements} / 空道次过滤之后），
 * 用来在**合法候选之间**决定取哪一个，而不是用来产生候选。</p>
 *
 * <p>参考：Holroyd–Propp <i>Low-discrepancy stacks</i>；Angel–Holroyd–Martin–Propp
 * <i>Discrete low-discrepancy sequences</i>（显式算法）。</p>
 */
public final class LowDiscrepancy {

    private LowDiscrepancy() {
    }

    /**
     * 生成一条序列：目标 {@code b} 恰好出现 {@code quotas[b]} 次，
     * 且对任意前缀都满足 {@code |count_b(k) − k·quota_b/N| ≤ 1}。
     *
     * @param quotas 各目标的整数配额（非负）；全 0 时返回空数组
     * @return 长度 {@code sum(quotas)} 的序列，元素为目标下标
     */
    public static int[] sequence(long[] quotas) {
        return sequence(quotas, 0);
    }

    /**
     * 同上，但并列时按「从 {@code rotate} 开始的轮转顺序」取值。
     *
     * @param rotate 轮转起点（会被规约到 {@code [0, B)}）
     */
    public static int[] sequence(long[] quotas, int rotate) {
        int buckets = quotas.length;
        long total = 0;
        for (long q : quotas) {
            if (q < 0) {
                throw new IllegalArgumentException("quota 不可为负: " + q);
            }
            total += q;
        }
        if (total == 0) {
            return new int[0];
        }
        if (total > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("总量过大，无法一次性展开: " + total);
        }
        int[] seq = new int[(int) total];
        long[] count = new long[buckets];
        int start = Math.floorMod(rotate, Math.max(1, buckets));

        for (int k = 1; k <= total; k++) {
            int best = -1;
            long bestDeficit = Long.MIN_VALUE;
            // 从 start 起轮转扫描：并列时天然取「轮转顺序里靠前」的那个，
            // 且因为并列项的亏欠完全相同，破并列不会放大偏差。
            for (int t = 0; t < buckets; t++) {
                int b = (start + t) % buckets;
                if (count[b] >= quotas[b]) {
                    continue;                      // 已满额，不再考虑
                }
                long deficit = quotas[b] * (long) k - (long) total * count[b];
                if (deficit > bestDeficit) {
                    bestDeficit = deficit;
                    best = b;
                }
            }
            if (best < 0) {
                // 理论上不可达（未满额者必存在）；留作不变量哨兵，避免静默产出残缺序列。
                throw new IllegalStateException("无可用目标，构造失败于 k=" + k);
            }
            seq[k - 1] = best;
            count[best]++;
        }
        return seq;
    }

    /**
     * 均匀配额：{@code total} 次尽量均分到 {@code buckets} 个目标
     * （前 {@code total % buckets} 个目标各多 1 次）。
     */
    public static int[] uniform(int total, int buckets) {
        return uniform(total, buckets, 0);
    }

    /**
     * 均匀配额 + 指定轮转起点（供「按班级 id 打散首道」这类用法）。
     *
     * @param rotate 轮转起点；并列项的亏欠相同，故不放大偏差（有穷举测试固化）
     */
    public static int[] uniform(int total, int buckets, int rotate) {
        if (buckets <= 0) {
            throw new IllegalArgumentException("buckets 必须为正: " + buckets);
        }
        if (total < 0) {
            throw new IllegalArgumentException("total 不可为负: " + total);
        }
        long[] quotas = new long[buckets];
        int base = total / buckets;
        int extra = total % buckets;
        for (int b = 0; b < buckets; b++) {
            quotas[b] = base + (b < extra ? 1 : 0);
        }
        return sequence(quotas, rotate);
    }

    /**
     * 前缀计数表：{@code out[k][b]} = 序列前 {@code k} 项里目标 {@code b} 出现的次数。
     *
     * <p>用途：把「第 k 次该走哪个道」这个理想值做成 O(1) 查表。
     * 分配过程是逐组次推进的，必须在**每一步**都能拿到当下应有的理想分布
     * （只算最终分布是不够的 —— 中途失衡无法事后纠正）。</p>
     */
    public static int[][] prefixCounts(int[] seq, int buckets) {
        int[][] out = new int[seq.length + 1][buckets];
        for (int k = 0; k < seq.length; k++) {
            System.arraycopy(out[k], 0, out[k + 1], 0, buckets);
            out[k + 1][seq[k]]++;
        }
        return out;
    }

    /**
     * 序列的最大偏差：{@code max_k,b |count_b(k) − k·quota_b/N|}。
     *
     * <p>刻意返回「用真实计数反算」的值而不是复用构造过程的内部量 ——
     * 否则测试会与实现共享同一个错误假设，等于没测。</p>
     */
    public static double maxDeviation(int[] seq, long[] quotas) {
        int buckets = quotas.length;
        long total = 0;
        for (long q : quotas) {
            total += q;
        }
        int[] count = new int[buckets];
        double worst = 0;
        for (int k = 0; k < seq.length; k++) {
            count[seq[k]]++;
            long n = k + 1;
            for (int b = 0; b < buckets; b++) {
                double ideal = total == 0 ? 0 : (double) n * quotas[b] / total;
                worst = Math.max(worst, Math.abs(count[b] - ideal));
            }
        }
        return worst;
    }
}
