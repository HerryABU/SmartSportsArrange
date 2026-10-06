"""神经网络的**基础构件**：残差块。

单独成文件的原因：它被 9 种专家架构**全部**复用（每一族都靠它堆深度），
是所有架构的共同底座 —— 放在专家族文件里就会形成「谁先 import 谁"拥有"它」的
隐性依赖，而放在这里，依赖方向永远是从专家族指向底座。
"""

from __future__ import annotations

import math
from typing import List, Optional, Tuple

import torch
import torch.nn as nn
import torch.nn.functional as F


# ---------------------------------------------------------------------------
# 基础块
# ---------------------------------------------------------------------------
class ResidualMLPBlock(nn.Module):
    """残差 MLP 块：LayerNorm → Linear → ReLU → Dropout → Linear → +残差。

    残差是这里所有块的共同骨架：它让「把专家堆到 6 层以上」这件事不会梯度爆炸，
    而堆深度正是用户明确要求的能力。
    """

    def __init__(self, dim: int, hidden: Optional[int] = None, dropout: float = 0.1):
        super().__init__()
        hidden = hidden or dim * 2
        self.norm = nn.LayerNorm(dim)
        self.fc1 = nn.Linear(dim, hidden)
        self.fc2 = nn.Linear(hidden, dim)
        self.drop = nn.Dropout(dropout)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        h = self.norm(x)
        h = self.fc2(self.drop(F.relu(self.fc1(h))))
        return x + h
