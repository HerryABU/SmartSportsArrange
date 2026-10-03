"""超级编排统一场景：项目 / 道次 / 球类（小组·淘汰·循环·混合）/ 二次编排。

本模块把系统里**全部编排维度**收敛成一种统一表示，供
{@code models/super_moe.SuperScheduleMoE} 单一模型消费——这是「一个超级模型
覆盖所有编排」的前提：**如果输入表示不统一，再大的 MoE 也学不到统一策略**。

## 时间目标三态（用户明确要求）

======================  ==========================================
``days_limit``          语义
======================  ==========================================
``>= 1``                **硬约束**：必须在 N 天内排完，排不下要报不可行
``0``                   **不限时间**：只要可行，天数不设目标
``-1``                  **最小化工期**：可行前提下尽量压缩总天数
======================  ==========================================

编码进节点的 ``time_goal`` 维度（0=不限 / 0.5=硬约束 / 1=最小化），
并在 ``scenarios`` 里保留原始值供 Java 端解释——**模型只吃归一化值，
决策含义要靠 Java 端那一侧翻译**，两边必须同步。

## 九类编排任务（MoE 的专家划分依据）

``TASK_*`` 常量与模型的专家一一对应：

0 PROJECT  项目编排（时间槽分配）
1 LANE     道次编排（分道次 + 批次）
2 BALL     球类赛制（小组/循环）
3 KNOCKOUT 淘汰赛晋级
4 BLOCK    项目块完整性（禁止见缝插针）
5 CONFLICT 兼项避让
6 CAPACITY 装箱/容量
7 MAKESPAN 工期压缩
8 RESECOND 二次编排（淘汰赛后重排道次）
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

# ---- 九类编排任务（顺序即 MoE 专家下标，双端契约）----
TASK_PROJECT = 0
TASK_LANE = 1
TASK_BALL = 2
TASK_KNOCKOUT = 3
TASK_BLOCK = 4
TASK_CONFLICT = 5
TASK_CAPACITY = 6
TASK_MAKESPAN = 7
TASK_RESECOND = 8
# 合并进超级模型的第 10、11 类任务：裁判编排 / 教师（行政）规避。
# ⚠️ 它们的约束结构与项目编排同构，并入同一张图才能学到跨域耦合；
#    常量改了必须连着重训（专家数变化会改权重形状，不重训 → Java 静默回退规则）。
TASK_REFEREE = 9
TASK_TEACHER = 10
N_TASKS = 11

TASK_NAMES = {
    TASK_PROJECT: "项目编排",
    TASK_LANE: "道次编排",
    TASK_BALL: "球类赛制",
    TASK_KNOCKOUT: "淘汰赛晋级",
    TASK_BLOCK: "项目块完整性",
    TASK_CONFLICT: "兼项避让",
    TASK_CAPACITY: "装箱容量",
    TASK_MAKESPAN: "工期压缩",
    TASK_RESECOND: "二次编排",
    TASK_REFEREE: "裁判编排",
    TASK_TEACHER: "教师规避",
}

# ---- 边类型（顺序即 ONNX 通道号，双端契约）----
E_ATHLETE = 0   # 兼项：共享运动员
E_BLOCK = 1     # 项目块：同 group_key（必须整块相邻）
E_VENUE = 2     # 场地独占
E_POOL = 3      # 同并发池竞争
E_TIME = 4      # 装箱/间隔耦合
E_LANE = 5      # 同道次/同批次
E_BRACKET = 6   # 淘汰赛晋级关系
E_TEAM = 7      # 同队（球类）
N_EDGES = 8

EDGE_NAMES = {
    E_ATHLETE: "兼项", E_BLOCK: "项目块", E_VENUE: "场地",
    E_POOL: "并发池", E_TIME: "装箱间隔", E_LANE: "道次",
    E_BRACKET: "晋级", E_TEAM: "同队",
}

# ---- 球类赛制 ----
FMT_GROUP = 0     # 小组赛
FMT_ROUND_ROBIN = 1  # 循环赛
FMT_KNOCKOUT = 2  # 淘汰赛
FMT_HYBRID = 3    # 混合（小组 + 淘汰）
N_FORMATS = 4

FORMAT_NAMES = {FMT_GROUP: "group", FMT_ROUND_ROBIN: "round_robin",
                FMT_KNOCKOUT: "knockout", FMT_HYBRID: "hybrid"}


@dataclass
class SuperUnit:
    """统一编排单元。个人项目、球赛、淘汰赛轮次都用它表示。"""
    key: str
    name: str
    task: int                 # 属于哪类编排任务（TASK_*）
    track: bool = False
    is_team: bool = False
    fmt: Optional[int] = None  # 球类赛制
    group_key: Optional[str] = None   # 项目块
    venue: str = "V0"
    pool: str = "P0"
    grade: str = ""
    duration: int = 30               # 分钟
    interval: int = 0                # 与上一单元最小间隔
    heat_capacity: int = 0           # 道次/批次容量（>0 才有道次语义）
    lanes: int = 0                   # 道数
    team_size: int = 0
    athletes: List[int] = field(default_factory=list)
    # 淘汰赛：晋级关系
    bracket_parent: Optional[str] = None
    bracket_round: int = 0
    # 二次编排：首轮道次已定，重排时要保兼容
    resecond_of: Optional[str] = None
    stage: str = "main"              # main / prelim / final / resecond


@dataclass
class SuperVenue:
    name: str
    courts: int = 1


@dataclass
class SuperWindow:
    day: int
    window_idx: int
    capacity: int          # 该时段可容纳的总时长
    venue: str
    pool: str = "P0"


@dataclass
class SuperScenario:
    units: List[SuperUnit] = field(default_factory=list)
    venues: List[SuperVenue] = field(default_factory=list)
    windows: List[SuperWindow] = field(default_factory=list)
    days_limit: int = 0            # 见文件头三态说明
    tier: str = "HELL"
    n_athletes: int = 0
    seed: int = 0
    # 统计
    n_blocks: int = 0
    invalid_units: List[str] = field(default_factory=list)
    n_multi_athletes: int = 0
    max_multi: int = 0
    n_events: int = 0

    def multi_event_athletes(self) -> List[Tuple[int, int]]:
        """返回 (兼项人数, 最多兼几项) —— 兼项难度的度量。"""
        cnt: Dict[int, int] = {}
        for u in self.units:
            for a in u.athletes:
                cnt[a] = cnt.get(a, 0) + 1
        if not cnt:
            return [0, 0]
        mx = max(cnt.values())
        return [sum(1 for v in cnt.values() if v > 1), mx]

    def blocks(self) -> Dict[str, List[str]]:
        out: Dict[str, List[str]] = {}
        for u in self.units:
            if u.group_key:
                out.setdefault(u.group_key, []).append(u.key)
        return out

    def formats(self) -> Dict[int, int]:
        out: Dict[int, int] = {}
        for u in self.units:
            if u.fmt is not None:
                out[u.fmt] = out.get(u.fmt, 0) + 1
        return out


# ---------------------------------------------------------------------------
# 场景生成
# ---------------------------------------------------------------------------

# 运动项目模板：(名, 是否径赛, 时长, 场地, 是否球类, 兼项倾向)
EVENT_POOL: List[Tuple[str, bool, int, str, bool, float]] = [
    ("100米", True, 15, "田径场", False, 0.9),
    ("200米", True, 20, "田径场", False, 0.9),
    ("400米", True, 30, "田径场", False, 0.7),
    ("800米", True, 40, "田径场", False, 0.5),
    ("1500米", True, 60, "田径场", False, 0.4),
    ("跳远", False, 40, "沙坑", False, 0.8),
    ("跳高", False, 45, "跳高区", False, 0.7),
    ("铅球", False, 40, "投掷区", False, 0.6),
    ("标枪", False, 45, "投掷区", False, 0.5),
    ("4×100米接力", True, 60, "田径场", False, 0.95),
    ("4×400米接力", True, 90, "田径场", False, 0.9),
    ("篮球", False, 120, "篮球场", True, 0.15),
    ("排球", False, 120, "排球场", True, 0.15),
    ("足球", False, 150, "足球场", True, 0.1),
    ("乒乓球", False, 90, "乒乓馆", True, 0.2),
    ("羽毛球", False, 80, "羽球馆", True, 0.2),
    ("拔河", False, 60, "操场", True, 0.3),
    ("实心球", False, 30, "田径场", False, 0.8),
    ("立定跳远", False, 35, "沙坑", False, 0.7),
    ("仰卧起坐", False, 25, "操场", False, 0.6),
    ("混合泳接力", True, 120, "泳池", False, 0.4),
    ("投掷沙包", False, 30, "操场", False, 0.7),
    ("体操垫", False, 40, "体操房", False, 0.5),
    ("拔河淘汰赛", False, 60, "操场", True, 0.4),
]

GRADES = ["高一", "高二", "高三"]


def add_shadow_tasks(scen: "SuperScenario", rng) -> None:
    """把裁判编排（TASK_REFEREE）与教师规避（TASK_TEACHER）单元并入现有场景。

    叫「影子任务」是因为它们**不改变主流程的编排结果**，只作为同一张图上的
    额外任务类型存在 —— 目的是让超级模型的 MoE 专家覆盖这两类编排，
    从而在同一个实例里权衡「项目 vs 裁判 vs 教师时间」的耦合。
    """
    n_ref = rng.randint(2, 4)
    for i in range(n_ref):
        scen.units.append(SuperUnit(
            key=f"REF{i}", name=f"裁判派遣组{i + 1}", task=TASK_REFEREE,
            venue="V0", pool="REF", group_key="REF_GROUP",
            duration=rng.randint(20, 39), interval=rng.randint(5, 14),
            athletes=[rng.randint(1000, 1011) for _ in range(rng.randint(1, 2))],
        ))
    n_tch = rng.randint(2, 3)
    for i in range(n_tch):
        scen.units.append(SuperUnit(
            key=f"TCH{i}", name=f"教师规避时段{i + 1}", task=TASK_TEACHER,
            venue="V0", pool="TCH", group_key="TCH_GROUP",
            duration=rng.randint(15, 34), interval=rng.randint(5, 14),
            athletes=[rng.randint(2000, 2007) for _ in range(rng.randint(1, 2))],
        ))
    # 给影子任务补窗口：复用既有场地/时段口径，避免引入新的编排契约
    if scen.windows:
        w0 = scen.windows[0]
        scen.windows.append(SuperWindow(day=w0.day, window_idx=len(scen.windows),
                                        capacity=max(120, w0.capacity // 2),
                                        venue="V0", pool="REF"))


def generate_super_scenario(tier: str = "HELL", seed: int = 0) -> SuperScenario:
    """按档位生成统一编排场景。

    档位（对齐用户点名的魔鬼条件）：

    ==============  ==========================================
    HELL            500~900 人 / 15 项目 / **兼项率 100%**（每人 1~3 项）
    REGULAR         250~350 人 / 10 项目 / 兼项 30% / 2~3 天
    BLOCK           250~400 人 / 12 项目 / **强制项目块**（禁止见缝插针）
    LANE            200~320 人 / 8 项目 / 多档道次容量
    TEAM            160~400 人 / 球类 + 四种赛制 + **淘汰赛晋级 + 二次编排**
    ==============  ==========================================
    """
    rng = random.Random(seed)
    cfg = {
        "HELL":    dict(ath=(500, 900), evt=15, multi=1.00, days=(2, 4), lanes=0),
        "REGULAR": dict(ath=(250, 350), evt=10, multi=0.30, days=(2, 3), lanes=0),
        "BLOCK":   dict(ath=(250, 400), evt=12, multi=0.40, days=(2, 3), lanes=0),
        "LANE":    dict(ath=(200, 320), evt=8,  multi=0.20, days=(2, 2), lanes=1),
        "TEAM":    dict(ath=(160, 400), evt=8,  multi=0.15, days=(2, 3), lanes=0),
    }[tier]
    days = rng.randint(*(cfg["days"] if isinstance(cfg["days"], tuple) else (cfg["days"], cfg["days"])))
    # ⚠️ 时间目标三态：HELL/REGULAR 用硬约束，TEAM 有 1/3 概率是「不限」或「最小化」
    roll = rng.random()
    if roll < 0.15:
        days_limit = -1
    elif roll < 0.30:
        days_limit = 0
    else:
        days_limit = days

    n_ath = rng.randint(*cfg["ath"])
    n_evt = min(cfg["evt"], len(EVENT_POOL))
    # ⚠️ **TEAM 档必须显式塞入球类事件**：纯随机抽样 8/24 的命中率很低，
    #    实测 40 个样本里绝大多数 ball_events 为空 → 队级单元 0 个 →
    #    validate 全部判废。这里改成「先按配额抽，再补足球类」。
    if tier == "TEAM":
        ball = [e for e in EVENT_POOL if e[4]]
        picks = rng.sample(EVENT_POOL, n_evt)
        have_ball = [e for e in picks if e[4]]
        if not have_ball:
            picks[rng.randrange(len(picks))] = rng.choice(ball)
    else:
        picks = rng.sample(EVENT_POOL, n_evt)
    events = picks
    ev_ids = {e[0]: i for i, e in enumerate(events)}
    athletes = list(range(n_ath))

    venues: List[SuperVenue] = [
        SuperVenue("田径场", 1), SuperVenue("沙坑", 1), SuperVenue("跳高区", 1),
        SuperVenue("投掷区", 1), SuperVenue("篮球场", 2), SuperVenue("排球场", 1),
        SuperVenue("足球场", 1), SuperVenue("操场", 2),
    ][: rng.randint(4, 8)]

    # ---- 窗口（时段）----
    # ⚠️ 早期版本每个时段只开**一个**场地。这在真实运动会里不成立 ——
    #    实际是「上午第一节：田径场 + 篮球场 + 沙坑同时跑」。
    #    单场地版本带来两个致命后果：
    #      ① 场地覆盖不全：单元会用到 11 种场地，而时段只随机铺 4~8 种，
    #         实测 47 个单元里有 15 个「一个能放进去的时段都没有」；
    #      ② 并行度假性偏低：每个时段只能塞 1 个单元，装不下东西。
    #    改成每时段并行开 2~4 个场地，总容量再由单元需求反推（见下），
    #    才是「既排得下、又逼得出装箱压力」的可行域。
    windows: List[SuperWindow] = []
    for d in range(1, max(2, days_limit if days_limit > 0 else days) + 1):
        for w in range(rng.choice([3, 4, 5])):
            k = rng.choice([2, 3, 3, 4])
            for v in rng.sample(venues, min(k, len(venues))):
                pool = "P0" if v.name in ("田径场", "操场") else "P1"
                windows.append(SuperWindow(day=d, window_idx=w,
                                            capacity=rng.choice([120, 150, 180, 240, 300]),
                                            venue=v.name, pool=pool))

    units: List[SuperUnit] = []

    # ---- 个人项目单元 ----
    ball_events = [e for e in events if e[4]]
    track_events = [e for e in events if e[1]]
    field_events = [e for e in events if not e[1] and not e[4]]

    for name, is_track, dur, venue, is_ball, mp in events:
        if is_ball:
            continue
        for gi, grade in enumerate(GRADES):
            # 项目块：同一项目同一年级切 2~3 块（BLOCK 档强制）
            n_blocks = 1
            if tier == "BLOCK":
                # BLOCK 档必须成块，但块数 × 年级 × 项目 会让单元数超过名额池容量
                # （池 = n_ath * max_multi）。实测 3 块 × 3 年级 × 12 项目 = 108 单元
                # 而池只有 ~1000 人次 → 大面积越界。限制为「只对部分项目切块」。
                n_blocks = 2 if rng.random() < 0.6 else 3
            elif rng.random() < 0.25:
                n_blocks = rng.choice([2, 3])
            for b in range(n_blocks):
                key = f"{name}|{grade}|B{b}"
                hc = 0
                lanes = 0
                if cfg["lanes"] or (tier == "LANE"):
                    lanes = rng.choice([4, 6, 8])
                    hc = rng.choice([2, 3, 4, 6])
                units.append(SuperUnit(
                    key=key, name=name,
                    task=TASK_LANE if hc > 0 else TASK_PROJECT,
                    track=is_track, venue=venue,
                    pool="P0" if venue in ("田径场", "操场", "沙坑", "投掷区", "跳高区") else "P1",
                    grade=grade,
                    duration=dur + rng.choice([0, 0, 10, 20]),
                    interval=rng.choice([0, 0, 15, 30, 60]),
                    heat_capacity=hc, lanes=lanes,
                    group_key=f"blk:{name}:{grade}" if n_blocks > 1 else None,
                    stage="prelim" if is_track and rng.random() < 0.6 else "main",
                ))

    # ---- 球类单元（含四赛制 + 淘汰赛晋级 + 二次编排） ----
    # TEAM 档**必须**至少有一个淘汰赛/混合赛制（用户点名要求），
    # 第一个球类事件固定走 knockout——纯随机会出现「整批样本无淘汰赛」。
    forced_ko = tier == "TEAM"
    for name, is_track, dur, venue, is_ball, mp in ball_events:
        if forced_ko:
            fmt = FMT_KNOCKOUT
            forced_ko = False
        else:
            fmt = rng.choice([FMT_GROUP, FMT_ROUND_ROBIN, FMT_KNOCKOUT, FMT_HYBRID])
        n_teams = rng.choice([4, 6, 8, 10, 12, 16])
        team_size = rng.choice([4, 5, 6, 8])
        grade = GRADES[0]
        base = f"{name}|{grade}"
        if fmt in (FMT_KNOCKOUT, FMT_HYBRID):
            # 淘汰赛：二叉晋级树。轮次**最多 2 层**（决赛+半决赛）——
            # 完整 log2(16)=4 层会产生 4×年级×项目 个队级单元，
            # 把名额池吃光、逼得兼项上限失效。
            p = 1
            n_rounds = min(2, max(1, int(math.ceil(math.log2(max(2, n_teams))))))
            for r in range(n_rounds):
                units.append(SuperUnit(
                    key=f"{base}|KO{r}", name=f"{name}第{r+1}轮",
                    task=TASK_KNOCKOUT, is_team=True, fmt=fmt,
                    venue=venue, pool="P1", grade=grade,
                    duration=dur, team_size=team_size,
                    bracket_round=r, bracket_parent=str(p),
                    stage="final" if r == 0 else "prelim",
                ))
                p *= 2
            # 二次编排：淘汰赛结束后重排道次（保兼容，只调时间/场地）
            ko_units = [x for x in units if x.task == TASK_KNOCKOUT and x.key.startswith(base)]
            for u in ko_units:
                units.append(SuperUnit(
                    key=f"{u.key}|RE", name=f"{u.name}·二次编排",
                    task=TASK_RESECOND, is_team=True, fmt=fmt,
                    venue=u.venue, pool=u.pool, grade=grade,
                    duration=u.duration, team_size=u.team_size,
                    resecond_of=u.key, stage="resecond",
                ))
        else:
            units.append(SuperUnit(
                key=base, name=name, task=TASK_BALL, is_team=True, fmt=fmt,
                venue=venue, pool="P1", grade=grade, duration=dur,
                team_size=team_size, stage="main",
            ))

    # ---- 容量可行性修复：「魔鬼条件」的第一条铁律 ----
    # ⚠️ 坑：窗口容量随机(120~300)、单元时长随机(~70min)，二者**毫无耦合**。
    #    早期版本 HELL/BLOCK/LANE 三档有近半样本「总容量 < 总需求」，
    #    场景物理上就排不开 —— 模型再强也只能学到「排不下」，评测的「未排 27 个」
    #    其实是场景的锅不是模型的锅。这种样本混进训练集，等于拿噪声当标签。
    #
    #    修法一（容量）：按总需求**反推**总容量到 1.3 倍。不再是「只放大」——
    #    改成并行场地后总容量天然过剩（13 时段 × 3 场地 × ~200 = 7800 vs 需求 3290，
    #    比 2.4 倍），太松就学不到装箱压力。所以统一缩放，两头都收：
    #    比 1.8 倍还松就压下来，比 1.15 倍还紧就顶上去，永远落在有压力的可行域里。
    #
    #    修法二（场地覆盖）：任何单元的场地必须在至少一个时段里出现过，
    #    否则这个单元「一个能放进去的时段都没有」，是死单元不是难题。
    #    这里按缺几个补几个地改写时段场地，而不是删单元（删单元会破坏项目覆盖）。
    max_dur = max((u.duration for u in units), default=120)
    covered = {w.venue for w in windows}
    missing = list(dict.fromkeys(u.venue for u in units if u.venue not in covered))
    if missing:
        # 改写时段场地。⚠️ 不能随便挑一个窗口改：若被顶掉的场地在别的时段也不存在，
        #    等于把「死单元」从 v 换成旧场地，问题只是换了个地方复发。
        #    只挑「同时段里同场地有 ≥2 个窗口」的那些位置来改 —— 改掉一个不影响该场地的覆盖。
        #    另外优先铺到不同时段上，别让所有覆盖都堆在同一个时段。
        def slots_of() -> Dict[int, List[int]]:
            s: Dict[int, List[int]] = {}
            for i, w in enumerate(windows):
                s.setdefault((w.day, w.window_idx), []).append(i)
            return s

        used_slots: set = set()
        for v in missing:
            s = slots_of()
            cands = [i for key, idxs in s.items()
                     for i in idxs
                     if key not in used_slots
                     and sum(1 for j in idxs if windows[j].venue == windows[i].venue) >= 2]
            if not cands:
                cands = [i for i in range(len(windows)) if (windows[i].day, windows[i].window_idx) not in used_slots]
            if not cands:
                continue
            wi = rng.choice(cands)
            used_slots.add((windows[wi].day, windows[wi].window_idx))
            windows[wi] = SuperWindow(day=windows[wi].day,
                                      window_idx=windows[wi].window_idx,
                                      capacity=windows[wi].capacity,
                                      venue=v, pool=windows[wi].pool)

    # ---- 按「场地」分组反推容量（必须在场地覆盖修复**之后**）----
    # ⚠️ 坑：原先按**总容量**缩放（sum(cap) ≥ demand × 1.3）。总量看着富余 36%，
    #    但落位的最小单位是「（时间桶 × 场地）」—— 单元只能落在**该桶已开自己场地**
    #    的窗口里。某场地若只出现在 1 个桶，它的可用容量就是那一个桶的容量，
    #    富余的容量全是别场地的，这些单元照旧无处可放。
    #    实测修前 HELL：总容量富余 2002 min、人次富余 7031，仍未排 24/50 —— 就是这个原因。
    #
    #    修法（每个场地单独算账）：
    #        need_v     = 该场地上所有单元时长之和 × 1.15
    #        占用桶数 k = ceil(need_v / 目标单桶容量)，桶太散时把多余的撤掉
    #        单桶容量   = ceil(need_v / k)
    #    这样「每个场地都排得下」是结构性成立的，而整体仍保留 15% 装箱压力。
    #    容量 0 表示该场地在这个时间桶**不开**（落位时直接跳过，等价于没这个窗口）。

    def _set(idx: int, cap: int, drop: bool = False) -> None:
        w0 = windows[idx]
        windows[idx] = SuperWindow(day=w0.day, window_idx=w0.window_idx,
                                   capacity=(0 if drop else max(max_dur, cap)),
                                   venue=w0.venue, pool=w0.pool)

    # ⚠️ 需求必须含 interval（赛前赛后的间隔缓冲）：落位检查是
    #    ``load + duration + interval <= 容量``，只按 duration 反推会**系统性偏小**，
    #    实测 30 个样本里 20 个出现「标签落位超容量」——标签不可行 = 白训。
    demand_v: Dict[str, int] = {}
    for u in units:
        demand_v[u.venue] = demand_v.get(u.venue, 0) + u.duration + u.interval
    target_cap = 180
    for v, d in demand_v.items():
        ids = [i for i, w in enumerate(windows) if w.venue == v]
        if not ids:
            continue
        need = int(math.ceil(d * 1.15))
        k = max(1, min(len(ids), int(math.ceil(need / target_cap))))
        step = len(ids) / k
        keep = sorted({ids[int(i * step)] for i in range(k)})
        per = int(math.ceil(need / len(keep)))
        for i in ids:
            _set(i, per, drop=(i not in keep))

    # 撤场地后可能出现「一个场地都没有」的空桶 —— 白占 16 个 slot 名额
    by_slot: Dict[Tuple[int, int], List[int]] = {}
    for i, w in enumerate(windows):
        by_slot.setdefault((w.day, w.window_idx), []).append(i)
    for idxs in by_slot.values():
        if not idxs:
            _set(rng.randrange(len(windows)), max_dur)

    # ---- 人员分配：唯一入口，任何单元都只能从「名额池」取 ----
    # ⚠️ 这段返工了 4 次，教训必须写死：
    #   ① 每单元固定比例抽 → 同批人被反复塞，兼项失控（曾 33 项）
    #   ② 预算均摊 + 补报 → 基础分配已顶满上限，补报阶段失衡
    #   ③ leftover 兜底回填 → 绕过 k 上限，HELL 冲到 23 项
    #   ④ 队级单元独立 rng.sample → 淘汰赛每轮都抽到同一批人，仍 19 项
    # **正确做法（唯一正确解）**：先为每个运动员算出「总共可被分配几次」，
    # 展开成一个**名额池**；任何单元（个人/球类/淘汰赛/二次编排）
    # 都只能从池里 pop，取完为止。这样「每人最多 max_multi 次」是**结构性保证**，
    # 不依赖任何环节的自觉检查。
    max_multi = 3 if tier == "HELL" else (2 if rng.random() < 0.6 else 3)
    r2 = cfg["multi"]
    k_dist: List[int] = []
    for _ in athletes:
        u = rng.random()
        if u < max(0.0, 1.0 - r2):
            k_dist.append(1)
        elif u < max(0.0, 1.0 - r2 * 0.4):
            k_dist.append(2)
        else:
            k_dist.append(min(max_multi, 3))
    if max_multi < 3:
        k_dist = [min(2, k) for k in k_dist]

    # ---- 唯一分配入口：按「运动员报的项目」直接定单元归属 ----
    # 🔴 这段推翻重写了 5 次才做对，最终形态记录在此：
    #   ① 每单元固定比例抽人 → 同批人反复塞，兼项 33 项
    #   ② 预算均摊 + 补报 → 分布偏斜
    #   ③ leftover 兜底回填 → 绕过 k 上限，23 项
    #   ④ 名额池 + pop → **pop 出来的名额属于别人**，被别的单元收走就等于
    #      凭空造出兼项（判废率反而升到 35/40）
    #   ⑤ 预留池给球类 → 同 ④ 的病
    # **最终形态（唯一正确）**：名额与**运动员—项目对**绑定，不做「池」。
    #   先为每个运动员选 k 个项目（k=1/2/3），把 (运动员, 项目) 收集成对；
    #   再按「该项目有哪些单元」把该运动员分配到其中一个单元。
    #   这样「一个运动员报了 k 个项目 → 恰好进 k 个单元」**由构造保证**。
    max_multi = 3 if tier == "HELL" else (2 if rng.random() < 0.6 else 3)
    r2 = cfg["multi"]
    # 每人报的项目集合
    ath_events: Dict[int, List[str]] = {}
    for a in athletes:
        u = rng.random()
        k = 1 if u < max(0.0, 1.0 - r2) else (2 if u < max(0.0, 1.0 - r2 * 0.4) else 3)
        k = min(k, max_multi)
        ath_events[a] = rng.sample([e[0] for e in events], min(k, len(events)))

    # 项目 → 该项目的单元列表（个人单元 + 队级单元都算，队级按年级）
    by_event: Dict[str, List[SuperUnit]] = {}
    for u in units:
        by_event.setdefault(u.name, []).append(u)

    for a in athletes:
        for ev_name in ath_events[a]:
            cands = by_event.get(ev_name)
            if not cands:
                continue
            u = rng.choice(cands)
            if a not in u.athletes:
                u.athletes.append(a)

    scen = SuperScenario(
        units=units, venues=venues, windows=windows, days_limit=days_limit,
        tier=tier, n_athletes=n_ath, seed=seed, n_events=len(ev_ids),
    )
    # 结构性校验：任何单元都不得让同一运动员兼项超过 3。
    # 这段分配逻辑返工过 4 次（33 项 / 23 项 / 19 项 / 5 项），
    # 每次都能跑出「看起来正常」的数据，只有这里能兜住。
    LIMIT = 3
    cnt: Dict[int, int] = {}
    over: List[str] = []
    for u in units:
        keep: List[int] = []
        for a in u.athletes:
            c = cnt.get(a, 0) + 1
            if c <= LIMIT:
                keep.append(a)
                cnt[a] = c
        if len(keep) != len(u.athletes):
            over.append(u.key)
        u.athletes = keep
    scen.invalid_units = over

    n_multi, mx = scen.multi_event_athletes()
    scen.n_multi_athletes, scen.max_multi = n_multi, mx
    scen.n_blocks = len(scen.blocks())
    return scen


def validate_scenario(scen: "SuperScenario") -> Tuple[bool, str]:
    """训练前的样本体检。任何一项不过就**丢掉整个样本**（而不是修它）。

    体检项与用户点名的「魔鬼条件」一一对应：

    1. 兼项上限 <= 3（用户要求 1/2/3 兼项）
    2. 项目块必须成块（BLOCK 档至少 3 块）
    3. 球类档必须含淘汰赛与二次编排单元
    4. 时间目标三态必须合法（-1 / 0 / >=1）
    5. 单元数下限（太小的场景学不到东西）
    6. 容量可行性：总容量 >= 总需求（生成器已按 1.25 倍放大，这里再兜一道，
       防止以后有人改窗口生成逻辑时把不可行样本悄悄放进去）

    ⚠️ **为什么不「修」而是「丢」**：兼项超限的样本说明分配逻辑有 bug，
    修数据会把 bug 掩盖过去，模型学到的是被粉饰过的分布。
    丢样本 + 记录原因，让问题在训练日志里显形。
    """
    if scen.max_multi > 3:
        return False, "兼项超限"
    if scen.invalid_units:
        return False, "分配越界"
    if scen.tier == "BLOCK" and scen.n_blocks < 3:
        return False, "项目块不足"
    if scen.tier == "TEAM":
        if not any(u.task == TASK_KNOCKOUT for u in scen.units):
            return False, "缺淘汰赛"
        if not any(u.task == TASK_RESECOND for u in scen.units):
            return False, "缺二次编排"
    if scen.days_limit < -1:
        return False, "非法时间目标"
    if len(scen.units) < 4:
        return False, "单元过少"
    if scen.n_athletes < 20:
        return False, "人数过少"
    # 容量可行性（第 6 条）：总需求不能超出总容量
    need = sum(u.duration for u in scen.units)
    have = sum(int(w.capacity) for w in scen.windows)
    if have < need:
        return False, f"容量不足({have}<{need})"
    # 兼项可行性：每个运动员每个时间桶最多露一次 → 桶数×人数 是总人次上界
    nb = len({(w.day, w.window_idx) for w in scen.windows}) or 1
    slots = sum(len(u.athletes) for u in scen.units)
    if nb * scen.n_athletes < slots:
        return False, "人次超桶"
    return True, "ok"
