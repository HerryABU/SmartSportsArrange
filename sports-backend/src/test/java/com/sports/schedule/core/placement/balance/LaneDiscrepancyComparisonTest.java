package com.sports.schedule.core.placement.balance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;


import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分道口径「旧 vs 新」的对照实验 —— 证明换口径**确实**改善了公平性，而不只是换了个写法。
 *
 * <h2>为什么必须有这个测试</h2>
 *
 * <p>改代价口径是最容易「看起来改了、其实没变好」的一类改动：两条口径都能跑、
 * 都给出合法分道、都不报错。唯一能区分它们的是<b>度量</b>。所以这里把两条口径
 * 放进同一个模拟赛程里跑，用同一个度量（每班道次直方图相对理想的<b>任意前缀</b>最大偏差）
 * 对比。</p>
 *
 * <h2>两条口径</h2>
 *
 * <ul>
 *   <li><b>旧</b>：代价 = 该班已用该道的次数。因为同一组次里代价只与「道」有关、
 *       与「人」无关，最小代价分配等价于：把该组次可用的道按「已用次数」升序取前
 *       |待分人| 个。⇒ 模拟器直接按这个规则分，等價于匈牙利在该代价下的最优解。</li>
 *   <li><b>新</b>：代价 = |该班走这道后的计数 − 低差异理想计数| × BIAS + 该道已用次数。
 *       同样只与道有关 ⇒ 按 (偏离理想, 已用次数) 字典序取前 |待分人| 个。</li>
 * </ul>
 *
 * <p>模拟器逐人推进（因为「第几次上场」在组次内会随同班人数变化），
 * 这与真实实现的处理一致（见 {@code ArrangementService.assignLanes}）。</p>
 */
@DisplayName("分道口径对照：低差异理想 vs 已用次数")
class LaneDiscrepancyComparisonTest {

    /**
     * 模拟一场比赛，返回两个度量：
     * {@code [0]} = 最终直方图偏差；{@code [1]} = **任意前缀**最大偏差（逐步推进时观测）。
     *
     * <p>⚠️ 只测最终直方图是**不够的**：旧口径「每次取用得最少的道」在总量上本就已经
     * 很均衡（实测平均 0.967，理论上界 1），所以只看总量会得出「两条口径差不多」的结论。
     * 差异理论真正的卖点是**任意前缀**都 ≤1 —— 即赛程进行到一半时同样不失衡，
     * 而这一点必须逐步观测才看得到。</p>
     */
    private static double[] simulate(boolean lowDiscrepancy, int classes, int lanes,
                                     int heats, int perHeat, long seed) {
        Random rnd = new Random(seed);
        // 造赛程：每个位置随机归一个班（模拟报名结构不均衡）
        List<List<Integer>> matrix = new ArrayList<>();
        int[] classTotal = new int[classes];
        for (int h = 0; h < heats; h++) {
            List<Integer> heat = new ArrayList<>();
            for (int i = 0; i < perHeat; i++) {
                int c = rnd.nextInt(classes);
                heat.add(c);
                classTotal[c]++;
            }
            matrix.add(heat);
        }

        int[][] use = new int[classes][lanes];              // 已用次数
        Map<Integer, int[][]> ideal = new HashMap<>();       // 低差异理想前缀表
        for (int c = 0; c < classes; c++) {
            if (classTotal[c] > 0) {
                ideal.put(c, LowDiscrepancy.prefixCounts(
                        LowDiscrepancy.uniform(classTotal[c], lanes, c % lanes), lanes));
            }
        }
        int maxTotal = 0;
        for (int t : classTotal) {
            maxTotal = Math.max(maxTotal, t);
        }
        long bias = maxTotal + 2L;

        double worstPrefix = 0;
        int[] seenInHeat = new int[classes];
        for (List<Integer> heat : matrix) {
            boolean[] taken = new boolean[lanes];
            java.util.Arrays.fill(seenInHeat, 0);
            for (int cls : heat) {
                int appearance = count(use[cls]) + (++seenInHeat[cls]);
                int[][] pre = ideal.get(cls);
                int[] idealAfter = pre == null ? null
                        : pre[Math.min(appearance, pre.length - 1)];
                // 选出代价最小的空闲道
                int bestLane = -1;
                long bestCost = Long.MAX_VALUE;
                for (int l = 0; l < lanes; l++) {
                    if (taken[l]) {
                        continue;
                    }
                    long cost;
                    if (lowDiscrepancy && idealAfter != null) {
                        long dev = Math.abs((long) use[cls][l] + 1 - idealAfter[l]);
                        cost = dev * bias + use[cls][l];
                    } else {
                        cost = use[cls][l];
                    }
                    if (cost < bestCost || (cost == bestCost && (bestLane < 0 || l < bestLane))) {
                        bestCost = cost;
                        bestLane = l;
                    }
                }
                taken[bestLane] = true;
                use[cls][bestLane]++;

                // 逐步观测前缀偏差：此刻该班已出场 k 次，理想是每道 k/lanes
                int k = count(use[cls]);
                for (int l = 0; l < lanes; l++) {
                    worstPrefix = Math.max(worstPrefix, Math.abs(use[cls][l] - (double) k / lanes));
                }
            }
        }

        double worstFinal = 0;
        for (int c = 0; c < classes; c++) {
            if (classTotal[c] == 0) {
                continue;
            }
            for (int l = 0; l < lanes; l++) {
                double want = (double) classTotal[c] / lanes;
                worstFinal = Math.max(worstFinal, Math.abs(use[c][l] - want));
            }
        }
        return new double[]{worstFinal, worstPrefix};
    }

    private static int count(int[] a) {
        int s = 0;
        for (int v : a) {
            s += v;
        }
        return s;
    }

    @Test
    @DisplayName("对照：200 组随机赛程 —— 同时看「最终直方图」与「任意前缀」两个度量")
    void newPolicyComparedToOld() {
        int finalWin = 0;
        int finalLose = 0;
        int preWin = 0;
        int preLose = 0;
        double sumFinalOld = 0;
        double sumFinalNew = 0;
        double sumPreOld = 0;
        double sumPreNew = 0;
        int cases = 0;

        for (int t = 0; t < 200; t++) {
            Random rnd = new Random(90000L + t);
            int classes = 2 + rnd.nextInt(6);          // 2~7 个班
            int lanes = 4 + rnd.nextInt(5);            // 4~8 条道
            int perHeat = lanes;                        // 每组次满道
            int heats = 2 + rnd.nextInt(8);
            long seed = 1234L + t;

            double[] oldR = simulate(false, classes, lanes, heats, perHeat, seed);
            double[] newR = simulate(true, classes, lanes, heats, perHeat, seed);
            cases++;
            sumFinalOld += oldR[0];
            sumFinalNew += newR[0];
            sumPreOld += oldR[1];
            sumPreNew += newR[1];
            if (newR[0] < oldR[0] - 1e-9) {
                finalWin++;
            } else if (newR[0] > oldR[0] + 1e-9) {
                finalLose++;
            }
            if (newR[1] < oldR[1] - 1e-9) {
                preWin++;
            } else if (newR[1] > oldR[1] + 1e-9) {
                preLose++;
            }
        }

        System.out.printf("[lane-policy] 用例 %d%n", cases);
        System.out.printf("  最终直方图偏差：旧 %.3f → 新 %.3f ｜ 新优 %d / 新劣 %d%n",
                sumFinalOld / cases, sumFinalNew / cases, finalWin, finalLose);
        System.out.printf("  任意前缀偏差：  旧 %.3f → 新 %.3f ｜ 新优 %d / 新劣 %d%n",
                sumPreOld / cases, sumPreNew / cases, preWin, preLose);

        // ── 结论（2026-10-06 实测，本条是**否定结果**的固化）──
        //
        // 两条口径在两个度量上都**打平**：最终直方图 新优 33 / 新劣 34；
        // 任意前缀 新优 39 / 新劣 40（纯属掷硬币），平均偏差也几乎相同
        // （0.967→0.972、1.159→1.159）。
        //
        // 根因：旧口径「每次取该班用得最少的道」对这个**问题结构**本身就已经接近最优 ——
        // 每班只需自己的道次直方图均衡，而"取最小"这一条规则天然把计数钉在相互差 ≤1 上；
        // 前缀偏差 1.159 > 1 是**组内撞道**造成的（理想道被别人占了），两条口径同样受影响。
        // ⇒ 低差异可证上界在这里换不到改善，故 `ArrangementService` 的代价口径**保持原样**
        //    （本次改动已回退，见 git 历史）。这个类保留为**经证实的原语**，
        //    用于那些「取最小」不可用的场景（如休息间隔分散、按可用性约束的派遣均衡）。
        //
        // 断言「打平」而不是「更优」：一旦将来有人改动使某一边显著占优，
        // 说明问题结构或口径已变，这条结论需要重新评估 —— 那正是该被发现的。
        assertTrue(Math.abs(finalWin - finalLose) <= cases * 0.15,
                "预计两口径在最终直方图上打平；实测 新优 " + finalWin + " / 新劣 " + finalLose
                        + " —— 若明显偏斜，说明结论已变，需重新评估本类注释中的判断");
        assertTrue(Math.abs(preWin - preLose) <= cases * 0.15,
                "预计两口径在任意前缀上打平；实测 新优 " + preWin + " / 新劣 " + preLose
                        + " —— 若明显偏斜，说明结论已变，需重新评估本类注释中的判断");
    }

    @Test
    @DisplayName("前提校验：模拟器本身必须能区分好坏口径（否则对照实验等于没做）")
    void simulatorIsDiscriminating() {
        // 一个「故意坏」的口径：永远优先选 0 道。若模拟器无法把它判成差，
        // 那么上面那条对照实验无论得出什么结论都不可信。
        double badPrefix = simulateBadPolicy(3, 5, 5, 5, 777L);
        double[] good = simulate(true, 3, 5, 5, 5, 777L);
        assertTrue(badPrefix > good[1] + 1e-9,
                "模拟器应能识别坏口径：坏 " + badPrefix + " vs 好 " + good[1]);
    }

    /** 故意坏的口径：能选 0 道就选 0 道，用于验证模拟器的判别力。 */
    private static double simulateBadPolicy(int classes, int lanes, int heats,
                                            int perHeat, long seed) {
        Random rnd = new Random(seed);
        List<List<Integer>> matrix = new ArrayList<>();
        for (int h = 0; h < heats; h++) {
            List<Integer> heat = new ArrayList<>();
            for (int i = 0; i < perHeat; i++) {
                heat.add(rnd.nextInt(classes));
            }
            matrix.add(heat);
        }
        int[][] use = new int[classes][lanes];
        double worst = 0;
        for (List<Integer> heat : matrix) {
            boolean[] taken = new boolean[lanes];
            for (int cls : heat) {
                int pick = -1;
                for (int l = 0; l < lanes; l++) {
                    if (!taken[l]) {
                        pick = l;
                        break;
                    }
                }
                taken[pick] = true;
                use[cls][pick]++;
                int k = count(use[cls]);
                for (int l = 0; l < lanes; l++) {
                    worst = Math.max(worst, Math.abs(use[cls][l] - (double) k / lanes));
                }
            }
        }
        return worst;
    }
}
