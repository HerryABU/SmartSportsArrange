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
        List<Match> out = new ArrayList<>();
        if (teams == null || teams.size() < 2) return out;
        int n = teams.size();
        int p = Seeding.nextPow2(n);
        int[] order = Seeding.seedPositions(n);          // 位置 → 种子号

        Map<Integer, String> byRank = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) byRank.put(i + 1, teams.get(i));

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

    /** 双淘汰：结构与占位（胜者组 + 败者组 + 总决赛）。 */
    public static Map<String, List<Match>> doubleElim(List<String> teams) {
        Map<String, List<Match>> out = new LinkedHashMap<>();
        out.put("winners", single(teams));
        int n = teams == null ? 0 : teams.size();
        int loserRounds = Math.max(1, 2 * (32 - Integer.numberOfLeadingZeros(Math.max(1, n))) - 1);
        List<Match> losers = new ArrayList<>();
        for (int r = 1; r <= loserRounds; r++) {
            for (int s = 1; s <= Math.max(1, n / 2); s++) {
                losers.add(new Match(r, s, null, null, false, true));
            }
        }
        out.put("losers", losers);
        List<Match> fin = new ArrayList<>();
        fin.add(new Match(1, 1, null, null, false, true));
        out.put("final", fin);
        return out;
    }
}
