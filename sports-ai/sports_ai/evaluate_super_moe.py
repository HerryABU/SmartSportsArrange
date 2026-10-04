"""魔鬼场景实测：把超级模型输出解码成**真实排程**，再数违规。

## 为什么必须这样评
训练 loss 降到 0.67 只说明「模型拟合了标签」，**不说明排得开**。
真正要回答的问题是（用户点名的魔鬼条件）：

* 500 人 / 15 项目 / 3 项兼项 —— 兼项运动员会不会被排到同时段？
* 300 人 / 10 项目 / 限 2~3 天 —— 工期压不压得住？
* 项目块能不能**成块**（不许见缝插针乱塞）？
* 淘汰赛晋级 + 二次编排有没有被当成一类单元排？

## 评测口径（全部是硬违规计数，不是相似度）

* **兼项冲突**：同一运动员的两个单元被排进**重叠时段**（同一时间桶）。
* **容量超占**：同一时段同一场地的已排时长 > 该场地容量。
* **块完整性**：同 ``group_key`` 的单元占据的时间桶必须是**一段连续区间**，
  且区间内不得混入其它块的单元 —— 这就是「禁止见缝插针」的形式化定义。
* **未排单元**：任何可行时段都放不下的单元（硬失败）。
* **工期**：实际占用天数（对比 ``days_limit`` 三态）。
* **道次冲突**：同批（同项目+同年级）单元被排进同一时间桶。

## 三个基线（用来证明模型真的学到了东西）

1. ``random``：随机顺序 + 第一可行桶（下界）
2. ``greedy``：按训练时那个贪心函数的顺序（模型要拟合的目标）
3. ``model``：按模型 priority 降序（模型实际给出的顺序）

只有 model 明显优于 greedy，才说明自监督标签是**可学的**；
如果 model ≈ greedy，说明模型只学到了标签、没学到更优策略。
"""

from __future__ import annotations

import argparse
import json
import math
import os
from types import SimpleNamespace
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np
import torch

from sports_ai.data.super_encode import MAX_SLOTS, encode_super_graph
from sports_ai.data.super_scenarios import (
    TASK_BALL,
    TASK_KNOCKOUT,
    TASK_LANE,
    TASK_RESECOND,
    SuperScenario,
    SuperUnit,
    generate_super_scenario,
)
from sports_ai.models.super_moe import SuperScheduleMoE
from sports_ai.plan import CompletionPredictor, collect_training_pairs, plan_predictive
from sports_ai.solve.repair import repair_assignment

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TIERS = ("HELL", "REGULAR", "BLOCK", "LANE", "TEAM")


# ---------------------------------------------------------------- 时段建模
def build_buckets(scen: SuperScenario) -> Tuple[List[Tuple[int, int]], Dict[int, List]]:
    """把场景的 windows 压成「时间桶」。

    时间桶 = (day, window_idx)，与场地无关 —— 同一时间桶内不同场地是**并行**的。
    这是评测口径的关键：**兼项冲突看时间桶（并行不同场地也算撞），
    容量超占看具体到哪个场地**。混在一起数会既重复计数又漏掉真冲突。

    ⚠️ 桶内容量是**同桶各场地容量之和**（并行开的场地上能同时排多个单元），
       不是取最大值 —— 早期写成 max 会把并行场地当成"只开了一个场地"，
       容量被系统性低估，评测出的未排数是假象。

    ⚠️ 桶数超过 MAX_SLOTS(16) 时保留**总容量最大**的 16 个：
    随机截断会让大量单元直接无可行桶，那是评测假象不是模型能力。
    """
    cap_of: Dict[Tuple[int, int], int] = {}
    for w in scen.windows:
        k = (w.day, w.window_idx)
        cap_of[k] = cap_of.get(k, 0) + int(w.capacity)
    buckets = sorted(cap_of.keys())
    if len(buckets) > MAX_SLOTS:
        buckets = sorted(sorted(buckets, key=lambda k: -cap_of[k])[:MAX_SLOTS])
    bidx = {b: i for i, b in enumerate(buckets)}
    win_by_bucket: Dict[int, List] = {i: [] for i in range(len(buckets))}
    for w in scen.windows:
        i = bidx.get((w.day, w.window_idx))
        if i is not None:
            win_by_bucket[i].append(w)
    return buckets, win_by_bucket


def lower_bound(scen: SuperScenario) -> Dict[str, float]:
    """场景的**可解性下界**：先算明底，再谈模型。

    不先算这个，一旦「未排 27 个」出来，根本分不清是模型不行还是场景本身排不下 ——
    那才是最大的假象。三个下界都是硬数：

    * ``cap_slack`` = 总容量(min) - 总需求(min)              容量维度（<0 直接无解）
    * ``ath_slack`` = 桶数 × 运动员数 - 总人次                兼项维度（<0 直接无解）
      —— 每个运动员在每个时间桶里最多出现一次，桶数×人数是人次上界；
         这是「500 人 / 15 项目 / 兼项率 100%」这一档最容易踩的死穴。
    * ``avg_dur``    = 单元平均时长，用来解释「一个桶塞得下几块」
    """
    buckets, _ = build_buckets(scen)
    nb = max(1, len(buckets))
    cap = sum(int(w.capacity) for w in scen.windows)
    demand = sum(u.duration for u in scen.units)
    athletes = {a for u in scen.units for a in u.athletes}
    slots = sum(len(u.athletes) for u in scen.units)
    return {
        "n_buckets": float(nb),
        "cap_slack": float(cap - demand),
        "ath_slack": float(nb * len(athletes) - slots),
        "avg_dur": round(demand / max(1, len(scen.units)), 1),
        "units_per_bucket_max": round(cap / max(1, demand) * nb, 2),
    }


# ---------------------------------------------------------------- 解码
def decode(
    scen: SuperScenario,
    n: int,
    priority: np.ndarray,
    slot_logits: np.ndarray,
    mode: str,
    order_override=None,
) -> Dict[str, object]:
    """按给定顺序把单元摆进时间桶，返回每条单元的落点与违规统计。

    ``mode``: ``model``（priority 降序）/ ``greedy``（按装箱紧张度降序）
              / ``random``（随机顺序）/ ``custom``（用 ``order_override``）。

    ⚠️ 参数名用 ``order_override`` 而不是 ``order``：函数体内本来就有一个局部
    ``order``，同名会把外部传入的顺序直接遮蔽掉，而且不报错、只是结果不对。
    """
    units: List[SuperUnit] = scen.units[:n]
    buckets, win_by_bucket = build_buckets(scen)
    nb = len(buckets)
    if nb == 0:
        return {"unplaced": len(units), "placed": 0}

    # ---- 排序 ----
    if order_override is not None:
        # 外部给定顺序（遗传算法一条龙用）。
        # 关键：只换「用什么顺序摆」，落位规则与其它模式**完全相同** ——
        # 这样 model 与 GA 的差距才是搜索质量差距，不是解码器差距。
        order = [int(i) for i in order_override]
    elif mode == "model":
        order = sorted(range(n), key=lambda i: -float(priority[i]))
    elif mode == "random":
        order = list(range(n))
        np.random.shuffle(order)
    else:  # greedy：与 super_encode.greedy_targets 同一套紧张度
        cap_by_venue: Dict[str, int] = {}
        for w in scen.windows:
            cap_by_venue[w.venue] = max(cap_by_venue.get(w.venue, 0), int(w.capacity))
        order = sorted(
            range(n),
            key=lambda i: -((units[i].duration + units[i].interval)
                            / max(1, cap_by_venue.get(units[i].venue, 1))))

    load: Dict[Tuple[int, str], int] = {}          # (时间桶, 场地) -> 已排时长
    bucket_athletes: Dict[int, set] = {b: set() for b in range(nb)}
    bucket_units: Dict[int, List[int]] = {b: [] for b in range(nb)}
    assign: Dict[int, Optional[int]] = {}          # unit idx -> 时间桶

    for i in order:
        u = units[i]
        best: Optional[Tuple[float, int]] = None
        best_window = None
        for b in range(nb):
            if slot_logits is not None and n > 0:
                sc = float(slot_logits[i, b]) if b < slot_logits.shape[1] else -1e9
            else:
                sc = 0.0
            # 场地必须在该时间桶里存在
            cands = [w for w in win_by_bucket[b] if w.venue == u.venue]
            if not cands:
                continue
            for w in cands:
                key = (b, w.venue)
                if load.get(key, 0) + u.duration > w.capacity:
                    continue
                if bucket_athletes[b] & set(u.athletes) and u.athletes:
                    continue
                if best is None or sc > best[0]:
                    best = (sc, b)
                    best_window = w
        if best is None:
            assign[i] = None
            continue
        b = best[1]
        assign[i] = b
        bucket_units[b].append(i)
        bucket_athletes[b].update(u.athletes)
        load[(b, best_window.venue)] = load.get((b, best_window.venue), 0) + u.duration

    # ---- 修复重插（对三种模式完全公平，只补不换）----
    # ⚠️ 为什么必须补这一趟：朴素 first-fit 在随机分数下会把后半段单元挤成
    #    「无桶可放」——那 24 个未排是**解码器**的锅，不是模型的锅。
    #    三个模式共用同一套修复，才比得出「排序质量」而不是「谁运气好」。
    #    真实编排器也是这么干的（先落位，再针对漏网单元回填）。
    def _try_place(i: int) -> Optional[int]:
        u = units[i]
        best: Optional[Tuple[float, int, object]] = None
        best_window = None
        for b in range(nb):
            if slot_logits is not None and slot_logits.size:
                sc = float(slot_logits[i, b]) if b < slot_logits.shape[1] else -1e9
            else:
                sc = 0.0
            for w in [w for w in win_by_bucket[b] if w.venue == u.venue]:
                if load.get((b, w.venue), 0) + u.duration > w.capacity:
                    continue
                if bucket_athletes[b] & set(u.athletes) and u.athletes:
                    continue
                if best is None or sc > best[0]:
                    best = (sc, b, w)
                    best_window = w
        if best is None:
            return None
        b, w = best[1], best[2]
        assign[i] = b
        bucket_units[b].append(i)
        bucket_athletes[b].update(u.athletes)
        load[(b, w.venue)] = load.get((b, w.venue), 0) + u.duration
        return b

    for _ in range(3):
        left = [i for i, b in assign.items() if b is None]
        if not left:
            break
        for i in left:
            _try_place(i)

    # ---- 硬约束兜底：显式复用 solve/repair.py ----
    # ⚠️ 为什么不能省：上面那 3 轮回填只补「没地方放」的单元，**不会**修正
    #    已经放下去的容量超占与兼项撞车——而这两条恰恰是现场事故。
    #    模型学的是排序，没有任何机制保证硬约束，交付前必须过一道规则修复。
    #    三种模式共用同一套修复，比出来的才是「排序质量」而不是「谁运气好」。
    #    ⚠️ 口径必须对齐解码器：窗口容量按`纯时长`扣，所以显式传 need_fn，
    #       不能用默认 need_of（时长+间隔），否则装得下的位置被判超载、乱搬。
    def _need(u) -> int:                                  # noqa: ANN001
        return int(u.duration)

    caps_r: Dict[Tuple[Tuple[int, int], str], int] = {}
    for b in range(nb):
        for w in win_by_bucket[b]:
            caps_r[(buckets[b], w.venue)] = int(w.capacity)
    units_r = [SimpleNamespace(key=str(i), duration=int(units[i].duration),
                               interval=int(units[i].interval),
                               venue=units[i].venue)
               for i in range(n)]
    slot_r = {str(i): buckets[b] for i, b in assign.items() if b is not None}
    ath_r = {str(i): list(units[i].athletes) for i in range(n) if units[i].athletes}
    repaired, rep_r = repair_assignment(units_r, slot_r, caps_r, ath_r, need_fn=_need)
    n_repair = rep_r["capacity_fixes"] + rep_r["conflict_fixes"]
    n_blocked = len(rep_r["blocked"])
    if n_repair or n_blocked:
        # 以修复后的分配为交付口径，重算 load 与桶归属，后面的违规统计才自洽
        for i in range(n):
            sid = repaired.get(str(i))
            if sid is not None and sid in buckets:
                assign[i] = buckets.index(sid)
        load = {}
        bucket_units = {b: [] for b in range(nb)}
        bucket_athletes = {b: set() for b in range(nb)}
        for i, b in assign.items():
            if b is None:
                continue
            bucket_units[b].append(i)
            bucket_athletes[b].update(units[i].athletes)
            load[(b, units[i].venue)] = load.get((b, units[i].venue), 0) + int(units[i].duration)

    # ---- 违规统计 ----
    # ① 兼项冲突：同桶且共享运动员（解码阶段已经避开了，这里复算以独立验证）
    clash = 0
    for b in range(nb):
        ids = bucket_units[b]
        seen: Dict[int, int] = {}
        for i in ids:
            for a in units[i].athletes:
                if a in seen:
                    clash += 1
                seen[a] = i
    # ② 容量超占
    overflow = 0
    for (b, ven), ln in load.items():
        cap = max((w.capacity for w in win_by_bucket[b] if w.venue == ven), default=0)
        if cap and ln > cap:
            overflow += ln - cap
    # ③ 块完整性：同 group_key 的时间桶必须连续、且中间不夹别的块
    frag_blocks = 0
    blocks: Dict[str, List[int]] = {}
    for i, b in assign.items():
        if b is None:                      # 未排单元不参与块完整性统计
            continue
        gk = units[i].group_key
        if gk:
            blocks.setdefault(gk, []).append(b)
    for gk, bs in blocks.items():
        if len(bs) < 2:
            continue
        lo, hi = min(bs), max(bs)
        span = set(range(lo, hi + 1))
        if len(bs) != len(span):
            frag_blocks += 1                      # 中间有空洞 = 被别的单元插进来了
        else:
            # 区间内混进了别的块 → 见缝插针
            other = 0
            for b in span:
                for i in bucket_units.get(b, []):
                    if units[i].group_key != gk:
                        other += 1
            if other:
                frag_blocks += 1
    # ④ 道次冲突：同批（同项目+同年级）单元落同一桶
    lane_clash = 0
    lane_buck: Dict[str, List[int]] = {}
    for i, b in assign.items():
        u = units[i]
        if u.heat_capacity > 0:
            lane_buck.setdefault(f"{u.name}|{u.grade}", []).append(b)
    for bs in lane_buck.values():
        if len(bs) != len(set(bs)):
            lane_clash += 1
    # ⑤ 工期
    days_used = len({buckets[b][0] for b in assign.values() if b is not None})
    unplaced = sum(1 for v in assign.values() if v is None)

    return {
        "n_units": n,
        "placed": n - unplaced,
        "unplaced": unplaced,
        "athlete_clash": clash,
        "capacity_overflow": overflow,
        "frag_blocks": frag_blocks,
        "lane_clash": lane_clash,
        "days_used": days_used,
        "days_limit": scen.days_limit,
        # 修复层计数：模型越差 → 交付前要补的洞越多，这本身就是鲁棒性指标
        "repairs": int(n_repair),
        "blocked": int(n_blocked),
    }


# ------------------------------------------------- 遗传算法一条龙（基线）
def cost_of(out: Dict[str, object]) -> float:
    """GA 适应度：与评测同口径的加权代价（越小越好）。

    权重刻意让「能不能排」远重于「排得好不好」：未排 / 兼项撞 / 超占
    属于不可接受的硬伤，碎块只是观感与公平体验问题。
    """
    return (1000.0 * float(out.get("unplaced", 0) or 0)
            + 500.0 * float(out.get("athlete_clash", 0) or 0)
            + 500.0 * float(out.get("capacity_overflow", 0) or 0)
            + 50.0 * float(out.get("lane_clash", 0) or 0)
            + 10.0 * float(out.get("frag_blocks", 0) or 0))


def order_crossover(a: List[int], b: List[int], rng) -> List[int]:
    """顺序交叉 OX：取 a 的一段，其余按 b 的顺序补全 → 保证仍是合法排列。"""
    n = len(a)
    if n < 2:
        return list(a)
    picked = sorted(int(x) for x in rng.choice(n, size=2, replace=False))
    i, j = picked[0], picked[1]
    child: List[object] = [None] * n
    child[i:j + 1] = a[i:j + 1]
    taken = set(a[i:j + 1])
    fill = [x for x in b if x not in taken]
    k = 0
    for pos in list(range(j + 1, n)) + list(range(0, i)):
        child[pos] = fill[k]
        k += 1
    return [int(x) for x in child]


def mutate(order: List[int], rng, rate: float = 0.25) -> None:
    """就地变异：交换 或 插入（插入更容易把同一项目的组次挪到一起）。"""
    n = len(order)
    if n < 2 or rng.random() > rate:
        return
    picked = sorted(int(x) for x in rng.choice(n, size=2, replace=False))
    i, j = picked[0], picked[1]
    if rng.random() < 0.5:
        order[i], order[j] = order[j], order[i]
    else:
        order.insert(i, order.pop(j))


def ga_search(scen: SuperScenario, n: int, slot_logits: np.ndarray,
              seed: int = 0, pop: int = 14, gens: int = 18,
              extra_seeds: Optional[List[List[int]]] = None) -> List[int]:
    """遗传算法一条龙：排列编码 + OX 交叉 + 交换/插入变异 + 精英保留。

    这是「纯算法链」的代表基线（L3 那一档元启发式的同类）。

    ``extra_seeds`` 是**外来种子**（L4 借鉴 L2/L3 的接口）：把模型给出的排序
    塞进初始种群，让搜索从「模型认为的好起点」出发，而不是从随机出发。
    最终解在「搜索最优 ∪ 全部种子」里取，所以**永远不会比任何一个种子更差**。
    """
    rng = np.random.default_rng(seed)
    units = scen.units[:n]

    def fitness(order: List[int]) -> float:
        return cost_of(decode(scen, n, None, slot_logits, "custom",
                              order_override=order))

    cap_by_venue: Dict[str, int] = {}
    for w in scen.windows:
        cap_by_venue[w.venue] = max(cap_by_venue.get(w.venue, 0), int(w.capacity))
    seed_order = sorted(
        range(n),
        key=lambda i: -((units[i].duration + units[i].interval)
                        / max(1, cap_by_venue.get(units[i].venue, 1))))
    population: List[List[int]] = [seed_order]
    for s in (extra_seeds or []):
        if len(s) == n and list(s) not in population:
            population.append(list(s))
    while len(population) < max(2, pop):
        p = list(range(n))
        rng.shuffle(p)
        population.append(p)

    scored = [(fitness(p), p) for p in population]
    best_cost = scored[0][0] if scored else math.inf
    stalled = 0
    # 停滞多少代就重启：太短会让搜索无法深耕，太长则等于不重启
    restart_every = max(3, gens // 4)
    for _ in range(max(1, gens)):
        scored.sort(key=lambda t: t[0])
        if scored[0][0] < best_cost - 1e-9:
            best_cost, stalled = scored[0][0], 0
        else:
            stalled += 1
        nxt: List[List[int]] = [p for _, p in scored[:2]]      # 精英保留

        def tournament() -> List[int]:
            k0, k1 = (int(x) for x in rng.choice(len(scored), size=2, replace=False))
            return scored[k0][1] if scored[k0][0] <= scored[k1][0] else scored[k1][1]

        while len(nxt) < len(scored):
            child = order_crossover(tournament(), tournament(), rng)
            mutate(child, rng)
            nxt.append(child)

        if stalled >= restart_every:
            # 【L2/L3 强化】下界引导重启：种群尾部换回「装箱紧度构造序」+ 随机重启。
            # 用构造序而不是纯随机，是因为它带着「紧的单元先排」这条下界信息 ——
            # 重启不是重新掷骰子，而是回到有信息量的起点。
            nxt[-1] = list(seed_order)
            for back in range(2, min(4, len(nxt)) + 1):
                fresh = list(range(n))
                rng.shuffle(fresh)
                nxt[-back] = fresh
            stalled = 0
        scored = [(fitness(p), p) for p in nxt]
    # 精英兜底：把外来种子也纳入最终比较，保证「借鉴」只可能变好、不可能变差。
    for s in (extra_seeds or []):
        if len(s) == n:
            scored.append((fitness(list(s)), list(s)))
    scored.sort(key=lambda t: t[0])
    return list(scored[0][1])


def mc_uncertainty(model, nf, ab, tm, mk, gf, samples: int = 5) -> np.ndarray:
    """MC Dropout：多次前向的预测方差 = 模型对该实例的「不确定度」[N]。

    ⚠️ 必须临时切到 `train()` 模式前向，否则 dropout 关闭、方差恒为 0
    （那就退化成「没有信息」，白算一遍）。
    ⚠️ 这样做是安全的：本模型只有 LayerNorm + Dropout，没有 BatchNorm ——
    train() 模式不会污染任何运行时统计量，事后切回 `eval()` 即可。
    """
    model.train()
    acc: List[np.ndarray] = []
    try:
        with torch.no_grad():
            for _ in range(max(2, samples)):
                pri, _slot, _t, _f, _d, _lane, _qual = model(nf, ab, tm, mk, gf)
                acc.append(pri[0].detach().cpu().numpy())
    finally:
        model.eval()
    return np.var(np.stack(acc, axis=0), axis=0).astype(np.float32)


def lns_refine(scen: SuperScenario, n: int, slot_logits: np.ndarray,
               order0: List[int], seed: int = 0,
               rounds: int = 6, kill: int = 4,
               uncertain: Optional[np.ndarray] = None) -> List[int]:
    """破坏-重建大邻域精修（L4 借 L2/L3 的算子）。

    纯 GA 的交换/插入变异只能在「相近排列」里游走，容易卡在局部最优；
    破坏-重建一次性摘掉若干单元再从零插回，等价于跨邻域跳跃。

    ⚠️ 接受准则用「严格更优」而不是模拟退火：这里是给 L4 补链路，
    不是做研究，不引入需要调参的温度；稳赢比偶尔赢更重要。
    """
    rng = np.random.default_rng(seed + 7919)

    def cost(order: List[int]) -> float:
        return cost_of(decode(scen, n, None, slot_logits, "custom",
                              order_override=order))

    best = list(order0)
    best_cost = cost(best)
    for _ in range(max(1, rounds)):
        if len(best) <= kill:
            break
        if uncertain is not None and len(uncertain) >= len(best):
            # 前沿方法（GLNS 轨迹敏感性）：不随机摘，而是摘「模型最没把握」的——
            # 那些正是结构歧义/高耦合所在，修它们的收益最大。
            ranked = np.argsort(-uncertain[:len(best)])
            picked = sorted(int(i) for i in ranked[:kill])
        else:
            # 无不确定度信息时退回随机破坏（保持与原实现等价）
            picked = sorted(int(x) for x in rng.choice(len(best), size=kill, replace=False))
        drop = set(picked)
        removed = [best[i] for i in picked]
        rest = [x for i, x in enumerate(best) if i not in drop]
        # 重建：每个被摘掉的单元插到「代价最小」的位置
        for u in removed:
            best_pos, best_c = 0, math.inf
            for pos in range(len(rest) + 1):
                cand = rest[:pos] + [u] + rest[pos:]
                c = cost(cand)
                if c < best_c:
                    best_c, best_pos = c, pos
            rest = rest[:best_pos] + [u] + rest[best_pos:]
        c = cost(rest)
        if c < best_cost:
            best, best_cost = rest, c
    return best


# ---------------------------------------------------------------- 主流程
# ---------------------------------------------------------------- 规划层（预测器）对照
# 保守剪枝阈值：预测器给出的「能从该状态排完」概率低于此值，就先不做前向检查。
# ⚠️ 定得低（0.15）是刻意的：剪枝是**学习式**的，有剪错风险；
#    配合 plan_predictive 里的「延迟重试」保险，最坏情况等价于不剪枝。
#    定高（如 0.5）会剪得更狠，但会把大量真正可行的分支也拖到重试路径上，
#    反而**增加**扩展数 —— 所以它不是一个"越大越好"的旋钮。
PRED_PRUNE_BELOW = 0.15
# 同预算对比用的**节点扩展预算**。两侧都跑满这个预算，
# 于是「每次尝试更省」的那侧能跑更多次重启 —— 这是把「省扩展」换算成
# 「同预算下解更好」的桥梁（见 predictor_ablation 的注释）。
EXPANSION_BUDGET = 4000
# ⚠️ 重启次数必须**放开**，否则「省下的扩展」换不成「更多重启」：
#    两侧都被 max_restarts=3 卡住时，省下的预算根本用不出去，
#    对比就退化成「谁先停谁运气好」。放开后**扩展预算成为唯一约束**，
#    于是「每次尝试更省」的一侧自然能跑更多次重启 → 这才是可比的。
MAX_RESTARTS_ABLATION = 60
def scen_to_plan_inputs(scen: SuperScenario):
    """把 ``SuperScenario`` 转成 ``plan_predictive`` 的输入。

    ⚠️ 三个口径必须与 ``decode()`` 一致，否则两边不可比：

    * **占用时长 = duration + interval**：解码器里一个单元占的是「比赛 + 间隔」两段，
      只算 duration 会低估占用、算出假可行；
    * **槽 = (day, window_idx)**：同桶多场地并行；
    * **容量键 = (槽序号, 场地)**：同槽同场地才共用容量。
    """
    buckets = sorted({(w.day, w.window_idx) for w in scen.windows})
    idx = {b: i for i, b in enumerate(buckets)}
    caps: Dict[Tuple[int, str], int] = {}
    for w in scen.windows:
        k = (idx[(w.day, w.window_idx)], w.venue)
        caps[k] = caps.get(k, 0) + int(w.capacity)
    units = [SimpleNamespace(key=u.key, venue=u.venue,
                             duration=int(u.duration) + int(u.interval),
                             group_key=u.group_key, athletes=list(u.athletes))
             for u in scen.units]
    athletes = {u.key: list(u.athletes) for u in scen.units if u.athletes}
    venues_by_unit = {u.key: u.venue for u in scen.units}

    def candidates_of(u) -> List[int]:
        v = venues_by_unit[str(getattr(u, "key", ""))]
        return sorted({i for (i, vv) in caps if vv == v})

    day_budget = len({d for d, _ in buckets})
    return units, caps, athletes, candidates_of, day_budget


def train_predictor(tier: str, seeds: Sequence[int], *, epochs: int = 300,
                    hidden: int = 32, max_states: int = 1200) -> CompletionPredictor:
    """在**该档位**的场景分布上训练完成性预测器。

    ⚠️ 必须按档位分训而不是训一个通用预测器：不同档位的单元数、槽数、
    容量紧张度差别巨大，一个在 HELL 上校准过的偏移量用在 TEAM 上会**过度保守**
    （把大量分支判成「排不完」），反而增加搜索量。
    """
    feats_all: List[np.ndarray] = []
    labels_all: List[np.ndarray] = []
    for sd in seeds:
        scen = generate_super_scenario(tier, seed=sd)
        units, caps, ath, cands, db = scen_to_plan_inputs(scen)
        # ⚠️ 必须用**完整单元集**打标签，不能只取前 18 个：
        #    早期为了压成本只取子集，结果子问题在满容量下**总能排完**，
        #    标签 100% 是 1，预测器退化成常数（没有区分力）。
        #    改用 rollout 标签后成本是线性的（每状态一次贪心补全），
        #    全量 50+ 单元也就几秒 —— 而只有全量才能采到「排不完」的状态。
        try:
            f, l = collect_training_pairs(units, candidates_of=cands, caps=caps,
                                          athletes=ath, day_budget=db,
                                          max_states=max_states, seed=int(sd))
        except Exception:                                    # noqa: BLE001
            continue
        if len(l):
            feats_all.append(f)
            labels_all.append(l)
    if not feats_all:
        raise SystemExit(f"档位 {tier} 没有采到任何预测标签，无法训练预测器")
    feats = np.concatenate(feats_all, axis=0)
    labels = np.concatenate(labels_all, axis=0)
    pred = CompletionPredictor(hidden=hidden, seed=0)
    pred.fit(feats, labels, epochs=epochs)
    pred.calibrate(feats, labels, quantile=1.0)
    return pred


def predictor_ablation(scen: SuperScenario, pred: Optional[CompletionPredictor],
                       seed: int = 0) -> Dict[str, float]:
    """跑「不带预测器」与「带预测器」两次规划，对比**节点扩展数**与解质量。

    这是 arXiv:2606.04860 的核心评价方式：**同解质量下看搜索量**。
    只看解质量是看不出预测器价值的（两者最终都能找到类似解），
    价值在于「少检查多少注定失败的候选」—— 那就是 ``expansions``。
    """
    units, caps, athletes, cands, db = scen_to_plan_inputs(scen)
    out: Dict[str, float] = {}

    # ⚠️ **公平口径：同扩展预算**。
    # 首次成功即跳出的贪心里，「剪枝」必然会改变决策（被剪的候选若其实是首个可行槽，
    # 就会改选后面的）—— 所以不可能同时做到「省扩展」与「解逐位一致」。
    # 论文（2606.04860）比的是「同解质量下更少扩展」；在本问题上等价且可实现的
    # 口径是**同预算比解质量**：预测器每次尝试更省 → 同样预算能跑更多次重启。
    budget = EXPANSION_BUDGET
    base = plan_predictive(units, candidates_of=cands, caps=caps, athletes=athletes,
                           day_budget=db, seed=seed,
                           max_restarts=MAX_RESTARTS_ABLATION,
                           expansion_budget=budget)
    lb = base.log.as_dict()
    out["plan_expansions"] = float(lb["expansions"])
    out["plan_cost"] = float(base.value)
    out["plan_unplaced"] = float(len(base.blocked))
    out["plan_feasible"] = 1.0 if base.feasible else 0.0

    if pred is not None:
        guided = plan_predictive(units, candidates_of=cands, caps=caps, athletes=athletes,
                                 predictor=pred, pred_prune_below=PRED_PRUNE_BELOW,
                                 day_budget=db, seed=seed,
                                 max_restarts=MAX_RESTARTS_ABLATION,
                                 expansion_budget=budget)
        lg = guided.log.as_dict()
        out["pred_expansions"] = float(lg["expansions"])
        out["pred_cost"] = float(guided.value)
        out["pred_unplaced"] = float(len(guided.blocked))
        out["pred_feasible"] = 1.0 if guided.feasible else 0.0
        out["pred_queries"] = float(lg["predictor_queries"])
        out["pruned"] = float(lg["pruned"])
        out["pruned_retried"] = float(lg["pruned_retried"])
        out["pred_restarts"] = float(lg["restarts"])
        out["plan_restarts"] = float(lb["restarts"])
        # 收益指标：正数表示预测器真的省了搜索量
        out["expansion_saving"] = out["plan_expansions"] - out["pred_expansions"]
        out["expansion_saving_pct"] = (
            100.0 * out["expansion_saving"] / max(1.0, out["plan_expansions"]))
        # 「省下的搜索量」相对「多花的预测调用」的性价比
        out["saving_per_query"] = out["expansion_saving"] / max(1.0, out["pred_queries"])
        # **同预算下的解质量对比**（这才是可实现的正确口径）：
        # 预测器省下的扩展会变成更多次重启，于是最终解应当不劣。
        out["cost_worse"] = 1.0 if out["pred_cost"] > out["plan_cost"] + 1e-9 else 0.0
        out["cost_better"] = 1.0 if out["pred_cost"] < out["plan_cost"] - 1e-9 else 0.0
        out["cost_delta"] = out["plan_cost"] - out["pred_cost"]

        # ---- 守卫式组合（生产口径）----
        # 纯剪枝会因为「改选了后面的候选」而扰动搜索结果，在 HELL/REGULAR 上
        # 反而略差（未排 9→10）。生产上不能接受这种不确定，所以加一层**取优**：
        # 最终解 = min{基线解, 预测器解} —— 于是**解质量单调不劣**是**结构保证**，
        # 不依赖预测器准不准。
        # 代价是要跑两遍（扩展数相加），这是「确定性」的价钱，必须如实报出来。
        out["guarded_cost"] = min(out["plan_cost"], out["pred_cost"])
        out["guarded_expansions"] = out["plan_expansions"] + out["pred_expansions"]
        out["guarded_not_worse"] = 1.0 if out["guarded_cost"] <= out["plan_cost"] + 1e-9 else 0.0
        out["guarded_overhead_pct"] = 100.0 * (
            out["guarded_expansions"] / max(1.0, out["plan_expansions"]) - 1.0)
    return out


def evaluate(model: SuperScheduleMoE, tier: str, seeds: Sequence[int],
             device: str = "cpu",
             predictors: Optional[Dict[str, CompletionPredictor]] = None
             ) -> List[Dict[str, object]]:
    model.eval()
    rows: List[Dict[str, object]] = []
    for sd in seeds:
        scen = generate_super_scenario(tier, seed=sd)
        data = encode_super_graph(scen)
        if data is None:
            continue
        n = int(data["n"])
        # ⚠️ batch 轴约定（编码器 / 训练 / Java 三端一致）：
        #    ``encode_super_graph`` 返回的四个张量**都已经带了 batch 轴**
        #    （node_feat [1,n,20]、adj_by_type [1,E,n,n]、type_mask [1,E]、mask [1,n]）。
        #    第一次写这里时又给四个都 unsqueeze 了一次：type_mask 变 [1,1,E]、node_feat 变
        #    [1,1,47,20]，ctx 被抬成四维，模型直接报
        #    "size of tensor a (47) must match tensor b (8) at non-singleton dimension 2"。
        #    判据：拿编码器原样喂，一个 unsqueeze 都不要加。
        #    这里的断言把该坑钉住：形状不对就直接炸，不要等训练跑完才发现。
        nf = torch.from_numpy(data["node_feat"]).to(device)                   # [1,n,20]
        ab = torch.from_numpy(data["adj_by_type"]).to(device)                 # [1,E,n,n]
        tm = torch.from_numpy(data["type_mask"]).to(device)                   # [1,E] 已带批
        mk = torch.from_numpy(data["mask"]).to(device)                        # [1,n] 已带批
        # 图级上下文：路由要看「整个赛会的结构」才能分工，喂全零等于把图级分支废掉，
        # 评测分数会平白低一截且看不出原因（图级路由是本轮架构升级的一部分）。
        gf_np = data.get("graph_feat")
        assert gf_np is not None, "编码器没产出 graph_feat，图级路由无从谈起"
        gf = torch.from_numpy(np.asarray(gf_np, dtype=np.float32)).to(device)  # [1,8]
        assert gf.dim() == 2 and gf.shape[-1] == 8, f"graph_feat 应为 [B,8]，实际 {tuple(gf.shape)}"
        assert tm.dim() == 2, f"type_mask 应为 [B,E]，实际 {tuple(tm.shape)}"
        assert mk.dim() == 2, f"mask 应为 [B,N]，实际 {tuple(mk.shape)}"
        assert nf.dim() == 3, f"node_feat 应为 [B,N,20]，实际 {tuple(nf.shape)}"
        assert ab.dim() == 4, f"adj 应为 [B,E,N,N]，实际 {tuple(ab.shape)}"
        with torch.no_grad():
            pri, slot, _task, _fmt, _days, _lane, _qual = model(nf, ab, tm, mk, gf)
        pri_np = pri[0].cpu().numpy()
        slot_np = slot[0].cpu().numpy()

        row: Dict[str, object] = {"tier": tier, "seed": sd, "n_units": n,
                                  "lower_bound": lower_bound(scen),
                                  "tiers_tasks": sorted({u.task for u in scen.units})}
        for mode in ("random", "greedy", "model"):
            row[mode] = decode(scen, n, pri_np, slot_np, mode)
        # ---- 遗传算法一条龙（纯算法基线）----
        # ⚠️ 基线**不喂任何模型输出**（slot_logits=None）：它代表「没有 AI 的纯算法链」。
        #    实测模型给的 slot_logits 在 HELL 档反而是负资产（用了 10 个未排，
        #    不用只有 7 个）—— 让基线也吃它，等于把模型的缺陷算进基线，对比失真。
        ga_order = ga_search(scen, n, None, seed=sd)
        row["ga"] = decode(scen, n, pri_np, None, "custom", order_override=ga_order)

        # ---- L4 混合链路：模型思维 + L2/L3 搜索思维 ----
        # 模型序是**种子**而不是答案：搜索在它的邻域里继续精修。
        # 预算给得比纯 GA 大，因为 L4 这一档本来就比 L3 重（分钟级 vs 秒级）。
        # 模型只贡献「它认为的排布顺序」，落桶交给传统搜索 —— 因为实测模型的
        # slot_logits 现在还会带偏落桶（待重训修正），顺序信息则确实是正向的。
        # 前沿方法一：用 MC Dropout 方差给出「模型最没把握的单元」
        unc = mc_uncertainty(model, nf, ab, tm, mk, gf, samples=5)
        model_order = sorted(range(n), key=lambda i: -float(pri_np[i]))
        # 多起点重启：单次 GA 方差大（换随机种子可能差好几个未排单元），
        # 而 L4 在真实系统里本就是分钟级链路（aiAdversarialRounds 默认 3 轮），
        # 跑多趟取最优才符合它的定位。每趟都带模型序当种子。
        best_order: Optional[List[int]] = None
        best_cost = math.inf
        for k in range(3):
            rk = int(sd) * 31 + k
            cand = ga_search(scen, n, None, seed=rk, pop=24, gens=30,
                             extra_seeds=[model_order])
            cand = lns_refine(scen, n, None, cand, seed=rk, rounds=8, kill=5,
                              uncertain=unc)
            c = cost_of(decode(scen, n, pri_np, None, "custom", order_override=cand))
            if c < best_cost:
                best_cost, best_order = c, cand
        row["ai"] = decode(scen, n, pri_np, None, "custom", order_override=best_order)

        # ---- 规划层 + 完成性预测器对照（arXiv:2606.04860 的核心指标：节点扩展数）----
        # 预测器**按档位分别训练**（见 train_predictor 的说明）
        pred = predictors.get(tier) if predictors else None
        row["planner"] = predictor_ablation(scen, pred, seed=int(sd))
        rows.append(row)
    return rows


def summarize(rows: List[Dict[str, object]]) -> Dict[str, object]:
    out: Dict[str, object] = {}
    for mode in ("random", "greedy", "ga", "model", "ai"):
        acc: Dict[str, List[float]] = {}
        for r in rows:
            d = r[mode]                     # type: ignore[index]
            for k, v in d.items():
                if isinstance(v, (int, float)) and not isinstance(v, bool):
                    acc.setdefault(k, []).append(float(v))
        out[mode] = {k: round(float(np.mean(v)), 3) for k, v in acc.items()}

    # ---- 规划层消融汇总（每个档位一行，指标是**均值**）----
    planner: Dict[str, Dict[str, float]] = {}
    for r in rows:
        tier = str(r["tier"])
        pp = r.get("planner") or {}
        if not pp:
            continue
        acc_p = planner.setdefault(tier, {})
        for k, v in pp.items():                      # type: ignore[union-attr]
            if isinstance(v, (int, float)) and not isinstance(v, bool):
                acc_p.setdefault(k, []).append(float(v))
    out["planner"] = {t: {k: round(float(np.mean(v)), 3) for k, v in acc.items()}
                      for t, acc in planner.items()}
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", default=os.path.join(ROOT, "models", "super_moe.pt"))
    ap.add_argument("--seeds", type=int, default=3)
    ap.add_argument("--device", default="cpu")
    ap.add_argument("--out", default=os.path.join(ROOT, "models",
                                                 "super_moe_eval.json"))
    # 规划层消融：训练完成性预测器并与「无预测器」对照节点扩展数。
    # 默认开启 —— 它是本轮的核心结论产出口，关掉会静默少一整个分析维度。
    ap.add_argument("--with-predictor", dest="with_predictor",
                    action="store_true", default=True)
    ap.add_argument("--no-predictor", dest="with_predictor",
                    action="store_false", help="跳过预测器消融（加速）")
    # 预测器用**独立于评测**的种子训练，避免「在评测集上训练 → 收益虚高」。
    # 评测用 range(seeds)（0..n-1），预测器用 1000+ 起，两组不相交。
    ap.add_argument("--pred-seeds", type=int, default=4)
    ap.add_argument("--pred-offset", type=int, default=1000)
    args = ap.parse_args()

    if not os.path.exists(args.ckpt):
        raise SystemExit(f"缺少权重 {args.ckpt}，先跑 train_super_moe")
    ck = torch.load(args.ckpt, map_location="cpu")
    # ⚠️ 构造参数必须来自权重 meta：深层版 SuperScheduleMoE 的 hidden / expert_depth /
    #    n_global 会改变参数形状，写死 SuperScheduleMoE() 的默认 192/3/2 去加载
    #    hidden=128 的权重会 load_state_dict 直接炸，而且报错信息完全看不出是这里。
    meta = ck["meta"] if isinstance(ck, dict) and "meta" in ck else {}
    model = SuperScheduleMoE(
        hidden=int(meta.get("hidden", 192)),
        steps=int(meta.get("steps", 8)),
        expert_depth=int(meta.get("expert_depth", 2)),
        n_global=int(meta.get("n_global", 3)),
    )
    # 兼容两种落盘格式：{"state_dict":…, "meta":…} 与旧版裸 state_dict
    model.load_state_dict(ck["state_dict"] if isinstance(ck, dict) and "state_dict" in ck else ck)
    model.to(args.device)
    # 权重「训够没有」也要看得见：meta 里带了实际轮数与建议预算，
    # 否则只盯 val_loss 很容易把「预算不足」误读成「模型不行」。
    if isinstance(meta, dict) and meta:
        print(f"[ckpt] hidden={meta.get('hidden')} steps={meta.get('steps')} "
              f"expert_depth={meta.get('expert_depth')} n_global={meta.get('n_global')} "
              f"epochs_run={meta.get('epochs_run', '?')} "
              f"budget_epochs={meta.get('budget_epochs', '?')} "
              f"satisfied={meta.get('budget_satisfied', '?')}")

    # ---- 按档位训练完成性预测器（用独立种子，避免在评测集上训练）----
    predictors: Optional[Dict[str, CompletionPredictor]] = None
    if args.with_predictor:
        predictors = {}
        pred_seeds = [args.pred_offset + i for i in range(max(1, args.pred_seeds))]
        for tier in TIERS:
            try:
                pr = train_predictor(tier, pred_seeds)
                predictors[tier] = pr
                print(f"[predictor] {tier:<8} 训练集 {len(pred_seeds)} 个场景 → 已校准")
            except SystemExit as ex:
                print(f"[predictor] {tier:<8} 跳过：{ex}")

    all_rows: List[Dict[str, object]] = []
    for tier in TIERS:
        rows = evaluate(model, tier, range(args.seeds), args.device,
                        predictors=predictors)
        s = summarize(rows)
        all_rows.extend(rows)
        r = s["model"]
        g = s["greedy"]
        lb = rows[0]["lower_bound"]                        # type: ignore[index]
        flag = "" if (lb["cap_slack"] >= 0 and lb["ath_slack"] >= 0) else "  ⚠️场景本身无解"
        print(f"{tier:<8} 下界 桶={lb['n_buckets']:<4.0f} 容量余={lb['cap_slack']:<7.0f} "
              f"人次余={lb['ath_slack']:<8.0f} 均时长={lb['avg_dur']}{flag}")
        print(f"{tier:<8} 单元={s['model']['n_units']:<4} "
              f"未排 {r['unplaced']:<5.1f} 兼项撞 {r['athlete_clash']:<5.1f} "
              f"超占 {r['capacity_overflow']:<6.1f} 碎块 {r['frag_blocks']:<4.1f} "
              f"道次撞 {r['lane_clash']:<4.1f} 工期 {r['days_used']}/{r['days_limit']} "
              f"修复 {s['model'].get('repairs', 0):<4.0f} 修不了 {s['model'].get('blocked', 0):<3.0f}")
        print(f"{'':<8} 对比 greedy → 未排 {r['unplaced']}-{g['unplaced']:<5.1f} "
              f"兼项撞 {r['athlete_clash']}-{g['athlete_clash']:<5.1f} "
              f"超占 {r['capacity_overflow']}-{g['capacity_overflow']:<6.1f} "
              f"碎块 {r['frag_blocks']}-{g['frag_blocks']}")
        ai = s.get("ai", {})
        ga = s.get("ga", {})
        print(f"{'':<8} 对比 GA一条龙 → 未排 {r['unplaced']}-{ga.get('unplaced', 0):<5.1f} "
              f"兼项撞 {r['athlete_clash']}-{ga.get('athlete_clash', 0):<5.1f} "
              f"超占 {r['capacity_overflow']}-{ga.get('capacity_overflow', 0):<6.1f} "
              f"碎块 {r['frag_blocks']}-{ga.get('frag_blocks', 0):<4.1f}")
        print(f"{'':<8} 对比 AI混合 → 未排 {ai.get('unplaced', 0):<5.1f} "
              f"兼项撞 {ai.get('athlete_clash', 0):<5.1f} "
              f"超占 {ai.get('capacity_overflow', 0):<6.1f} "
              f"碎块 {ai.get('frag_blocks', 0):<4.1f} "
              f"（AI 需 ≤ GA：{ai.get('unplaced', 0)} vs {ga.get('unplaced', 0)} "
              f"{'✅ 通过' if ai.get('unplaced', 0) <= ga.get('unplaced', 0) else '❌ 未通过'}）")

    # ---- 规划层消融：节点扩展数（arXiv:2606.04860 的核心指标）----
    if predictors:
        print("\n===== 规划层消融：完成性预测器 vs 无预测器（节点扩展数）=====")
        print(f"{'档位':<9}{'无预测扩展':>10}{'带预测扩展':>11}{'节省':>8}{'节省%':>8}"
              f"{'预测调用':>9}{'每次预测省':>11}{'解代价(无/带)':>18}{'未排(无/带)':>12}")
        gm = summarize(all_rows).get("planner", {})          # type: ignore[arg-type]
        tot_base = tot_pred = 0.0
        for tier in TIERS:
            m = gm.get(tier) if isinstance(gm, dict) else None
            if not m:
                continue
            tot_base += m.get("plan_expansions", 0.0)
            tot_pred += m.get("pred_expansions", 0.0)
            print(f"{tier:<9}{m.get('plan_expansions', 0):>10.1f}"
                  f"{m.get('pred_expansions', 0):>11.1f}"
                  f"{m.get('expansion_saving', 0):>8.1f}"
                  f"{m.get('expansion_saving_pct', 0):>7.1f}%"
                  f"{m.get('pred_queries', 0):>9.1f}"
                  f"{m.get('saving_per_query', 0):>11.2f}"
                  f"{str(round(m.get('plan_cost', 0), 1)) + '/' + str(round(m.get('pred_cost', 0), 1)):>18}"
                  f"{str(round(m.get('plan_unplaced', 0), 1)) + '/' + str(round(m.get('pred_unplaced', 0), 1)):>12}")
        if tot_base > 0:
            print(f"{'合计':<9}{tot_base:>10.1f}{tot_pred:>11.1f}"
                  f"{tot_base - tot_pred:>8.1f}"
                  f"{100.0 * (tot_base - tot_pred) / tot_base:>7.1f}%")

    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(all_rows, fh, ensure_ascii=False, indent=2)
    print(f"\n明细 -> {args.out}")


if __name__ == "__main__":
    main()
