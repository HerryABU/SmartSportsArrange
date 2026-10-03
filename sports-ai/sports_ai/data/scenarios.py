"""**档位化训练场景**生成器——按用户点名的四类场景组织训练分布。

为什么不改 ``data/generator.py``
------------------------------
那个模块已经被 8 个已训练模型（selector / gnn / lane_advisor / GAN 三件套…）依赖，
改它会让既有模型的训练分布漂移。**新增本模块**按「档位」组织场景，
既有模块保持原样，两套并存、各训各的。

四类档位
--------
=========  =====================================  ==========================
档位        规模                                    训练目标
=========  =====================================  ==========================
REGULAR    300 人 / 10 项目 / 兼项率 30% / 2-3 天    常规赛会的主分布
HELL       500+ 人 / 15+ 项目 / 兼项率 100%           压力上限：人人多项目
           （多人兼 1/2/3 项）                        + 装箱极度紧张
BLOCK      项目块约束                                同一项目的多个单元必须
                                                      整块排在一起（group_key）
LANE       道次场景                                  径赛分道次/分批，考验
                                                      heat_capacity 与拆批
TEAM       球类/拔河（团体赛）                       分队 + 轮转公平 + 赛制
=========  =====================================  ==========================

补齐了既有生成器的两个**真实缺口**（异构图那轮发现的）：

* ``Unit.interval`` —— 训练侧原本没有，间隔约束无从表达；
* ``venue`` 场地维度 —— 训练侧原本没有，VENUE 通道只能用 bin_key 近似。
本模块两者都补上，Java 编排域与训练侧语义就此对齐。
"""

from __future__ import annotations

import random
from dataclasses import dataclass, field
from typing import List, Optional, Tuple

# ---------------------------------------------------------------------------
# 项目模板（扩展到 18 个，含球类/拔河）
#   name, track(是否径赛), pool, 每批分钟, 每批容量(道数/分组), specialty, is_team
# ---------------------------------------------------------------------------
EVENT_TEMPLATES_EXT: List[Tuple[str, bool, str, int, int, str, bool]] = [
    # 径赛（track=True，占跑道）
    ("50m",        True,  "径赛", 15, 8, "sprint", False),
    ("100m",       True,  "径赛", 20, 8, "sprint", False),
    ("200m",       True,  "径赛", 25, 8, "sprint", False),
    ("400m",       True,  "径赛", 30, 8, "sprint", False),
    ("800m",       True,  "径赛", 45, 8, "middle", False),
    ("1500m",      True,  "径赛", 50, 8, "middle", False),
    ("3000m",      True,  "径赛", 60, 8, "middle", False),
    ("4x100m接力",  True,  "径赛", 30, 8, "sprint", False),   # 项目块 + 队内 4 人
    # 田赛（track=False，独立工位）
    ("跳远",        False, "田赛", 60, 6, "jump",  False),
    ("三级跳",      False, "田赛", 70, 6, "jump",  False),
    ("跳高",        False, "田赛", 90, 6, "jump",  False),
    ("铅球",        False, "田赛", 50, 6, "throw", False),
    ("铁饼",        False, "田赛", 60, 6, "throw", False),
    ("标枪",        False, "田赛", 55, 6, "throw", False),
    # 球类 / 拔河（团体赛：整队为一个单元，占整块场地与较长时间）
    ("篮球赛",      False, "球类", 120, 12, "team", True),
    ("排球赛",      False, "球类", 110, 12, "team", True),
    ("拔河",        False, "球类", 90,  8, "team", True),
    ("足球赛",      False, "球类", 120, 22, "team", True),
]

# 场地池：每个场地同时只能进行一个单元（独占）
VENUES = ["田径场", "跳远区", "铅球区", "篮球场", "排球场", "足球场", "拔河区"]

# 球类赛制
TEAM_FORMATS = ["round_robin", "elimination", "hybrid"]


@dataclass
class UnitEx:
    """扩展单元——比 ``generator.Unit`` 多了 ``interval`` / ``venue`` / ``block_id``。"""
    key: str
    event_id: int
    event_name: str
    grade: Optional[str]
    track: bool
    pool_label: str
    group_key: Optional[str]        # 项目块 id：同块必须整块排在一起
    interval: int                   # 与上一单元的最小间隔（分钟）★ 新增
    venue: str                      # 独占场地 ★ 新增
    raw_duration: int
    batch_minutes: Optional[int]
    heat_capacity: Optional[int]
    athletes: List[int]
    is_team: bool = False
    team_no: Optional[str] = None
    team_size: int = 0
    fmt: Optional[str] = None       # 球类赛制


@dataclass
class PlacementEx:
    """扩展时段——补 venue 维度。"""
    pool_label: str
    slot_idx: int
    window_idx: int
    day: int
    window_capacity: int
    venue: str                      # ★ 新增

    @property
    def bin_key(self) -> str:
        return f"{self.pool_label}#{self.slot_idx}#{self.window_idx}"


@dataclass
class ScenarioEx:
    units: List[UnitEx] = field(default_factory=list)
    placements: List[PlacementEx] = field(default_factory=list)
    tier: str = "REGULAR"
    n_days: int = 2


# ---------------------------------------------------------------------------
# 档位定义
# ---------------------------------------------------------------------------
TIERS = {
    #            人数区间   项目数  兼项率  天数
    "REGULAR": dict(athletes=(250, 350), events=10, multi=0.30, days=(2, 3)),
    "HELL":    dict(athletes=(500, 900), events=15, multi=1.00, days=(2, 4)),
    "BLOCK":   dict(athletes=(250, 400), events=12, multi=0.40, days=(2, 3)),
    "LANE":    dict(athletes=(200, 320), events=8,  multi=0.20, days=2),
    "TEAM":    dict(athletes=(160, 400), events=6,  multi=0.15, days=(2, 3)),
}


def generate_tier_scenario(tier: str, seed: int = 0) -> ScenarioEx:
    """按档位生成一个场景。"""
    cfg = TIERS.get(tier, TIERS["REGULAR"])
    rng = random.Random(seed)

    n_ath = rng.randint(*cfg["athletes"])
    n_evt = min(cfg["events"], len(EVENT_TEMPLATES_EXT))
    multi_rate = cfg["multi"]
    n_days = rng.randint(*cfg["days"]) if isinstance(cfg["days"], tuple) else cfg["days"]
    grades = ["高一", "高二", "高三"]

    # ---- 项目子集（含球类） ----
    events = rng.sample(EVENT_TEMPLATES_EXT, n_evt)
    if tier == "TEAM":
        events = [e for e in events if e[6]] + [e for e in events if not e[6]][:3]
    ev_ids = {e[0]: i for i, e in enumerate(events)}

    # ---- 运动员：编号 0..n_ath-1 ----
    athletes = list(range(n_ath))

    # ---- 时段：每天按池给若干并发位 × 时段容量，并绑定场地 ----
    placements: List[PlacementEx] = []
    pool_cfg = {"径赛": (rng.choice([1, 2, 3]), 240), "田赛": (rng.choice([2, 3, 4]), 210),
                "球类": (rng.choice([1, 2]), 240)}
    for day in range(1, n_days + 1):
        for pool, (slots, cap) in pool_cfg.items():
            venues = [v for v in VENUES if v != "田径场"] if pool == "球类" else (
                ["田径场"] if pool == "径赛" else ["跳远区", "铅球区"])
            for s in range(slots):
                for w in range(2):                      # 每天上午/下午各一个时段
                    vi = (day - 1) * 2 + w
                    venue = venues[s % len(venues)]
                    placements.append(PlacementEx(pool, s, vi, day, cap, venue))
    placements.sort(key=lambda p: (p.day, p.window_idx))

    # ---- 报名：每人至少 1 项，兼项按 multi_rate ----
    regs: List[Tuple[int, int]] = []                 # (athlete, event_id)
    for a in athletes:
        k = 1
        if rng.random() < multi_rate:
            # 1/2/3 项兼项（用户要求「含 1/2/3 个兼项」）
            k = rng.choice([1, 1, 2, 2, 2, 3])
        picks = rng.sample([e[0] for e in events], min(k, len(events)))
        for p in picks:
            regs.append((a, ev_ids[p]))
    # HELL 档：确保**人人**至少 2 项（真正的压力上限）
    if tier == "HELL":
        by_ath: dict = {}
        for a, e in regs:
            by_ath.setdefault(a, []).append(e)
        for a in athletes:
            have = by_ath.get(a, [])
            for _ in range(2 - len(have)):
                cand = [e[0] for e in events if ev_ids[e[0]] not in have]
                if not cand:
                    break
                pick = rng.choice(cand)
                regs.append((a, ev_ids[pick]))
                have.append(ev_ids[pick])

    by_ath2: dict = {}
    for a, e in regs:
        by_ath2.setdefault(a, []).append(e)

    # ---- 生成单元（项目 × 年级；TEAM 档按队伍而非年级） ----
    units: List[UnitEx] = []
    ev_pool = [e for e in events]
    for eid, tmpl in enumerate(ev_pool):
        name, track, pool, batch_min, cap, _spec, is_team = tmpl
        member_map: dict = {}
        for a, e in regs:
            if e == eid:
                member_map.setdefault(0, []).append(a)   # TEAM 也先聚成一块
        for _grp, members in member_map.items():
            if not members:
                continue
            if is_team:
                # 球类：整队一个单元，容量 = 队伍数
                size = 6 if "篮球" in name or "排球" in name else (11 if "足球" in name else 8)
                teams = [members[i:i + size] for i in range(0, len(members), size)]
                units.append(UnitEx(
                    key=f"e{eid}#team", event_id=eid, event_name=name, grade=None,
                    track=False, pool_label="球类", group_key=f"team-{name}",
                    interval=15, venue=rng.choice(VENUES),
                    raw_duration=len(teams) * batch_min, batch_minutes=batch_min,
                    heat_capacity=max(1, len(teams)),
                    athletes=members, is_team=True,
                    team_size=size, fmt=rng.choice(TEAM_FORMATS),
                ))
            else:
                if tier == "BLOCK":
                    # 项目块：按年级切成 2-3 块，每块是独立单元但共享 group_key
                    chunks = [members[i::3] for i in range(3)]
                    chunks = [c for c in chunks if c]
                    for ci, chunk in enumerate(chunks):
                        units.append(UnitEx(
                            key=f"e{eid}#b{ci}", event_id=eid, event_name=name, grade=None,
                            track=track, pool_label=pool,
                            group_key=f"block-{name}",     # ★ 项目块：同块必须挨着排
                            interval=rng.choice([0, 5, 10, 15]),
                            venue=rng.choice(["田径场"] if track else ["跳远区", "铅球区"]),
                            raw_duration=max(1, (len(chunk) + cap - 1) // cap) * batch_min,
                            batch_minutes=batch_min, heat_capacity=cap, athletes=chunk,
                        ))
                else:
                    for gi, g in enumerate(grades):
                        sub = members[gi::len(grades)]
                        if not sub:
                            continue
                        units.append(UnitEx(
                            key=f"e{eid}#{g}", event_id=eid, event_name=name, grade=g,
                            track=track, pool_label=pool, group_key=None,
                            interval=rng.choice([0, 5, 10, 15]),
                            venue=rng.choice(["田径场"] if track else ["跳远区", "铅球区"]),
                            raw_duration=max(1, (len(sub) + cap - 1) // cap) * batch_min,
                            batch_minutes=batch_min, heat_capacity=cap, athletes=sub,
                        ))

    # LANE 档：径赛比例更高、批次更细（考验分道次编排）
    if tier == "LANE":
        for u in units:
            if u.track:
                u.heat_capacity = rng.choice([4, 6, 8])
                u.batch_minutes = rng.choice([10, 15, 20])
                u.raw_duration = max(1, (len(u.athletes) + u.heat_capacity - 1)
                                     // u.heat_capacity) * u.batch_minutes

    return ScenarioEx(units=units, placements=placements, tier=tier, n_days=n_days)
