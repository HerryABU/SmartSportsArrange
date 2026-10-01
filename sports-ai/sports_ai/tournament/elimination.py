"""淘汰赛对阵图生成：单淘汰 / 双淘汰。

单淘汰：N 队，每轮淘汰一半。N 不是 2 的幂时补「轮空（Bye）」——轮空数 = P - N
（P 为 ≥N 的最小 2 的幂），高种子优先获得轮空；种子按标准 bracket 排位保证
1/2 号种子分居不同半区、决赛才相遇。

双淘汰：每队输两次才出局，分胜者组 + 败者组。
"""

from __future__ import annotations

from typing import Dict, List, Optional

from .seeding import seed_positions


def _next_pow2(n: int) -> int:
    p = 1
    while p < n:
        p *= 2
    return p


def single_elimination(teams: List[str], seeds: Optional[List[int]] = None) -> List[Dict]:
    """单淘汰对阵图（含轮空 + 种子分布）。

    ``seeds``：可选，与 ``teams`` 等长，数值越小种子越高（1 号种子）。缺省按 teams 顺序。
    返回 ``[{round, slot, home, away, bye}]``；``bye=True`` 表示该场对手轮空、home 直接晋级。
    """
    n = len(teams)
    if n < 2:
        return []
    p = _next_pow2(n)
    order = seed_positions(p)                    # 位置 → 种子号（1..p），>n 即轮空
    by_rank = {}
    for i, t in enumerate(teams):
        rank = seeds[i] if seeds else i + 1
        by_rank[rank] = t

    matches: List[Dict] = []
    # 第一轮
    rnd = 1
    for slot in range(p // 2):
        s_home, s_away = order[2 * slot], order[2 * slot + 1]
        home = by_rank.get(s_home)
        away = by_rank.get(s_away)
        if home is None and away is None:
            continue
        if home is None or away is None:
            matches.append({"round": rnd, "slot": slot + 1,
                            "home": home or away, "away": None, "bye": True})
        else:
            matches.append({"round": rnd, "slot": slot + 1,
                            "home": home, "away": away, "bye": False})
    # 后续轮次（占位，标注轮次与场次供赛程编排填充）
    cur = p // 2
    rnd = 2
    while cur > 1:
        cur //= 2
        for slot in range(cur):
            matches.append({"round": rnd, "slot": slot + 1,
                            "home": None, "away": None, "bye": False,
                            "placeholder": True})
        rnd += 1
    return matches


def double_elimination(teams: List[str]) -> Dict[str, List[Dict]]:
    """双淘汰：返回 ``{"winners": [...], "losers": [...], "final": [...]}``。"""
    w = single_elimination(teams)
    n = len(teams)
    # 败者组轮次随胜者组递进，这里给出结构占位（§ 赛程编排据此填槽）
    losers = [{"round": r + 1, "slot": s + 1, "home": None, "away": None,
               "placeholder": True}
              for r in range(max(1, 2 * (n.bit_length() - 1) - 1))
              for s in range(max(1, n // 2))]
    final = [{"round": 1, "slot": 1, "home": None, "away": None,
              "note": "胜者组冠军 vs 败者组冠军（若胜者组冠军首败则加赛一场）"}]
    return {"winners": w, "losers": losers, "final": final}


def rounds_of(matches: List[Dict]) -> int:
    return max((m["round"] for m in matches), default=0)
