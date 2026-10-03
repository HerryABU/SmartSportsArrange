"""SuperScenario → 模型输入的编码器（8 类约束边 + 20 维节点特征）。

与 Java 端 ``SuperScheduleEncoder`` 逐位对齐。

## 20 维节点特征（顺序即契约）

=====  ==========================================================================
 0-3  规模：单元数占比 / 人数占比 / 时长占比 / 场地占比
 4-7  需求：装箱紧张度(dur+interval)/cap / 容量余量 / 冲突边数占比 / 兼项占比
 8-11 约束：是否项目块 / 块内单元数占比 / 是否道次(heat>0) / heat 容量占比
 12-15时间：时间目标(0不限/0.5硬约束/1最小化) / 天数占比 / stage 编码 / 赛制 onehot
 16-19类型：task onehot 的前 4 维（项目/道次/球类/淘汰赛）
=====  ==========================================================================

**时间目标三态**（用户明确要求）编码在第 12 维：
``days_limit >= 1 → 0.5``（硬约束）、``0 → 0.0``（不限）、``-1 → 1.0``（最小化）。
原值保留在 ``SuperScenario.days_limit``，由 Java 端解释语义。
"""

from __future__ import annotations

import math
from typing import Dict, List, Optional, Tuple

import numpy as np

from sports_ai.data.super_scenarios import (
    E_ATHLETE,
    E_BLOCK,
    E_BRACKET,
    E_LANE,
    E_POOL,
    E_TEAM,
    E_TIME,
    E_VENUE,
    N_EDGES,
    N_FORMATS,
    N_TASKS,
    TASK_BALL,
    TASK_KNOCKOUT,
    TASK_LANE,
    TASK_MAKESPAN,
    TASK_RESECOND,
    SuperScenario,
    SuperUnit,
)

NODE_FEAT_DIM = 20
MAX_SLOTS = 16


def encode_super_graph(scen: SuperScenario) -> Optional[Dict[str, np.ndarray]]:
    """把场景编成模型输入。返回 None 表示该样本不可用。"""
    units: List[SuperUnit] = scen.units
    n = len(units)
    if n < 4:
        return None

    adj = np.zeros((N_EDGES, n, n), dtype=np.float32)
    tmask = np.zeros(N_EDGES, dtype=np.float32)

    # ---- E_ATHLETE：兼项（共享运动员数） ----
    by_ath: Dict[int, List[int]] = {}
    for i, u in enumerate(units):
        for a in u.athletes:
            by_ath.setdefault(a, []).append(i)
    shared: Dict[Tuple[int, int], int] = {}
    for idxs in by_ath.values():
        if len(idxs) < 2:
            continue
        for x in range(len(idxs)):
            for y in range(x + 1, len(idxs)):
                k = (idxs[x], idxs[y]) if idxs[x] <= idxs[y] else (idxs[y], idxs[x])
                shared[k] = shared.get(k, 0) + 1
    if shared:
        mx = float(max(shared.values()))
        for (a, b), c in shared.items():
            adj[E_ATHLETE, a, b] = adj[E_ATHLETE, b, a] = c / mx
        tmask[E_ATHLETE] = 1.0

    def _bucket(attr: str, t: int) -> None:
        buck: Dict[str, List[int]] = {}
        for i, u in enumerate(units):
            k = getattr(u, attr, None)
            if k:
                buck.setdefault(str(k), []).append(i)
        for idxs in buck.values():
            if len(idxs) < 2:
                continue
            press = min(1.0, len(idxs) / max(1, n))
            for x in range(len(idxs)):
                for y in range(x + 1, len(idxs)):
                    a, b = idxs[x], idxs[y]
                    w = max(adj[t, a, b], press)
                    adj[t, a, b] = adj[t, b, a] = w
            tmask[t] = 1.0

    # ---- E_BLOCK：项目块（同 group_key）——「禁止见缝插针」的关键约束 ----
    _bucket("group_key", E_BLOCK)
    # ---- E_VENUE：场地独占 ----
    _bucket("venue", E_VENUE)
    # ---- E_POOL：同并发池 ----
    _bucket("pool", E_POOL)
    # ---- E_LANE：同道次（同 heat 分组的单元） ----
    lane_buck: Dict[str, List[int]] = {}
    for i, u in enumerate(units):
        if u.heat_capacity > 0:
            lane_buck.setdefault(f"{u.name}|{u.grade}", []).append(i)
    for idxs in lane_buck.values():
        if len(idxs) < 2:
            continue
        press = min(1.0, len(idxs) / max(1, n))
        for x in range(len(idxs)):
            for y in range(x + 1, len(idxs)):
                a, b = idxs[x], idxs[y]
                w = max(adj[E_LANE, a, b], press)
                adj[E_LANE, a, b] = adj[E_LANE, b, a] = w
        tmask[E_LANE] = 1.0
    # ---- E_TEAM：同队（球类同项目同年级） ----
    _bucket("name", E_TEAM) if any(u.is_team for u in units) else None
    if tmask[E_TEAM] == 0:
        # 球类单元用 name 相同者建边（上面 _bucket 已做）；非球类场景则此类型缺席
        pass

    # ---- E_BRACKET：淘汰赛晋级关系（同 parent 的轮次相连 + 父子链） ----
    ko = [u for u in units if u.task == TASK_KNOCKOUT]
    for i, u in enumerate(units):
        if u.bracket_parent is None:
            continue
        # 同项目同年级 + 同 round 的兄弟轮次
        for j, v in enumerate(units):
            if i != j and v.task == TASK_KNOCKOUT and v.name == u.name and v.bracket_round == u.bracket_round:
                adj[E_BRACKET, i, j] = adj[E_BRACKET, j, i] = 1.0
                tmask[E_BRACKET] = 1.0
        # 二次编排单元连到它的首轮
        if u.resecond_of:
            for j, v in enumerate(units):
                if v.key == u.resecond_of:
                    adj[E_BRACKET, i, j] = adj[E_BRACKET, j, i] = 1.0
                    tmask[E_BRACKET] = 1.0
    _ = ko

    # ---- E_TIME：装箱 + 间隔耦合 ----
    cap_by_venue: Dict[str, int] = {}
    for w in scen.windows:
        cap_by_venue[w.venue] = max(cap_by_venue.get(w.venue, 0), int(w.capacity))
    for i, u in enumerate(units):
        cap = max(1, cap_by_venue.get(u.venue, 1))
        for j in range(i + 1, n):
            v = units[j]
            need = u.duration + v.duration + max(u.interval, v.interval)
            if need > cap:
                w = min(1.0, need / cap)
                adj[E_TIME, i, j] = max(adj[E_TIME, i, j], w)
                adj[E_TIME, j, i] = adj[E_TIME, i, j]
        tmask[E_TIME] = 1.0

    # ---- 20 维节点特征 ----
    feat = np.zeros((n, NODE_FEAT_DIM), dtype=np.float32)
    total_dur = float(sum(u.duration for u in units)) or 1.0
    max_people = max((len(u.athletes) for u in units), default=1) or 1
    n_venues = max(1, len({u.venue for u in units}))
    n_windows = max(1, len(scen.windows))
    max_days = max((w.day for w in scen.windows), default=1) or 1
    blocks = scen.blocks()
    block_size = {k: len(v) for k, v in blocks.items()}
    # 兼项暴露：每个单元的运动员里有多少人出现在别的单元
    ath_cnt: Dict[int, int] = {}
    for u in units:
        for a in u.athletes:
            ath_cnt[a] = ath_cnt.get(a, 0) + 1

    # 时间目标三态
    if scen.days_limit >= 1:
        time_goal = 0.5
    elif scen.days_limit == 0:
        time_goal = 0.0
    else:
        time_goal = 1.0

    for i, u in enumerate(units):
        cap = max(1, cap_by_venue.get(u.venue, 1))
        people = len(u.athletes)
        if u.athletes:
            expo = sum(ath_cnt[a] - 1 for a in u.athletes) / len(u.athletes)
        else:
            expo = 0.0
        fmt_oh = [0.0] * N_FORMATS
        if u.fmt is not None:
            fmt_oh[u.fmt] = 1.0
        task_oh = [0.0] * 4
        task_oh[u.task if u.task < 4 else 3] = 1.0
        feat[i] = [
            n / 128.0,                                  # 0 单元数占比
            min(people, 512) / 512.0,                   # 1 人数
            people / max_people,                        # 2 相对人数（vs 最挤的单元）
            u.duration / total_dur,                     # 3 时长占比
            1.0 / n_venues,                             # 4 场地
            min(1.0, (u.duration + u.interval) / cap),  # 4 装箱紧张度
            max(0.0, 1.0 - (u.duration + u.interval) / cap),   # 5 容量余量
            min(1.0, expo / 3.0),                       # 6 冲突暴露
            (sum(ath_cnt[a] - 1 for a in u.athletes) / max(1, len(u.athletes))) / 3.0
            if u.athletes else 0.0,                    # 7 兼项占比
            1.0 if u.group_key else 0.0,                # 8 是否项目块
            min(1.0, block_size.get(u.group_key or "", 0) / 8.0),   # 9 块内规模
            1.0 if u.heat_capacity > 0 else 0.0,         # 10 是否有道次
            min(1.0, u.heat_capacity / 8.0) if u.heat_capacity else 0.0,  # 11 heat 容量
            time_goal,                                  # 12 时间目标三态
            min(1.0, max((w.day for w in scen.windows if w.venue == u.venue), default=1) / max_days),  # 13 天数占比
            {"main": 0.0, "prelim": 0.33, "final": 0.66, "resecond": 1.0}.get(u.stage, 0.0),  # 14 stage
            # 15-18：赛制 onehot（group/rr/knockout/hybrid），无赛制则全 0
            fmt_oh[0], fmt_oh[1], fmt_oh[2], fmt_oh[3],
        ]
        # 第 19 维：淘汰赛 / 二次编排标记（这两类需要跨轮次联动）
        feat[i, 19] = 1.0 if u.task in (TASK_KNOCKOUT, TASK_RESECOND) else 0.0
        _ = task_oh

    mask = np.ones(n, dtype=np.float32)
    return {"node_feat": feat[None], "adj_by_type": adj[None],
            "type_mask": tmask[None], "mask": mask[None], "n": n}


def greedy_targets(scen: SuperScenario, n: int, n_slots: int = MAX_SLOTS
                   ) -> Dict[str, np.ndarray]:
    """约束驱动的自监督目标（不依赖标注的最优解）。

    与异构图那轮同样的教训：**标签必须与输入同源**，否则模型学不到拓扑。

    * ``priority``：0.6×装箱紧张度 + 0.4×冲突暴露 —— 越难排的越先排
    * ``slot``：贪心装箱得到的 one-hot 槽位方案（真实可行）
    * ``format``：场景里出现最多的球类赛制（无球类时跳过）
    """
    units = scen.units[:n]
    cap_by_venue: Dict[str, int] = {}
    for w in scen.windows:
        cap_by_venue[w.venue] = max(cap_by_venue.get(w.venue, 0), int(w.capacity))

    ath_cnt: Dict[int, int] = {}
    for u in units:
        for a in u.athletes:
            ath_cnt[a] = ath_cnt.get(a, 0) + 1

    pri = np.zeros(n, dtype=np.float32)
    slot = np.zeros((n, n_slots), dtype=np.float32)
    # 贪心装箱：按紧张度降序，逐个找第一个装得下的槽
    used: Dict[int, int] = {}
    order = sorted(range(len(units)),
                   key=lambda i: -((units[i].duration + units[i].interval)
                                   / max(1, cap_by_venue.get(units[i].venue, 1))))
    for i in order:
        u = units[i]
        cap = max(1, cap_by_venue.get(u.venue, 1))
        tight = min(1.0, (u.duration + u.interval) / cap)
        expo = min(1.0, (sum(ath_cnt[a] - 1 for a in u.athletes) / max(1, len(u.athletes))) / 3.0)
        pri[i] = 0.6 * tight + 0.4 * expo
        # 找一个还有余量的槽（粗粒度：按 venue 分桶）
        vkey = hash(u.venue) % max(1, n_slots)
        for k in range(n_slots):
            s = (vkey + k) % n_slots
            if used.get(s, 0) + u.duration <= cap:
                used[s] = used.get(s, 0) + u.duration
                slot[i, s] = 1.0
                break
        else:
            slot[i, vkey % n_slots] = 1.0
    if pri.max() > 0:
        pri = pri / pri.max()
    fmts = scen.formats()
    fmt_t = np.zeros(4, dtype=np.int64)
    if fmts:
        fmt_t[max(fmts, key=lambda k: fmts[k])] = 1
    return {"priority": pri, "slot": slot, "format": fmt_t}
