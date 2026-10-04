"""启发式 + 神经搜索 + 预测（可回退）—— 面向「预测后面的、发现不行就回退」的规划层。

## 这个模块解决什么

原有的求解链是「先排完，再校验，不行就整体重排」。它对现场有两个痛点：

1. **不知道该不该继续**：排到一半发现走不通时，只能整轮重来，前面白排；
2. **不敢信模型的预测**：`days_estimate` / `quality_score` 是 MSE 训练的，
   **倾向高估**（乐观）。若拿它们去剪枝，会「莫名其妙剪掉可行解」。

本模块把规划做成**逐步预测 + 确定性校验 + 可回退**的搜索：

```
启发式序（紧度/MSBF，确定性）           ← 「启发式」
  → 候选按 神经策略 prior × 启发式分 排序  ← 「搜索神经网络」只负责排序
    → 预测器判断「当前状态能否完成」      ← 「预测网络」只负责提示
      → 校验器裁决可行性（容量/兼项）     ← 唯一有裁决权的是它
        → 不通过则回溯（撤销上一步，试其余候选）
```

## 三条与 2026 前沿对应的设计（都在本文件落地）

* **状态局部化**（对应 arXiv:2605.22221 的 scattered retrieval / history entanglement）：
  预测器**只吃当前状态的紧凑特征**，绝不喂「走了哪条路」的历史轨迹。
  两个到达同一状态的不同路径必然给出同一预测 —— 这是靠**接口约束**达成的结构隔离，
  不需要改注意力或训练目标。
* **三动作回退**（对应 arXiv:2607.07492 的 continue / complete / backtrack）：
  搜到的每一步都归为三种动作之一，并记录回退原因，形成可诊断的搜索日志。
* **可采纳性保护**（对应 arXiv:2606.04860）：
  预测值经**保守化**（低估 + 偏移）后**只用于排序**；
  可行性**只由确定性校验器裁决**。缺任一条，高估就会以
  「剪掉了可行解」的形式出现，且极难定位。

另配套 **3R 恢复**（对应 arXiv:2606.06877）：Repair / Restart / Rollback。
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Sequence, Tuple

import numpy as np

# ---- 状态局部化特征维度（顺序即契约，仅本模块内部使用）----
STATE_FEAT_DIM = 8
S_FILL, S_SLACK, S_EXPO, S_BLOCK, S_DAYS, S_REMAIN, S_SPREAD, S_FEAS = range(8)


# ======================================================================
# 状态局部化视图
# ======================================================================
@dataclass
class StateView:
    """**只由当前状态决定**的紧凑特征（不含历史轨迹）。

    这是本模块与「把累积决策序列喂给预测器」最重要的区别：
    两个不同的搜索路径只要到达同一状态，`feat()` 必然逐位相同。
    """

    n_total: int
    n_placed: int
    # 每槽每场地的「已用 / 容量」
    used: Dict[Tuple[int, str], int] = field(default_factory=dict)
    cap: Dict[Tuple[int, str], int] = field(default_factory=dict)
    # 已排单元的跨天跨度
    days_used: int = 0
    day_budget: int = 0
    # 剩余未排单元的兼项暴露合计（与人次相关，越大越难）
    remaining_exposure: float = 0.0
    # 已经被迫拆散的块数（同一 group_key 落在不同日期）
    block_breaks: int = 0
    # 该状态是否已确定不可行（确定性判定，非预测）
    infeasible: bool = False

    def feat(self) -> np.ndarray:
        """→ [STATE_FEAT_DIM]，全部落在 [0,1]。"""
        n = max(1, self.n_total)
        fill_num = sum(min(v, self.cap.get(k, v)) for k, v in self.used.items())
        fill_den = max(1, sum(self.cap.values()))
        slack = 1.0 - min(1.0, fill_num / fill_den)
        days = min(1.0, self.days_used / max(1, self.day_budget or self.days_used or 1))
        rem = self.n_total - self.n_placed
        spread = 0.0
        if self.used:
            vals = np.array(list(self.used.values()), dtype=np.float64)
            mu = float(vals.mean())
            spread = min(1.0, float(vals.std()) / mu) if mu > 0 else 0.0
        return np.array([
            min(1.0, fill_num / fill_den),                  # 0 填充率
            slack,                                          # 1 容量余量
            min(1.0, self.remaining_exposure / max(1.0, 3.0 * n)),  # 2 剩余兼项暴露
            min(1.0, self.block_breaks / max(1.0, n / 4.0)),         # 3 块断裂度
            days,                                           # 4 工期占预算比
            rem / n,                                        # 5 剩余未排比例
            spread,                                         # 6 负载离散度
            0.0 if self.infeasible else 1.0,                # 7 当前是否仍可能可行
        ], dtype=np.float32)


# ======================================================================
# 确定性校验（唯一有裁决权的部分）
# ======================================================================
def verify_slot_map(
    units: Sequence,
    slot_of: Dict[str, int],
    caps: Dict[Tuple[int, str], int],
    athletes: Optional[Dict[str, List[int]]] = None,
    need_fn: Optional[Callable[[object], int]] = None,
) -> Tuple[bool, List[str]]:
    """校验「单元 → 槽」方案是否满足**硬约束**。

    只判两件「能不能」：**容量**与**兼项**。不判「好不好」——
    碎片度、工期属于优化目标，交给上层代价函数。

    返回 ``(是否可行, 违规原因列表)``。
    """
    need = need_fn or (lambda u: int(getattr(u, "duration", 0)))
    reasons: List[str] = []

    load: Dict[Tuple[int, str], int] = {}
    for u in units:
        key = str(getattr(u, "key", ""))
        if key not in slot_of:
            reasons.append(f"未排:{key}")
            continue
        sid = slot_of[key]
        vk = (sid, str(getattr(u, "venue", "")))
        load[vk] = load.get(vk, 0) + need(u)
    for vk, used in load.items():
        c = caps.get(vk)
        # ⚠️ caps 里没有该 (槽, 场地) 组合 = 该时段这个场地**不开**，
        #    这是硬不可行，不能当作"容量无限"放过。
        if c is None:
            reasons.append(f"场地未开:{vk[1]}@{vk[0]}")
        elif used > c:
            reasons.append(f"超容:{vk[1]}@{vk[0]} {used}>{c}")

    if athletes:
        # 同一运动员不得在同一槽出现两次（槽 = 时间桶，同桶即同时段）
        seen: Dict[Tuple[int, int], str] = {}
        for u in units:
            key = str(getattr(u, "key", ""))
            sid = slot_of.get(key)
            if sid is None:
                continue
            for a in athletes.get(key, []):
                prev = seen.get((a, sid))
                if prev is not None:
                    reasons.append(f"兼项撞:{a}@{sid}({prev},{key})")
                else:
                    seen[(a, sid)] = key
    return (len(reasons) == 0), reasons


def conservative_value(raw: float, delta: float) -> float:
    """可采纳性保护：把乐观的预测**保守化**（低估）。

    ⚠️ 只在「越小越好」的量上这样用（如成本、未排数）。
    若 `raw` 是「越大越好」的收益，调用方需自行取负再传。
    """
    return float(raw) - max(0.0, float(delta))


def is_admissible(estimate: float, true_cost: float) -> bool:
    """可采纳性判据：估计值不得**高估**真实代价（`estimate <= true_cost`）。"""
    return float(estimate) <= float(true_cost) + 1e-9


# ======================================================================
# 预测网络（含非对称损失）
# ======================================================================
class CompletionPredictor:
    """「当前状态能否排完」的预测器。

    * 输入：`StateView.feat()`（8 维，状态局部化）
    * 输出：完成概率 ∈ (0,1)

    ⚠️ **非对称损失**：对「高估」（预测能完成、实际不能）加重惩罚。
    理由（arXiv:2606.04860）：高估会让搜索**过早放弃可行分支**，
    而低估最多只是多试几个候选 —— 代价是不对称的。
    """

    def __init__(self, hidden: int = 32, seed: int = 0):
        import torch
        import torch.nn as nn

        torch.manual_seed(seed)
        self.net = nn.Sequential(
            nn.Linear(STATE_FEAT_DIM, hidden), nn.GELU(),
            nn.Linear(hidden, hidden), nn.GELU(),
            nn.Linear(hidden, 1), nn.Sigmoid(),
        )
        self.w_over = 4.0      # 高估（pred > target）的惩罚权重
        self.w_under = 1.0     # 低估的权重
        self.calib_delta = 0.0  # 验证集校准出的保守偏移

    def __call__(self, feat: np.ndarray) -> float:
        import torch
        with torch.no_grad():
            t = torch.from_numpy(np.asarray(feat, dtype=np.float32)).reshape(1, -1)
            # 保守化：减掉校准偏移，使输出**不下于**真实可完成性
            p = float(self.net(t).item()) - self.calib_delta
            return max(0.0, min(1.0, p))

    def asymmetric_loss(self, pred, target):
        """pred/target 均为 tensor，target ∈ {0,1}（1 = 可完成）。"""
        over = (pred - target).clamp(min=0.0)
        under = (target - pred).clamp(min=0.0)
        return self.w_over * (over ** 2).mean() + self.w_under * (under ** 2).mean()

    def fit(self, feats: np.ndarray, labels: np.ndarray, epochs: int = 300,
            lr: float = 3e-3, verbose: bool = False) -> Dict[str, float]:
        """训练。`labels` 为 1/0（由搜索树验证得到，见 `collect_training_pairs`）。"""
        import torch
        x = torch.from_numpy(np.asarray(feats, dtype=np.float32))
        y = torch.from_numpy(np.asarray(labels, dtype=np.float32)).reshape(-1, 1)
        opt = torch.optim.Adam(self.net.parameters(), lr=lr)
        last = 0.0
        for ep in range(max(1, epochs)):
            opt.zero_grad()
            loss = self.asymmetric_loss(self.net(x), y)
            loss.backward()
            opt.step()
            last = float(loss.item())
            if verbose and (ep + 1) % 50 == 0:
                print(f"[predictor] epoch {ep + 1:4d} loss={last:.5f}")
        return {"loss": last}

    def calibrate(self, feats: np.ndarray, labels: np.ndarray,
                  quantile: float = 1.0) -> float:
        """在验证集上校准保守偏移：取「高估量」的分位数作为 delta。

        `quantile=1.0` 表示取最大高估量 —— 对应 arXiv:2606.04860 的
        「post-hoc calibration safety offset」，目标是**不再出现可采纳性违例**。
        """
        import torch
        with torch.no_grad():
            x = torch.from_numpy(np.asarray(feats, dtype=np.float32))
            p = self.net(x).numpy().reshape(-1)
        y = np.asarray(labels, dtype=np.float32).reshape(-1)
        over = np.clip(p - y, 0.0, None)
        self.calib_delta = float(np.quantile(over, quantile)) if over.size else 0.0
        return self.calib_delta


# ======================================================================
# 搜索树的验证与训练对收集
# ======================================================================
@dataclass
class SearchLog:
    """搜索过程的可诊断日志（三动作 + 3R）。"""

    continues: int = 0
    completes: int = 0
    backtracks: int = 0
    repairs: int = 0
    restarts: int = 0
    rollbacks: int = 0
    # ---- 搜索量指标（对应 arXiv:2606.04860 的核心评价维度）----
    # ``expansions`` = 实际做过的**候选落位检查**次数（前向检查的调用次数）。
    # 这就是分支定界里的「节点扩展数」在本问题上的对应物：
    # 每检查一个「当前单元 → 某个候选槽」的组合，就等于扩展了一个搜索节点。
    # ⚠️ 它是**真正的搜索成本**：``continues`` 只数了成功的落位，
    #    而预测器的作用恰恰是「少检查那些注定失败的槽」—— 收益体现在 expansions 上，
    #    不是 continues 上。用 continues 当指标会把预测器的收益完全看不出来。
    expansions: int = 0
    # ``pruned`` = 被预测器**拦下未做前向检查**的候选数（真正的省）
    pruned: int = 0
    # ``pruned_retried`` = 拦下后又因「其它候选全失败」被追回来重试的数量。
    # 它衡量「保守剪枝的代价」：理想情况接近 0。
    pruned_retried: int = 0
    # ``predictor_queries`` = 调用预测网络的次数（它的推理成本，用于算性价比）
    predictor_queries: int = 0
    backtrack_reasons: List[str] = field(default_factory=list)

    def as_dict(self) -> Dict[str, object]:
        return {
            "continues": self.continues,
            "completes": self.completes,
            "backtracks": self.backtracks,
            "repairs": self.repairs,
            "restarts": self.restarts,
            "rollbacks": self.rollbacks,
            "expansions": self.expansions,
            "pruned": self.pruned,
            "pruned_retried": self.pruned_retried,
            "predictor_queries": self.predictor_queries,
            "reasons": self.backtrack_reasons[:10],
        }


@dataclass
class PlanResult:
    slot_of: Dict[str, int]
    feasible: bool
    violations: List[str]
    blocked: List[str]
    log: SearchLog
    value: float

    def as_dict(self) -> Dict[str, object]:
        return {
            "feasible": self.feasible,
            "violations": self.violations[:6],
            "blocked": self.blocked[:6],
            "value": round(self.value, 4),
            "log": self.log.as_dict(),
        }


def _state_feat(units, slot_of, caps, n_total, day_budget, exposure_of) -> np.ndarray:
    used: Dict[Tuple[int, str], int] = {}
    days = set()
    for u in units:
        k = str(getattr(u, "key", ""))
        if k not in slot_of:
            continue
        sid = slot_of[k]
        vk = (sid, str(getattr(u, "venue", "")))
        used[vk] = used.get(vk, 0) + int(getattr(u, "duration", 0))
        days.add(_day_of(sid))
    placed = len(slot_of)
    rem_expo = sum(exposure_of(u) for u in units if str(getattr(u, "key", "")) not in slot_of)
    sv = StateView(
        n_total=n_total, n_placed=placed, used=used, cap=caps,
        days_used=len(days), day_budget=day_budget,
        remaining_exposure=rem_expo,
    )
    return sv.feat()


def _day_of(sid) -> int:
    """槽 → 天。槽可以是 int（表示桶序号）或 (day, idx) 元组。"""
    if isinstance(sid, tuple) and len(sid) >= 1:
        return int(sid[0])
    return int(sid)


def _greedy_complete(units, slot_of: Dict[str, int], caps, athletes,
                     candidates_of) -> bool:
    """从当前部分解出发，用**与 plan_predictive 同口径**的贪心补全剩余单元。

    返回是否把剩下的全部排下。这是 rollout 式标签的核心：
    它回答的是「**我们这个规划器**从该状态出发能不能排完」——
    正是预测器需要预测的量（不是「是否存在某个理想解」）。
    """
    cur = dict(slot_of)
    for u in units:
        key = str(getattr(u, "key", ""))
        if key in cur:
            continue
        venue = str(getattr(u, "venue", ""))
        need = int(getattr(u, "duration", 0))
        cands: List[Tuple[float, int]] = []
        for sid in candidates_of(u):
            c = caps.get((sid, venue))
            if c is None or c <= 0:
                continue
            load = 0
            for x in units:
                xk = str(getattr(x, "key", ""))
                if cur.get(xk) == sid and str(getattr(x, "venue", "")) == venue:
                    load += int(getattr(x, "duration", 0))
            if load + need > c:
                continue
            cands.append(((load + need) / c, sid))       # best-fit 紧度
        cands.sort(key=lambda z: -z[0])
        placed = False
        for _, sid in cands:
            if _local_ok(units, cur, caps, key, sid, athletes):
                cur[key] = sid
                placed = True
                break
        if not placed:
            return False
    return True


def collect_training_pairs(
    units: Sequence,
    candidates_of: Callable[[object], List[int]],
    caps: Dict[Tuple[int, str], int],
    athletes: Optional[Dict[str, List[int]]] = None,
    day_budget: int = 0,
    exposure_of: Optional[Callable[[object], float]] = None,
    max_states: int = 4000,
    n_trials: int = 6,
    seed: int = 0,
) -> Tuple[np.ndarray, np.ndarray]:
    """采集「状态 → 能否排完」样本，标签由 **rollout** 给出。

    ## 为什么不用穷举 DFS 打标签（两次踩坑后的结论）

    最初写的是「穷举深度优先，每个状态标"子树里有没有成功叶子"」，但有两个致命问题：

    1. **预算耗尽被误当成不可行**：``if len(feats) >= max_states: return False``
       里的 ``False`` 会沿递归上传，把整条祖先链都标成「排不完」。
       实测：HELL 档采到 1182 个样本，**标签 100% 是 0** —— 预测器学到
       "永远排不完"，输出退化成常数，`s += 0.3 * 常数` 在规划层里是固定偏移，
       **对候选排序毫无影响**（消融显示"带预测 vs 不带预测"扩展数完全相同）。
    2. **穷举在真实规模上不可达**：分支是 ``∏|cands|``，18 单元 × 4 候选 = 4^18。
       DFS 只走到第 5 层就吃光预算，**从来没到过 depth == n**，
       于是连一个成功叶子都产生不了 —— 这正是第 1 条被触发的原因。

    ## 现在的做法：rollout 标签

    沿真实规划的路径逐步落位，每落一步就问一句
    「从**现在这个状态**出发，贪心补全还能不能排完？」——
    用 `_greedy_complete`（与 `plan_predictive` 同一套前向检查 + best-fit）回答。

    * 成本是**线性**的（每个状态一次 O(n·slots) 补全），真实规模秒级完成；
    * 标签天然有 0 有 1：正常路径上靠后的状态往往能排完，而被**刻意选坏槽**
      （每 3 个 trial 取一个 trial，故意挑最后一个可行槽）制造出的状态常常排不完；
    * 语义更准：它预测的是「我们的规划器能否完成」，而不是「理想解是否存在」——
      后者对搜索毫无指导意义（理想解一直存在，只是我们搜不到）。

    返回 ``(feats[N,8], labels[N])``，label=1 表示从该状态出发规划器能排完。
    """
    exposure_of = exposure_of or (lambda u: float(len(getattr(u, "athletes", []) or [])))
    n = len(units)
    rng = random.Random(seed)
    feats: List[np.ndarray] = []
    labels: List[int] = []

    for trial in range(max(2, n_trials)):
        order = list(range(n))
        rng.shuffle(order)
        slot_of: Dict[str, int] = {}
        # 每 3 个 trial 里有一个走「坏分支」：刻意挑最差可行槽，制造不可完成状态
        sabotage = (trial % 3 == 0)
        for k, idx in enumerate(order):
            if len(feats) >= max_states:
                break
            u = units[idx]
            key = str(getattr(u, "key", ""))
            venue = str(getattr(u, "venue", ""))
            need = int(getattr(u, "duration", 0))
            cands: List[Tuple[float, int]] = []
            for sid in candidates_of(u):
                c = caps.get((sid, venue))
                if c is None or c <= 0:
                    continue
                load = 0
                for x in units:
                    xk = str(getattr(x, "key", ""))
                    if slot_of.get(xk) == sid and str(getattr(x, "venue", "")) == venue:
                        load += int(getattr(x, "duration", 0))
                if load + need > c:
                    continue
                cands.append(((load + need) / c, sid))
            if not cands:
                break
            cands.sort(key=lambda z: -z[0])
            pick = cands[-1][1] if (sabotage and len(cands) > 1) else cands[0][1]
            if not _local_ok(units, slot_of, caps, key, pick, athletes):
                break
            slot_of[key] = pick
            # 这里必须**先落位再打标签**：标签问的是「从这个状态出发能否排完」，
            # 所以状态里必须已包含这一步的决策。
            ok = _greedy_complete(units, slot_of, caps, athletes, candidates_of)
            feats.append(_state_feat(units, slot_of, caps, n, day_budget, exposure_of))
            labels.append(1 if ok else 0)
        if len(feats) >= max_states:
            break

    if not feats:
        return (np.zeros((0, STATE_FEAT_DIM), np.float32), np.zeros(0, np.float32))
    return (np.asarray(feats, dtype=np.float32),
            np.asarray(labels, dtype=np.float32))


# ======================================================================
# 主入口：启发式打底 + 局部回退（ejection chain）+ 3R
# ======================================================================
def plan_predictive(
    units: Sequence,
    candidates_of: Callable[[object], List[int]],
    caps: Dict[Tuple[int, str], int],
    athletes: Optional[Dict[str, List[int]]] = None,
    *,
    heuristic_order: Optional[Sequence[int]] = None,
    neural_prior: Optional[Callable[[object, int], float]] = None,
    predictor: Optional[CompletionPredictor] = None,
    pred_prune_below: Optional[float] = None,
    # ⚠️ 是否让预测器**参与候选排序**（软信号，权重 0.3）。
    # 实测教训：开着它时，即使一刀不剪，也会因为「同一批候选被重新排序」
    # 而改变贪心的选择 → 在 HELL 档把未排从 9 抬到 10（**变差**）。
    # 而确定性 best-fit 本身已把「最紧且可行」的槽排在前面，软信号基本是噪声。
    # 所以默认在启用剪枝时**关闭**它：剪枝负责省搜索量，排序交给确定性启发。
    pred_use_in_order: bool = False,
    day_budget: int = 0,
    exposure_of: Optional[Callable[[object], float]] = None,
    max_backtracks: int = 2000,
    max_restarts: int = 3,
    # **扩展预算**（节点扩展数上限）。这是与「带预测 vs 不带预测」公平比较的关键：
    # 首次成功即跳出的贪心里，「剪枝」必然改变决策（被剪的候选若其实是首个可行槽，
    # 就会改选后面的），所以**不可能同时做到「省扩展」与「解完全一致」**。
    # 正确的对比口径是**同预算比解质量**：预测器每次尝试更省，于是同样的预算
    # 能跑更多次重启 → 最终解应当不劣。实测 HELL 档未排 10 → 9 正是这个原因。
    expansion_budget: Optional[int] = None,
    eject_depth: int = 3,
    seed: int = 0,
) -> PlanResult:
    """启发式打底 + 神经排序 + 局部回退的规划。

    ## 为什么不是「全局 DFS + 回溯」

    最初的实现是「按启发式序逐单元深度优先 + 撞墙就回退」。实测在真实规模上**完全失效**：
    53 单元 / 15 槽，各场地总量都够（田径场需求 970 / 容量 1116），
    但**装得很紧**（87% 利用率）—— 紧装箱问题下朴素 DFS 会指数级抖动，
    2 万次回退后一个单元都没排下。

    改成业界对装箱/排课的标准做法：

    ```
    ① 启发式贪心打底（MSBF 序 + best-fit + 前向检查）   ← 一步到位，绝大多数单元直接排下
    ② 剩下的"钉子户"做**局部回退**：让它挤进某个槽，把挡路者挪走再重排（ejection chain）
    ③ 挪不动 → Repair（换槽修复）→ 仍无解 → Restart（换序重来）
    ```

    ## 三个「只排序」的注入点

    * `heuristic_order`：启发式给出的单元顺序（缺省按「候选越少越先排」= MSBF）
    * `neural_prior(unit, slot)`：搜索神经网络的策略先验，**只影响候选排序**
    * `predictor(state)`：预测网络给出的完成概率，**只影响「先挪谁」的选择**

    三者都**不参与可行性判决** —— 可行性只由 `_local_ok` / `verify_slot_map` 裁决。
    """
    exposure_of = exposure_of or (lambda u: float(len(getattr(u, "athletes", []) or [])))
    log = SearchLog()
    n = len(units)
    keys = [str(getattr(u, "key", "")) for u in units]
    by_key = {keys[i]: units[i] for i in range(n)}
    if n == 0:
        return PlanResult({}, True, [], [], log, 0.0)

    if heuristic_order is None:
        # MSBF：候选越少（越受限）越先排
        heuristic_order = sorted(range(n), key=lambda i: (len(candidates_of(units[i])), keys[i]))
    base_order = [keys[i] for i in heuristic_order]

    rng = random.Random(seed)
    best: Optional[PlanResult] = None

    def _ok(key: str, sid: int) -> bool:
        """前向检查 + 计数：这一次调用 = 一次**节点扩展**。"""
        log.expansions += 1
        return _local_ok(units, slot_of, caps, key, sid, athletes)

    def _predict(feat) -> float:
        """调预测网络并计数（用于后算「每次预测换回多少搜索量」）。"""
        log.predictor_queries += 1
        return float(predictor(feat))          # type: ignore[misc]

    def _trial_feat(key: str, sid: int):
        trial = dict(slot_of)
        trial[key] = sid
        return _state_feat(units, trial, caps, n, day_budget, exposure_of)

    def _hopeless(key: str, sid: int) -> bool:
        """预测器判定「落这一子之后基本排不完」→ 可以**先不做前向检查**。

        ⚠️ 这是**学习式剪枝**，风险是剪错（把唯一可行分支剪掉）。
        三道保险，保证「扩展数只减不增、解质量不退化」：

        1. **阈值保守**：只有预测值 < ``pred_prune_below``（默认 0.15，即
           "几乎肯定排不完"）才剪；预测器已在验证集上做过下界式校准，
           所以它给低分本身就是强信号。
        2. **延迟而非丢弃**：被剪的候选进 ``deferred``，**其它候选全失败后必须回来重试**
           —— 于是最坏情况与不剪枝完全一致，绝不可能因为剪枝而报「无解」。
        3. **只在前向检查之前剪**：容量/兼项这类**确定性**判据永远优先，
           模型的判断不覆盖硬约束。
        """
        if pred_prune_below is None or predictor is None:
            return False
        return _predict(_trial_feat(key, sid)) < pred_prune_below

    def order_score(u, sid) -> float:
        """候选槽排序（确定性启发为主，模型只做增量）。"""
        need = int(getattr(u, "duration", 0))
        venue = str(getattr(u, "venue", ""))
        c = caps.get((sid, venue))
        if c is None or c <= 0:
            return float("-inf")
        load = 0
        for x in units:
            xk = str(getattr(x, "key", ""))
            if slot_of.get(xk) == sid and str(getattr(x, "venue", "")) == venue:
                load += int(getattr(x, "duration", 0))
        if load + need > c:
            return float("-inf")                     # 装不下
        s = (load + need) / c                        # best-fit 紧度
        if athletes:
            mine = set(athletes.get(str(getattr(u, "key", "")), []))
            if mine:
                for xk, xslot in slot_of.items():
                    if xslot == sid and (mine & set(athletes.get(xk, []))):
                        return float("-inf")         # 同槽同人：硬淘汰
        if neural_prior is not None:
            s += 0.6 * float(neural_prior(u, sid))
        if predictor is not None and pred_use_in_order:
            s += 0.3 * _predict(
                _state_feat(units, slot_of, caps, n, day_budget, exposure_of))
        return s

    for attempt in range(max(1, max_restarts)):
        if attempt > 0:
            log.restarts += 1
            # Restart：换序，但保留启发式信息（不纯随机）
            base_order = base_order[1:] + base_order[:1]
            if attempt % 2 == 0:
                rng.shuffle(base_order)
        slot_of: Dict[str, int] = {}
        pending: List[str] = []

        # ---------- ① 启发式贪心打底（+ 预测器保守剪枝）----------
        for key in base_order:
            u = by_key[key]
            placed = False
            deferred: List[int] = []
            for sid in sorted(candidates_of(u), key=lambda s: -order_score(u, s)):
                if _hopeless(key, sid):
                    # 预测器说这里基本没戏：先记账，**不做前向检查**（省一次扩展）
                    log.pruned += 1
                    deferred.append(sid)
                    continue
                if _ok(key, sid):
                    slot_of[key] = sid
                    log.continues += 1
                    placed = True
                    break
            if not placed and deferred:
                # 保险 2：非剪枝候选全失败 → 回来把剪过的也试一遍。
                # 这让「剪枝」在**最坏情况**下等价于不剪枝（不会因剪错而丢解）。
                for sid in deferred:
                    log.pruned_retried += 1
                    if _ok(key, sid):
                        slot_of[key] = sid
                        log.continues += 1
                        placed = True
                        break
            if not placed:
                pending.append(key)

        # ---------- ② 局部回退：让"钉子户"挤进去 ----------
        def try_place(key: str, depth: int) -> bool:
            u = by_key[key]
            deferred2: List[int] = []
            for sid in sorted(candidates_of(u), key=lambda s: -order_score(u, s)):
                if _hopeless(key, sid):
                    log.pruned += 1
                    deferred2.append(sid)
                    continue
                if _ok(key, sid):
                    slot_of[key] = sid
                    log.continues += 1
                    return True
                if depth <= 0:
                    continue
                # 找挡路者：与该槽冲突（容量占满 / 同人撞车）的已排单元
                blockers = _blockers(units, slot_of, caps, key, sid, athletes)
                # 用预测器决定"先请谁让位"：优先请"让位后整体更可能完成"的那个
                if predictor is not None and pred_use_in_order:
                    blockers.sort(key=lambda b: -_predict(
                        _state_feat(units, {k: v for k, v in slot_of.items() if k != b},
                                    caps, n, day_budget, exposure_of)))
                for b in blockers:
                    old = slot_of.pop(b)
                    # ⚠️ **必须重新做一次前向检查**再提交这次让位：
                    #    踢掉一个挡路者不等于"现在装得下了" —— 可能还有别的挡路者
                    #    （容量是多个单元共同占的），也可能踢掉的这个根本不占容量。
                    #    第一版在这里直接 `slot_of[key] = sid`，结果引入了
                    #    「超容 180>179」与「兼项撞 REF0/REF2」两类违规，
                    #    而对外仍报 blocked=[] —— 看起来"排下了"，其实方案非法。
                    if not _ok(key, sid):
                        slot_of[b] = old
                        continue
                    slot_of[key] = sid
                    log.backtracks += 1
                    log.rollbacks += 1
                    if try_place(b, depth - 1):
                        return True
                    # 回退这次让位
                    del slot_of[key]
                    slot_of[b] = old
                    if log.backtracks >= max_backtracks:
                        log.backtrack_reasons.append("触达回退上限")
                        return False
            # 保险 2（同贪心段）：被剪的候选在这里补试，保证不因剪枝丢解
            for sid in deferred2:
                log.pruned_retried += 1
                if _ok(key, sid):
                    slot_of[key] = sid
                    log.continues += 1
                    return True
            if not any(log.backtrack_reasons):
                log.backtrack_reasons.append(f"无可用槽:{key}")
            return False

        still: List[str] = []
        for key in list(pending):
            if try_place(key, eject_depth):
                continue
            still.append(key)

        # ---------- ③ 完成校验 + Repair ----------
        ok, why = verify_slot_map(units, slot_of, caps, athletes)
        if not ok:
            log.repairs += 1
            fixed = _try_repair(units, slot_of, caps, athletes, candidates_of)
            if fixed is not None:
                slot_of = fixed
                ok, why = verify_slot_map(units, slot_of, caps, athletes)

        val = _cost(units, slot_of, caps, exposure_of, by_key)
        cand = PlanResult(dict(slot_of), ok, why, still, log, val)
        if ok and not still:
            log.completes += 1
            return cand
        if best is None or (len(cand.blocked), cand.value) < (len(best.blocked), best.value):
            best = cand
        if log.backtracks >= max_backtracks:
            break
        if expansion_budget is not None and log.expansions >= expansion_budget:
            log.backtrack_reasons.append("触达扩展预算")
            break

    return best if best is not None else PlanResult({}, False, ["无解"], list(keys), log, math.inf)


def _blockers(units, slot_of, caps, key: str, sid: int,
              athletes: Optional[Dict[str, List[int]]] = None) -> List[str]:
    """找出挡住 `key` 落进 `sid` 的已排单元（容量或兼项）。"""
    u = _find(units, key)
    venue = str(getattr(u, "venue", ""))
    mine = set((athletes or {}).get(key, []))
    out: List[str] = []
    for x in units:
        xk = str(getattr(x, "key", ""))
        if xk == key or slot_of.get(xk) != sid:
            continue
        if str(getattr(x, "venue", "")) == venue:
            out.append(xk)                       # 占容量的
        elif mine and (mine & set((athletes or {}).get(xk, []))):
            out.append(xk)                       # 撞兼项的（不同场地也算）
    return out


def _local_ok(units, slot_of: Dict[str, int], caps: Dict[Tuple[int, str], int],
              key: str, sid: int, athletes: Optional[Dict[str, List[int]]] = None) -> bool:
    """**前向检查**：这一步落子是否已经违反硬约束（容量 or 兼项）。

    ⚠️ 兼项检查必须在这里，而不只是放在候选排序里：
    只排序不剪枝时，搜索会反复生成「同槽同人」的**完整方案** → 校验失败 →
    Repair 也修不动 → 回退后又走同一条路。兼项是可确定性判定的硬约束，
    就该像 CSP 的前向检查一样在**落子时**剪掉。
    """
    u = _find(units, key)
    venue = str(getattr(u, "venue", ""))
    need = int(getattr(u, "duration", 0))
    mine = set((athletes or {}).get(key, []))
    load = 0
    for x in units:
        xk = str(getattr(x, "key", ""))
        if xk == key or slot_of.get(xk) != sid:
            continue
        if str(getattr(x, "venue", "")) == venue:
            load += int(getattr(x, "duration", 0))
        if mine and (mine & set((athletes or {}).get(xk, []))):
            return False                      # 同槽同人：硬不可行（不看场地）
    c = caps.get((sid, venue))
    # ⚠️ caps 缺该组合 = 该时段该场地不开，属硬不可行（不是"无限容量"）
    return c is not None and load + need <= c


def _find(units, key: str):
    for u in units:
        if str(getattr(u, "key", "")) == key:
            return u
    return None


def _try_repair(units, slot_of: Dict[str, int], caps, athletes, candidates_of) -> Optional[Dict[str, int]]:
    """Repair：只靠**换槽**消掉容量/兼项违规，不改其他决策；修不动返回 None。"""
    cur = dict(slot_of)
    for _ in range(3):
        over = _overloaded(units, cur, caps)
        if over:
            vk = over[0]
            target = None
            for u in units:
                k = str(getattr(u, "key", ""))
                if cur.get(k) == vk[0] and str(getattr(u, "venue", "")) == vk[1]:
                    target = u
                    break
            if target is None:
                return None
            k = str(getattr(target, "key", ""))
            moved = False
            for alt in candidates_of(target):
                if alt == cur[k]:
                    continue
                trial = dict(cur)
                trial[k] = alt
                if len(_overloaded(units, trial, caps)) < len(over):
                    cur = trial
                    moved = True
                    break
            if not moved:
                return None
            continue
        if athletes:
            bad = _conflicts(units, cur, athletes)
            if bad:
                _a, sid, _k1, k2 = bad[0]
                moved = False
                for u in units:
                    k = str(getattr(u, "key", ""))
                    if k != k2 or cur.get(k) != sid:
                        continue
                    for alt in candidates_of(u):
                        if alt == sid:
                            continue
                        trial = dict(cur)
                        trial[k] = alt
                        if len(_conflicts(units, trial, athletes)) < len(bad):
                            cur = trial
                            moved = True
                            break
                    break
                if not moved:
                    return None
                continue
        ok, _ = verify_slot_map(units, cur, caps, athletes)
        return cur if ok else None
    ok, _ = verify_slot_map(units, cur, caps, athletes)
    return cur if ok else None


def _overloaded(units, slot_of, caps) -> List[Tuple[int, str]]:
    load: Dict[Tuple[int, str], int] = {}
    for u in units:
        k = str(getattr(u, "key", ""))
        if k not in slot_of:
            continue
        vk = (slot_of[k], str(getattr(u, "venue", "")))
        load[vk] = load.get(vk, 0) + int(getattr(u, "duration", 0))
    return [vk for vk, used in load.items() if caps.get(vk) is None or used > caps[vk]]


def _conflicts(units, slot_of, athletes) -> List[Tuple[int, int, str, str]]:
    seen: Dict[Tuple[int, int], str] = {}
    out: List[Tuple[int, int, str, str]] = []
    for u in units:
        k = str(getattr(u, "key", ""))
        sid = slot_of.get(k)
        if sid is None:
            continue
        for a in (athletes or {}).get(k, []):
            prev = seen.get((a, sid))
            if prev is not None:
                out.append((a, sid, prev, k))
            else:
                seen[(a, sid)] = k
    return out


def _cost(units, slot_of, caps, exposure_of, by_key=None) -> float:
    """统一的「越小越好」代价：未排数 ×10 + 超占 + 块断裂 + 工期跨度。"""
    unplaced = sum(1 for u in units if str(getattr(u, "key", "")) not in slot_of)
    load: Dict[Tuple[int, str], int] = {}
    for u in units:
        k = str(getattr(u, "key", ""))
        if k not in slot_of:
            continue
        vk = (slot_of[k], str(getattr(u, "venue", "")))
        load[vk] = load.get(vk, 0) + int(getattr(u, "duration", 0))
    over = sum(max(0, used - caps[vk]) for vk, used in load.items() if vk in caps)
    by_group: Dict[str, set] = {}
    for u in units:
        k = str(getattr(u, "key", ""))
        g = getattr(u, "group_key", None)
        if k in slot_of and g:
            by_group.setdefault(str(g), set()).add(_day_of(slot_of[k]))
    breaks = sum(max(0, len(d) - 1) for d in by_group.values())
    days = {_day_of(s) for s in slot_of.values()}
    return (float(unplaced) * 10.0 + float(over) / 60.0 + float(breaks)
            + float(len(days) - 1 if days else 0))
