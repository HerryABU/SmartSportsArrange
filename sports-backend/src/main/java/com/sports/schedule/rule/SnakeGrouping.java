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

    // ==================== L1：按「年级/班级」顺序的蛇形分组入口 ====================
    //
    // 说明（L1 基础层，非 L2/L3 优化层）：
    //   · 种子蛇形 assign(n, heats)            → 按成绩/种子强度均衡（竞技标准分组）
    //   · 年级/班级蛇形 assignByGradeClass(...) → 按「年级 → 班级」顺序 S 形分散（本校常规编排）
    //   · 团体/趣味 assignTeamUnits(...)        → 同班同组号（一整支队伍）不可拆分，整队同组
    // 三者均为确定性 O(n) 纯函数，输出「每组包含哪些原始位置」，由调用方映射回运动员/道次。

    /**
     * L1 蛇形分组（按「年级/班级」顺序）—— 个体项目。
     *
     * <p>输入为与人数等长的排序键（建议 {@code 年级 + "\u0001" + 班级}）；先按键<b>稳定排序</b>
     * （同键保持原相对顺序），再对排序后的位置做 S 形分配。效果：同年级/同班级的运动员被
     * 均匀摊到各组，避免某组被单一班级占满。</p>
     *
     * @param gradeClassKeys 与人数等长的排序键（null/空视为空串，排在最前）
     * @param heats          组数（≥ 1）
     * @return 每组包含的<b>原始位置</b>列表（长度 = heats，含可能为空的末尾组）
     */
    public static List<List<Integer>> assignByGradeClass(List<String> gradeClassKeys, int heats) {
        if (gradeClassKeys == null) throw new IllegalArgumentException("排序键列表不能为 null");
        if (heats <= 0) throw new IllegalArgumentException("组数必须 ≥ 1: " + heats);
        int n = gradeClassKeys.size();

        // 位置 0..n-1 按 (键, 原下标) 稳定升序
        List<Integer> order = new ArrayList<>(n);
        for (int i = 0; i < n; i++) order.add(i);
        order.sort((a, b) -> {
            int byKey = keyOf(gradeClassKeys.get(a)).compareTo(keyOf(gradeClassKeys.get(b)));
            return byKey != 0 ? byKey : Integer.compare(a, b);
        });

        // 槽位蛇形 → 映射回排序后的原始位置
        List<List<Integer>> slots = assign(n, heats);
        List<List<Integer>> groups = new ArrayList<>(heats);
        for (List<Integer> slotGroup : slots) {
            List<Integer> mapped = new ArrayList<>(slotGroup.size());
            for (int slot : slotGroup) mapped.add(order.get(slot));
            groups.add(mapped);
        }
        return groups;
    }

    /**
     * L1 蛇形分组（按「年级/班级」顺序）—— 团体/趣味项目。
     *
     * <p>键相同的条目构成一个<b>不可拆分单元</b>（如同一班级同一组号的一整支接力/趣味队伍），
     * 整单元的成员必被分到同一组；单元之间再按 S 形分配到各组。调用方应先按
     * 「年级 → 班级 → 组号」排好序，键建议 {@code 年级 + "\u0001" + 班级 + "\u0001" + 组号}。</p>
     *
     * @param unitKeys 与人数等长的单元键（键相同 = 同一支队伍，成员不可拆开）
     * @param heats    组数（≥ 1）
     * @return 每组包含的<b>原始位置</b>列表（同一队伍的成员必在同一组）
     */
    public static List<List<Integer>> assignTeamUnits(List<String> unitKeys, int heats) {
        if (unitKeys == null) throw new IllegalArgumentException("单元键列表不能为 null");
        if (heats <= 0) throw new IllegalArgumentException("组数必须 ≥ 1: " + heats);

        // 按键聚合为单元（LinkedHashMap 保留首次出现顺序 = 调用方的年级/班级/组号序）
        java.util.Map<String, List<Integer>> unitMembers = new java.util.LinkedHashMap<>();
        for (int i = 0; i < unitKeys.size(); i++) {
            unitMembers.computeIfAbsent(keyOf(unitKeys.get(i)), k -> new ArrayList<>()).add(i);
        }

        // 单元级蛇形 → 展开为成员位置
        List<List<Integer>> unitSlots = assign(unitMembers.size(), heats);
        List<List<Integer>> groups = new ArrayList<>(heats);
        List<List<Integer>> units = new ArrayList<>(unitMembers.values());
        for (List<Integer> slotGroup : unitSlots) {
            List<Integer> mapped = new ArrayList<>();
            for (int unitIdx : slotGroup) mapped.addAll(units.get(unitIdx));
            groups.add(mapped);
        }
        return groups;
    }

    /**
     * L1 组内道次分配：把一组内按蛇形顺序排好的运动员依次落到 1..lanes 道；
     * 第二圈（i/lanes 为奇数）反向，使同班/同批运动员尽量占用不同道次。
     *
     * @param heatSize 该组人数（≥ 0）
     * @param lanes    道次数（≥ 1）
     * @return 长度 heatSize 的道次号数组（取值 1..lanes）
     */
    public static int[] assignLanes(int heatSize, int lanes) {
        if (heatSize < 0) throw new IllegalArgumentException("人数不能为负: " + heatSize);
        if (lanes <= 0) throw new IllegalArgumentException("道次数必须 ≥ 1: " + lanes);
        int[] out = new int[heatSize];
        boolean leftToRight = true;
        int pos = 0;
        while (pos < heatSize) {
            if (leftToRight) {
                for (int l = 1; l <= lanes && pos < heatSize; l++) out[pos++] = l;
            } else {
                for (int l = lanes; l >= 1 && pos < heatSize; l--) out[pos++] = l;
            }
            leftToRight = !leftToRight;
        }
        return out;
    }

    private static String keyOf(String s) {
        return s == null ? "" : s;
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
