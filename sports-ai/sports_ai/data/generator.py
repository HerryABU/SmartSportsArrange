"""合成报名数据生成器：镜像真实运动会报名结构，产出 :class:`Scenario`。

真实领域（对应 sports-backend 的 ``ScheduleUnit`` / ``Placement``）：
- 单元 = 项目 × 年级（径赛/田赛并发池、同组开赛、真实估算用时）
- 位置 = 并发池 × 槽位 × 时段窗口（含窗口容量）
- 兼项冲突 = 同一运动员出现在多个单元

生成器刻意还原三大「冲突簇」领域先验（架构文档第三节）：
- 短跑簇：100m / 200m / 400m / 接力 高频同报
- 跳跃簇：跳远 / 三级跳 / 跳高 高频同报
- 投掷簇：铅球 / 铁饼 / 标枪 高频同报

这样生成的冲突图是稀疏的（网络密度 0.02-0.29），由若干稠密子图（冲突簇）组成，
与真实排课冲突图的结构特征一致。
"""

from __future__ import annotations

import random
from dataclasses import dataclass, field
from typing import List, Optional


# ---------------------------------------------------------------------------
# 数据模型（与 Java 端 ScheduleUnit / Placement 一一对应，作为特征契约的输入）
# ---------------------------------------------------------------------------
@dataclass
class Unit:
    """待排赛程单元 = 项目 × 年级。"""

    key: str
    event_id: int
    event_name: str
    grade: Optional[str]
    track: bool
    pool_label: str
    group_key: Optional[str]
    raw_duration: int
    athletes: List[int]  # 升序（兼项冲突判定用）


@dataclass
class Placement:
    """可放置位置 = 并发池 × 槽位 × 时段窗口。"""

    pool_label: str
    slot_idx: int
    window_idx: int
    day: int
    window_capacity: int

    @property
    def bin_key(self) -> str:
        """并发位标识：同一 bin 是独占资源，容量求和即总供给。"""
        return f"{self.pool_label}#{self.slot_idx}#{self.window_idx}"


@dataclass
class Scenario:
    """一个编排实例（特征提取的输入）。"""

    units: List[Unit]
    placements: List[Placement]


# ---------------------------------------------------------------------------
# 项目模板：name, track, pool, 每批时长(分钟), 每批容量(道数/分组), specialty_cluster
# ---------------------------------------------------------------------------
_EVENT_TEMPLATES = [
    ("50m",       True,  "径赛", 15, 8, "sprint"),
    ("100m",      True,  "径赛", 20, 8, "sprint"),
    ("200m",      True,  "径赛", 25, 8, "sprint"),
    ("400m",      True,  "径赛", 30, 8, "sprint"),
    ("800m",      True,  "径赛", 45, 8, "middle"),
    ("1500m",     True,  "径赛", 50, 8, "middle"),
    ("4x100m接力", True,  "径赛", 30, 8, "sprint"),
    ("跳远",      False, "田赛", 60, 6, "jump"),
    ("三级跳",    False, "田赛", 70, 6, "jump"),
    ("跳高",      False, "田赛", 90, 6, "jump"),
    ("铅球",      False, "田赛", 50, 6, "throw"),
    ("铁饼",      False, "田赛", 60, 6, "throw"),
]

# 田赛「同组同时开赛」的分组（组名 → 组内项目）
_FIELD_GROUPS = {
    "田赛跳跃组": ["跳远", "三级跳", "跳高"],
    "田赛投掷组": ["铅球", "铁饼"],
}

# 每个 specialty 可报项目的偏好权重（未列出的项目权重为 0）
_SPECIALTY_PREFS = {
    "sprint": {"50m": 8, "100m": 8, "200m": 8, "400m": 5, "4x100m接力": 4, "跳远": 1},
    "middle": {"400m": 4, "800m": 8, "1500m": 8},
    "jump":   {"跳远": 8, "三级跳": 8, "跳高": 5, "100m": 2},
    "throw":  {"铅球": 8, "铁饼": 8},
    "general": {"100m": 3, "800m": 3, "跳远": 3, "铅球": 3, "1500m": 2},
}


def _weighted_choice(rng: random.Random, weights: dict):
    items = list(weights.keys())
    ws = [weights[i] for i in items]
    total = sum(ws)
    r = rng.random() * total
    acc = 0.0
    for item, w in zip(items, ws):
        acc += w
        if r <= acc:
            return item
    return items[-1]


def generate_scenario(
    seed: int = 0,
    n_athletes: int = 200,
    n_days: int = 2,
    multi_event_prob: float = 0.6,
    grades: Optional[List[str]] = None,
    event_drop_prob: float = 0.0,
) -> Scenario:
    """生成一个编排实例。

    参数：
        seed: 随机种子（可复现）
        n_athletes: 运动员总数
        n_days: 比赛天数（越少容量越紧张，tension 越高）
        multi_event_prob: 运动员报第二个项目的概率（控制兼项占比）
        grades: 年级列表（None = 不分年级）
        event_drop_prob: 每个项目被随机裁掉的概率——让「项目数 / group_count / duration_cv」
            等特征具有分布多样性（否则这些特征在训练里近乎恒定，归一化会在真实数据上爆掉）
    """
    rng = random.Random(seed)
    grades = grades or [None]
    grade_pool = [g for g in grades if g is not None]
    all_grade_names = grade_pool or [None]

    # 随机保留项目子集（模拟不同运动会设置的项目数量与组合）
    active_events = [i for i in range(len(_EVENT_TEMPLATES)) if rng.random() >= event_drop_prob]
    if not active_events:
        active_events = [0]

    # 运动员 → 报名项目（映射到事件 id）
    name_by_idx = [t[0] for t in _EVENT_TEMPLATES]
    idx_by_name = {n: i for i, n in enumerate(name_by_idx)}

    athlete_grades = [
        rng.choice(all_grade_names) if all_grade_names else None
        for _ in range(n_athletes)
    ]
    athlete_events: dict[int, List[int]] = {}
    for aid in range(n_athletes):
        specialty = rng.choices(
            list(_SPECIALTY_PREFS.keys()), weights=[0.22, 0.15, 0.23, 0.20, 0.20], k=1
        )[0]
        prefs = _SPECIALTY_PREFS[specialty]
        chosen: List[int] = []
        # 必报一个
        first = idx_by_name[_weighted_choice(rng, prefs)]
        chosen.append(first)
        # 可能再报 1-2 个（同簇优先 → 制造冲突簇）
        while rng.random() < multi_event_prob and len(chosen) < 3:
            nxt = idx_by_name[_weighted_choice(rng, prefs)]
            if nxt not in chosen:
                chosen.append(nxt)
        athlete_events[aid] = chosen

    # 聚合为「项目 × 年级」单元
    units: List[Unit] = []
    group_of_event = {}
    for gname, members in _FIELD_GROUPS.items():
        for m in members:
            group_of_event[m] = gname

    uid = 0
    for eid in active_events:
        name, track, pool, batch_min, cap, _ = _EVENT_TEMPLATES[eid]
        for grade in all_grade_names:
            members = sorted(
                aid
                for aid, evs in athlete_events.items()
                if eid in evs and athlete_grades[aid] == grade
            )
            if not members:
                continue
            # 真实时长模型（与 Java 端一致）：单元的「全部时长」= 组数 × 每批时长，
            # 组数 = ceil(人数 / 每批容量)。注意区分两个时长概念——「每批时长」是单组用时，
            # 「全部时长」是该单元所有组次之和（人数多时可达数百分钟），编排取后者。
            heats = max(1, -(-len(members) // cap))
            total_duration = heats * batch_min
            units.append(
                Unit(
                    key=f"{name}@{grade or '不分年级'}",
                    event_id=eid,
                    event_name=name,
                    grade=grade,
                    track=track,
                    pool_label=pool,
                    group_key=group_of_event.get(name),
                    raw_duration=total_duration,
                    athletes=members,
                )
            )
            uid += 1

    # 位置：径赛 1 槽、田赛 3 槽；每天 2 时段（上午 180 / 下午 150 分钟）。
    # window_idx 取「全局窗口序号」（跨天唯一），保证不同比赛日是不同的并发位（bin）。
    placements: List[Placement] = []
    slot_counts = {"径赛": 1, "田赛": 3}
    for pool in ("径赛", "田赛"):
        for day in range(1, n_days + 1):
            for win, cap in ((1, 180), (2, 150)):
                window_idx = (day - 1) * 2 + win
                for slot in range(slot_counts[pool]):
                    placements.append(
                        Placement(pool_label=pool, slot_idx=slot,
                                   window_idx=window_idx, day=day, window_capacity=cap)
                    )

    return Scenario(units=units, placements=placements)
