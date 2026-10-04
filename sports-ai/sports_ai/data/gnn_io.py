"""冲突簇 GNN 的输入编码（**动态节点数**契约，与 Java 端 ``ConflictGraphEncoder`` 严格对齐）。

把编排实例编码成 GNN 三路输入：
- ``node_feat``: [1, n, CONFLICT_FEAT_DIM]  节点特征（17 维 = 16 通用维 + 第 17 维「度数」，构造保证落在 [0,1]）
- ``adj``:       [1, n, n]              **带权邻接**（共享运动员数归一化，无自环）
- ``mask``:      [1, n]                 1=真实节点，0=填充（仅当 ``pad_to`` 补齐时出现）

以及训练标签 ``degree_label``: [n]，每节点归一化度数中心度（着色优先级）。

**为什么从「固定 1024 节点」改为「动态 n」**

固定 shape 有两处致命代价：
1. **训练慢到不可用**：邻接是 O(n²)，padding 到 1024 时每个样本 4MB，一组 batch 上百 MB，
   而真实实例往往只有几十个单元——99% 的算力花在 padding 上；
2. **仍有硬上限**：超过 1024 个单元的场景只能截断，与「支持很大规模」的目标相悖。

GNN 是**归纳式**的（参数与节点数无关），天然支持可变图规模。因此：
- ``pad_to=None``（默认，**推理用**）：不补齐，shape = [1, n, F] / [1, n, n]，n 由实例决定；
- ``pad_to=K``（**训练用**）：batch 内补齐到 K，让张量可拼接；由于 mask 与归一化都已正确
  屏蔽 padding，补齐数量**不影响**真实节点的输出（数值等价性由 test_features 断言）。

ONNX 导出用 ``dynamic_axes`` 声明 n 维可变，Java 侧按实际节点数构造 shape。
``MAX_NODES`` 退化为**安全上限**（防单个实例过大爆内存），不再是模型契约。

节点特征（16 维）：
```
0  track               径赛=1 / 田赛=0
1  athlete_count_norm  min(人数,512)/512
2  duration_norm       min(全部时长,600)/600
3  has_group           同组同时开赛=1
4  group_size_norm     min(同组单元数,16)/16
5  pool_idx_norm       并发池序号/(池数-1)
6  event_idx_norm      项目序号/(项目数-1)
7  log_athlete_norm    log1p(人数)/log(513)
8  is_final            决赛轮次=1
9  grade_idx_norm      年级序号/(年级数-1)
10 duration_share      该单元时长 / 全部单元时长之和
11 conflict_exposure   (人数×时长) / 全局最大（冲突暴露量——人多且久的单元最难排）
12 event_freq_norm     同项目单元数 / 单元总数
13 pool_share_norm     同池单元数 / 单元总数
14 is_large_unit       时长 ≥ 300 分钟（需拆批的超大单元）
15 order_norm          单元在输入中的相对位置 i/(n-1)
```

**为什么把 8 维扩到 16 维**：8 维版本里，模型几乎只能看到「人数、时长、池、项目」四件事，
于是它对「预赛/决赛」「哪个年级」「这个单元在全局里占多少时间」一无所知——而这些恰恰是
真实编排里决定「谁该先着色」的关键。维度翻倍不是堆料，是把领域知识喂给模型。

**邻接为什么带权**：一条冲突边的真实强度是「两个单元共享多少名运动员」。共享 20 人与共享 1 人
在着色时该被区别对待；二值邻接把这个信息压掉了，模型的度数中心度会虚高。
"""

from __future__ import annotations

import math
from typing import Dict, List, Optional, Tuple

import numpy as np

from .features import MAX_NODES, NODE_FEAT_DIM

# ⚠️ 冲突图**单独**多一维「度数」，不动共享的 NODE_FEAT_DIM：
#    共享常量还被生成式三件套（generator/discriminator/refiner）使用，
#    改它会让三个已导出的 onnx 全部失效（又要重训）。
#
#    为什么必须显式给度数：本模型的**预测目标就是归一化度数中心度**
#    （`degree/(n-1)`），而图注意力用的是对称归一化 D^{-1/2} A D^{-1/2} ——
#    在星形图上中心与叶子的聚合幅度**完全一样**，度数信息被归一化抹掉了。
#    实测：16 维时模型在 4 节点星形图上输出 [0.108,0.070,0.082,0.119]，
#    argmax 指向只有 1 条边的叶子，而正确答案是度数为 3 的中心节点。
#    一句话：**要预测什么，输入里就该有那个东西的可判据。**
CONFLICT_FEAT_DIM = NODE_FEAT_DIM + 1
from .generator import Scenario, Unit

#: 归一化上界（与 Java 端逐位对齐，改动必须双端同步）
ATH_CAP = 512.0
DUR_CAP = 600.0
GROUP_CAP = 16.0
LARGE_UNIT_MINUTES = 300.0

#: 训练时的默认补齐长度（batch 内统一 shape 用）。推理不补齐。
TRAIN_PAD_TO = 256


def _shared_counts(units: List[Unit], n: int) -> Dict[Tuple[int, int], int]:
    """(i, j) → 两单元共享的运动员人数（i < j）。只统计前 n 个节点之间的边。"""
    athlete_units: Dict[int, List[int]] = {}
    for i, u in enumerate(units[:n]):
        for a in u.athletes:
            athlete_units.setdefault(a, []).append(i)
    shared: Dict[Tuple[int, int], int] = {}
    for idxs in athlete_units.values():
        for x in range(len(idxs)):
            for y in range(x + 1, len(idxs)):
                a, b = idxs[x], idxs[y]
                if a >= n or b >= n:
                    continue
                if a > b:
                    a, b = b, a
                shared[(a, b)] = shared.get((a, b), 0) + 1
    return shared


def encode_gnn_inputs(scenario: Scenario, pad_to: Optional[int] = None,
                      return_meta: bool = False):
    """返回 (node_feat, adj, mask, degree_label)[, meta]。

    :param pad_to: None = 不补齐（**推理**，shape 随实例变化）；
                   K = 补齐/截断到 K 个节点（**训练**，便于 batch 拼接）。
    :param return_meta: 附带 ``nodeCount`` / ``totalUnits`` / ``dropped`` / ``maxSharedAthletes``。
    """
    units: List[Unit] = scenario.units
    total = len(units)
    n = min(total, MAX_NODES)
    size = n if pad_to is None else max(n, int(pad_to))

    node_feat = np.zeros((size, CONFLICT_FEAT_DIM), dtype=np.float32)
    adj = np.zeros((size, size), dtype=np.float32)
    mask = np.zeros((size,), dtype=np.float32)

    # ---- 全局统计（归一化分母）----
    pools = sorted({u.pool_label for u in units})
    pool_idx: Dict[str, int] = {p: i for i, p in enumerate(pools)}
    pool_count = max(1, len(pools))
    pool_size: Dict[str, int] = {}
    for u in units:
        pool_size[u.pool_label] = pool_size.get(u.pool_label, 0) + 1

    event_ids = sorted({u.event_id for u in units})
    event_idx: Dict[int, int] = {e: i for i, e in enumerate(event_ids)}
    n_events = max(1, len(event_ids))
    event_freq: Dict[int, int] = {}
    for u in units:
        event_freq[u.event_id] = event_freq.get(u.event_id, 0) + 1

    grades = sorted({(u.grade or "") for u in units})
    grade_idx: Dict[str, int] = {g: i for i, g in enumerate(grades)}
    n_grades = max(1, len(grades))

    group_size: Dict[str, int] = {}
    for u in units:
        if u.group_key:
            group_size[u.group_key] = group_size.get(u.group_key, 0) + 1

    total_duration = max(1, sum(max(0, u.raw_duration) for u in units))
    exposures = [max(0, u.raw_duration) * len(u.athletes) for u in units]
    max_exposure = max([1] + exposures)
    n_total = max(1, total)

    # ---- 带权邻接（共享运动员数归一化）----
    shared = _shared_counts(units, n)
    max_shared = max([1] + list(shared.values()))
    degree = [0] * n
    for (a, b), c in shared.items():
        w = c / max_shared
        adj[a, b] = w
        adj[b, a] = w
        degree[a] += 1
        degree[b] += 1

    # ---- 节点特征（node_feat 是 [size, NODE_FEAT_DIM] 二维数组，按行写）----
    for i, u in enumerate(units[:n]):
        ath = len(u.athletes)
        dur = max(0, u.raw_duration)
        node_feat[i, 0] = 1.0 if u.track else 0.0
        node_feat[i, 1] = min(ath, ATH_CAP) / ATH_CAP
        node_feat[i, 2] = min(dur, DUR_CAP) / DUR_CAP
        node_feat[i, 3] = 1.0 if u.group_key else 0.0
        node_feat[i, 4] = min(group_size.get(u.group_key or "", 0), GROUP_CAP) / GROUP_CAP
        node_feat[i, 5] = pool_idx.get(u.pool_label, 0) / max(1, pool_count - 1)
        node_feat[i, 6] = event_idx.get(u.event_id, 0) / max(1, n_events - 1)
        node_feat[i, 7] = math.log1p(ath) / math.log(ATH_CAP + 1.0)
        node_feat[i, 8] = 1.0 if "决赛" in (u.event_name or "") else 0.0
        node_feat[i, 9] = grade_idx.get(u.grade or "", 0) / max(1, n_grades - 1)
        node_feat[i, 10] = dur / total_duration
        node_feat[i, 11] = (dur * ath) / max_exposure
        node_feat[i, 12] = event_freq.get(u.event_id, 0) / n_total
        node_feat[i, 13] = pool_size.get(u.pool_label, 0) / n_total
        node_feat[i, 14] = 1.0 if dur >= LARGE_UNIT_MINUTES else 0.0
        node_feat[i, 15] = (i / max(1, n - 1)) if n > 1 else 0.0
        # 第 16 维：归一化度数 —— **与标签同源**（标签就是 degree/(n-1)）。
        # 对称归一化的消息传递会把度数幅度抹平，所以必须显式给出。
        node_feat[i, 16] = degree[i] / max(1, n - 1)
        mask[i] = 1.0

    # ---- 标签：归一化度数中心度（着色优先级，落在 [0,1]）----
    degree_label = np.zeros((size,), dtype=np.float32)
    denom = max(1, n - 1)
    for i in range(n):
        degree_label[i] = degree[i] / denom

    if return_meta:
        meta = {"nodeCount": n, "totalUnits": total, "dropped": max(0, total - n),
                "padded": size - n, "maxSharedAthletes": int(max_shared) if shared else 0}
        return (node_feat[None, ...], adj[None, ...], mask[None, ...], degree_label, meta)
    return node_feat[None, ...], adj[None, ...], mask[None, ...], degree_label
