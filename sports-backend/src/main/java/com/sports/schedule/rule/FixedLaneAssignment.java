package com.sports.schedule.rule;

import java.util.List;

/**
 * 固定分道（Fixed Lane Assignment）—— 按固定规则把组内运动员映射到道次，O(n) 确定性。
 *
 * <p>竞品（豪杰/索美）的「按成绩分道 / 按报名顺序分道」即此类。本实现提供两种<b>可枚举验证</b>的策略：</p>
 *
 * <table border="1">
 *   <caption>分道策略</caption>
 *   <tr><th>策略</th><th>语义</th><th>典型用法</th></tr>
 *   <tr><td>{@link Policy#REGISTRATION REGISTRATION}</td>
 *       <td>按报名顺序依次占 1,2,3,… 道</td><td>预赛首轮（无成绩可依）</td></tr>
 *   <tr><td>{@link Policy#PERFORMANCE PERFORMANCE}</td>
 *       <td>种子第 1 名进 4 道，2→5，3→3，4→6，5→2，6→7，7→1，8→8（由中间向两翼扩散）</td>
 *       <td>决赛（按预赛成绩，快者在中间道次——径赛黄金惯例）</td></tr>
 * </table>
 *
 * <p>工程要点：</p>
 * <ul>
 *   <li><b>确定性</b>：纯静态映射，无随机 → 可穷举验证、可复现。</li>
 *   <li><b>密集道次</b>：人少于道次时按 PERFORMANCE 从中间向外紧凑填充（不跳道），REGISTRATION 从 1 道起连排。</li>
 *   <li><b>越界防御</b>：lanes &lt; 1 或组空直接给出可解释结果/异常。</li>
 *   <li><b>人工锁定项</b>：已占道次作为输入，固定分配自动避让（规则层不覆盖人工决定）。</li>
 * </ul>
 */
public final class FixedLaneAssignment {

    /** 8 道标准「中间向两翼」序：4,5,3,6,2,7,1,8（世界田联惯例） */
    private static final int[] CENTER_OUT_8 = {4, 5, 3, 6, 2, 7, 1, 8};

    private FixedLaneAssignment() {
    }

    /** 分道策略 */
    public enum Policy {
        /** 报名顺序：依次占 1..n 道 */
        REGISTRATION,
        /** 成绩（种子）顺序：最优者进中间道，向两翼扩散 */
        PERFORMANCE
    }

    /**
     * 组内固定分道。
     *
     * @param groupSize 组内人数（已按策略口径排序：REGISTRATION=报名序，PERFORMANCE=种子序）
     * @param lanes     可用道位数（通常 6/8/10）
     * @param policy    分道策略
     * @return 长度 = groupSize 的数组：result[i] = 第 i 个（按输入序）运动员的道次号（1-based）
     * @throws IllegalArgumentException 参数非法或人数超出道位数
     */
    public static int[] assign(int groupSize, int lanes, Policy policy) {
        if (groupSize < 0) throw new IllegalArgumentException("人数不能为负: " + groupSize);
        if (lanes < 1) throw new IllegalArgumentException("道位数必须 ≥ 1: " + lanes);
        if (groupSize > lanes) {
            throw new IllegalArgumentException(
                    "组内人数(" + groupSize + ")超过道位数(" + lanes + ")，应先增加组数");
        }
        int[] out = new int[groupSize];
        if (policy == Policy.REGISTRATION) {
            for (int i = 0; i < groupSize; i++) out[i] = i + 1;
            return out;
        }
        // PERFORMANCE：标准 8 道「中间向两翼」；其它道位数按「靠近中线的道次优先」实时生成同型序列
        int[] pattern = lanePattern(lanes);
        for (int i = 0; i < groupSize; i++) {
            out[i] = pattern[i];
        }
        return out;
    }

    /**
     * 固定分道（含人工锁定项避让）。
     *
     * @param groupSize     组内未锁定人数
     * @param lanes         可用道位数
     * @param policy        分道策略
     * @param occupiedLanes 已被人工锁定占用的道次号（1-based）
     * @return 未锁定运动员的道次分配（在空闲道次中按策略序紧密填充）
     */
    public static int[] assignAvoiding(int groupSize, int lanes, Policy policy, List<Integer> occupiedLanes) {
        if (groupSize < 0) throw new IllegalArgumentException("人数不能为负: " + groupSize);
        boolean[] taken = new boolean[lanes + 1];
        int free = lanes;
        if (occupiedLanes != null) {
            for (int lane : occupiedLanes) {
                if (lane >= 1 && lane <= lanes && !taken[lane]) {
                    taken[lane] = true;
                    free--;
                }
            }
        }
        if (groupSize > free) {
            throw new IllegalArgumentException(
                    "组内人数(" + groupSize + ")超过剩余空闲道位(" + free + ")");
        }
        // 按策略序生成「空闲道次」序列：REGISTRATION=1..lanes；PERFORMANCE=中间向两翼
        int[] pattern = lanePattern(lanes);
        int[] out = new int[groupSize];
        int idx = 0;
        if (policy == Policy.REGISTRATION) {
            for (int lane = 1; lane <= lanes && idx < groupSize; lane++) {
                if (!taken[lane]) out[idx++] = lane;
            }
        } else {
            for (int lane : pattern) {
                if (idx >= groupSize) break;
                if (!taken[lane]) out[idx++] = lane;
            }
        }
        return out;
    }

    /**
     * 生成 n 道的「中间向两翼」序列。
     *
     * <p>8 道固化经典序 {4,5,3,6,2,7,1,8}（与人工惯例完全一致，避免浮点生成的顺序漂移）；
     * 其它道位数（6/9/10…）按同一几何规则推导：|lane - center| 升序，平局时<b>较小道次优先</b>
     * （与 8 道经典序「4 在 5 前」保持同一惯例），保证确定性与经典序的一致性。</p>
     */
    static int[] lanePattern(int lanes) {
        if (lanes == 8) return CENTER_OUT_8.clone();
        int center2 = lanes + 1;   // 2×center，避免半道浮点
        Integer[] order = new Integer[lanes];
        for (int i = 0; i < lanes; i++) order[i] = i + 1;
        java.util.Arrays.sort(order, (a, b) -> {
            int da = Math.abs(2 * a - center2);
            int db = Math.abs(2 * b - center2);
            if (da != db) return Integer.compare(da, db);
            // 平局（与中线等距的两道）：与 8 道经典序「4 在 5 前」一致 → 较小道次在先
            return Integer.compare(a, b);
        });
        int[] out = new int[lanes];
        for (int i = 0; i < lanes; i++) out[i] = order[i];
        return out;
    }
}
