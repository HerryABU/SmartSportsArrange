"""状态局部化视图：把「当前排布」压成一份紧凑、无历史的特征表示。

对应 arXiv:2605.22221 的 scattered retrieval / history entanglement ——
*只吃当前状态*，绝不喂「走了哪条路」，于是两个到达同一状态的不同路径
必然给出同一预测。这是靠**接口约束**达成的结构隔离。
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Sequence, Tuple

import numpy as np

from ._common import (STATE_FEAT_DIM, S_FILL, S_SLACK, S_EXPO, S_BLOCK,
                     S_DAYS, S_REMAIN, S_SPREAD, S_FEAS)


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


def _day_of(sid) -> int:
    """槽 → 天。槽可以是 int（表示桶序号）或 (day, idx) 元组。"""
    if isinstance(sid, tuple) and len(sid) >= 1:
        return int(sid[0])
    return int(sid)
