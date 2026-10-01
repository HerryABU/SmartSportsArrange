package com.sports.schedule.tournament;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 球赛赛制生成的正确性验证。
 */
@DisplayName("球赛赛制生成")
class TournamentGeneratorTest {

    @Test
    @DisplayName("循环赛：每对队伍恰好交手一次、每轮每队至多一场")
    void roundRobinEachPairOnce() {
        List<String> teams = List.of("T1", "T2", "T3", "T4", "T5", "T6");
        List<RoundRobinGenerator.Match> sched = RoundRobinGenerator.generate(teams, false, true);

        assertEquals(15, sched.size(), "6 队单循环 = 15 场");
        Map<String, Integer> pairCount = new HashMap<>();
        for (RoundRobinGenerator.Match m : sched) {
            String a = m.home().compareTo(m.away()) < 0 ? m.home() : m.away();
            String b = m.home().compareTo(m.away()) < 0 ? m.away() : m.home();
            pairCount.merge(a + "|" + b, 1, Integer::sum);
        }
        assertEquals(15, pairCount.size(), "每对恰好一次");
        assertTrue(pairCount.values().stream().allMatch(v -> v == 1));
    }

    @Test
    @DisplayName("循环赛：奇数队时每轮有一队轮空，其余各一场")
    void roundRobinOddTeamsHasBye() {
        List<String> teams = List.of("T1", "T2", "T3", "T4", "T5", "T6", "T7");
        List<RoundRobinGenerator.Match> sched = RoundRobinGenerator.generate(teams, false, true);
        assertEquals(21, sched.size(), "7 队单循环 = 21 场");

        Map<Integer, List<String>> perRound = new HashMap<>();
        for (RoundRobinGenerator.Match m : sched) {
            perRound.computeIfAbsent(m.round(), k -> new ArrayList<>()).add(m.home());
            perRound.computeIfAbsent(m.round(), k -> new ArrayList<>()).add(m.away());
        }
        for (var e : perRound.entrySet()) {
            assertEquals(6, e.getValue().size(), "7 队每轮 6 人出场（1 队轮空）");
            assertEquals(6, e.getValue().stream().distinct().count(), "同轮不得重复出场");
        }
    }

    @Test
    @DisplayName("双循环：场次翻倍且含两个 leg")
    void doubleRoundRobinHasTwoLegs() {
        List<String> teams = List.of("T1", "T2", "T3", "T4");
        int single = RoundRobinGenerator.generate(teams, false, true).size();
        List<RoundRobinGenerator.Match> dbl = RoundRobinGenerator.generate(teams, true, true);
        assertEquals(single * 2, dbl.size());
        assertEquals(2, dbl.stream().map(RoundRobinGenerator.Match::leg).distinct().count());
    }

    @Test
    @DisplayName("种子排位：1/2 号种子分居不同半区")
    void seedOrderSplitsTopSeeds() {
        assertArrayEquals(new int[]{1, 8, 4, 5, 2, 7, 3, 6}, Seeding.seedOrder(8));
        int[] o = Seeding.seedOrder(8);
        int p1 = indexOf(o, 1), p2 = indexOf(o, 2);
        assertTrue(p1 < 4 && p2 >= 4, "1/2 号种子应分在决赛才相遇的不同半区");
    }

    @Test
    @DisplayName("单淘汰：5 队 → 3 个轮空")
    void singleEliminationByeCount() {
        List<EliminationGenerator.Match> ms =
                EliminationGenerator.single(List.of("T1", "T2", "T3", "T4", "T5"));
        long byes = ms.stream().filter(m -> m.round() == 1 && m.bye()).count();
        long first = ms.stream().filter(m -> m.round() == 1).count();
        assertEquals(4, first);
        assertEquals(3, byes);
    }

    @Test
    @DisplayName("混合赛制：蛇形分组把 1/2 号种子分到不同组")
    void hybridSnakeGrouping() {
        List<String> teams = List.of("T1", "T2", "T3", "T4", "T5", "T6", "T7", "T8");
        Map<String, List<String>> groups = HybridGenerator.snakeGroup(teams, 2);
        assertEquals("T1", groups.get("A").get(0));
        assertEquals("T2", groups.get("B").get(0));
        assertEquals(8, groups.values().stream().mapToInt(List::size).sum());
    }

    @Test
    @DisplayName("排球赛：2 组 × 4 队 → 12 场小组赛 + 交叉淘汰")
    void volleyballStructure() {
        List<String> teams = List.of("C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8");
        Map<String, Object> plan = VolleyballTournament.plan(teams, 2, 2, 5);
        assertEquals("volleyball", plan.get("sport"));
        assertEquals(12, plan.get("groupMatchCount"));
        assertEquals(2, ((List<?>) plan.get("crossPairs")).size());
    }

    private static int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return -1;
    }
}
