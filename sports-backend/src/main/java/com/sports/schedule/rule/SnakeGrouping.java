package com.sports.schedule.rule;

import java.util.ArrayList;
import java.util.List;

/**
 * 蛇形分组（Snake Grouping）—— 按种子排序后 S 形分配到各组，O(n) 确定性算法。
 *
 * <p>这是田径/游泳等竞技编排的<b>标准分组算法</b>：把按种子排序的运动员依次蛇形分配到各组，
 * 使各组的<b>种子强度之和</b>严格均衡（人数整除组数时完全相等）。
 * 24 人分 3 组（每组 8 人）的经典结果：</p>
 * <pre>
 *   第 1 组： 1,  6,  7, 12, 13, 18, 19, 24   （种子和 = 100）
 *   第 2 组： 2,  5,  8, 11, 14, 17, 20, 23   （种子和 = 100）
 *   第 3 组： 3,  4,  9, 10, 15, 16, 21, 22   （种子和 = 100）
 * </pre>
 *
 * <p>工程要点：</p>
 * <ul>
 *   <li><b>确定性</b>：纯函数，相同输入必相同输出（无随机、无时间依赖）→ 可复现、可穷举验证。</li>
 *   <li><b>O(n)</b>：单次线性遍历，n=10^4 仍是毫秒级。</li>
 *   <li><b>均衡</b>：当 n % heats == 0 时，各组种子和相等；否则差值 ≤ 最大种子。</li>
 *   <li><b>不感知业务</b>：只接收"已排序的序号数组"，按位置蛇形切分 → 调用方自行把序号映射回运动员/道次。</li>
 * </ul>
 *
 * <p>竞品（豪杰/索美）的分组器本质上就是这一算法；本实现独立、可单测、可作为规则模式的基石，
 * 也可作为优化模式的初始解生成器。</p>
 */
public final class SnakeGrouping {

    private SnakeGrouping() {
    }

    /**
     * 蛇形分组（按位置）。
     *
     * @param n     已按种子升序排列的运动员人数（位置 0..n-1）
     * @param heats 组数（≥ 1；heats ≥ n 时每人一组，剩余组为空）
     * @return 每组的运动员<b>位置</b>列表（组内按蛇形填充顺序，即组内升序 = 种子从外圈到内圈）；
     *         返回列表长度 = heats（含可能为空的末尾组）
     */
    public static List<List<Integer>> assign(int n, int heats) {
        if (n < 0) throw new IllegalArgumentException("人数不能为负: " + n);
        if (heats <= 0) throw new IllegalArgumentException("组数必须 ≥ 1: " + heats);

        List<List<Integer>> groups = new ArrayList<>(heats);
        for (int h = 0; h < heats; h++) groups.add(new ArrayList<>());

        boolean leftToRight = true;
        int pos = 0;
        while (pos < n) {
            if (leftToRight) {
                for (int h = 0; h < heats && pos < n; h++) groups.get(h).add(pos++);
            } else {
                for (int h = heats - 1; h >= 0 && pos < n; h--) groups.get(h).add(pos++);
            }
            leftToRight = !leftToRight;
        }
        return groups;
    }

    /**
     * 蛇形分组（按已排序的运动员 ID 数组）。
     *
     * <p>输入必须已按种子升序排列；输出为每组的运动员 ID 列表，保持蛇形分配顺序。</p>
     *
     * @param sortedAthleteIds 已按种子升序排列的运动员 ID 数组
     * @param heats            组数
     * @return 每组的运动员 ID 列表（长度 = heats，含可能为空的末尾组）
     */
    public static List<List<Long>> assign(long[] sortedAthleteIds, int heats) {
        if (sortedAthleteIds == null) throw new IllegalArgumentException("运动员数组不能为 null");
        List<List<Integer>> byPosition = assign(sortedAthleteIds.length, heats);
        List<List<Long>> groups = new ArrayList<>(heats);
        for (List<Integer> positions : byPosition) {
            List<Long> ids = new ArrayList<>(positions.size());
            for (int p : positions) ids.add(sortedAthleteIds[p]);
            groups.add(ids);
        }
        return groups;
    }

    /**
     * 位置 → 组号的扁平映射（供调用方快速查「某位置在第几组」）。
     *
     * @param n     人数
     * @param heats 组数
     * @return 长度 n 的数组，result[pos] = 该位置被分到的组号（0-based）
     */
    public static int[] heatOf(int n, int heats) {
        List<List<Integer>> groups = assign(n, heats);
        int[] out = new int[n];
        for (int h = 0; h < heats; h++) {
            for (int pos : groups.get(h)) out[pos] = h;
        }
        return out;
    }

    /**
     * 评估分组均衡度：各组种子位置之和的标准差（越小越均衡，0 = 完美均衡）。
     *
     * <p>仅作测试断言/诊断用，不参与编排。</p>
     *
     * @param groups 分组结果（每组是位置列表）
     * @return 各组和的标准差
     */
    public static double balanceScore(List<List<Integer>> groups) {
        if (groups.isEmpty()) return 0;
        double[] sums = new double[groups.size()];
        double total = 0;
        for (int i = 0; i < groups.size(); i++) {
            int s = 0;
            for (int p : groups.get(i)) s += (p + 1);   // 种子 = 位置+1
            sums[i] = s;
            total += s;
        }
        double mean = total / groups.size();
        double var = 0;
        for (double s : sums) var += (s - mean) * (s - mean);
        return Math.sqrt(var / groups.size());
    }
}
