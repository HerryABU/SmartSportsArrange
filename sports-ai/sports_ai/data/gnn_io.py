"""冲突簇 GNN 的输入编码（固定 shape 契约，与 Java 端 ``ConflictGraphEncoder`` 对齐）。

把编排实例编码成 GNN 的三路输入：
- ``node_feat``: [1, MAX_NODES, NODE_FEAT_DIM]  节点特征
- ``adj``:       [1, MAX_NODES, MAX_NODES]       二值邻接（无自环、无归一化）
- ``mask``:      [1, MAX_NODES]                   1=真实节点，0=填充

以及训练标签 ``degree_label``: [MAX_NODES]，每节点归一化度数中心度（着色优先级）。

节点特征（8 维，全部由构造保证落在 [0,1]，无需再归一化）：
0 track               径赛=1/田赛=0
1 athlete_count_norm  min(运动员数,64)/64
2 duration_norm       min(rawDuration,300)/300
3 has_group           同组同时开赛=1
4 group_size_norm     min(同组单元数,8)/8
5 pool_idx_norm       并发池序号 / (池数-1)
6 event_idx_norm      项目序号 / (项目数-1)
7 log_athlete_norm    log1p(运动员数) / log(65)
"""

from __future__ import annotations

import math
from typing import Dict, List, Tuple

import numpy as np

from .features import MAX_NODES, NODE_FEAT_DIM
from .generator import Scenario, Unit


def encode_gnn_inputs(
    scenario: Scenario,
) -> Tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """返回 (node_feat, adj, mask, degree_label)，shape 均为固定契约。"""
    units: List[Unit] = scenario.units
    n = min(len(units), MAX_NODES)

    node_feat = np.zeros((MAX_NODES, NODE_FEAT_DIM), dtype=np.float32)
    adj = np.zeros((MAX_NODES, MAX_NODES), dtype=np.float32)
    mask = np.zeros((MAX_NODES,), dtype=np.float32)
    degree = [0] * n

    # 并发池序号
    pools = sorted({u.pool_label for u in units})
    pool_idx: Dict[str, int] = {p: i for i, p in enumerate(pools)}
    pool_count = max(1, len(pools))

    # 项目序号
    event_ids = sorted({u.event_id for u in units})
    event_idx: Dict[int, int] = {e: i for i, e in enumerate(event_ids)}
    n_events = max(1, len(event_ids))

    # 同组单元数
    group_size: Dict[str, int] = {}
    for u in units:
        if u.group_key:
            group_size[u.group_key] = group_size.get(u.group_key, 0) + 1

    # 运动员 → 单元下标（求冲突边 + 度数）
    athlete_units: Dict[int, List[int]] = {}
    for i, u in enumerate(units[:n]):
        for a in u.athletes:
            athlete_units.setdefault(a, []).append(i)

    # 冲突边（去重）→ 邻接 + 度数。口径与 features.py 的 conflict_edges 严格一致：
    # 同一对单元即使被多名运动员共享，也只算一条边（否则度数中心度会虚高）。
    edge_set = set()
    for idxs in athlete_units.values():
        for x in range(len(idxs)):
            for y in range(x + 1, len(idxs)):
                a, b = idxs[x], idxs[y]
                if a >= n or b >= n:
                    continue
                if a > b:
                    a, b = b, a
                edge_set.add((a, b))

    for a, b in edge_set:
        adj[a, b] = 1.0
        adj[b, a] = 1.0
        degree[a] += 1
        degree[b] += 1

    for i, u in enumerate(units[:n]):
        ath = len(u.athletes)
        node_feat[i, 0] = 1.0 if u.track else 0.0
        node_feat[i, 1] = min(ath, 64) / 64.0
        node_feat[i, 2] = min(u.raw_duration, 300) / 300.0
        node_feat[i, 3] = 1.0 if u.group_key else 0.0
        node_feat[i, 4] = min(group_size.get(u.group_key or "", 0), 8) / 8.0
        node_feat[i, 5] = pool_idx.get(u.pool_label, 0) / max(1, pool_count - 1)
        node_feat[i, 6] = event_idx.get(u.event_id, 0) / max(1, n_events - 1)
        node_feat[i, 7] = math.log1p(ath) / math.log(65.0)
        mask[i] = 1.0

    # 标签：归一化度数中心度 = degree / (n-1)（着色优先级，落在 [0,1]）
    degree_label = np.zeros((MAX_NODES,), dtype=np.float32)
    denom = max(1, n - 1)
    for i in range(n):
        degree_label[i] = degree[i] / denom

    return node_feat[None, ...], adj[None, ...], mask[None, ...], degree_label
