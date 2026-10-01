"""球赛赛程 → 编排任务适配：让球赛也能进编排的时间槽。

**为什么需要这层**：球赛生成器（round_robin / elimination / hybrid）产出的是
「谁跟谁打、第几轮」，而编排层要的是「某个时段、某个场地、占多长时间」。
两者之间差一层**结构翻译**——把一个赛制结构摊平成可排任务列表，并保留任务之间的
**先后依赖**（小组赛全部结束才能开淘汰赛）。

**跨大类兼项冲突的关键**：任务的 ``athletes`` 字段是队员名单。当球赛任务与田径任务
放进同一个编排实例时，冲突图会自动把「同一名学生既打篮球又跑 100 米」连成边——
这正是需求文档第六节要的「跨大类兼项冲突」，不需要额外机制。

用法：
    struct = hybrid_schedule(teams, 2, 2)
    tasks = structure_to_tasks(struct, minutes_per_match=40)
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional

#: 球赛默认场地池标签（与田径的「径赛/田赛」并列，互不占用资源）
DEFAULT_POOL = "球赛"


@dataclass
class MatchTask:
    """一场可排比赛（编排层的最小单位）。"""

    key: str
    stage: str                      # group / knockout / final
    group: str                      # 小组名（淘汰赛为空）
    round: int
    home: str
    away: str
    pool: str = DEFAULT_POOL
    duration: int = 40              # 预计用时（分钟）
    depends_on: List[str] = field(default_factory=list)   # 前置任务（偏序）
    athletes: List[int] = field(default_factory=list)     # 参赛队员（跨大类兼项冲突用）

    @property
    def is_placeholder(self) -> bool:
        """对阵未定（淘汰赛待小组赛结果填入）。"""
        return not self.home or not self.away


def _round_order(stage: str, rnd: int) -> int:
    """全局顺序：小组赛全部早于淘汰赛；同阶段内轮次递增。"""
    return (0 if stage == "group" else 100) + rnd


def matches_to_tasks(matches: List[Dict], stage: str,
                     minutes_per_match: int = 40, pool: str = DEFAULT_POOL,
                     prefix: str = "") -> List[MatchTask]:
    """把一组对阵转成任务列表（同一阶段内按轮次编号）。"""
    tasks: List[MatchTask] = []
    for m in matches:
        rnd = int(m.get("round", 1))
        key = f"{prefix}{stage}-R{rnd}-{m.get('slot', len(tasks) + 1)}"
        tasks.append(MatchTask(
            key=key,
            stage=stage,
            group=str(m.get("group", "") or ""),
            round=rnd,
            home=str(m.get("home") or ""),
            away=str(m.get("away") or ""),
            pool=pool,
            duration=minutes_per_match,
        ))
    return tasks


def structure_to_tasks(struct: Dict[str, object], minutes_per_match: int = 40,
                       pool: str = DEFAULT_POOL,
                       roster: Optional[Dict[str, List[int]]] = None) -> List[MatchTask]:
    """把赛制结构（循环 / 淘汰 / 混合）摊平成可排任务列表。

    :param roster: 队伍名 → 队员 id 列表。给出后会填入任务的 ``athletes``，
        编排时即可检测**跨大类的兼项冲突**（同一学生既打球又田径）。
    """
    tasks: List[MatchTask] = []

    # ---- 循环赛 / 混合赛制的小组赛 ----
    group_matches = struct.get("group_matches")
    if group_matches:
        tasks += matches_to_tasks(group_matches, "group", minutes_per_match, pool, prefix="G")
    elif isinstance(struct, list):
        tasks += matches_to_tasks(struct, "group", minutes_per_match, pool, prefix="G")

    # ---- 淘汰赛（可能嵌在 knockout 字段，也可能是双淘汰的 dict）----
    knockout = struct.get("knockout") if isinstance(struct, dict) else None
    if knockout is not None:
        if isinstance(knockout, dict):
            wk = matches_to_tasks(knockout.get("winners", []), "knockout",
                                  minutes_per_match, pool, prefix="W")
            lk = matches_to_tasks(knockout.get("losers", []), "knockout",
                                  minutes_per_match, pool, prefix="L")
            gf = matches_to_tasks(knockout.get("grandFinal", []), "final",
                                  minutes_per_match, pool, prefix="F")
            tasks += wk + lk + gf
        else:
            tasks += matches_to_tasks(knockout, "knockout", minutes_per_match, pool, prefix="K")

    # ---- 淘汰赛依赖小组赛：形成偏序，避免「小组赛没打完就开淘汰赛」----
    group_keys = [t.key for t in tasks if t.stage == "group"]
    if group_keys:
        for t in tasks:
            if t.stage != "group":
                t.depends_on = list(group_keys)

    if roster:
        for t in tasks:
            ids: List[int] = []
            for team in (t.home, t.away):
                ids += roster.get(team, [])
            t.athletes = sorted(set(ids))

    return tasks


def tasks_summary(tasks: List[MatchTask]) -> Dict[str, object]:
    """任务摘要（供报表 / 编排诊断展示）。"""
    by_stage: Dict[str, int] = {}
    for t in tasks:
        by_stage[t.stage] = by_stage.get(t.stage, 0) + 1
    return {
        "total": len(tasks),
        "byStage": by_stage,
        "totalMinutes": sum(t.duration for t in tasks),
        "placeholders": sum(1 for t in tasks if t.is_placeholder),
        "athleteSlots": sum(len(t.athletes) for t in tasks),
        "pool": tasks[0].pool if tasks else None,
    }
