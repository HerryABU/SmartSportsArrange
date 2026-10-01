package com.sports.schedule.tournament;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 循环赛赛程生成：圆桌轮转法（Circle Method）。
 *
 * <p>N 支队伍，每队与其余 N-1 队各打一场，共 N(N-1)/2 场；N 为奇数时补一个「轮空位」。
 * 固定第 0 位、其余绕圈轮转，保证每队每轮恰好一场、与其余每队恰好交手一次。
 * 主客场用 (轮次+配对的奇偶) 交替分配以避免某队连续主场；双循环时第二轮主客对调。</p>
 */
public final class RoundRobinGenerator {

    /** 一场比赛。 */
    public record Match(int round, String home, String away, int leg) {
    }

    private RoundRobinGenerator() {
    }

    /** 单/双循环赛程表。 */
    public static List<Match> generate(List<String> teams, boolean doubleLeg, boolean balanceHomeAway) {
        List<Match> out = new ArrayList<>();
        if (teams == null || teams.size() < 2) return out;

        List<String> cur = new ArrayList<>(teams);
        if (cur.size() % 2 == 1) cur.add(null);          // 轮空
        int n = cur.size();
        List<List<String[]>> rounds = new ArrayList<>();

        for (int r = 0; r < n - 1; r++) {
            List<String[]> pairs = new ArrayList<>();
            for (int i = 0; i < n / 2; i++) {
                String a = cur.get(i);
                String b = cur.get(n - 1 - i);
                if (a == null || b == null) continue;
                if (balanceHomeAway && (r + i) % 2 == 1) {
                    String t = a; a = b; b = t;
                }
                pairs.add(new String[]{a, b});
            }
            rounds.add(pairs);
            // 固定第 0 位，其余右旋
            List<String> next = new ArrayList<>();
            next.add(cur.get(0));
            next.add(cur.get(n - 1));
            next.addAll(cur.subList(1, n - 1));
            cur = next;
        }

        for (int r = 0; r < rounds.size(); r++) {
            for (String[] p : rounds.get(r)) {
                out.add(new Match(r + 1, p[0], p[1], 1));
            }
        }
        if (doubleLeg) {
            int base = rounds.size();
            for (int r = 0; r < rounds.size(); r++) {
                for (String[] p : rounds.get(r)) {
                    out.add(new Match(base + r + 1, p[1], p[0], 2));
                }
            }
        }
        return out;
    }

    /** 分组循环：组内各自单/双循环。 */
    public static Map<String, List<Match>> groupRoundRobin(Map<String, List<String>> groups, boolean doubleLeg) {
        Map<String, List<Match>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : groups.entrySet()) {
            out.put(e.getKey(), generate(e.getValue(), doubleLeg, true));
        }
        return out;
    }
}
