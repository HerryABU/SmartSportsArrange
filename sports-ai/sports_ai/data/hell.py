"""地狱级测试场景：真实规模的田径运动会报名（3 年级 × 8 班 × 30 人 = 720 名运动员）。

与 ``generator.py`` 的合成数据不同，这里刻意还原**真实田径项目表**与**真实报名结构**：

项目表（含两个时长概念 + 成绩排序方向 + 复赛）：
- **每批时长** ``batch_minutes``：一个组次（一道/一批）的用时；
- **全部时长**：该项目该年级所有组次之和（= 组数 × 每批时长），即编排里单元的 ``raw_duration``；
- **成绩排序方向** ``sort``：径赛用时**小→大**（``asc``）、田赛远度/高度/个数**大→小**（``desc``）
  —— 用于「取前 N 名进决赛」；
- **是否有复赛** ``has_final``：50m/100m/4×100 预赛取前 8 进决赛；800/1000 直接决赛。

报名结构（用户指定的变态分布）：大部分报 1 项、少部分报 2 项、**每班至少 1 人报 3 项**。

单元 = 项目 × 年级 × 轮次（预赛 / 决赛）。
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

from .generator import Placement, Scenario, Unit


# ---------------------------------------------------------------------------
# 真实田径项目表
# ---------------------------------------------------------------------------
@dataclass
class EventDef:
    code: str
    name: str
    track: bool
    pool: str
    batch_minutes: int      # 每批（一个组次）时长
    heat_capacity: int      # 一批容纳人数（道数 / 分组容量）
    sort: str               # 'asc' = 用时小→大；'desc' = 远度/高度/个数 大→小
    has_final: bool         # 是否有复赛（预赛取前 N 进决赛）
    group: Optional[str]    # 田赛「同组同时开赛」
    gender: Optional[str] = None   # None=不分性别, 'f'/'m'=限性别
    team: bool = False             # 团体项目（接力）

    @property
    def sort_label(self) -> str:
        return "小→大(用时)" if self.sort == "asc" else "大→小(远度/高度/个数)"


EVENTS: List[EventDef] = [
    EventDef("50m",   "50米",        True,  "径赛", 15, 8,  "asc",  True,  None),
    EventDef("100m",  "100米",       True,  "径赛", 20, 8,  "asc",  True,  None),
    EventDef("800m",  "800米(女子)", True,  "径赛", 45, 8,  "asc",  False, None, gender="f"),
    EventDef("1000m", "1000米(男子)", True, "径赛", 50, 8,  "asc",  False, None, gender="m"),
    EventDef("4x100", "4×100米接力", True,  "径赛", 30, 8,  "asc",  True,  None, team=True),
    EventDef("SLJ",   "立定跳远",    False, "田赛", 60, 6,  "desc", False, "田赛跳跃组"),
    EventDef("HJ",    "跳高",        False, "田赛", 90, 6,  "desc", False, "田赛跳跃组"),
    EventDef("PU",    "引体向上",    False, "田赛", 40, 10, "desc", False, "田赛力量组"),
    EventDef("SP",    "铅球",        False, "田赛", 50, 6,  "desc", False, "田赛投掷组"),
]

# 专长 → 可报项目（同簇倾向，制造真实兼项冲突簇）
_SPECIALTY_EVENTS: Dict[str, List[str]] = {
    "sprint": ["50m", "100m", "4x100"],
    "middle": ["800m", "1000m", "100m"],
    "jump":   ["SLJ", "HJ", "50m"],
    "strength": ["PU", "SP", "100m"],
    "allround": ["50m", "100m", "SLJ", "SP", "PU", "HJ", "4x100", "800m", "1000m"],
}


@dataclass
class Athlete:
    id: int
    name: str
    grade: str
    clazz: str
    gender: str          # 'f' / 'm'
    events: List[str]    # 报名项目 code 列表


@dataclass
class HellMeet:
    athletes: List[Athlete]
    scenario: Scenario
    days: Optional[int]          # None = 不限时间
    estimated_days: int          # 反推的需要天数
    daily_capacity: int          # 每天供给（分钟）
    stats: Dict[str, object] = field(default_factory=dict)


# ---------------------------------------------------------------------------
def _eligible(ev: EventDef, gender: str) -> bool:
    return ev.gender is None or ev.gender == gender


def generate_athletes(seed: int, n_grades: int = 3, classes_per_grade: int = 8,
                      per_class: int = 30) -> List[Athlete]:
    """生成 3 年级 × 8 班 × 30 人 = 720 名运动员 + 报名（大部分 1 项 / 少部分 2 项 / 每班至少 1 人 3 项）。"""
    rng = random.Random(seed)
    grades = [f"高{i + 1}" for i in range(n_grades)]
    athletes: List[Athlete] = []
    aid = 1
    for gi, grade in enumerate(grades):
        for ci in range(classes_per_grade):
            clazz = f"{grade}（{ci + 1}）班"
            # 本班报名人数分布：大部分 1 项、少部分 2 项、至少 1 人 3 项
            counts = [1] * per_class
            n2 = max(1, int(per_class * 0.25))          # ~25% 报 2 项
            n3 = max(1, int(per_class * 0.04))          # ~4% 报 3 项
            for k in range(n2):
                counts[k] = 2
            for k in range(n3):
                counts[-1 - k] = 3
            assert any(c == 3 for c in counts), "每班至少 1 人报 3 项"
            for k in range(per_class):
                gender = "f" if rng.random() < 0.5 else "m"
                spec = rng.choices(list(_SPECIALTY_EVENTS.keys()),
                                   weights=[0.30, 0.15, 0.20, 0.25, 0.10], k=1)[0]
                pool = _SPECIALTY_EVENTS[spec]
                want = counts[k]
                chosen: List[str] = []
                # 先按专长挑，不足再全项目池补齐
                for code in pool:
                    if len(chosen) >= want:
                        break
                    ev = next(e for e in EVENTS if e.code == code)
                    if _eligible(ev, gender) and code not in chosen:
                        chosen.append(code)
                all_codes = [e.code for e in EVENTS if _eligible(e, gender)]
                rng.shuffle(all_codes)
                for code in all_codes:
                    if len(chosen) >= want:
                        break
                    if code not in chosen:
                        chosen.append(code)
                athletes.append(Athlete(id=aid, name=f"{grade}学生{aid:03d}", grade=grade,
                                        clazz=clazz, gender=gender, events=chosen))
                aid += 1
    return athletes


def _build_units(athletes: List[Athlete], grades: List[str]) -> Tuple[List[Unit], Dict[str, object]]:
    """单元 = 项目 × 年级 × 轮次（预赛 / 决赛）。返回 (units, 统计)。"""
    units: List[Unit] = []
    round_counts = {"prelim": 0, "final": 0}
    heat_total = 0
    for eid, ev in enumerate(EVENTS):
        for grade in grades:
            parts = [a for a in athletes if a.grade == grade and ev.code in a.events]
            if not parts:
                continue
            gkey = ev.group  # 田赛同组同时开赛
            # ---- 预赛（或直接决赛的长跑项目）----
            n = len(parts)
            heats = max(1, math.ceil(n / ev.heat_capacity))
            heat_total += heats
            prelim_dur = heats * ev.batch_minutes           # 全部时长 = 组数 × 每批时长
            rnd = "决赛" if not ev.has_final else "预赛"
            units.append(Unit(
                key=f"{ev.code}@{grade}@{rnd}", event_id=eid, event_name=f"{ev.name}({rnd})",
                grade=grade, track=ev.track, pool_label=ev.pool, group_key=gkey,
                raw_duration=prelim_dur, athletes=sorted(a.id for a in parts),
                batch_minutes=ev.batch_minutes, heat_capacity=ev.heat_capacity))
            if not ev.has_final:
                round_counts["final"] += 1
            else:
                round_counts["prelim"] += 1
                # ---- 决赛：预赛取前 8 名（按成绩排序方向；此处用种子近似）----
                #      径赛 asc（用时小→大）：取最快 8 人；田赛 desc（远度大→小）：取最好 8 人
                seeded = sorted(parts, key=lambda a: a.id)[:8]
                units.append(Unit(
                    key=f"{ev.code}@{grade}@决赛", event_id=eid,
                    event_name=f"{ev.name}(决赛)", grade=grade, track=ev.track,
                    pool_label=ev.pool, group_key=gkey, raw_duration=ev.batch_minutes,
                    athletes=sorted(a.id for a in seeded),
                    batch_minutes=ev.batch_minutes, heat_capacity=ev.heat_capacity))
                round_counts["final"] += 1
    stats = {"unitCount": len(units), "prelimUnits": round_counts["prelim"],
             "finalUnits": round_counts["final"], "heatTotal": heat_total,
             "teamUnits": sum(1 for u in units for e in EVENTS if e.code == u.key.split("@")[0] and e.team)}
    return units, stats


# 真实田径运动会的默认时间资源（可在 generate_hell 覆盖）
DEFAULT_DAY_MINUTES = (240, 240)   # 每天 2 时段：上午 4h + 下午 4h = 480 分钟
DEFAULT_TRACK_LANES = 2            # 径赛 2 条并行流（短跑直道 + 中长跑环道）
DEFAULT_FIELD_LANES = 4            # 田赛 4 块场地（立定跳远 / 跳高 / 引体向上 / 铅球）


def _placements(days: int, track_lanes: int = DEFAULT_TRACK_LANES,
                field_lanes: int = DEFAULT_FIELD_LANES,
                day_minutes: Tuple[int, ...] = DEFAULT_DAY_MINUTES) -> List[Placement]:
    """位置网格：径赛 ``track_lanes`` 并发、田赛 ``field_lanes`` 并发；每天按时段窗口切分。

    ``window_idx`` 取全局窗口序号（跨天唯一），同一 ``(day, window)`` 即同一**时段**
    ——兼项冲突的判定单位（一个运动员同一时段只能出现在一个并发位上）。
    """
    out: List[Placement] = []
    for pool, lanes in (("径赛", max(1, track_lanes)), ("田赛", max(1, field_lanes))):
        for day in range(1, days + 1):
            for wi, cap in enumerate(day_minutes, start=1):
                widx = (day - 1) * len(day_minutes) + wi
                for s in range(lanes):
                    out.append(Placement(pool_label=pool, slot_idx=s, window_idx=widx,
                                         day=day, window_capacity=cap))
    return out


def estimate_days(units: List[Unit], track_lanes: int = DEFAULT_TRACK_LANES,
                  field_lanes: int = DEFAULT_FIELD_LANES,
                  day_minutes: Tuple[int, ...] = DEFAULT_DAY_MINUTES) -> int:
    """不限时间时反推需要几天：径赛/田赛分别算（池独立），取较大者，向上取整。"""
    per_day = sum(day_minutes)
    per_day_track = max(1, track_lanes) * per_day
    per_day_field = max(1, field_lanes) * per_day
    d_track = sum(u.raw_duration for u in units if u.pool_label == "径赛")
    d_field = sum(u.raw_duration for u in units if u.pool_label == "田赛")
    need_track = math.ceil(d_track / per_day_track) if per_day_track else 0
    need_field = math.ceil(d_field / per_day_field) if per_day_field else 0
    return max(1, need_track, need_field)


def generate_hell(seed: int = 20260918, days: Optional[int] = None,
                  n_grades: int = 3, classes_per_grade: int = 8, per_class: int = 30,
                  track_lanes: int = DEFAULT_TRACK_LANES,
                  field_lanes: int = DEFAULT_FIELD_LANES,
                  day_minutes: Tuple[int, ...] = DEFAULT_DAY_MINUTES) -> HellMeet:
    """生成地狱级场景。

    ``days=None`` → 不限运动会时间，按需求反推天数（约 3 天）；
    ``days=2``    → 限定 2 天（容量紧张，但通过拆批 + 智能错峰仍尽量可解）。
    """
    athletes = generate_athletes(seed, n_grades, classes_per_grade, per_class)
    grades = [f"高{i + 1}" for i in range(n_grades)]
    units, ustat = _build_units(athletes, grades)
    estimated = estimate_days(units, track_lanes, field_lanes, day_minutes)
    use_days = estimated if days is None else days
    placements = _placements(use_days, track_lanes, field_lanes, day_minutes)

    def pool_supply(pool: str) -> int:
        lanes = track_lanes if pool == "径赛" else field_lanes
        return max(1, lanes) * sum(day_minutes) * use_days

    def pool_demand(pool: str) -> int:
        return sum(u.raw_duration for u in units if u.pool_label == pool)

    demand = sum(u.raw_duration for u in units)
    supply = pool_supply("径赛") + pool_supply("田赛")
    stats = dict(ustat)
    stats.update({
        "athleteCount": len(athletes),
        "classCount": n_grades * classes_per_grade,
        "eventCount": len(EVENTS),
        "demandMinutes": demand,
        "trackDemand": pool_demand("径赛"),
        "fieldDemand": pool_demand("田赛"),
        "trackSupply": pool_supply("径赛"),
        "fieldSupply": pool_supply("田赛"),
        "dailyCapacity": supply // max(1, use_days),
        "trackLanes": track_lanes,
        "fieldLanes": field_lanes,
        "dayMinutes": list(day_minutes),
        "estimatedDays": estimated,
        "usedDays": use_days,
        "tension": round(demand / supply, 3) if supply else 0.0,
    })
    scenario = Scenario(units=units, placements=placements)
    return HellMeet(athletes=athletes, scenario=scenario, days=days,
                    estimated_days=estimated, daily_capacity=supply // max(1, use_days),
                    stats=stats)
