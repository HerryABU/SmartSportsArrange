"""拆批：把「单元」展开成可独立排期的**组次任务**。

核心动机：单元的 ``raw_duration`` 是**全部时长**（组数 × 每批时长），真实规模下可达
数百分钟（如 720 人的立定跳远决赛 = 9 组 × 60 = 540 分钟），**远超单个时段的容量**
（4 小时 = 240 分钟）。若不拆批，这些单元在数据结构上就放不进任何时段——这是
「地狱场景不可解」的真正根因，而不是单纯的容量紧张。

拆批后：
- 每个组次 = 一个独立任务（时长 = 每批时长，人数 ≤ 每批容量）；
- 组次之间**运动员互不重叠**（按报名名单顺序切片），因此把一个单元摊到多个时段，
  反而降低了与其他项目的兼项冲突面；
- 同项目同年级的**预赛组次必须整体早于决赛组次**（有向偏序，见 ``round_order``）。
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import List, Tuple

from ..data.generator import Unit


@dataclass(frozen=True)
class HeatTask:
    """一个组次任务（拆批后的最小排期单位）。"""

    uid: int                    # 全局任务序号
    unit_index: int             # 所属单元下标
    unit_key: str               # 所属单元键（如 "100米(预赛)@高1"）
    event_id: int
    grade: str
    pool: str                   # 径赛 / 田赛
    batch: int                  # 第几组（0-based）
    n_batches: int              # 该单元共几组
    duration: int               # 本组用时（分钟）
    athletes: Tuple[int, ...]   # 本组运动员（互不重叠）
    round_order: int            # 0 = 预赛/单轮；1 = 决赛


def _round_order(unit: Unit) -> int:
    return 1 if "决赛" in unit.event_name else 0


def expand_heats(units: List[Unit]) -> List[HeatTask]:
    """把单元列表展开成组次任务列表。"""
    tasks: List[HeatTask] = []
    uid = 0
    for ui, u in enumerate(units):
        if u.batch_minutes is None:
            # 无拆批元信息：整体视为一个任务（不可拆）
            tasks.append(HeatTask(uid, ui, u.key, u.event_id, u.grade or "", u.pool_label,
                                  0, 1, u.raw_duration, tuple(u.athletes), _round_order(u)))
            uid += 1
            continue

        cap = max(1, u.heat_capacity or len(u.athletes) or 1)
        n = max(1, math.ceil(len(u.athletes) / cap))
        # 数据源可能给出与 n×每批时长 不一致的全部时长；以全部时长为准均分（向下取整至少 1 分钟）
        per = u.batch_minutes
        if abs(n * u.batch_minutes - u.raw_duration) > u.batch_minutes:
            per = max(1, u.raw_duration // n)

        for b in range(n):
            sub = tuple(u.athletes[b * cap:(b + 1) * cap])
            if not sub:
                continue
            tasks.append(HeatTask(uid, ui, u.key, u.event_id, u.grade or "", u.pool_label,
                                  b, n, per, sub, _round_order(u)))
            uid += 1
    return tasks
