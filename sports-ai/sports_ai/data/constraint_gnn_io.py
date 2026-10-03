"""约束分型图的 IO 契约——与 Java 端 ``ConstraintAwareGraphEncoder`` **逐位对齐**。

⚠️ **双端硬契约**（改动必须双端同步，否则 shape 不匹配 → ONNX 加载失败）

===========  ==================  ===========================================
通道 / 字段   形状                 含义
===========  ==================  ===========================================
node_feat     [1, n, 16]          节点特征（16 维，与旧模型同维）
adj_by_type   [1, T=6, n, n]      6 类约束的逐类型归一化邻接
type_mask     [1, T=6]            该实例实际存在哪些约束类型（0/1）
mask          [1, n]              1=真实节点，0=填充
===========  ==================  ===========================================

通道顺序（**必须与 Java 端 ``TYPES`` 一致**）::

    0 ATHLETE  1 POOL  2 VENUE  3 GROUP  4 GRADE  5 TIME
"""

from __future__ import annotations

from typing import Dict, List, Optional, Tuple

import numpy as np

from .features import MAX_NODES, NODE_FEAT_DIM, extract_features
from .generator import Scenario

# 约束类型顺序——与 Java ConstraintAwareGraphEncoder.TYPES 逐位对齐
CONSTRAINT_TYPES: List[str] = ["ATHLETE", "POOL", "VENUE", "GROUP", "GRADE", "TIME"]
T_IDX: Dict[str, int] = {t: i for i, t in enumerate(CONSTRAINT_TYPES)}
N_TYPES = len(CONSTRAINT_TYPES)


def _pair_key(a: int, b: int, n: int) -> Tuple[int, int]:
    return (a, b) if a <= b else (b, a)


def encode_constraint_gnn_inputs(
    scenario: Scenario,
    pad_to: Optional[int] = None,
) -> Dict[str, np.ndarray]:
    """把一个场景编码成约束分型异构图网络的输入。

    返回 ``{node_feat, adj_by_type, type_mask, mask, n}``。
    """
    units = scenario.units
    total = len(units)
    n = min(total, MAX_NODES)
    size = int(pad_to or n)

    node_feat = np.zeros((size, NODE_FEAT_DIM), dtype=np.float32)
    adj_by_type = np.zeros((N_TYPES, size, size), dtype=np.float32)
    type_mask = np.zeros(N_TYPES, dtype=np.float32)
    mask = np.zeros(size, dtype=np.float32)

    if n == 0:
        return {
            "node_feat": node_feat[None], "adj_by_type": adj_by_type[None],
            "type_mask": type_mask[None], "mask": mask[None], "n": 0,
        }

    # ---- 节点特征：复用统一口径（与 Java InstanceFeatures / ConflictGraphEncoder 同源） ----
    f = extract_features(scenario)          # [N_FEATURES]，16 维
    per_unit = _unit_node_features(scenario, n)
    node_feat[:n] = per_unit
    mask[:n] = 1.0

    # ---- 0 ATHLETE：共享运动员 ----
    by_ath: Dict[int, List[int]] = {}
    for i, u in enumerate(units[:n]):
        for aid in getattr(u, "athletes", []) or []:
            by_ath.setdefault(aid, []).append(i)
    shared: Dict[Tuple[int, int], int] = {}
    for idxs in by_ath.values():
        for x in range(len(idxs)):
            for y in range(x + 1, len(idxs)):
                k = _pair_key(idxs[x], idxs[y], n)
                shared[k] = shared.get(k, 0) + 1
    if shared:
        mx = float(max(shared.values()))
        for (a, b), c in shared.items():
            adj_by_type[T_IDX["ATHLETE"], a, b] = c / mx
            adj_by_type[T_IDX["ATHLETE"], b, a] = c / mx
        type_mask[T_IDX["ATHLETE"]] = 1.0

    # ---- 1 POOL / 3 GROUP / 4 GRADE：单元自身属性分组 ----
    _group_edges(units[:n], "pool_label", T_IDX["POOL"], adj_by_type, type_mask, n, size)
    _group_edges(units[:n], "group_key", T_IDX["GROUP"], adj_by_type, type_mask, n, size)
    _group_edges(units[:n], "grade", T_IDX["GRADE"], adj_by_type, type_mask, n, size)

    # ---- 2 VENUE：资源独占 ----
    # ⚠️ 训练侧 Scenario **没有「场地」维度**（Java 编排域有 venue），
    #    这里用 bin_key = pool#slot#window 作等价近似：bin 本身就是独占资源，
    #    语义上都是「同一物理资源不可并发」。若日后生成器补上场地，应改为按场地建边。
    by_venue: Dict[Tuple[str, int], List[int]] = {}
    for i, u in enumerate(units[:n]):
        for w in _placements_of(scenario, i):
            by_venue.setdefault((getattr(w, "bin_key", ""), int(getattr(w, "day", 0))), []).append(i)
    for idxs in by_venue.values():
        if len(idxs) < 2:
            continue
        press = min(1.0, len(idxs) / max(1, n))
        for x in range(len(idxs)):
            for y in range(x + 1, len(idxs)):
                a, b = idxs[x], idxs[y]
                adj_by_type[T_IDX["VENUE"], a, b] = max(adj_by_type[T_IDX["VENUE"], a, b], press)
                adj_by_type[T_IDX["VENUE"], b, a] = adj_by_type[T_IDX["VENUE"], a, b]
        type_mask[T_IDX["VENUE"]] = 1.0

    # ---- 5 TIME：装箱耦合（两个单元的时长和超过其公共时段容量 → 必然错开） ----
    # ⚠️ 训练侧 Unit **没有 interval 字段**（Java 编排域有），
    #    因此这里只判「装箱放不下」这一种最强信号；间隔耦合待生成器补齐后再加。
    for i in range(n):
        u = units[i]
        cap = _max_capacity(scenario, i)
        if cap <= 0:
            continue
        for j in range(i + 1, n):
            v = units[j]
            need = int(u.raw_duration) + int(v.raw_duration)
            if need > cap:
                w = min(1.0, need / cap)
                adj_by_type[T_IDX["TIME"], i, j] = max(adj_by_type[T_IDX["TIME"], i, j], w)
                adj_by_type[T_IDX["TIME"], j, i] = adj_by_type[T_IDX["TIME"], i, j]
        type_mask[T_IDX["TIME"]] = 1.0

    return {
        "node_feat": node_feat[None],
        "adj_by_type": adj_by_type[None],
        "type_mask": type_mask[None],
        "mask": mask[None],
        "n": n,
    }


def _group_edges(units, attr: str, t: int, adj_by_type, type_mask, n: int, size: int) -> None:
    buckets: Dict[str, List[int]] = {}
    for i, u in enumerate(units):
        k = getattr(u, attr, None)
        if k:
            buckets.setdefault(str(k), []).append(i)
    for idxs in buckets.values():
        if len(idxs) < 2:
            continue
        press = min(1.0, len(idxs) / max(1, n))
        for x in range(len(idxs)):
            for y in range(x + 1, len(idxs)):
                a, b = idxs[x], idxs[y]
                adj_by_type[t, a, b] = max(adj_by_type[t, a, b], press)
                adj_by_type[t, b, a] = adj_by_type[t, a, b]
        type_mask[t] = 1.0


def _placements_of(scenario: Scenario, unit_idx: int):
    """取某单元的候选时段。

    训练侧 Scenario 是「全局 placements + 每单元 bin 需求」的结构，
    没有 Java 编排域那样的 candidatePlacements；这里按「单元的池标签」取同池时段，
    并叠加单元自身只在该池内参与的隐含约束。
    """
    units = scenario.units
    if unit_idx >= len(units):
        return []
    pool = getattr(units[unit_idx], "pool_label", None)
    return [w for w in scenario.placements if getattr(w, "pool_label", None) == pool]


def _max_capacity(scenario: Scenario, unit_idx: int) -> int:
    ws = _placements_of(scenario, unit_idx)
    return max((int(getattr(w, "window_capacity", 0)) for w in ws), default=0)


def _unit_node_features(scenario: Scenario, n: int) -> np.ndarray:
    """逐单元的 16 维节点特征。

    与 Java ``ConflictGraphEncoder`` 的 16 维语义保持一致（顺序固定）：
    track / 人数归一 / 时长归一 / has_group / group_size / pool_idx / event_idx /
    log_人数 / is_final / grade_idx / 时长占比 / 冲突暴露 / event_freq / pool_share /
    is_large_unit / 顺序
    """
    units = scenario.units[:n]
    out = np.zeros((n, NODE_FEAT_DIM), dtype=np.float32)
    if not units:
        return out

    pools, groups, events, grades = set(), set(), set(), set()
    for u in units:
        if getattr(u, "pool_label", None):
            pools.add(u.pool_label)
        if getattr(u, "group_key", None):
            groups.add(u.group_key)
        if getattr(u, "event_id", None) is not None:
            events.add(u.event_id)
        if getattr(u, "grade", None):
            grades.add(u.grade)
    pool_list, grade_list = sorted(pools), sorted(grades)
    ev_list = sorted(e for e in events if e is not None)

    total_dur = float(sum(int(u.raw_duration) for u in units)) or 1.0
    max_expo = max((len(getattr(u, "athletes", []) or []) * int(u.raw_duration)
                    for u in units), default=1) or 1

    ev_count: Dict[object, int] = {}
    pool_count: Dict[object, int] = {}
    grp_count: Dict[object, int] = {}
    for u in units:
        ev_count[u.event_id] = ev_count.get(u.event_id, 0) + 1
        if getattr(u, "pool_label", None):
            pool_count[u.pool_label] = pool_count.get(u.pool_label, 0) + 1
        if getattr(u, "group_key", None):
            grp_count[u.group_key] = grp_count.get(u.group_key, 0) + 1

    for i, u in enumerate(units):
        ath = getattr(u, "athletes", []) or []
        people = len(ath)
        dur = int(u.raw_duration)
        out[i] = [
            1.0 if getattr(u, "track", False) else 0.0,
            min(people, 512) / 512.0,
            min(sum(int(x.raw_duration) for x in units), 600) / 600.0,
            1.0 if getattr(u, "group_key", None) else 0.0,
            min(grp_count.get(getattr(u, "group_key", None), 0), 16) / 16.0,
            (pool_list.index(u.pool_label) / max(1, len(pool_list) - 1)) if getattr(u, "pool_label", None) in pool_list else 0.0,
            (ev_list.index(u.event_id) / max(1, len(ev_list) - 1)) if getattr(u, "event_id", None) in ev_list else 0.0,
            np.log1p(people) / np.log(513),
            0.0,   # is_final：训练场景统一按单轮处理
            (grade_list.index(u.grade) / max(1, len(grade_list) - 1)) if getattr(u, "grade", None) in grade_list else 0.0,
            dur / total_dur,
            (people * dur) / max_expo,
            ev_count.get(u.event_id, 0) / max(1, n),
            (pool_count.get(getattr(u, "pool_label", None), 0) / max(1, n)) if getattr(u, "pool_label", None) else 0.0,
            1.0 if dur >= 300 else 0.0,
            (i / max(1, n - 1)) if n > 1 else 0.0,
        ]
    return out
