"""淘汰赛对阵图生成：单淘汰 / 双淘汰。

单淘汰：N 队，每轮淘汰一半。N 不是 2 的幂时补「轮空（Bye）」——轮空数 = P - N
（P 为 ≥N 的最小 2 的幂），高种子优先获得轮空；种子按标准 bracket 排位保证
1/2 号种子分居不同半区、决赛才相遇。

三条真实约束：
- **轮空给高种子**：由标准 seed_positions 结构性保证（种子号 > N 的位置即轮空位）；
- **同单位回避**：同一个班级/年级/学校的队伍尽量**不要在第一轮就相遇**——这是校园赛事的
  硬性惯例（同班两队首轮互淘汰，班主任会直接找上来）。做法是在种子位之间做一次
  **局部交换爬山**（见 :func:`_reduce_same_unit_clashes`），把同单位对局压到 0；
- **双淘汰真实结构**：败者组不是一串占位符，而是与胜者组**交错递进**的确定轮次，
  每个场次都标明「接收谁的败者 / 谁的胜者」，赛程编排据此就能填槽。

双淘汰（P 队，k = log2(P)）的结构：
```
胜者组 WB：R1 → R2 → … → Rk            （k 轮）
败者组 LB：L1 … L(2k-2)                （2k-2 轮，与 WB 交错）
   L1 ← WB-R1 的败者两两相打
   L2 ← L1 胜者 与 WB-R2 败者
   …（奇偶轮交替：奇数轮「LB 内部淘汰」，偶数轮「接收 WB 败者」）
总决赛 GF：WB 冠军 vs LB 冠军（WB 冠军若首败则需加赛一场 —— 双败规则）
```
"""

from __future__ import annotations

import math
import random
from typing import Dict, List, Optional

from .seeding import seed_positions


def _next_pow2(n: int) -> int:
    p = 1
    while p < n:
        p *= 2
    return p


def _reduce_same_unit_clashes(order: List[int], by_rank: Dict[int, str],
                              unit_of: Dict[str, str], trials: int = 400) -> int:
    """局部交换爬山：把「第一轮同单位对局」压到最少。返回剩余的冲突场次数。

    <p>只交换**两支队伍占据的种子位**（不改变位置集合），因此轮空位与 bracket 形状不变；
    由于只在代价下降时接受，种子秩序在「不与同单位回避冲突」的前提下得以保留——
    这正确表达了优先级：**同单位回避是硬约束，种子分布是偏好**。</p>
    """
    ranks = [r for r, t in by_rank.items() if t is not None]

    def clashes() -> int:
        c = 0
        for k in range(0, len(order), 2):
            t1, t2 = by_rank.get(order[k]), by_rank.get(order[k + 1])
            if t1 and t2 and unit_of.get(t1) and unit_of.get(t1) == unit_of.get(t2):
                c += 1
        return c

    best = clashes()
    if best == 0 or len(ranks) < 2:
        return best
    rng = random.Random(20260918)
    for _ in range(trials):
        if best == 0:
            break
        r1, r2 = rng.sample(ranks, 2)
        by_rank[r1], by_rank[r2] = by_rank[r2], by_rank[r1]
        cur = clashes()
        if cur < best:
            best = cur
        else:
            by_rank[r1], by_rank[r2] = by_rank[r2], by_rank[r1]
    return best


def single_elimination(teams: List[str], seeds: Optional[List[int]] = None,
                       units: Optional[List[str]] = None) -> List[Dict]:
    """单淘汰对阵图（含轮空 + 种子分布 + 同单位回避）。

    :param teams: 队伍名列表
    :param seeds: 可选，与 teams 等长，数值越小种子越高（1 号种子）。缺省按 teams 顺序。
    :param units: 可选，与 teams 等长的「单位标签」（班级/年级/学校）。
        给定后会做同单位回避，尽量不让同单位队伍第一轮相遇。
    :return: ``[{round, slot, home, away, bye, placeholder, sameUnit}]``
    """
    n = len(teams)
    if n < 2:
        return []
    p = _next_pow2(n)
    order = seed_positions(p)                    # 位置 → 种子号（>n 即轮空）
    by_rank: Dict[int, str] = {}
    for i, t in enumerate(teams):
        rank = seeds[i] if seeds else i + 1
        by_rank[rank] = t

    unit_of: Dict[str, str] = {}
    if units:
        for t, u in zip(teams, units):
            unit_of[t] = u
        _reduce_same_unit_clashes(order, by_rank, unit_of)

    matches: List[Dict] = []
    # 第一轮
    for slot in range(p // 2):
        s_home, s_away = order[2 * slot], order[2 * slot + 1]
        home = by_rank.get(s_home)
        away = by_rank.get(s_away)
        if home is None and away is None:
            continue
        if home is None or away is None:
            matches.append({"round": 1, "slot": slot + 1,
                            "home": home or away, "away": None, "bye": True,
                            "sameUnit": False})
        else:
            matches.append({"round": 1, "slot": slot + 1,
                            "home": home, "away": away, "bye": False,
                            "sameUnit": bool(unit_of) and unit_of.get(home) == unit_of.get(away)})
    # 后续轮次（占位，标注轮次与场次供赛程编排填充）
    cur = p // 2
    rnd = 2
    while cur > 1:
        cur //= 2
        for slot in range(cur):
            matches.append({"round": rnd, "slot": slot + 1,
                            "home": None, "away": None, "bye": False,
                            "placeholder": True, "sameUnit": False})
        rnd += 1
    return matches


def double_elimination(teams: List[str], seeds: Optional[List[int]] = None,
                       units: Optional[List[str]] = None) -> Dict[str, List[Dict]]:
    """双淘汰：返回 ``{"winners": [...], "losers": [...], "grandFinal": [...]}``。

    败者组轮次与胜者组**交错递进**，每个场次标注 ``feedsFrom``（它接收谁的败者/胜者），
    赛程编排可以直接据此排槽，不必再猜结构。
    """
    n = len(teams)
    if n < 2:
        return {"winners": [], "losers": [], "grandFinal": []}
    p = _next_pow2(n)
    k = int(math.log2(p))

    winners = single_elimination(teams, seeds, units)

    losers: List[Dict] = []
    for i in range(1, 2 * k - 1):                 # 败者组共 2k-2 轮
        # 奇数轮：LB 内部淘汰（场次 = LB 上一轮胜者两两相打）；
        # 偶数轮：接收 WB 对应轮的败者（场次 = LB 胜者数，恰好等于 WB 败者数）
        exp = (i + 1) // 2 + 1 if i % 2 == 1 else i // 2 + 1
        cnt = max(1, p // (2 ** exp))
        src = (f"WB-R{i // 2} 的败者" if i % 2 == 0 else f"LB-L{i - 1} 的胜者")
        for slot in range(cnt):
            losers.append({
                "round": i, "slot": slot + 1,
                "home": None, "away": None, "placeholder": True,
                "bracket": "LB", "feedsFrom": src,
            })

    grand_final = [{
        "round": 1, "slot": 1, "home": None, "away": None, "placeholder": True,
        "bracket": "GF",
        "feedsFrom": "WB 冠军 vs LB 冠军",
        "note": "若 WB 冠军在总决赛首败，两队各负一场 → 需加赛一场决胜（双败规则）",
        "resetMatch": True,
    }]
    return {"winners": winners, "losers": losers, "grandFinal": grand_final}


def rounds_of(matches: List[Dict]) -> int:
    return max((m["round"] for m in matches), default=0)


def same_unit_clash_count(matches: List[Dict]) -> int:
    """统计对阵图里第一轮的同单位对局数（0 = 完全回避成功）。"""
    return sum(1 for m in matches if m.get("sameUnit") and m.get("round") == 1)
