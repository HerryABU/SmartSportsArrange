"""循环赛赛程生成：圆桌轮转法（Circle Method）。

N 支队伍：每队与其余 N-1 队各打一场，共 N(N-1)/2 场。
- N 为偶数 → 分 N-1 轮，每轮 N/2 场；
- N 为奇数 → 加一个「轮空（BYE）」队补成偶数，共 N 轮。

圆桌轮转：固定一支队（arr[0]），其余队绕圈轮转，每轮由「固定队 vs 轮转队」+
「轮转队两两配对」生成。保证每队每轮恰好一场、且与其余每队恰好交手一次。

约束：
- **主客场平衡**：用 (round + pair_index) 的奇偶交替分配主客，避免某队连续主场；
- **双循环**：第二轮主客场对调；
- **分组循环**：先分组，组内各自单循环。
"""

from __future__ import annotations

from typing import Dict, List, Optional


def round_robin(teams: List[str], double: bool = False,
                balance_home_away: bool = True) -> List[Dict]:
    """单/双循环赛程表。

    返回 ``[{round, home, away}, ...]``；``double=True`` 时含第二轮（主客对调）。
    """
    arr: List[Optional[str]] = list(teams)
    if len(arr) < 2:
        return []
    bye_added = False
    if len(arr) % 2 == 1:
        arr.append(None)          # 轮空
        bye_added = True
    n = len(arr)
    rounds: List[List[tuple]] = []
    cur = arr[:]
    for r in range(n - 1):
        pairs = []
        for i in range(n // 2):
            a, b = cur[i], cur[n - 1 - i]
            if a is None or b is None:
                continue
            if balance_home_away and (r + i) % 2 == 1:
                a, b = b, a
            pairs.append((a, b))
        rounds.append(pairs)
        # 固定第 0 位，其余右旋
        cur = [cur[0]] + [cur[-1]] + cur[1:-1]

    out: List[Dict] = []
    for r, pairs in enumerate(rounds):
        for home, away in pairs:
            out.append({"round": r + 1, "home": home, "away": away, "leg": 1})
    if double:
        base = len(rounds)
        for r, pairs in enumerate(rounds):
            for home, away in pairs:
                out.append({"round": base + r + 1, "home": away, "away": home, "leg": 2})
    return out


def group_round_robin(groups: Dict[str, List[str]], double: bool = False) -> List[Dict]:
    """分组循环：组内各自单/双循环。返回带 ``group`` 字段的赛程。"""
    out: List[Dict] = []
    for gname, teams in groups.items():
        for m in round_robin(teams, double=double):
            m = dict(m)
            m["group"] = gname
            out.append(m)
    return out


def home_away_stats(schedule: List[Dict]) -> Dict[str, Dict[str, int]]:
    """统计每队主/客场次数（用于校验平衡）。"""
    stat: Dict[str, Dict[str, int]] = {}
    for m in schedule:
        stat.setdefault(m["home"], {"home": 0, "away": 0})["home"] += 1
        stat.setdefault(m["away"], {"home": 0, "away": 0})["away"] += 1
    return stat
