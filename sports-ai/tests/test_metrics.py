"""加权代价口径的契约测试。

要钉住三件事：

1. **权重表** —— 它是双端（Python 评测 / Java `PlanCost`）共用的唯一尺子，
   数字一变，两边的比较结论就不可比了，而结果看起来仍然「合理」（最难查）。
2. **工期三态** —— `x` 天 / `0` 不限 / `-1` 尽可能减少。
   `-1` 若被当成「上限 = -1」，就会把「多占一天」判成违规，与语义正好相反。
3. **比较判据** —— `is_better` 的主判据必须是加权代价，tie-break 只是为了让结果可复现。
"""

from __future__ import annotations

import math

from sports_ai.metrics import (
    COMPONENTS,
    WEIGHTS,
    days_over,
    is_better,
    weighted_breakdown,
    weighted_cost,
)


def test_weights_are_pinned_to_python_and_java():
    """权重数字必须与 Java `PlanCost` 的常量逐位一致。"""
    assert WEIGHTS["unplaced"] == 1000.0
    assert WEIGHTS["athlete_clash"] == 500.0
    assert WEIGHTS["capacity_overflow"] == 500.0
    assert WEIGHTS["lane_clash"] == 50.0
    assert WEIGHTS["frag_blocks"] == 10.0
    assert WEIGHTS["days_over"] == 20.0
    assert set(WEIGHTS) == set(COMPONENTS), "分量集合与规范顺序表必须一致"


def test_unplaced_and_illegality_outweigh_soft_quality():
    """「能不能排 / 合不合法」必须远重于「排得好不好」。

    量级感受（数量级而非「无条件占优」，见下一条用例的说明）：

    * 1 个未排 = 1000；
    * 「软指标」在同一量级下：碎块 20 处(200) + 道次撞 2(100) + 超期 1 天(20) = 320 < 1000。
    """
    soft_only = weighted_cost({"frag_blocks": 20, "lane_clash": 2, "days_over": 1})
    assert soft_only == 320.0
    assert weighted_cost({"unplaced": 1}) > soft_only


def test_overflow_is_counted_in_minutes_not_in_events():
    """容量超占按**分钟**计价（沿用原评测口径），所以量级可以很大。

    ⚠️ 因此「1 个未排 > 任意非法量」是**不成立**的：超 60 分钟 = 30000 分，
    远大于一个未排单元。这是刻意的 —— 非法方案**不可交付**，
    而「少排一个单元」至少还能交付一个合法的部分方案。
    这条用例的存在就是为了防止后人按「未排应当无条件占优」的直觉去改权重。
    """
    assert weighted_cost({"capacity_overflow": 60}) == 30000.0
    illegal = {"unplaced": 0, "capacity_overflow": 60}
    incomplete = {"unplaced": 1}
    assert weighted_cost(illegal) > weighted_cost(incomplete)


def test_days_over_three_states():
    """工期三态：固定 x 天会超限；0（不限）与 -1（尽可能减少）都不算超限。"""
    assert days_over(4, 3) == 1.0
    assert days_over(3, 3) == 0.0
    assert days_over(5, 0) == 0.0, "0 = 不限，没有上限就谈不上超限"
    assert days_over(5, -1) == 0.0, "-1 = 尽可能减少，不是上限；当成上限会与语义相反"
    assert days_over(None, 3) == 0.0
    assert days_over(4, None) == 0.0


def test_days_over_weighted_into_total():
    """超限一天 = 20 分；未超限不产生任何分数。"""
    assert weighted_cost({"days_used": 4, "days_limit": 3}) == 20.0
    assert weighted_cost({"days_used": 4, "days_limit": 0}) == 0.0
    assert weighted_cost({"days_used": 4, "days_limit": 4}) == 0.0


def test_breakdown_sums_to_total():
    m = {"unplaced": 1, "athlete_clash": 1, "capacity_overflow": 30,
         "lane_clash": 2, "frag_blocks": 3, "days_over": 1}
    bd = weighted_breakdown(m)
    assert math.isclose(sum(bd.values()), weighted_cost(m)), "分项之和必须等于总代价"
    assert math.isclose(bd["unplaced"], 1000.0)
    assert math.isclose(bd["capacity_overflow"], 15000.0)


def test_missing_components_count_as_zero():
    """缺项 = 该分量在这一层不适用，按 0 计 —— 不是「未知」也不是报错。"""
    assert weighted_cost({"unplaced": 2}) == 2000.0
    assert weighted_cost({}) == 0.0


def test_is_better_uses_weighted_cost_not_raw_unplaced():
    """主判据是加权代价：未排相同、但碎块更少的一方胜出（这正是「换口径」的意义）。"""
    a = {"unplaced": 1, "frag_blocks": 5}      # 1000 + 50 = 1050
    b = {"unplaced": 1, "frag_blocks": 0}      # 1000
    assert is_better(b, a)
    assert not is_better(a, b)

    # 未排少一个（−1000）足以压过大量碎块（每处 −10）
    c = {"unplaced": 1, "frag_blocks": 0}      # 1000
    d = {"unplaced": 0, "frag_blocks": 50}     # 500
    assert is_better(d, c)


def test_tie_break_is_deterministic():
    """同分时用未排数做 tie-break（仅为了让结果可复现，不是第二套口径）。"""
    a = {"unplaced": 1, "frag_blocks": 0}
    b = {"unplaced": 1, "frag_blocks": 0}
    assert not is_better(a, b) and not is_better(b, a), "完全同分 → 互不优于，顺序稳定"
