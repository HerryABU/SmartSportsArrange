"""球赛赛制生成的正确性测试（纯逻辑，不依赖 torch）。"""

from collections import Counter, defaultdict

from sports_ai.tournament import (
    double_elimination, hybrid_schedule, round_robin, seed_order,
    single_elimination, volleyball_demo,
)


def test_round_robin_each_pair_once():
    teams = [f"T{i}" for i in range(1, 7)]
    sched = round_robin(teams)
    pairs = Counter(tuple(sorted((m["home"], m["away"]))) for m in sched)
    n = len(teams)
    assert len(sched) == n * (n - 1) // 2
    assert all(v == 1 for v in pairs.values()), "每对队伍恰好交手一次"


def test_round_robin_each_team_once_per_round():
    # 偶数队每轮全员出战；奇数队每轮有 1 队轮空（少 1 人）
    for n_teams, expected in [(8, 8), (7, 6)]:
        teams = [f"T{i}" for i in range(1, n_teams + 1)]
        sched = round_robin(teams)
        per_round = defaultdict(list)
        for m in sched:
            per_round[m["round"]] += [m["home"], m["away"]]
        for r, players in per_round.items():
            assert len(players) == len(set(players)) == expected, f"第 {r} 轮出场人数异常"
            assert set(players) <= set(teams)


def test_double_round_robin_has_two_legs():
    teams = [f"T{i}" for i in range(1, 5)]
    single = round_robin(teams)
    double = round_robin(teams, double=True)
    assert len(double) == 2 * len(single)
    assert {m["leg"] for m in double} == {1, 2}


def test_seed_order_standard():
    assert seed_order(8) == [1, 8, 4, 5, 2, 7, 3, 6]
    # 1、2 号种子分居两半区（位置 0 与位置 4）
    o = seed_order(8)
    assert o.index(1) < 4 <= o.index(2), "1/2 号种子应分在决赛才相遇的不同半区"


def test_single_elimination_bye_count():
    # 5 队 → 补齐 8 → 3 个轮空
    matches = single_elimination([f"T{i}" for i in range(1, 6)])
    first = [m for m in matches if m["round"] == 1]
    assert len(first) == 4
    assert sum(1 for m in first if m["bye"]) == 3


def test_hybrid_group_distribution():
    plan = hybrid_schedule([f"T{i}" for i in range(1, 9)], n_groups=2, advance_per_group=2)
    groups = plan["groups"]
    assert set(groups.keys()) == {"A", "B"}
    assert sum(len(v) for v in groups.values()) == 8
    # 蛇形分组：1 号与 2 号种子分在不同组
    assert groups["A"][0] == "T1" and groups["B"][0] == "T2"


def test_volleyball_demo_structure():
    demo = volleyball_demo([f"班级{i}" for i in range(1, 9)], n_groups=2, advance_per_group=2)
    assert demo["sport"] == "volleyball"
    assert demo["group_match_count"] == 12         # 2 组 × C(4,2)=6
    assert len(demo["cross_pairs"]) == 2
    assert demo["knockout_rounds"] >= 1


def test_double_elimination_structure():
    de = double_elimination([f"T{i}" for i in range(1, 5)])
    assert "winners" in de and "losers" in de and "final" in de
    assert len(de["final"]) == 1
