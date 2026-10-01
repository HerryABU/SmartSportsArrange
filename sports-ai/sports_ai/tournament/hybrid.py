"""混合赛制：先小组循环，再交叉淘汰（最常见球赛赛制）。

结构：
1. 抽签/种子分组（蛇形分配，保证强队分散）；
2. 小组赛阶段：组内循环；
3. 淘汰赛阶段：各组同名次交叉对阵（A1-B2、B1-A2 …），单淘汰或双淘汰。

编排难点（文档第四节）：小组赛全部结束后才能确定淘汰赛对阵；若同一批学生兼田径，
两个大类的赛程需要协调（跨大类兼项冲突）。
"""

from __future__ import annotations

from typing import Dict, List

from .elimination import single_elimination
from .round_robin import group_round_robin


def snake_group(teams: List[str], n_groups: int) -> Dict[str, List[str]]:
    """蛇形分组：把按种子排序的队伍按 1→G、G→1 的往复顺序分到各组，强队分散。"""
    groups: Dict[str, List[str]] = {chr(65 + g): [] for g in range(n_groups)}
    names = list(groups.keys())
    for i, t in enumerate(teams):
        band = i // n_groups
        pos = i % n_groups
        if band % 2 == 1:
            pos = n_groups - 1 - pos
        groups[names[pos]].append(t)
    return groups


def hybrid_schedule(teams: List[str], n_groups: int = 2, advance_per_group: int = 2,
                    double_group: bool = False) -> Dict[str, object]:
    """生成「小组循环 → 交叉淘汰」完整赛制结构。

    返回 ``{"groups", "group_matches", "cross_pairs", "knockout"}``。
    """
    groups = snake_group(teams, n_groups)
    group_matches = group_round_robin(groups, double=double_group)

    # 交叉对阵：相邻组同名次交叉（A1-B2、B1-A2、A3-B4 …）
    names = list(groups.keys())
    cross_pairs: List[Dict] = []
    for g in range(0, len(names) - 1, 2):
        a, b = names[g], names[g + 1]
        for k in range(advance_per_group):
            if k % 2 == 0:
                cross_pairs.append({"home": f"{a}{k + 1}", "away": f"{b}{advance_per_group - k}"})
            else:
                cross_pairs.append({"home": f"{b}{k + 1}", "away": f"{a}{advance_per_group - k}"})

    # 淘汰赛对阵（占位，待小组赛结果填入）
    knockout = single_elimination([f"P{i + 1}" for i in range(len(cross_pairs))])
    return {
        "groups": groups,
        "group_matches": group_matches,
        "cross_pairs": cross_pairs,
        "knockout": knockout,
    }
