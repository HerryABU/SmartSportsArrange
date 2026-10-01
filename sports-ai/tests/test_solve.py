"""求解层测试：拆批 / 下界 / 尽量可解 / 不可解冲突报告。

直接以「地狱级场景」（3 年级 × 8 班 × 30 人 = 720 人）为夹具，断言真实规模下的行为：
- 3 天（真实上限）必须 **100% 排下且零冲突**；
- 2 天（容量紧张）必须 **如实报告**未排清单、容量缺口、团下界与建议动作，
  而不是悄悄给出一个「看起来可行」的方案。
"""

from __future__ import annotations

import math

from sports_ai.data.hell import generate_hell
from sports_ai.solve import analyze_bounds, build_report, expand_heats, schedule


def test_expand_heats_covers_athletes_once():
    """拆批必须「不重不漏」：各组次运动员互不相交，并集等于原单元名单。"""
    meet = generate_hell(seed=20260918, days=3)
    tasks = expand_heats(meet.scenario.units)
    by_unit = {}
    for t in tasks:
        by_unit.setdefault(t.unit_index, []).append(t)
    for ui, group in by_unit.items():
        u = meet.scenario.units[ui]
        merged = [a for t in group for a in t.athletes]
        assert len(merged) == len(set(merged)) == len(u.athletes), "组次之间不得重复覆盖运动员"
        assert set(merged) == set(u.athletes), "组次并集必须等于单元全部报名者"
        assert all(t.duration > 0 for t in group)
        # 组数 = ceil(人数 / 每批容量)
        if u.heat_capacity:
            assert len(group) == math.ceil(len(u.athletes) / u.heat_capacity)


def test_bounds_detect_capacity_shortfall_and_min_days():
    """2 天时径赛容量必须被判定为不足；最少天数按容量算应为 3 天。"""
    meet = generate_hell(seed=20260918, days=2)
    b = analyze_bounds(meet.scenario.units, meet.scenario.placements)
    assert b["pools"]["径赛"]["shortfall"] > 0, "2 天径赛容量应当不足"
    assert b["pools"]["径赛"]["feasibleByCapacity"] is False
    assert b["pools"]["田赛"]["shortfall"] == 0, "2 天田赛容量应当够"
    assert b["minDaysByCapacity"] == 3, "按容量反推最少需要 3 天"
    assert b["availablePeriods"] == 4 and b["cliqueLowerBound"] > 4, \
        "2 天仅 4 个时段，而冲突图最大团为 5 → 结构性不可解"
    assert b["cliqueFeasible"] is False
    assert b["oversizedCount"] > 0, "存在全部时长超过单时段容量、必须拆批的单元"


def test_schedule_three_days_fully_feasible():
    """3 天（真实上限）必须完全可解：全部组次排下且零兼项重叠。"""
    meet = generate_hell(seed=20260918, days=3)
    res = schedule(meet.scenario.units, meet.scenario.placements, rounds=12)
    assert res.tasks_total > 100, "地狱场景应有上百个组次"
    assert res.placed_tasks == res.tasks_total, f"3 天应当 100% 可解（未排 {res.tasks_total - res.placed_tasks}）"
    assert res.conflict_multiplicity == 0, "完全可解时不应有兼项重叠"
    assert res.feasible is True


def test_schedule_two_days_reports_what_cannot_fit():
    """2 天排不满时必须如实报告：有未排组次、有容量缺口、可行标志为 False。"""
    meet = generate_hell(seed=20260918, days=2)
    res = schedule(meet.scenario.units, meet.scenario.placements, rounds=12)
    assert res.placed_tasks < res.tasks_total, "2 天存在硬缺口，不应全部排下"
    assert res.unplaced_athlete_slots > 0
    assert res.unplaced_units, "未排清单必须可定位到具体单元"
    assert res.feasible is False
    # 高排下率：即便排不满也要「尽量可解」
    assert res.placed_ratio > 0.9, f"应尽量排下（实际 {res.placed_ratio:.1%}）"


def test_report_schema_and_actions():
    """报告 schema 稳定，且能给出可执行动作（加天 / 加并发位 / 取消报名）。"""
    meet = generate_hell(seed=20260918, days=2)
    b = analyze_bounds(meet.scenario.units, meet.scenario.placements)
    res = schedule(meet.scenario.units, meet.scenario.placements, rounds=8)
    rep = build_report("2 天情形", meet, res, b)

    assert rep["schema"] == "sports-ai/infeasibility-report@1"
    assert rep["feasible"] is False
    for key in ("scene", "summary", "bounds", "unplacedTasks", "conflicts", "actions"):
        assert key in rep, f"报告缺少字段 {key}"
    assert rep["scene"]["athletes"] == 720
    assert rep["summary"]["unplaced"] == res.tasks_total - res.placed_tasks

    kinds = {c["type"] for c in rep["conflicts"]}
    assert "capacity_shortfall" in kinds
    assert "clique_exceeds_periods" in kinds
    assert "oversized_unit" in kinds
    assert any(k.startswith("athlete_clash") for k in kinds)

    actions = {a["action"] for a in rep["actions"]}
    assert "extend_days" in actions
    assert "add_lanes" in actions
    assert "cancel_entry" in actions


def test_report_feasible_when_three_days():
    """3 天情形报告应为可行、无建议动作。"""
    meet = generate_hell(seed=20260918, days=3)
    b = analyze_bounds(meet.scenario.units, meet.scenario.placements)
    res = schedule(meet.scenario.units, meet.scenario.placements, rounds=8)
    rep = build_report("3 天情形", meet, res, b)
    assert rep["feasible"] is True
    assert rep["unplacedTasks"] == []
    assert rep["actions"] == []
