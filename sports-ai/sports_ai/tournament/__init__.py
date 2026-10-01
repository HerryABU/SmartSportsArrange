"""球赛赛制生成：循环 / 淘汰 / 混合，以及种子分配。

对应架构文档「球赛编排」部分——球赛的输入是参赛队伍，输出首先是**赛制结构**：

    参赛队伍 → 赛制生成（循环/淘汰/混合）→ 赛程结构（对阵图）
             → 赛程编排（时间槽 + 场地分配）

赛制生成与「时间槽着色」是两个性质完全不同的问题，故独立成包。排球赛（volleyball）
是球赛的一个实例，见 ``volleyball_demo``。
"""

from .seeding import seed_order, seed_positions, distribute_seeds
from .round_robin import round_robin
from .elimination import single_elimination, double_elimination
from .hybrid import hybrid_schedule
from .volleyball import volleyball_demo

__all__ = [
    "seed_order", "seed_positions", "distribute_seeds",
    "round_robin", "single_elimination", "double_elimination",
    "hybrid_schedule", "volleyball_demo",
]
