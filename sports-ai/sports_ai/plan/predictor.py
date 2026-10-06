"""完成度预测器（「搜索神经网络」）—— 只负责排序，不负责裁决。

⚠️ 预测值必须先经**保守化**（低估 + 偏移）才允许参与排序：
乐观的预测一旦被当成可行性判据，就会静默剪掉可行解。
`is_admissible` 把这个前提写成了可断言的判据，而不是注释里的约定。
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Sequence, Tuple

import numpy as np

from ._common import (STATE_FEAT_DIM, S_FILL, S_SLACK, S_EXPO, S_BLOCK,
                     S_DAYS, S_REMAIN, S_SPREAD, S_FEAS)

from .state import StateView, _day_of
from .verify import _local_ok


def conservative_value(raw: float, delta: float) -> float:
    """可采纳性保护：把乐观的预测**保守化**（低估）。

    ⚠️ 只在「越小越好」的量上这样用（如成本、未排数）。
    若 `raw` 是「越大越好」的收益，调用方需自行取负再传。
    """
    return float(raw) - max(0.0, float(delta))


def is_admissible(estimate: float, true_cost: float) -> bool:
    """可采纳性判据：估计值不得**高估**真实代价（`estimate <= true_cost`）。"""
    return float(estimate) <= float(true_cost) + 1e-9


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
