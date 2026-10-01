"""可解性下界分析：在求解之前先算清楚「数学上够不够」。

下界分析的价值在于**区分三种「排不下」**，它们的处置方式完全不同：

1. **容量缺口**（capacity）：某池的总需求 > 总供给 → 加天数 / 加并发位 / 减项目，
   与算法无关，是资源配置问题；
2. **超大单元**（oversized）：单个单元的全部时长 > 任一时段容量 → 必须拆批，
   否则它在数据结构上就放不进任何位置（地狱场景的真正根因）；
3. **团下界**（clique）：冲突图的最大团表示「两两互相冲突、必须错开到不同时段」的
   单元集合，若团的大小 > 可用时段数 → 结构性不可解（与容量无关，加场地也没用，
   只能拆组次或取消报名）。

这些结论会在最终报告里以 ``bounds`` 字段输出给上层程序。
"""

from __future__ import annotations

import math
from typing import Dict, List, Optional, Tuple

import networkx as nx

from ..data.generator import Placement, Unit
from .heats import expand_heats


def _pools(placements: List[Placement]) -> List[str]:
    return sorted({p.pool_label for p in placements})


def _periods(placements: List[Placement]) -> List[int]:
    return sorted({p.window_idx for p in placements})


def _supply(placements: List[Placement], pool: str) -> int:
    """某池的总供给（去重并发位的容量之和）。"""
    cap: Dict[str, int] = {}
    for p in placements:
        if p.pool_label == pool:
            cap[p.bin_key] = p.window_capacity
    return sum(cap.values())


def _max_window(placements: List[Placement], pool: str) -> int:
    caps = [p.window_capacity for p in placements if p.pool_label == pool]
    return max(caps) if caps else 0


def _clique_lower_bound(units: List[Unit]) -> int:
    """冲突图最大团大小 = 必须错开到不同时段的最少时段数（团内两两冲突）。"""
    tasks = expand_heats(units)
    # 节点 = 单元（而非组次）：团刻画的是「项目之间」的结构性冲突
    n = len(units)
    if n < 2:
        return n
    g = nx.Graph()
    g.add_nodes_from(range(n))
    athlete_units: Dict[int, List[int]] = {}
    for i, u in enumerate(units):
        for a in u.athletes:
            athlete_units.setdefault(a, []).append(i)
    for idxs in athlete_units.values():
        for x in range(len(idxs)):
            for y in range(x + 1, len(idxs)):
                g.add_edge(idxs[x], idxs[y])
    try:
        best = nx.algorithms.clique.graph_clique_number(g)
    except Exception:  # noqa: BLE001 - 精确团过慢时退化为启发式
        best = max((len(c) for c in nx.find_cliques(g)), default=1)
    del tasks
    return int(best)


def analyze_bounds(units: List[Unit], placements: List[Placement]) -> Dict[str, object]:
    """产出可解性下界分析（容量 / 天数 / 超大单元 / 团）。"""
    pools = _pools(placements)
    periods = _periods(placements)
    days = len({p.day for p in placements})
    periods_per_day = max(1, len(periods) // max(1, days))

    per_pool: Dict[str, Dict[str, object]] = {}
    min_days = 1
    for pool in pools:
        demand = sum(u.raw_duration for u in units if u.pool_label == pool)
        supply = _supply(placements, pool)
        lanes = max(1, len({p.slot_idx for p in placements if p.pool_label == pool}))
        # 每天供给 = 并发位 × 单日时段容量之和。
        # ⚠️ 只取 day=1 的时段——window_idx 是跨天唯一的，若直接聚合会把多天容量累加进来，
        # 导致「最少天数」被严重低估（曾算出 3 天场景「最少 1 天」）。
        day_caps = {p.window_idx: p.window_capacity
                    for p in placements if p.pool_label == pool and p.day == 1}
        per_day = lanes * sum(day_caps.values())
        need = math.ceil(demand / per_day) if per_day else 0
        min_days = max(min_days, need)
        per_pool[pool] = {
            "demand": demand,
            "supply": supply,
            "shortfall": max(0, demand - supply),
            "lanes": lanes,
            "periodsPerDay": periods_per_day,
            "minDays": need,
            "feasibleByCapacity": demand <= supply,
        }

    # 超大单元：全部时长超过「单时段容量」的单元 —— 不拆批就放不进去
    oversized = []
    for u in units:
        cap = _max_window(placements, u.pool_label)
        if cap and u.raw_duration > cap:
            heats = max(1, math.ceil(u.raw_duration / cap))
            oversized.append({
                "unit": u.key,
                "duration": u.raw_duration,
                "maxWindowCapacity": cap,
                "minSplits": heats,
            })

    clique = _clique_lower_bound(units)
    available_periods = len(periods)

    return {
        "days": days,
        "periodsPerDay": periods_per_day,
        "availablePeriods": available_periods,
        "pools": per_pool,
        "minDaysByCapacity": min_days,
        "oversizedUnits": oversized,
        "oversizedCount": len(oversized),
        "cliqueLowerBound": clique,
        "cliqueFeasible": clique <= available_periods,
        "capacityFeasible": all(v["feasibleByCapacity"] for v in per_pool.values()),
    }
