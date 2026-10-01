"""分批装箱 + 局部搜索：在真实时间资源下把赛程**尽量排下**。

模型（与 Java 端 Placement 语义一致）：
- **并发位（bin）** = 池 × 槽位 × 时段窗口，容量 = 该时段分钟数（独占资源）；
- **时段（period）** = 某个 ``window_idx``（跨天唯一）——同一时段下的所有并发位在
  时间上重叠，因此**兼项冲突的判定单位是时段**：一个运动员同一时段只能出现在一个位置。

四类约束：
1. **容量**：同一并发位内所有组次时长之和 ≤ 该时段容量；
2. **池匹配**：径赛组次只能进径赛位，田赛组次只能进田赛位；
3. **兼项**：同一时段内同一运动员至多出现一次（跨池也成立——人不能分身）；
4. **偏序**：同项目同年级的**预赛组次整体早于决赛组次**（预赛→决赛）。

目标（字典序式的加权和）：最小化「未排组次数 → 未排人次 → 兼项重叠重复度」。
先贪心构造（难放的先放、能无损放入则尽早放），再局部搜索（单任务重定位爬山）。
"""

from __future__ import annotations

import random
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence, Tuple

from ..data.generator import Placement, Unit
from .heats import HeatTask, expand_heats

# 代价为**字典序元组**（未排组次数, 未排人次, 兼项重叠重复度）：
# 先不惜一切代价把「排不下」压到最少，再在同等的未排规模下压兼项重叠。
# 用元组而非加权和，是为了避免权重标定失当导致「多排 1 组但引入一堆冲突」这种劣解被选中。
Cost = Tuple[int, int, int]


@dataclass(frozen=True)
class Bin:
    """一个独占的并发位。"""

    key: str
    day: int
    window: int          # window_idx（跨天唯一的时段序号）
    pool: str
    lane: int
    capacity: int
    period_idx: int = 0  # 时段的全局排序下标（偏序比较用）


@dataclass
class Assignment:
    task: HeatTask
    bin: Bin


@dataclass
class ScheduleResult:
    """求解结果（含未排清单与残留冲突，供报告层导出）。"""

    assigns: List[Assignment] = field(default_factory=list)
    unplaced: List[HeatTask] = field(default_factory=list)
    conflicts: List[Dict[str, object]] = field(default_factory=list)
    bins_total: int = 0
    periods_total: int = 0
    tasks_total: int = 0
    placed_tasks: int = 0
    unplaced_athlete_slots: int = 0
    total_athlete_slots: int = 0
    conflict_multiplicity: int = 0
    conflict_athletes: int = 0
    unplaced_units: List[str] = field(default_factory=list)
    rounds_run: int = 0
    cost: Cost = (0, 0, 0)

    @property
    def feasible(self) -> bool:
        return self.placed_tasks == self.tasks_total and self.conflict_multiplicity == 0

    @property
    def placed_ratio(self) -> float:
        return self.placed_tasks / self.tasks_total if self.tasks_total else 1.0


# ---------------------------------------------------------------------------
def _make_bins(placements: List[Placement]) -> List[Bin]:
    seen: Dict[str, Placement] = {}
    for p in placements:
        seen.setdefault(p.bin_key, p)
    windows = sorted({p.window_idx for p in placements})
    widx = {w: i for i, w in enumerate(windows)}
    return sorted(
        (Bin(key=p.bin_key, day=p.day, window=p.window_idx, pool=p.pool_label,
             lane=p.slot_idx, capacity=p.window_capacity, period_idx=widx[p.window_idx])
         for p in seen.values()),
        key=lambda b: (b.period_idx, b.pool, b.lane),
    )


class _State:
    """增量状态：分配 / 负载 / 时段运动员计数 / 偏序索引。"""

    def __init__(self, tasks: List[HeatTask], bins: List[Bin]):
        self.tasks = tasks
        self.bins = bins
        self.by_pool: Dict[str, List[Bin]] = defaultdict(list)
        for b in bins:
            self.by_pool[b.pool].append(b)
        self.bin_of = {b.key: b for b in bins}
        self.assign: Dict[int, Optional[str]] = {t.uid: None for t in tasks}
        self.load: Dict[str, int] = {b.key: 0 for b in bins}
        self.period_ath: Dict[int, Counter] = defaultdict(Counter)
        self.period_conf: Dict[int, int] = defaultdict(int)
        # 偏序：只对「同时存在预赛与决赛」的 (event, grade) 生效
        pre = {(t.event_id, t.grade) for t in tasks if t.round_order == 0}
        fin = {(t.event_id, t.grade) for t in tasks if t.round_order == 1}
        self.related = pre & fin
        self.prelim_at: Dict[Tuple[int, str], Counter] = defaultdict(Counter)
        self.final_at: Dict[Tuple[int, str], Counter] = defaultdict(Counter)
        self.unplaced_tasks = len(tasks)
        self.unplaced_athletes = sum(len(t.athletes) for t in tasks)

    # ---- 冲突账本 ----------------------------------------------------------
    def _add(self, t: HeatTask, b: Bin) -> None:
        c = self.period_ath[b.period_idx]
        conf = self.period_conf[b.period_idx]
        for a in t.athletes:
            if c[a] >= 1:
                conf += 1
            c[a] += 1
        self.period_conf[b.period_idx] = conf

    def _rm(self, t: HeatTask, b: Bin) -> None:
        c = self.period_ath[b.period_idx]
        conf = self.period_conf[b.period_idx]
        for a in t.athletes:
            c[a] -= 1
            if c[a] >= 1:
                conf -= 1
            if c[a] <= 0:
                del c[a]
        self.period_conf[b.period_idx] = conf

    def conflict_delta(self, t: HeatTask, b: Bin) -> int:
        c = self.period_ath[b.period_idx]
        return sum(1 for a in t.athletes if c[a] >= 1)

    # ---- 候选并发位 --------------------------------------------------------
    def candidates(self, t: HeatTask, for_move: bool = False) -> List[Bin]:
        out: List[Bin] = []
        key = (t.event_id, t.grade)
        related = key in self.related
        cur = self.assign.get(t.uid)
        cur_bin = self.bin_of[cur] if cur else None
        pmax: Optional[int] = None
        fmin: Optional[int] = None
        if related:
            if t.round_order == 1 and self.prelim_at.get(key):
                pmax = max(self.prelim_at[key].keys())
            if t.round_order == 0 and self.final_at.get(key):
                fmin = min(self.final_at[key].keys())
        for b in self.by_pool[t.pool]:
            used = self.load[b.key]
            if for_move and cur_bin is not None and b.key == cur_bin.key:
                used -= t.duration
            if used + t.duration > b.capacity:
                continue
            if pmax is not None and b.period_idx <= pmax:
                continue
            if fmin is not None and b.period_idx >= fmin:
                continue
            out.append(b)
        return out

    # ---- 移动评估与应用 ----------------------------------------------------
    def cost_after(self, t: HeatTask, target: Optional[Bin]) -> Cost:
        """把 t 移动到 target（None = 移出/不排）后的全局面代价（评估后自动回滚）。"""
        old_key = self.assign.get(t.uid)
        old_bin = self.bin_of[old_key] if old_key else None
        if old_bin is not None:
            self._rm(t, old_bin)
        if target is not None:
            self._add(t, target)
        conf = sum(self.period_conf.values())
        du_t = (1 if old_bin is None else 0) - (1 if target is None else 0)
        du_a = ((len(t.athletes) if old_bin is None else 0)
                - (len(t.athletes) if target is None else 0))
        cost = (self.unplaced_tasks + du_t,
                self.unplaced_athletes + du_a,
                conf)
        if target is not None:
            self._rm(t, target)
        if old_bin is not None:
            self._add(t, old_bin)
        return cost

    def apply(self, t: HeatTask, target: Optional[Bin]) -> None:
        old_key = self.assign.get(t.uid)
        old_bin = self.bin_of[old_key] if old_key else None
        key = (t.event_id, t.grade)
        related = key in self.related
        if old_bin is not None:
            self._rm(t, old_bin)
            self.load[old_bin.key] -= t.duration
        else:
            self.unplaced_tasks -= 1
            self.unplaced_athletes -= len(t.athletes)
        if target is not None:
            self._add(t, target)
            self.load[target.key] += t.duration
        else:
            self.unplaced_tasks += 1
            self.unplaced_athletes += len(t.athletes)
        self.assign[t.uid] = target.key if target is not None else None
        if related:
            store = self.prelim_at if t.round_order == 0 else self.final_at
            if old_bin is not None:
                store[key][old_bin.period_idx] -= 1
                if store[key][old_bin.period_idx] <= 0:
                    del store[key][old_bin.period_idx]
            if target is not None:
                store[key][target.period_idx] += 1

    def current_cost(self) -> Cost:
        return (self.unplaced_tasks, self.unplaced_athletes,
                sum(self.period_conf.values()))


# ---------------------------------------------------------------------------
def _greedy(st: _State, tasks: List[HeatTask], backward: bool = False) -> None:
    """贪心构造。

    - **正排**（``backward=False``）：先预赛后决赛，每个任务落**最早**可行位——适合容量宽松；
    - **倒排**（``backward=True``）：先决赛（受偏序钳制、时长小、必须放下）占**最晚**时段，
      预赛再往前铺——避免「预赛把尾部时段塞满、决赛无处容身」的碎片化失败。

    两种起点的解都保留，最后由局部搜索与代价比较取优（见 :func:`schedule`）。
    """
    if backward:
        order = sorted(tasks, key=lambda t: (-t.round_order, -t.duration,
                                             -len(t.athletes), t.uid))
    else:
        order = sorted(tasks, key=lambda t: (t.round_order, -t.duration,
                                             -len(t.athletes), t.uid))
    for t in order:
        cands = st.candidates(t)
        if not cands:
            continue                       # 保持未排
        best = None
        best_key = None
        for b in cands:
            if backward:
                score = (st.conflict_delta(t, b), -b.period_idx, -st.load[b.key])
            else:
                score = (st.conflict_delta(t, b), b.period_idx, -st.load[b.key])
            if best_key is None or score < best_key:
                best_key, best = score, b
        if best is not None:
            st.apply(t, best)


def _local_search(st: _State, tasks: List[HeatTask], rounds: int,
                  rng: random.Random) -> int:
    """单任务重定位爬山：反复尝试把任务挪到更优并发位，直到无改进。"""
    used = 0
    for r in range(rounds):
        improved = False
        order = list(tasks)
        rng.shuffle(order)
        for t in order:
            cur_key = st.assign.get(t.uid)
            cur_bin = st.bin_of[cur_key] if cur_key else None
            best = cur_bin
            best_cost = st.cost_after(t, cur_bin)
            for b in st.candidates(t, for_move=True):
                if cur_bin is not None and b.key == cur_bin.key:
                    continue
                c = st.cost_after(t, b)
                if c < best_cost:
                    best_cost, best = c, b
            if best is not cur_bin:
                st.apply(t, best)
                improved = True
        used = r + 1
        if not improved:
            break
    return used


def schedule(units: List[Unit], placements: List[Placement], rounds: int = 6,
             seed: int = 20260918, conflict_sample: int = 200) -> ScheduleResult:
    """求解：返回排期结果（含未排清单与残留兼项冲突）。"""
    tasks = expand_heats(units)
    bins = _make_bins(placements)
    res = ScheduleResult(
        bins_total=len(bins),
        periods_total=len({b.period_idx for b in bins}),
        tasks_total=len(tasks),
        total_athlete_slots=sum(len(t.athletes) for t in tasks),
    )
    if not tasks or not bins:
        res.unplaced = list(tasks)
        res.unplaced_athlete_slots = res.total_athlete_slots
        return res

    st = _State(tasks, bins)
    # 双起点：正排（容量宽松时优）与倒排（尾部时段紧张、决赛受偏序钳制时优），取代价更小者
    st_a = _State(tasks, bins)
    _greedy(st_a, tasks, backward=False)
    cost_a = st_a.current_cost()
    st_b = _State(tasks, bins)
    _greedy(st_b, tasks, backward=True)
    cost_b = st_b.current_cost()
    st = st_b if cost_b < cost_a else st_a
    res.rounds_run = _local_search(st, tasks, rounds, random.Random(seed))

    # ---- 汇总 ----
    res.assigns = [Assignment(task=t, bin=st.bin_of[st.assign[t.uid]])
                   for t in tasks if st.assign[t.uid] is not None]
    res.unplaced = [t for t in tasks if st.assign[t.uid] is None]
    res.placed_tasks = len(res.assigns)
    res.unplaced_athlete_slots = sum(len(t.athletes) for t in res.unplaced)
    res.unplaced_units = sorted({t.unit_key for t in res.unplaced})
    res.conflict_multiplicity = sum(st.period_conf.values())
    res.cost = st.current_cost()

    by_period: Dict[int, List[HeatTask]] = defaultdict(list)
    day_of: Dict[int, int] = {}
    for a in res.assigns:
        by_period[a.bin.period_idx].append(a.task)
        day_of[a.bin.period_idx] = a.bin.day
    conflicts: List[Dict[str, object]] = []
    clash_athletes = set()
    for p, ts in sorted(by_period.items()):
        cnt: Counter = Counter()
        for t in ts:
            for a in t.athletes:
                cnt[a] += 1
        for a, c in cnt.items():
            if c > 1:
                clash_athletes.add(a)
                if len(conflicts) < conflict_sample:
                    conflicts.append({
                        "period": p,
                        "day": day_of.get(p),
                        "athlete": int(a),
                        "count": int(c),
                        "units": [t.unit_key for t in ts if a in t.athletes],
                    })
    res.conflicts = conflicts
    res.conflict_athletes = len(clash_athletes)
    return res
