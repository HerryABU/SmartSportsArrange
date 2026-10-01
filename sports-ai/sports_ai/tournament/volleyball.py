"""排球赛赛制生成示例——球赛赛制生成的一个真实实例。

排球赛（volleyball）通常采用「分组循环 + 交叉淘汰」的混合赛制，且排球有**局分**概念
（3 局 2 胜 / 5 局 3 胜），赛时长约束比田径强。本模块把排球赛的赛制结构生成串起来：

    参赛队 → 蛇形分组 → 小组循环（排球常为单循环）→ 交叉淘汰 → 对阵表
"""

from __future__ import annotations

from typing import Dict, List

from .hybrid import hybrid_schedule
from .round_robin import home_away_stats
from .elimination import rounds_of


def volleyball_demo(teams: List[str], n_groups: int = 2,
                    advance_per_group: int = 2, best_of: int = 5) -> Dict[str, object]:
    """生成一份排球赛完整赛制结构 + 统计。

    ``best_of``：局制（3=三局两胜，5=五局三胜）。
    """
    plan = hybrid_schedule(teams, n_groups=n_groups,
                           advance_per_group=advance_per_group, double_group=False)
    gm = plan["group_matches"]
    stats = home_away_stats(gm)
    return {
        "sport": "volleyball",
        "best_of": best_of,
        "team_count": len(teams),
        "groups": plan["groups"],
        "group_matches": gm,
        "group_match_count": len(gm),
        "cross_pairs": plan["cross_pairs"],
        "knockout_rounds": rounds_of(plan["knockout"]),
        "home_away_stats": stats,
    }


if __name__ == "__main__":
    import json
    demo = volleyball_demo([f"班级{i}" for i in range(1, 9)], n_groups=2, advance_per_group=2)
    print(json.dumps({
        "sport": demo["sport"],
        "groups": demo["groups"],
        "group_match_count": demo["group_match_count"],
        "cross_pairs": demo["cross_pairs"],
        "knockout_rounds": demo["knockout_rounds"],
    }, ensure_ascii=False, indent=2))
