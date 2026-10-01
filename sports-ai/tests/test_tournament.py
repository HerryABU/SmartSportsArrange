"""球赛赛制生成的正确性测试（纯逻辑，不依赖 torch）。

覆盖三类赛制 + 真实约束：
- 循环赛：每对交手一次、每轮每队一场、**主客场连续段受控**；
- 淘汰赛：轮空数、种子半区、**同单位回避**、双淘汰的真实轮次结构；
- 混合赛制：蛇形分组、**交叉对阵不同组相遇**；
- 适配层：赛制结构 → 可排任务（含先后依赖与队员名单）。
"""

from collections import Counter, defaultdict

from sports_ai.tournament import (
    cross_pairs, double_elimination, home_away_report, hybrid_schedule,
    round_robin, same_unit_clash_count, seed_order, single_elimination,
    structure_to_tasks, tasks_summary, volleyball_demo,
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


def test_home_away_balance_is_not_just_a_count():
    """主客场：不仅总数均衡，**连续段也必须受控**（不能出现连续 3 个主场 / 3 个客场）。"""
    teams = [f"T{i}" for i in range(1, 9)]
    sched = round_robin(teams)
    rep = home_away_report(sched)
    assert len(rep) == 8
    for t, r in rep.items():
        assert abs(r["home"] - r["away"]) <= 1, f"{t} 主客场总数偏差过大: {r}"
        assert r["maxHomeStreak"] <= 2, f"{t} 出现连续 {r['maxHomeStreak']} 个主场"
        assert r["maxAwayStreak"] <= 2, f"{t} 出现连续 {r['maxAwayStreak']} 个客场"


def test_double_round_robin_has_two_legs():
    teams = [f"T{i}" for i in range(1, 5)]
    single = round_robin(teams)
    double = round_robin(teams, double=True)
    assert len(double) == 2 * len(single)
    assert {m["leg"] for m in double} == {1, 2}
    # 第二轮主客对调：同一对队伍两次交手各自主场一次
    pair_home = defaultdict(set)
    for m in double:
        pair_home[tuple(sorted((m["home"], m["away"])))].add(m["home"])
    assert all(len(v) == 2 for v in pair_home.values()), "双循环每对队伍应各自主场一次"


def test_seed_order_standard():
    assert seed_order(8) == [1, 8, 4, 5, 2, 7, 3, 6]
    o = seed_order(8)
    assert o.index(1) < 4 <= o.index(2), "1/2 号种子应分在决赛才相遇的不同半区"


def test_single_elimination_bye_count():
    # 5 队 → 补齐 8 → 3 个轮空
    matches = single_elimination([f"T{i}" for i in range(1, 6)])
    first = [m for m in matches if m["round"] == 1]
    assert len(first) == 4
    assert sum(1 for m in first if m["bye"]) == 3


def test_single_elimination_same_unit_avoidance():
    """同一班级/年级的队伍不应在第一轮相遇。"""
    from sports_ai.tournament import seed_positions

    # 8 支队，两两同班：T1 独立、T2/T3 同班、T4/T5 同班 …
    teams = [f"T{i}" for i in range(1, 9)]
    units = [f"班{(i + 1) // 2}" for i in range(8)]

    # 先确认「不做回避」时按标准种子序确实会撞（否则这条测试没有意义）
    order = seed_positions(8)
    naive = sum(1 for k in range(0, 8, 2)
                if units[order[k] - 1] == units[order[k + 1] - 1])
    assert naive >= 1, "构造的样例本应存在同单位首轮对局"

    matches = single_elimination(teams, units=units)
    assert same_unit_clash_count(matches) == 0, "同单位回避后首轮不应再有同单位对局"
    # 队伍集合不变（只换位置，不丢队）
    first = [m for m in matches if m["round"] == 1]
    seen = {m["home"] for m in first if m["home"]} | {m["away"] for m in first if m["away"]}
    assert seen == set(teams)


def test_double_elimination_real_structure():
    """双淘汰：败者组轮次 = 2k-2，且每场标明它接收谁的败者/胜者。"""
    de = double_elimination([f"T{i}" for i in range(1, 9)])     # P=8, k=3
    assert set(de.keys()) == {"winners", "losers", "grandFinal"}
    assert max(m["round"] for m in de["losers"]) == 2 * 3 - 2, "败者组应为 4 轮"
    assert all(m.get("feedsFrom") for m in de["losers"]), "每场败者组比赛都要说明来源"
    assert de["grandFinal"][0]["resetMatch"] is True, "双败规则：总决赛可能需加赛"
    # 胜者组照常
    assert sum(1 for m in de["winners"] if m["round"] == 1) == 4


def test_hybrid_group_distribution():
    plan = hybrid_schedule([f"T{i}" for i in range(1, 9)], n_groups=2, advance_per_group=2)
    groups = plan["groups"]
    assert set(groups.keys()) == {"A", "B"}
    assert sum(len(v) for v in groups.values()) == 8
    assert groups["A"][0] == "T1" and groups["B"][0] == "T2", "蛇形分组：1/2 号种子分居两组"


def test_cross_pairs_avoid_same_group():
    """交叉对阵：同一小组的两支出线队不得在首轮淘汰赛相遇。"""
    pairs = cross_pairs(["A", "B"], advance_per_group=4)
    assert len(pairs) == 4
    for p in pairs:
        assert p["home"][0] != p["away"][0], f"同组相遇: {p}"
    # 且覆盖全部 8 支出线队
    seen = {p["home"] for p in pairs} | {p["away"] for p in pairs}
    assert seen == {"A1", "A2", "A3", "A4", "B1", "B2", "B3", "B4"}


def test_volleyball_demo_structure():
    demo = volleyball_demo([f"班级{i}" for i in range(1, 9)], n_groups=2, advance_per_group=2)
    assert demo["sport"] == "volleyball"
    assert demo["group_match_count"] == 12         # 2 组 × C(4,2)=6
    assert len(demo["cross_pairs"]) == 2
    assert demo["knockout_rounds"] >= 1


def test_structure_to_tasks():
    """适配层：赛制结构 → 可排任务，含先后依赖与队员名单（跨大类兼项冲突用）。"""
    plan = hybrid_schedule([f"T{i}" for i in range(1, 9)], n_groups=2, advance_per_group=2)
    roster = {f"T{i}": [i * 10, i * 10 + 1] for i in range(1, 9)}
    tasks = structure_to_tasks(plan, minutes_per_match=40, roster=roster)

    s = tasks_summary(tasks)
    assert s["total"] == len(tasks) > 0
    assert s["byStage"].get("group", 0) == 12, "2 组 × C(4,2) = 12 场小组赛"
    assert s["totalMinutes"] == 40 * len(tasks)
    assert s["athleteSlots"] > 0, "队员名单应被填入（用于跨大类兼项冲突检测）"

    group_keys = {t.key for t in tasks if t.stage == "group"}
    for t in tasks:
        if t.stage != "group":
            assert set(t.depends_on) == group_keys, "淘汰赛必须依赖全部小组赛（偏序）"
    # 每个任务的队员来自两队并集
    g0 = next(t for t in tasks if t.stage == "group")
    assert set(g0.athletes) == set(roster.get(g0.home, []) + roster.get(g0.away, []))
