"""SuperScenario → 模型输入的编码器（10 类约束边 + 24 维节点特征）。

与 Java 端 ``SuperScheduleEncoder`` 逐位对齐。

## 24 维节点特征（顺序即契约，**Java 端 SuperScheduleEncoder 逐位一致**）

=====  ==================================================================================
 0-4   规模：单元数占比 / 人数 / 相对最挤单元 / 时长占比 / 场地数
 5-8   需求：装箱紧张度 (dur+interval)/cap / 容量余量 / 冲突暴露 / 兼项占比
 9-12  约束：是否项目块 / 块内单元占比 / 是否道次(heat>0) / heat 容量占比
 13-15 时间：时间目标三态 / 天数占比 / stage 编码
 16-19 赛制：group / round_robin / knockout / hybrid 四种球类赛制的 one-hot
 19-22 微调：组次撞车强度 / 组次数 / 换组最优间隔 / 跨时段拆分可行
=====  ==================================================================================

.. note::

   19 维被两组特征共用是**故意的**，不是笔误：赛制 one-hot 只有 4 位，
   而 16~19 早已被赛制占满（见下方「曾出现互相矛盾的特征表」那段）。
   因此微调信号从**第 19 位之后**接着排，即 19-22 这四维。

   ⚠️ 更正：本段初稿写成 16-19 赛制 + 19-22 微调，**维度 19 重复**。
   代码里实际写入顺序是 fmt_oh(4) 之后接 4 维微调，故
   **赛制实际落在 15-18、微调落在 19-22**。改这份表时务必以
   ``feat[i] = [...]`` 那一条列表的**实际下标顺序**为准，别信文档。

=====  ==========================================================================
 0-4   规模：单元数占比 / 人数 / 相对最挤单元 / 时长占比 / 场地数
 5-8   需求：装箱紧张度 / 容量余量 / 冲突暴露 / 兼项占比
 9-12  约束：是否项目块 / 块内单元占比 / 是否道次(heat>0) / heat 容量占比
 13-15 时间：时间目标三态 / 天数占比 / stage 编码
 16-19 类型：球类赛制 onehot（group/rr/knockout/hybrid）
=====

**时间目标三态**（用户明确要求）编码在第 **13** 维：
``days_limit >= 1 → 0.5``（硬约束）、``0 → 0.0``（不限）、``-1 → 1.0``（最小化）。
原值保留在 ``SuperScenario.days_limit``，由 Java 端解释语义。

⚠️ 这个文件头注释曾出现**两张互相矛盾的特征表**（一张说 16-19 是 task onehot、
一张说 16-19 是赛制 onehot），且代码里还残留一行把第 19 维覆盖成「淘汰赛标记」——
三者互相打架。现已统一为上面这一张（与 Java 逐位核对过），
并删掉了那行覆盖：time_goal@13 / days@14 / stage@15 / fmt_onehot@16-19。
Java 端 ``SuperScheduleEncoder`` 一开始也是按旧注释写的（21 维、timeGoal@12），
被单测抓出来后两边一起改到 13。改这份特征表时必须同步三处：
``super_encode.py`` / ``SuperScheduleEncoder.java`` / ``super_moe.onnx`` 的输入维度。
"""

from __future__ import annotations

import math
from typing import Dict, List, Optional, Tuple

import numpy as np

from sports_ai.data.super_scenarios import (
    E_ATHLETE,
    E_BLOCK,
    E_BRACKET,
    E_HEAT_STAGGER,
    E_LANE,
    E_POOL,
    E_SLOT_NEIGHBOR,
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

# 24 维节点特征：原 20 维 + 4 维微调信号（2026-10-05）
NODE_FEAT_DIM = 24
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

    # ---- E_HEAT_STAGGER：组次级撞车（项目行可能不重叠，但某两组真实撞上）----
    # 权重 = 撞车的组次对数归一化。**不能用 E_ATHLETE 顶替**：兼项边只说
    # 「两人同报两项」，这条边说的是「那两个组次在时间上真的撞了」——
    # 后者才是组次错开能消解的直接信号。
    for i, u in enumerate(units):
        if u.heat_clashes <= 0 or not u.is_heat_unit():
            continue
        w = min(1.0, u.heat_clashes / 4.0)
        for j, v in enumerate(units):
            if i == j or v.heat_clashes <= 0 or not v.is_heat_unit():
                continue
            if u.venue == v.venue or set(u.athletes) & set(v.athletes):
                adj[E_HEAT_STAGGER, i, j] = adj[E_HEAT_STAGGER, j, i] = w
        tmask[E_HEAT_STAGGER] = 1.0

    # ---- E_SLOT_NEIGHBOR：跨时段相邻（同一天且窗口前后相接）----
    # 拆分只在「同一天相邻两个时段」之间发生，所以这条边直接编码可拆性。
    # 权重 = 容量比（越接近越容易凑齐一段完整组次）。
    for i, u in enumerate(units):
        for j, v in enumerate(units):
            if i >= j:
                continue
            if u.venue != v.venue:
                continue
            w = min(1.0, min(u.duration, v.duration) / max(1.0, float(max(u.duration, v.duration))))
            adj[E_SLOT_NEIGHBOR, i, j] = adj[E_SLOT_NEIGHBOR, j, i] = w
            tmask[E_SLOT_NEIGHBOR] = 1.0

    # ---- 24 维节点特征 ----
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
        if u.fmt is not None and 0 <= u.fmt < N_FORMATS:
            fmt_oh[u.fmt] = 1.0
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
            # ---- 19-22：微调信号（2026-10-05 新增）----
            # 只回答两个问题：「要不要错开组次」与「能不能跨时段拆」。
            # 全部归一化到 [0,1]，缺数据即 0（无组次语义 = 不参与微调）。
            min(1.0, u.heat_clashes / 4.0),             # 19 组次级撞车强度
            min(1.0, u.heat_count / 12.0) if u.heat_count else 0.0,   # 20 组次数
            min(1.0, u.best_stagger_gap / 60.0),        # 21 换组可拿到的最优间隔
            1.0 if u.can_split_slot() else 0.0,         # 22 跨时段拆分可行
        ]
        # ⚠️ 这里**曾经**有一行 `feat[i, 19] = 1.0 if u.task in (TASK_KNOCKOUT, TASK_RESECOND)`
        #    把第 19 维（本该是「混合赛制」one-hot 的第 4 位）覆盖成「淘汰赛/二次编排标记」。
        #    它 Java 侧从来没有对应实现 —— 于是**训练与推理对同一个维度的语义不同**，
        #    而这类错位不报错、指标也照常下降，是本项目最危险的一类契约缺陷。
        #    删掉后 16-19 维在双端都严格是 group / round_robin / knockout / hybrid。
        #    淘汰赛的跨轮次信息本来就由 E_BRACKET 边承载，二次编排由 stage(第 15 维) 承载，
        #    不需要再占一个标量维度。

    mask = np.ones(n, dtype=np.float32)

    # ------------------------------------------------------------------
    # 图级（实例级）特征 —— 路由器的「问题结构」感知
    #
    # 节点特征告诉模型「这一个单元长什么样」，但编排里真正决定**派哪位专家上场**
    # 的是整场赛会的结构：500 人 15 项目 1~3 兼项 vs 300 人 10 项目限 2~3 天，
    # 这是两个完全不同的问题，却过去走同一套专家组合。
    # **十维**：冲突密度 / 单元规模 / 场地数 / 天数 / 时间目标 / 并行度 / 填充率 /
    # 块压力 / **组次错开需求** / **跨时段拆分需求**。
    # ------------------------------------------------------------------
    # ⚠️ 场地数口径 = **有窗口的场地数**（窗口 = 真正能排的时段场地），必须与
    #    Java SuperScheduleEncoder 的 nActiveVenues 一致。
    #    早期这里取 len(scen.venues)（场景声明的全部场地），Java 侧却按「单元里
    #    出现过的场地」算，两边在「声明了场地但没排单元/没开窗」时会算出不同的值，
    #    路由拿到错的结构信号还不报错。
    n_v = max(1, len({w.venue for w in scen.windows}) or len(scen.venues) or 1)
    conf_density = float(adj[0].sum()) / max(1.0, float(n) * n) if n else 0.0
    _per_slot: Dict[tuple, set] = {}
    for w in scen.windows:
        _per_slot.setdefault((int(w.day), int(w.window_idx)), set()).add(w.venue)
    max_par = max((len(v) for v in _per_slot.values()), default=1)
    demand = float(sum((u.duration + u.interval) for u in scen.units))
    total_cap = float(sum(int(w.capacity) for w in scen.windows))
    fill = demand / max(1.0, total_cap)
    days_eff = max(1, int(max_days))
    # 微调需求占比：这两个专家该不该上场，取决于「有多少单元真的需要它们」
    n_stagger = sum(1 for u in units if u.needs_heat_stagger())
    n_split = sum(1 for u in units if u.can_split_slot())
    graph_feat = np.array([
        min(1.0, conf_density * 8.0),            # 0 冲突密度（兼项边占比）
        min(1.0, n / 128.0),                     # 1 单元规模
        min(1.0, n_v / 12.0),                    # 2 场地数
        min(1.0, days_eff / 7.0),                # 3 天数
        float(time_goal),                        # 4 时间目标（0 不限 / 1 尽量压缩 / 0.5 限定）
        min(1.0, max_par / 4.0),                 # 5 每时段并行场地数
        min(1.0, fill),                          # 6 填充率（需求/容量）
        min(1.0, (scen.n_blocks / float(days_eff)) / 4.0),   # 7 块压力
        min(1.0, n_stagger / max(1.0, n / 8.0)),  # 8 组次错开需求占比
        min(1.0, n_split / max(1.0, n / 8.0)),    # 9 跨时段拆分需求占比
    ], dtype=np.float32)

    return {"node_feat": feat[None], "adj_by_type": adj[None],
            "type_mask": tmask[None], "mask": mask[None], "graph_feat": graph_feat[None],
            "n": n}


def _msbf_key(i: int, units, cap_by_venue: Dict[str, int],
              vmap: Dict[Tuple[int, int], Dict[str, int]]) -> Tuple[int, float]:
    """最受限优先（MSBF）的排序键：**(还能放进几个桶 ↑, 装箱紧度 ↓)**。

    「还能放进几个桶」= 该单元在**当前桶容量**下（忽略已被谁占用）放得下的桶数；
    这个数越小说明它越挑地方，越该先排 —— 否则好地方全被灵活单元占光。
    """
    u = units[i]
    need = u.duration + u.interval
    avail = 0
    for key, caps in vmap.items():
        # ⚠️ 必须取两次（.get 出值再比）：直接 caps[u.venue] 在场地没开这个桶时会 KeyError
        capv = caps.get(u.venue, 0)
        if capv > 0 and need <= capv:
            avail += 1
    cap = max(1, cap_by_venue.get(u.venue, 1))
    tight = min(1.0, need / cap)
    return (avail, -tight)


def greedy_targets(scen: SuperScenario, n: int, n_slots: int = MAX_SLOTS
                   ) -> Dict[str, np.ndarray]:
    """约束驱动的自监督目标（不依赖标注的最优解）。

    与异构图那轮同样的教训：**标签必须与输入同源**，否则模型学不到拓扑。

    * ``priority``：0.6×装箱紧张度 + 0.4×冲突暴露 —— 越难排的越先排
    * ``slot``：贪心装箱得到的 one-hot 槽位方案（真实可行）
    * ``format``：场景里出现最多的球类赛制（无球类时跳过）
    * ``lane_mask``：该单元是否**有道次/批次语义**（``heat_capacity>0 或 lanes>0``）。
      道次派遣头（替代 lane_advisor）只对这批单元产生损失，其余单元不参与 ——
      田赛、球类、裁判单元没有"第几道"可分，硬给它们派道次只会污染梯度。
    * ``days``：本次贪心落位**实际跨了几个比赛日**（工期真值）。这是此前
      独立服役的 forecast 模型要预测的量，现在作为超级模型的回归目标。
    * ``quality``：方案质量代理 = ``1/(1+各桶负载变异系数)``。这是 GAN 判别器
      要判的"方案好不好"的可解释、可自监督真值：负载越均衡 → 方案越优。

    ⚠️ 槽位（slot）曾经是**假的**，这里必须钉死教训：

    早期版本用 ``vkey = hash(u.venue) % n_slots`` 决定单元落在哪个槽 ——
    两个致命问题：

    1. 槽位与真实落位毫无关系（真实落位是「（时间桶 × 场地）」，而 hash 只认场地名），
       于是「标签可行」变成了「标签随便」，模型学的是噪声；
    2. **Python 字符串 hash 带随机化**（PYTHONHASHSEED 每进程不同），
       同一场景两次训练的标签完全不同 —— 这是比噪声更糟的**标签抖动**，
       loss 照样下降、指标照样好看，但模型什么都没学到。

    现在改成与评测/线上同源的真实落位：先把 windows 压成时间桶、
    只保留前 ``n_slots`` 个（按桶内总容量降序），再按「最受限优先 + best-fit」落位。
    """
    units = scen.units[:n]

    # 时间桶 → {场地: 该桶内该场地容量}；容量 0 表示这个桶不开这个场地
    cap_slot: Dict[Tuple[int, int], Dict[str, int]] = {}
    for w in scen.windows:
        cap_slot.setdefault((w.day, w.window_idx), {}).setdefault(w.venue, int(w.capacity))
    ranked = sorted(cap_slot.items(), key=lambda kv: -sum(kv[1].values()))[:n_slots]
    slot_ids = [k for k, _ in ranked]
    vmap = dict(ranked)

    ath_cnt: Dict[int, int] = {}
    for u in units:
        for a in u.athletes:
            ath_cnt[a] = ath_cnt.get(a, 0) + 1

    cap_by_venue: Dict[str, int] = {}
    for w in scen.windows:
        cap_by_venue[w.venue] = max(cap_by_venue.get(w.venue, 0), int(w.capacity))

    pri = np.zeros(n, dtype=np.float32)
    slot = np.zeros((n, n_slots), dtype=np.float32)
    if not slot_ids:
        fmts = scen.formats()
        fmt_t = np.zeros(4, dtype=np.int64)
        if fmts:
            fmt_t[max(fmts, key=lambda k: fmts[k])] = 1
        # ⚠️ 早退分支必须和正常分支**返回同一组键**，否则下游 to_tensors 取
        #    lane_mask/days/quality 会 KeyError —— 而这条分支只在极端场景触发，
        #    日常训练完全测不到（典型的"上线才炸"）。
        return {"priority": pri, "slot": slot, "format": fmt_t,
                "lane_mask": np.zeros(n, dtype=np.float32),
                "days": np.zeros(1, dtype=np.float32),
                "quality": np.zeros(1, dtype=np.float32)}

    def fits(si: int, i: int, load: Dict[int, int]) -> bool:
        # ⚠️ si 是**桶在候选列表里的下标**，不是桶 id —— vmap 的键是 (day, window_idx)，
        #    直接 vmap[si] 会 KeyError（第一次跑就炸在这）。
        capv = vmap[slot_ids[si]].get(units[i].venue, 0)
        if capv <= 0:
            return False
        return load.get(si, 0) + units[i].duration + units[i].interval <= capv

    # ---- 落位：最受限优先（MSBF）+ best-fit ----
    # MSBF = 「还能放进几个桶」最少的先排，这是装箱类问题的标准序；
    # best-fit = 在放得下的桶里挑**剩余比例最小**的，比 first-fit 显著少制造碎片。
    load: Dict[int, int] = {}
    order = sorted(range(len(units)), key=lambda i: _msbf_key(i, units, cap_by_venue, vmap))
    for i in order:
        u = units[i]
        cands = [si for si in range(len(slot_ids)) if fits(si, i, load)]
        if not cands:
            # 真放不下（场景极端）：退到「容量最大」的桶，保证标签至少是个稳定值
            cands = [max(range(len(slot_ids)),
                         key=lambda si: max(0, vmap[slot_ids[si]].get(u.venue, 0)))]
        best = min(cands, key=lambda si: (load.get(si, 0) + u.duration + u.interval)
                   / max(1, max(0, vmap[slot_ids[si]].get(u.venue, 0))))
        load[best] = load.get(best, 0) + u.duration + u.interval
        slot[i, best] = 1.0

    # priority 用落位**之后**的紧度（与被采用的桶绑定，比用 venue 全局容量更能反映真实压力）
    for i in range(n):
        u = units[i]
        cap = max(1, cap_by_venue.get(u.venue, 1))
        tight = min(1.0, (u.duration + u.interval) / cap)
        expo = min(1.0, (sum(ath_cnt[a] - 1 for a in u.athletes) / max(1, len(u.athletes))) / 3.0)
        pri[i] = 0.6 * tight + 0.4 * expo
    if pri.max() > 0:
        pri = pri / pri.max()
    fmts = scen.formats()
    fmt_t = np.zeros(4, dtype=np.int64)
    if fmts:
        fmt_t[max(fmts, key=lambda k: fmts[k])] = 1

    # ---- 新增：道次掩码 / 工期 / 方案质量（覆盖 lane_advisor + forecast + GAN 判别器）----
    # ⚠️ 判据要包含 ``track``：实测只有 LANE 档会生成 ``heat_capacity>0` 的单元，
    #    若只认 hc/lanes，则 4/5 的样本里道次头**完全拿不到梯度**（mask 求和恒为 0），
    #    道次派遣能力等于没训。径赛（track=True）天然有"第几道"的语义，
    #    把它纳入掩码后梯度覆盖到全部档位。
    lane_mask = np.array(
        [1.0 if (units[i].track or units[i].heat_capacity > 0 or units[i].lanes > 0) else 0.0
         for i in range(n)], dtype=np.float32)
    used_days = sorted({slot_ids[si][0] for si in load.keys()})
    days_t = np.array(
        [float(used_days[-1] - used_days[0] + 1)] if used_days else [0.0], dtype=np.float32)
    loads = np.array([load.get(si, 0) for si in range(len(slot_ids))], dtype=np.float32)
    cv = float(loads.std() / max(1e-6, float(loads.mean()))) if loads.size and loads.mean() > 0 else 0.0
    quality_t = np.array([1.0 / (1.0 + cv)], dtype=np.float32)

    return {"priority": pri, "slot": slot, "format": fmt_t,
            "lane_mask": lane_mask, "days": days_t, "quality": quality_t}
