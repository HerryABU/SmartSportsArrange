"""附加输出头：后续步骤预测（next_step）。

它回答「下一步该做什么」（继续精修 / 组次错开 / 跨时段拆分 / 可收尾），
与序列预测头（预测未来 H 步排到哪）是**两个不同的任务**，不要混用。
"""

from __future__ import annotations

import math
from typing import List, Optional, Tuple

import torch
import torch.nn as nn
import torch.nn.functional as F


# ---------------------------------------------------------------------------
class NextStepHead(nn.Module):
    """预测「下一步」：给定当前位置的融合表征，输出对**后续若干步**的预测。

    ==================  ==========================================================
    ``step``=1        下一组的组次数（能不能排得下）
    ``step``=2        间隔是否达标（够不够缓冲）
    ``step``=3        后续是否还能继续错开（有没有更优解）
    ==================  ==========================================================

    依据前面的步骤来预测后面的步骤 —— 这让模型输出的是**一条决策轨迹**，
    而不是孤立的单点建议；调用方据此可以「只执行到第 k 步就停」，
    实现渐进式消解（先换组次，不够再动项目时间）。

    刻意做成**多头**而不是一个大输出：各步的量纲完全不同
    （组次数是计数、间隔是分钟、布尔是 0/1），混在一个回归头里会互相压制。
    """

    def __init__(self, dim: int, n_steps: int = 3, hidden: Optional[int] = None):
        super().__init__()
        hidden = hidden or dim
        self.n_steps = n_steps
        self.trunk = nn.Sequential(
            nn.LayerNorm(dim), nn.Linear(dim, hidden), nn.ReLU())
        self.heads = nn.ModuleList([nn.Linear(hidden, 1) for _ in range(n_steps)])

    def forward(self, fused: torch.Tensor) -> torch.Tensor:
        """``fused`` [B,D] → [B,n_steps]（每步一个标量；调用方自行 sigmoid/argmax）。"""
        h = self.trunk(fused)
        return torch.cat([hd(h) for hd in self.heads], dim=-1)
