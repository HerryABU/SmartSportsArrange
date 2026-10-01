package com.sports.schedule.tournament;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合赛制：先小组循环，再交叉淘汰（最常见球赛赛制，含排球）。
 *
 * <p>蛇形分组把强队分散；小组循环；各组同名次交叉对阵（A1-B2、B1-A2 …）；后续单淘汰。
 * 对应架构文档「球赛编排 → 混合赛制」。</p>
 */
public final class HybridGenerator {

    /** 交叉对阵：出线名额的占位（如 A1 vs B2）。 */
    public record CrossPair(String home, String away) {
    }

    /** 完整混合赛制结构。 */
    public record Plan(Map<String, List<String>> groups,
                       Map<String, List<RoundRobinGenerator.Match>> groupMatches,
                       List<CrossPair> crossPairs,
                       List<EliminationGenerator.Match> knockout) {
    }

    private HybridGenerator() {
    }

    /** 蛇形分组：按种子顺序 1→G、G→1 往复分配，强队分散。 */
    public static Map<String, List<String>> snakeGroup(List<String> teams, int nGroups) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (int g = 0; g < nGroups; g++) {
            groups.put(String.valueOf((char) ('A' + g)), new ArrayList<>());
        }
        List<String> names = new ArrayList<>(groups.keySet());
        for (int i = 0; i < teams.size(); i++) {
            int band = i / nGroups;
            int pos = i % nGroups;
            if (band % 2 == 1) pos = nGroups - 1 - pos;
            groups.get(names.get(pos)).add(teams.get(i));
        }
        return groups;
    }

    public static Plan plan(List<String> teams, int nGroups, int advancePerGroup, boolean doubleGroup) {
        Map<String, List<String>> groups = snakeGroup(teams, nGroups);
        Map<String, List<RoundRobinGenerator.Match>> groupMatches =
                RoundRobinGenerator.groupRoundRobin(groups, doubleGroup);

        List<String> names = new ArrayList<>(groups.keySet());
        List<CrossPair> crossPairs = new ArrayList<>();
        for (int g = 0; g + 1 < names.size(); g += 2) {
            String a = names.get(g), b = names.get(g + 1);
            for (int k = 0; k < advancePerGroup; k++) {
                if (k % 2 == 0) {
                    crossPairs.add(new CrossPair(a + (k + 1), b + (advancePerGroup - k)));
                } else {
                    crossPairs.add(new CrossPair(b + (k + 1), a + (advancePerGroup - k)));
                }
            }
        }
        List<String> placeholders = new ArrayList<>();
        for (int i = 0; i < crossPairs.size(); i++) placeholders.add("P" + (i + 1));
        List<EliminationGenerator.Match> knockout = EliminationGenerator.single(placeholders);
        return new Plan(groups, groupMatches, crossPairs, knockout);
    }
}
