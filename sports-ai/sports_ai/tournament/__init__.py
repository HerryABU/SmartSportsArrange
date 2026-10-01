"""球赛赛制生成：循环 / 淘汰 / 混合，以及种子分配、编排任务适配。

对应架构文档「球赛编排」部分——球赛的输入是参赛队伍，输出首先是**赛制结构**：

    参赛队伍 → 赛制生成（循环/淘汰/混合）→ 赛程结构（对阵图）
             → 赛程编排（时间槽 + 场地分配）

赛制生成与「时间槽着色」是两个性质完全不同的问题，故独立成包。排球赛（volleyball）
是球赛的一个实例，见 ``volleyball_demo``。

``adapt`` 子模块是两者之间的**结构翻译层**：把赛制结构摊平成可排任务（含先后依赖），
任务的队员名单让球赛与田径能共用一张冲突图 —— 跨大类兼项冲突由此自动成立。
"""

from .seeding import seed_order, seed_positions, distribute_seeds
from .round_robin import (round_robin, group_round_robin, home_away_report,
                          home_away_stats, strong_pair_spread)
from .elimination import (single_elimination, double_elimination,
                          same_unit_clash_count, rounds_of)
from .hybrid import hybrid_schedule, snake_group, cross_pairs
from .volleyball import volleyball_demo
from .adapt import MatchTask, matches_to_tasks, structure_to_tasks, tasks_summary

__all__ = [
    "seed_order", "seed_positions", "distribute_seeds",
    "round_robin", "group_round_robin", "home_away_report", "home_away_stats",
    "strong_pair_spread",
    "single_elimination", "double_elimination", "same_unit_clash_count", "rounds_of",
    "hybrid_schedule", "snake_group", "cross_pairs",
    "volleyball_demo",
    "MatchTask", "matches_to_tasks", "structure_to_tasks", "tasks_summary",
]
