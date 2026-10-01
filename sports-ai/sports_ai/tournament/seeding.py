"""种子分配：标准淘汰赛对阵图的种子排位。

标准种子排位（recursive bracket seeding）保证：
- 1 号与 2 号种子分在**不同半区**，只可能在决赛相遇；
- 3/4 号种子分在另外两个 1/4 区，依此类推；
- 高种子（1..N）优先获得轮空。

``seed_order(8)`` → ``[1, 8, 4, 5, 2, 7, 3, 6]``，即对阵 1v8、4v5、2v7、3v6。
"""

from __future__ import annotations

from typing import List


def _next_pow2(n: int) -> int:
    p = 1
    while p < n:
        p *= 2
    return p


def seed_order(n: int) -> List[int]:
    """返回 n（2 的幂）个位置上的标准种子号序列。"""
    if n <= 1:
        return [1]
    prev = seed_order(n // 2)
    out: List[int] = []
    for s in prev:
        out.append(s)
        out.append(n + 1 - s)
    return out


def seed_positions(n_teams: int) -> List[int]:
    """参赛队数不一定是 2 的幂 → 补齐到 2 的幂后的标准种子序列。

    返回长度 = 2 的幂；其中种子号 > n_teams 的位置即「轮空位」（byes）。
    """
    p = _next_pow2(max(1, n_teams))
    return seed_order(p)


def distribute_seeds(teams: List[str], rule: str = "rank") -> List[str]:
    """按规则给队伍排序（种子顺序）。

    rule:
    - ``rank``    ：按给定顺序（调用方已按上届成绩/班级序号排好序）；
    - ``reverse`` ：反转（用于抽签后的逆序）；
    - ``bracket`` ：按标准 bracket 种子序重排（1v8 4v5 …）。
    """
    if rule == "reverse":
        return list(reversed(teams))
    if rule == "bracket":
        order = seed_positions(len(teams))
        # order 给出「位置 → 种子号」；反过来取被 1..n 种子占据的位置顺序
        seq = [teams[s - 1] for s in order if s <= len(teams)]
        return seq
    return list(teams)
