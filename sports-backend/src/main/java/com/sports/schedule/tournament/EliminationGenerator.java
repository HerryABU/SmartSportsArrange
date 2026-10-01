package com.sports.schedule.tournament;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 淘汰赛对阵图生成：单淘汰（含轮空 + 种子分布）。
 *
 * <p>N 队补齐到 2 的幂 P，轮空数 = P - N，高种子优先获得轮空；按标准种子序列填位，
 * 保证 1/2 号种子分居不同半区。后续轮次给出结构占位，由「赛程编排」在结果产生后填槽。</p>
 */
public final class EliminationGenerator {

    /** 一场淘汰赛：{@code bye=true} 表示对手轮空、home 直接晋级；{@code placeholder=true} 表示待定。 */
    public record Match(int round, int slot, String home, String away, boolean bye, boolean placeholder) {
    }

    private EliminationGenerator() {
    }

    public static List<Match> single(List<String> teams) {
        return single(teams, null);
    }

    /**
     * 单淘汰（可带**同单位回避**）。
     *
     * <p>同单位回避：同一班级/年级的队伍尽量不要在首轮相遇 —— 校园赛事的硬性惯例
     * （同班两队首轮互淘汰，班主任会直接找上来）。做法是在**种子位之间做局部交换爬山**：
     * 只换「哪支队伍占哪个种子位」，位置集合与轮空结构完全不变，因此在不与回避冲突时
     * 种子分布依然保持 —— 这正确表达了优先级：<b>同单位回避是硬约束，种子分布是偏好</b>。</p>
     *
     * @param units 与 teams 等长的单位标签（班级/年级）；为 null 时跳过回避
     */
    public static List<Match> single(List<String> teams, List<String> units) {
        List<Match> out = new ArrayList<>();
        if (teams == null || teams.size() < 2) return out;
        int n = teams.size();
        int p = Seeding.nextPow2(n);
        int[] order = Seeding.seedPositions(n);          // 位置 → 种子号

        Map<Integer, String> byRank = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) byRank.put(i + 1, teams.get(i));

        if (units != null && units.size() == n) {
            Map<String, String> unitOf = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) unitOf.put(teams.get(i), units.get(i));
            reduceSameUnitClashes(order, byRank, unitOf);
        }

        int round = 1;
        for (int slot = 0; slot < p / 2; slot++) {
            int sHome = order[2 * slot];
            int sAway = order[2 * slot + 1];
            String home = byRank.get(sHome);
            String away = byRank.get(sAway);
            if (home == null && away == null) continue;
            if (home == null || away == null) {
                out.add(new Match(round, slot + 1, home != null ? home : away, null, true, false));
            } else {
                out.add(new Match(round, slot + 1, home, away, false, false));
            }
        }
        int cur = p / 2;
        round = 2;
        while (cur > 1) {
            cur /= 2;
            for (int slot = 0; slot < cur; slot++) {
                out.add(new Match(round, slot + 1, null, null, false, true));
            }
            round++;
        }
        return out;
    }

    /** 局部交换爬山：把「首轮同单位对局」压到最少（返回剩余冲突数，0 = 完全回避）。 */
    public static int reduceSameUnitClashes(int[] order, Map<Integer, String> byRank,
                                            Map<String, String> unitOf) {
        List<Integer> ranks = new ArrayList<>(byRank.keySet());
        int best = sameUnitClashes(order, byRank, unitOf);
        if (best == 0 || ranks.size() < 2) return best;
        java.util.Random rng = new java.util.Random(20260918L);
        for (int t = 0; t < 400 && best > 0; t++) {
            int r1 = ranks.get(rng.nextInt(ranks.size()));
            int r2 = ranks.get(rng.nextInt(ranks.size()));
            if (r1 == r2) continue;
            String a = byRank.get(r1);
            String b = byRank.get(r2);
            byRank.put(r1, b);
            byRank.put(r2, a);
            int c = sameUnitClashes(order, byRank, unitOf);
            if (c < best) {
                best = c;
            } else {
                byRank.put(r1, a);      // 回滚：只在变好时接受，种子秩序因此得以保持
                byRank.put(r2, b);
            }
        }
        return best;
    }

    /** 统计首轮的同单位对局数。 */
    public static int sameUnitClashes(int[] order, Map<Integer, String> byRank,
                                      Map<String, String> unitOf) {
        int c = 0;
        for (int k = 0; k + 1 < order.length; k += 2) {
            String t1 = byRank.get(order[k]);
            String t2 = byRank.get(order[k + 1]);
            if (t1 == null || t2 == null) continue;
            String u1 = unitOf.get(t1);
            String u2 = unitOf.get(t2);
            if (u1 != null && u1.equals(u2)) c++;
        }
        return c;
    }

    /**
     * 双淘汰：胜者组 + **真实结构的败者组**（2k−2 轮，k = log2(P)）+ 总决赛。
     *
     * <p>败者组不是一串等长占位：奇数轮是「败者组内部淘汰」，偶数轮「接收胜者组对应轮的败者」，
     * 因此每轮场次按 ``P / 2^⌈·⌉`` 递减。赛程编排据此即可正确排槽，而不是拿到一堆无差别占位。</p>
     */
    public static Map<String, List<Match>> doubleElim(List<String> teams) {
        Map<String, List<Match>> out = new LinkedHashMap<>();
        out.put("winners", single(teams));
        int n = teams == null ? 0 : teams.size();
        int p = Seeding.nextPow2(Math.max(2, n));
        int k = Integer.numberOfTrailingZeros(p);        // log2(p)
        List<Match> losers = new ArrayList<>();
        for (int i = 1; i <= Math.max(1, 2 * k - 2); i++) {
            int exp = (i % 2 == 1) ? (i + 1) / 2 + 1 : i / 2 + 1;
            int cnt = Math.max(1, p / (1 << exp));
            for (int s = 1; s <= cnt; s++) {
                losers.add(new Match(i, s, null, null, false, true));
            }
        }
        out.put("losers", losers);
        List<Match> fin = new ArrayList<>();
        fin.add(new Match(1, 1, null, null, false, true));
        out.put("final", fin);
        return out;
    }
}
