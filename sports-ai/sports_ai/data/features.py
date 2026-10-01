"""实例特征提取（兼项共现统计层）——AI 与 Java 端共享的**数据契约**。

16 维实例特征向量的定义是训练侧（本文件）与推理侧
（``com.sports.schedule.ai.InstanceFeatures``）之间的**唯一权威契约**：
两边必须用**完全相同的口径**计算同一个 16 维向量，否则训练好的 ONNX 模型
喂给 Java 推理时语义就对不上。

设计原则：
- 特征全部是「原始可计算量」，**不做归一化**。归一化在导出 ONNX 时作为模型的第一层
  固化进去（见 ``export_onnx.py``），因此 Java 端只喂原始特征即可，无需复刻归一化常数。
- 冲突图口径与 ``EventCooccurrenceService`` 一致：顶点 = 单元（项目×年级），
  边 = 同一运动员同时出现在两个单元。
"""

from __future__ import annotations

import math
from typing import List, Tuple

import networkx as nx

from .generator import Scenario, Unit

# ---------------------------------------------------------------------------
# 特征契约：16 维，顺序固定（Java 端必须逐位对齐）
# ---------------------------------------------------------------------------
FEATURE_NAMES: List[str] = [
    "unit_count",                # 0  待排单元数
    "demand_minutes",            # 1  总需求时长（rawDuration 之和）
    "supply_minutes",            # 2  总供给时长（去重并发位容量之和）
    "tension_ratio",             # 3  紧张度 = demand / supply（>1 容量客观不足）
    "multi_event_athlete_ratio", # 4  兼项运动员占比（报 ≥2 项 ÷ 报名人数）
    "athlete_count",             # 5  参与运动员总数（去重）
    "conflict_edges",            # 6  兼项冲突边数（单元对共享运动员）
    "conflict_density",          # 7  冲突图密度 = 2E / N(N-1)
    "conflict_components",       # 8  冲突图连通分量数
    "max_degree",                # 9  冲突图最大度
    "avg_degree",                # 10 冲突图平均度
    "pool_count",                # 11 并发池数
    "day_count",                 # 12 比赛天数
    "avg_duration",              # 13 平均单元时长
    "duration_cv",               # 14 单元时长变异系数（std/mean）
    "group_count",               # 15 同组同时开赛的组数
]

N_FEATURES = len(FEATURE_NAMES)

# GNN 契约（固定 shape，便于 ONNX 导出与 Java 端对齐）
MAX_NODES = 256       # 最大单元数（不足补齐、超出截断）
NODE_FEAT_DIM = 8     # 每个节点的输入特征维数


def _build_conflict_graph(units: List[Unit]) -> Tuple[int, int, int, int, int]:
    """构建冲突图并返回 (边数, 密度, 连通分量数, 最大度, 平均度)。

    边 = 两个单元共享至少一名运动员（无向）。用运动员 → 单元集合求交，
    比两两单元 O(N^2) 求交更贴近真实统计口径。
    """
    n = len(units)
    if n < 2:
        return 0, 0.0, n, 0, 0.0

    # 运动员 → 所在单元下标
    athlete_units: dict[int, List[int]] = {}
    for i, u in enumerate(units):
        for a in u.athletes:
            athlete_units.setdefault(a, []).append(i)

    # 去重收集冲突边
    edges = set()
    for idxs in athlete_units.values():
        for x in range(len(idxs)):
            for y in range(x + 1, len(idxs)):
                a, b = idxs[x], idxs[y]
                if a > b:
                    a, b = b, a
                edges.add((a, b))

    edge_count = len(edges)
    density = (2.0 * edge_count) / (n * (n - 1))

    g = nx.Graph()
    g.add_nodes_from(range(n))
    g.add_edges_from(edges)
    components = nx.number_connected_components(g) if n > 0 else 0
    degrees = [d for _, d in g.degree()]
    max_deg = max(degrees) if degrees else 0
    avg_deg = sum(degrees) / n if n else 0.0

    return edge_count, density, components, max_deg, avg_deg


def extract_features(scenario: Scenario) -> List[float]:
    """从编排实例提取 16 维特征向量（顺序见 FEATURE_NAMES）。"""
    units = scenario.units
    placements = scenario.placements

    unit_count = len(units)
    demand = sum(u.raw_duration for u in units if u.raw_duration > 0)

    # 供给：去重并发位（bin）的窗口容量之和
    bin_cap: dict[str, int] = {}
    for p in placements:
        bin_cap.setdefault(p.bin_key, p.window_capacity)
    supply = sum(bin_cap.values())

    tension = (demand / supply) if supply > 0 else 0.0

    # 兼项运动员占比
    athlete_units: dict[int, int] = {}
    for u in units:
        for a in u.athletes:
            athlete_units[a] = athlete_units.get(a, 0) + 1
    athlete_count = len(athlete_units)
    multi = sum(1 for c in athlete_units.values() if c >= 2)
    multi_ratio = (multi / athlete_count) if athlete_count else 0.0

    edges, density, components, max_deg, avg_deg = _build_conflict_graph(units)

    pool_count = len({u.pool_label for u in units})
    day_count = len({p.day for p in placements})

    durs = [u.raw_duration for u in units if u.raw_duration > 0]
    avg_duration = (sum(durs) / len(durs)) if durs else 0.0
    if durs and avg_duration > 0:
        var = sum((d - avg_duration) ** 2 for d in durs) / len(durs)
        duration_cv = math.sqrt(var) / avg_duration
    else:
        duration_cv = 0.0

    group_count = len({u.group_key for u in units if u.group_key})

    return [
        float(unit_count),
        float(demand),
        float(supply),
        round(tension, 4),
        round(multi_ratio, 4),
        float(athlete_count),
        float(edges),
        round(density, 4),
        float(components),
        float(max_deg),
        round(avg_deg, 4),
        float(pool_count),
        float(day_count),
        round(avg_duration, 2),
        round(duration_cv, 4),
        float(group_count),
    ]
