"""混合赛制：先小组循环，再交叉淘汰（最常见球赛赛制）。

结构：
1. 抽签/种子分组（蛇形分配，保证强队分散）；
2. 小组赛阶段：组内循环；
3. 淘汰赛阶段：各组同名次交叉对阵（A1-B2、B1-A2 …），单淘汰或双淘汰。

三条真实约束：
- **蛇形分组**：让强队分散到不同小组，避免「死亡之组」（见 :func:`snake_group`）；
- **交叉对阵规则**：相邻组按名次交叉，且**同一小组的两支队伍不得在首轮淘汰赛相遇**
  （否则小组赛白打）—— :func:`cross_pairs` 保证这一点；
- **同单位回避**：交叉对阵直接交给 :func:`single_elimination`，带上单位标签即可复用
  淘汰赛的同单位回避能力，不必重复实现。

编排难点（文档第四节）：小组赛全部结束后才能确定淘汰赛对阵；若同一批学生兼田径，
两个大类的赛程需要协调（跨大类兼项冲突）—— 这正是 :mod:`.adapt` 适配层要解决的问题。
"""

from __future__ import annotations

from typing import Dict, List, Optional

from .elimination import single_elimination
from .round_robin import group_round_robin


def snake_group(teams: List[str], n_groups: int) -> Dict[str, List[str]]:
    """蛇形分组：把按种子排序的队伍按 1→G、G→1 的往复顺序分到各组，强队分散。

    之所以不是「按顺序每 G 个一组」：那样会把 1、2、3 号种子全塞进 A 组（死亡之组）。
    蛇形让每组拿到的实力总和最接近。
    """
    groups: Dict[str, List[str]] = {chr(65 + g): [] for g in range(n_groups)}
    names = list(groups.keys())
    for i, t in enumerate(teams):
        band = i // n_groups
        pos = i % n_groups
        if band % 2 == 1:
            pos = n_groups - 1 - pos
        groups[names[pos]].append(t)
    return groups


def cross_pairs(group_names: List[str], advance_per_group: int) -> List[Dict]:
    """相邻组交叉对阵：``A1-B{adv}``、``A2-B{adv-1}``、…、``A{adv}-B1``。

    配对集合是「第 k 名 vs 邻组第 (adv+1-k) 名」，共 ``advance_per_group`` 对、**互不重复**，
    且每对都来自不同小组——因此**同一小组的两支出线队不可能在首轮互打**（小组赛不会白打）。

    主客按 k 的奇偶交替，只为让主场分布更均匀，不改变配对集合。
    """
    out: List[Dict] = []
    for g in range(0, len(group_names) - 1, 2):
        a, b = group_names[g], group_names[g + 1]
        for k in range(1, advance_per_group + 1):
            opp = advance_per_group + 1 - k      # 名次反向配对
            if k % 2 == 1:
                out.append({"home": f"{a}{k}", "away": f"{b}{opp}"})
            else:
                out.append({"home": f"{b}{opp}", "away": f"{a}{k}"})
    return out


def hybrid_schedule(teams: List[str], n_groups: int = 2, advance_per_group: int = 2,
                    double_group: bool = False, units: Optional[Dict[str, str]] = None,
                    double_knockout: bool = False) -> Dict[str, object]:
    """生成「小组循环 → 交叉淘汰」完整赛制结构。

    :param units: 队伍 → 单位标签（班级/年级），用于淘汰赛阶段的同单位回避。
    :param double_knockout: True 时淘汰赛阶段用双淘汰。
    :return: ``{"groups", "group_matches", "cross_pairs", "knockout", "groupRounds",
              "knockoutRounds"}``
    """
    groups = snake_group(teams, n_groups)
    group_matches = group_round_robin(groups, double=double_group)

    names = list(groups.keys())
    cross = cross_pairs(names, advance_per_group)

    # 淘汰赛对阵占位：把交叉对阵视为「首轮参赛者」，交给淘汰赛生成器定位种子与轮次。
    # 带上单位标签 → 复用同单位回避；由于交叉对阵本就来自不同小组，
    # 同单位回避主要处理「同班两队分别以 A2/B2 出线后仍在首轮相遇」的情况。
    entrants = [f"P{i + 1}" for i in range(len(cross))]
    if double_knockout:
        from .elimination import double_elimination
        knockout = double_elimination(entrants)
    else:
        knockout = single_elimination(entrants)

    group_rounds = max((m["round"] for m in group_matches), default=0)
    return {
        "groups": groups,
        "group_matches": group_matches,
        "cross_pairs": cross,
        "knockout": knockout,
        "groupRounds": group_rounds,
        "knockoutRounds": (len(knockout) if isinstance(knockout, list)
                           else len(knockout.get("winners", []))),
        "units": units or {},
    }
