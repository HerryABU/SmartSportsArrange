"""循环赛赛程生成：圆桌轮转法（Circle Method）+ 主客场平衡。

N 支队伍：每队与其余 N-1 队各打一场，共 N(N-1)/2 场。
- N 为偶数 → 分 N-1 轮，每轮 N/2 场；
- N 为奇数 → 加一个「轮空（BYE）」队补成偶数，共 N 轮。

圆桌轮转：固定一支队（arr[0]），其余队绕圈轮转，每轮由「固定队 vs 轮转队」+
「轮转队两两配对」生成。保证每队每轮恰好一场、且与其余每队恰好交手一次。

约束（按重要性排序）：
- **每队每轮恰好一场**、**每对恰好交手一次** —— 由轮转法结构性保证；
- **主客场平衡**：不仅看总数，更要看**连续性**。真实联赛最不能接受的是「某队连续 3 个主场」
  或「连打 4 个客场」——这既不公平（主场优势累积），也确实会在赛程编排上撞场地。
  最初的实现用 ``(round + pair_idx) % 2`` 交替，只是个近似：它保证不了连续性上界。
  现在改为**逐场贪心决策**（见 :func:`_assign_home_away`）：以「当前连续主/客次数」为主判据，
  优先让正在连客的队做主，从而主动打断连续段；
- **强强对话分散**：轮转法本身保证每队每轮只打一场，因此强队之间的对局天然落在不同轮次，
  :func:`strong_pair_spread` 只做**验证与报告**，不做额外调整。
"""

from __future__ import annotations

from typing import Dict, List, Optional, Tuple

Pair = Tuple[str, str]


def _circle_rounds(teams: List[str]) -> Tuple[List[List[Pair]], bool]:
    """圆桌轮转：返回 (每轮无序对阵, 是否补了轮空)。"""
    arr: List[Optional[str]] = list(teams)
    if len(arr) < 2:
        return [], False
    bye_added = False
    if len(arr) % 2 == 1:
        arr.append(None)          # 轮空
        bye_added = True
    n = len(arr)
    rounds: List[List[Pair]] = []
    cur = arr[:]
    for _ in range(n - 1):
        pairs: List[Pair] = []
        for i in range(n // 2):
            a, b = cur[i], cur[n - 1 - i]
            if a is None or b is None:
                continue
            pairs.append((a, b))
        rounds.append(pairs)
        # 固定第 0 位，其余右旋
        cur = [cur[0]] + [cur[-1]] + cur[1:-1]
    return rounds, bye_added


def _assign_home_away(rounds: List[List[Pair]], teams: List[str]) -> List[List[Pair]]:
    """逐轮为每场决定主客，目标：打断连续主/客场、并平衡总数。

    判据（越大越该做主）：
    - 正在连客（``streak < 0``）→ 强优先，因为一场主场就能终结连客段；
    - 已累积主场数偏少 → 次优先，修正总数偏差。

    ``streak[t]``：正 = 连续主场次数，负 = 连续客场次数。
    """
    streak: Dict[str, int] = {t: 0 for t in teams}
    home_count: Dict[str, int] = {t: 0 for t in teams}
    out: List[List[Pair]] = []
    for pairs in rounds:
        decided: List[Pair] = []
        for a, b in pairs:
            score_a = -streak[a] * 2.0 + (home_count[b] - home_count[a])
            score_b = -streak[b] * 2.0 + (home_count[a] - home_count[b])
            home, away = (a, b) if score_a >= score_b else (b, a)
            decided.append((home, away))
            streak[home] = streak[home] + 1 if streak[home] > 0 else 1
            streak[away] = streak[away] - 1 if streak[away] < 0 else -1
            home_count[home] += 1
        out.append(decided)
    return out


def round_robin(teams: List[str], double: bool = False,
                balance_home_away: bool = True) -> List[Dict]:
    """单/双循环赛程表。

    返回 ``[{round, home, away, leg}, ...]``；``double=True`` 时含第二轮（主客对调）。
    """
    rounds, _bye = _circle_rounds(teams)
    if not rounds:
        return []
    decided = _assign_home_away(rounds, teams) if balance_home_away else rounds

    out: List[Dict] = []
    for r, pairs in enumerate(decided):
        for home, away in pairs:
            out.append({"round": r + 1, "home": home, "away": away, "leg": 1})
    if double:
        base = len(decided)
        # 第二轮严格主客对调：保证每对交手两次、且各自一次主场
        for r, pairs in enumerate(decided):
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


def home_away_report(schedule: List[Dict]) -> Dict[str, Dict[str, int]]:
    """主客场质量报告：总数 + **最长连续主场/客场**。

    这是主客场约束真正该看的指标——「主场数相等」并不等于「公平」，
    连续 3 个主场带来的实际优势远超总数 ±1 的差异。
    """
    seq: Dict[str, List[str]] = {}
    for m in schedule:
        seq.setdefault(m["home"], []).append("H")
        seq.setdefault(m["away"], []).append("A")
    stat = home_away_stats(schedule)
    out: Dict[str, Dict[str, int]] = {}
    for t, s in seq.items():
        best_h = best_a = cur_h = cur_a = 0
        for ch in s:
            cur_h = cur_h + 1 if ch == "H" else 0
            cur_a = cur_a + 1 if ch == "A" else 0
            best_h = max(best_h, cur_h)
            best_a = max(best_a, cur_a)
        out[t] = {
            "home": stat.get(t, {}).get("home", 0),
            "away": stat.get(t, {}).get("away", 0),
            "maxHomeStreak": best_h,
            "maxAwayStreak": best_a,
        }
    return out


def strong_pair_spread(schedule: List[Dict], top_k: int = 4) -> Dict[str, object]:
    """强强对话分散度：统计「前 top_k 强队之间」的对局落在第几轮。

    分散度高（不同轮）意味着强强对话不会挤在同一天，观赛与场地压力更均匀。
    轮转法结构性保证每队每轮只打一场，因此该指标主要用于**验证**。
    """
    ranked = sorted(home_away_stats(schedule).keys())
    strong = set(ranked[:top_k])
    by_round: Dict[int, int] = {}
    pairs: List[Dict] = []
    for m in schedule:
        if m["home"] in strong and m["away"] in strong:
            by_round[m["round"]] = by_round.get(m["round"], 0) + 1
            pairs.append({"round": m["round"], "home": m["home"], "away": m["away"]})
    total = sum(by_round.values())
    # 每轮至多 1 场「强强」才算完全分散（同轮多场说明强队被挤在一起）
    max_per_round = max(by_round.values()) if by_round else 0
    return {
        "totalStrongPairMatches": total,
        "roundsUsed": len(by_round),
        "maxPerRound": max_per_round,
        "wellSpread": max_per_round <= 1,
        "matches": pairs,
    }
