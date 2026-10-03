"""球类赛制训练数据：4 类约束边 + 14 维节点特征 + 自监督标签。

## 标签怎么来的（自监督，不需要人工标注的最优赛制）

三个标签都**由规则引擎实跑出来的真实代价**反推，而不是人工拍脑袋：

1. **赛制标签**（分类）：把 ``round_robin`` / ``elimination`` / ``hybrid`` 三个赛制
   在同一份参赛队上**都跑一遍**，按真实代价（总场次×耗时 + 队伍等待轮数 + 同班早遇惩罚）
   选最优者。这是「穷举取最优」——规则引擎成了**标签生成器**，AI 学的是
   「从约束结构推断该用哪个赛制」这个映射。
2. **种子排序标签**（回归）：用 ``seeding`` + 局部搜索找一个**强队不相遇**的排列，
   拿该排列下每队的「种子理想分」当目标。
3. **公平性标签**（回归）：每队「对手实力方差」——方差越小越公平，归一化后作目标。

⚠️ **标签必须与输入同源**（这是异构图那轮踩过的坑：贪心顺序作标签 → Spearman 0.08）。
这里的标签全部由「边所表达的关系」直接算出，模型能学。
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

import numpy as np

from sports_ai.models.tournament_gnn import (
    N_TYPES,
    NODE_FEAT_DIM,
    T_ROUND,
    T_SAME_CLASS,
    T_STRENGTH,
    T_VENUE,
)

# 赛制枚举（与 Java 端 TournamentAdvisor.FORMATS 顺序一致）
FORMAT_ROUND_ROBIN = 0
FORMAT_ELIMINATION = 1
FORMAT_HYBRID = 2
N_FORMATS = 3
FORMAT_NAMES = {FORMAT_ROUND_ROBIN: "round_robin",
                FORMAT_ELIMINATION: "elimination",
                FORMAT_HYBRID: "hybrid"}


@dataclass
class BallTeam:
    """一支参赛队（或一名参赛者，拔河按班分队）。"""
    name: str
    strength: float          # 实力 0..1（1=最强）
    unit: str                # 所属单位（班/年级）——同队应尽量错开
    venue_pref: str = "主"    # 偏好场地
    games_played: int = 0
    rest_need: float = 0.0   # 需要的休息强度 0..1


@dataclass
class BallVenue:
    name: str
    n_courts: int = 1


@dataclass
class BallTournament:
    teams: List[BallTeam]
    venues: List[BallVenue]
    minutes_per_match: int = 40
    available_slots: int = 0        # 可用时段数（0=不限）
    days: int = 1
    sport: str = "篮球"
    format_name: str = ""
    schedule: List[Dict] = field(default_factory=list)


# ---------------------------------------------------------------------------
# 场景生成
# ---------------------------------------------------------------------------

SPORTS = ["篮球", "排球", "足球", "拔河", "乒乓球", "羽毛球"]


def generate_ball_tournament(n_teams: int, seed: int, sport: str = "",
                             n_venues: int = 3, courts_per_venue: int = 2,
                             available_slots: int = 0, days: int = 1) -> BallTournament:
    """生成一份球类赛参赛场景。

    覆盖规模：拔河常见 8~24 支（按班分队），篮球/排球 4~16 支。
    """
    rng = random.Random(seed)
    if not sport:
        sport = rng.choice(SPORTS)
    units = [f"高{i}班" for i in range(1, 7)] + [f"初{i}班" for i in range(1, 7)]
    teams: List[BallTeam] = []
    for i in range(n_teams):
        # 实力分布：强者少数。用 Beta(2,3) 偏向下，模拟「强队少」的常态
        st = min(1.0, max(0.0, rng.betavariate(2.0, 3.0)))
        teams.append(BallTeam(
            name=f"T{i:02d}",
            strength=st,
            # 同班扎堆：前 40% 的队来自 3 个班，制造同班早遇的真实风险
            unit=rng.choice(units[:3]) if rng.random() < 0.4 else rng.choice(units),
            venue_pref=rng.choice(["A", "B", "C", "D"]),
            rest_need=rng.random(),
        ))
    venues = [BallVenue(name=chr(ord("A") + i), n_courts=courts_per_venue)
              for i in range(max(1, n_venues))]
    slots = available_slots or (days * 8)
    return BallTournament(teams=teams, venues=venues,
                          minutes_per_match=40 + rng.choice([0, 0, 10]),
                          available_slots=slots, days=days, sport=sport)


# ---------------------------------------------------------------------------
# 图编码：4 类约束边 + 14 维节点特征
# ---------------------------------------------------------------------------

def _bucket(v: float, nb: int) -> int:
    return min(nb - 1, max(0, int(v * nb)))


def encode_ball_graph(t: BallTournament) -> Dict[str, np.ndarray]:
    """把球类场景编成 4 通道图。

    边类型语义：
      T_STRENGTH  实力相近（同档位）——实力接近的队伍相遇观赏性/风险都更高
      T_SAME_CLASS 同单位（同班同队）——**应该错开**，早遇不公平
      T_ROUND     同轮次——同轮必须能塞进现有场地数
      T_VENUE     偏好同场地——同场地同时开赛会冲突
    """
    n = len(t.teams)
    adj = np.zeros((N_TYPES, n, n), dtype=np.float32)
    tmask = np.zeros(N_TYPES, dtype=np.float32)

    # ---- T_STRENGTH：实力相近（4 档） ----
    NB = 4
    buckets: Dict[Tuple[int, int], List[int]] = {}
    for i, tm in enumerate(t.teams):
        buckets.setdefault((_bucket(tm.strength, NB), 0), []).append(i)
    for idxs in buckets.values():
        if len(idxs) < 2:
            continue
        press = min(1.0, len(idxs) / max(1, n))
        for x in range(len(idxs)):
            for y in range(x + 1, len(idxs)):
                a, b = idxs[x], idxs[y]
                w = 1.0 - abs(t.teams[a].strength - t.teams[b].strength)  # 越接近越强
                adj[T_STRENGTH, a, b] = adj[T_STRENGTH, b, a] = max(w, press * 0.3)
        tmask[T_STRENGTH] = 1.0

    # ---- T_SAME_CLASS：同单位 ----
    by_unit: Dict[str, List[int]] = {}
    for i, tm in enumerate(t.teams):
        by_unit.setdefault(tm.unit, []).append(i)
    for idxs in by_unit.values():
        if len(idxs) < 2:
            continue
        press = min(1.0, len(idxs) / max(1, n))
        for x in range(len(idxs)):
            for y in range(x + 1, len(idxs)):
                a, b = idxs[x], idxs[y]
                adj[T_SAME_CLASS, a, b] = adj[T_SAME_CLASS, b, a] = press
        tmask[T_SAME_CLASS] = 1.0

    # ---- T_ROUND：同轮（按现有 round_robin 规则生成一版参考赛程） ----
    from sports_ai.tournament.round_robin import round_robin
    from sports_ai.tournament.seeding import distribute_seeds
    name2idx = {tm.name: i for i, tm in enumerate(t.teams)}
    ordered = distribute_seeds([tm.name for tm in t.teams], "rank")
    ref = round_robin(ordered, double=False)
    round_of: Dict[int, int] = {}
    for m in ref:
        h, a = name2idx.get(m["home"]), name2idx.get(m["away"])
        if h is None or a is None:
            continue
        round_of.setdefault(h, m["round"])
        round_of.setdefault(a, m["round"])
        # 同轮的两个队之间建边（说明它们被安排在同一轮）
        if round_of.get(h) == round_of.get(a) == m["round"]:
            adj[T_ROUND, h, a] = adj[T_ROUND, a, h] = 1.0
            tmask[T_ROUND] = 1.0
    t.schedule = ref

    # ---- T_VENUE：同偏好场地 ----
    by_pref: Dict[str, List[int]] = {}
    for i, tm in enumerate(t.teams):
        by_pref.setdefault(tm.venue_pref, []).append(i)
    for idxs in by_pref.values():
        if len(idxs) < 2:
            continue
        press = min(1.0, len(idxs) / max(1, n))
        for x in range(len(idxs)):
            for y in range(x + 1, len(idxs)):
                a, b = idxs[x], idxs[y]
                adj[T_VENUE, a, b] = adj[T_VENUE, b, a] = press
        tmask[T_VENUE] = 1.0

    # ---- 14 维节点特征 ----
    feat = np.zeros((n, NODE_FEAT_DIM), dtype=np.float32)
    strengths = [tm.strength for tm in t.teams] or [1.0]
    s_max, s_min = max(strengths), min(strengths)
    s_rng = (s_max - s_min) or 1.0
    n_rounds = max(1, len(set(round_of.values())))
    for i, tm in enumerate(t.teams):
        unit_size = len(by_unit.get(tm.unit, []))
        pref_size = len(by_pref.get(tm.venue_pref, []))
        feat[i] = [
            tm.strength,                                   # 0 实力
            (tm.strength - s_min) / s_rng,                  # 1 实力归一
            _bucket(tm.strength, 4) / 3.0,                  # 2 实力档位
            n / 32.0,                                       # 3 规模（越大越难）
            len(by_unit) / max(1, len(units_of(t))) / 1.0,   # 4 单位数占比
            unit_size / max(1, n),                          # 5 同单位扎堆度
            min(unit_size, 8) / 8.0,                        # 6 同单位规模
            tm.venue_pref and pref_size / max(1, n),        # 7 同场地偏好扎堆
            len(t.venues) / 4.0,                            # 8 场地数
            sum(v.n_courts for v in t.venues) / 8.0,        # 9 总场地数
            t.minutes_per_match / 60.0,                     # 10 单场耗时
            t.available_slots / 32.0,                       # 11 可用时段
            t.days / 4.0,                                   # 12 天数
            round_of.get(i, 0) / n_rounds,                  # 13 参考赛程轮次占比
        ]
    mask = np.ones(n, dtype=np.float32)
    return {"node_feat": feat[None], "adj_by_type": adj[None],
            "type_mask": tmask[None], "mask": mask[None], "n": n}


def units_of(t: BallTournament) -> set:
    return {tm.unit for tm in t.teams}


# ---------------------------------------------------------------------------
# 自监督标签：穷举赛制取最优
# ---------------------------------------------------------------------------

def _fairness_penalty(t: BallTournament) -> float:
    """同单位早遇惩罚：同班队伍在首轮相遇是最刺眼的不公平。"""
    from sports_ai.tournament.round_robin import round_robin
    from sports_ai.tournament.seeding import distribute_seeds
    ordered = distribute_seeds([tm.name for tm in t.teams], "rank")
    sch = round_robin(ordered, double=False)
    by_name = {tm.name: tm for tm in t.teams}
    pen = 0.0
    for m in sch:
        if m["round"] == 1:
            h, a = by_name.get(m["home"]), by_name.get(m["away"])
            if h and a and h.unit == a.unit:
                pen += 1.0
    return pen


def _eval_format(t: BallTournament, fmt: int) -> Optional[float]:
    """在给定赛制下真实跑一遍，返回总代价（越小越好）。排不出来返回 None。"""
    names = [tm.name for tm in t.teams]
    n = len(names)
    if n < 2:
        return None
    try:
        from sports_ai.tournament.round_robin import round_robin
        from sports_ai.tournament.elimination import single_elimination
        from sports_ai.tournament.hybrid import hybrid_schedule

        if fmt == FORMAT_ROUND_ROBIN:
            if n > 12:                 # 循环赛场次爆炸
                return None
            sch = round_robin(names, double=False)
            groups = 1
        elif fmt == FORMAT_ELIMINATION:
            sch = single_elimination(names)
            groups = 0
        else:
            g = 2 if n >= 6 else 2
            struct = hybrid_schedule(names, n_groups=g, advance_per_group=2)
            from sports_ai.tournament.adapt import structure_to_tasks
            tasks = structure_to_tasks(struct, minutes_per_match=t.minutes_per_match)
            sch = [{"round": getattr(x, "round_index", 1),
                    "home": getattr(x, "home", "?"), "away": getattr(x, "away", "?")}
                   for x in tasks]
            groups = g
    except Exception:
        return None
    if not sch:
        return None

    n_rounds = max(1, max(m["round"] for m in sch))
    # 参赛覆盖率：有多少队真的上场了。淘汰赛里早早出局的队只打 1 场，
    # 对运动会「人人有份」的初衷是伤害——**这一项必须计入代价**，
    # 否则循环赛永远因为场次多而落选，标签退化成「按规模选赛制」的硬映射。
    played = {m["home"] for m in sch} | {m["away"] for m in sch}
    coverage = len(played) / max(1, n)
    # 代价 = 场次×耗时 + 轮数×等待 + 规模惩罚 + 同班早遇 − 覆盖率收益
    cost = (len(sch) * t.minutes_per_match
            + n_rounds * 25
            + (n * 2 if groups else 0)
            + _fairness_penalty(t) * 60
            - coverage * n * t.minutes_per_match * 0.9)
    return cost


def ball_labels(t: BallTournament, n: int) -> Optional[Dict[str, np.ndarray]]:
    """穷举三个赛制取最优，生成赛制/种子/公平性三类标签。"""
    costs: Dict[int, float] = {}
    for f in range(N_FORMATS):
        c = _eval_format(t, f)
        if c is not None:
            costs[f] = c
    if not costs:
        return None
    best = min(costs, key=lambda k: costs[k])
    # 不可行的赛制给一个**有限**的大惩罚（不是 1e4）：留 1e4 会让 softmax 梯度饱和。
    finite = np.full(N_FORMATS, 3.0, dtype=np.float32)
    mx = max(costs.values()) or 1.0
    for f, c in costs.items():
        finite[f] = c / mx
    # ⚠️ **软目标而非 one-hot**。若只留 argmax，标签会退化成「按规模选赛制」的硬映射
    #（实测只有 2 类出现，且 n=4~6 恒 hybrid、n>=8 恒 elimination），
    # 第三类彻底学不到，且模型学不到「为什么另一个赛制次优」。
    # TimePrism（arXiv:2509.19975）的做法：softmax(-cost/T) 给出每个赛制的**概率**，
    # 既保留 argmax 可导出性，又让梯度携带完整的代价序信息。
    z = -finite * 3.0
    z -= z.max()
    e = np.exp(z)
    probs = (e / e.sum()).astype(np.float32)

    # 种子理想分：强队应该拿到「不易早遇」的位置 → 用名次分近似
    order = np.argsort([-tm.strength for tm in t.teams])
    seed_t = np.zeros(n, dtype=np.float32)
    for pos, i in enumerate(order):
        seed_t[i] = 1.0 - pos / max(1, n - 1) if n > 1 else 1.0

    # 公平性目标：对手实力方差（低=公平）
    strengths = np.array([tm.strength for tm in t.teams], dtype=np.float32)
    fair_t = np.zeros(n, dtype=np.float32)
    for i in range(n):
        opp = [strengths[j] for j in range(n) if j != i]
        fair_t[i] = float(np.var(opp)) if opp else 0.0
    if fair_t.max() > 0:
        fair_t = fair_t / fair_t.max()
    return {"format": probs, "format_costs": finite,
            "seed": seed_t, "fair": fair_t, "best_format": best}
