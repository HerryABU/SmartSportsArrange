"""对抗精修器（Refiner）：把「推理时迭代自对抗精修」蒸馏成**一次前向**。

推理时在潜在空间做梯度上升（refine.py）效果显著（冲突 ↓35%），但 Java 生产端无法反传。
于是训练一个可导出的精修器：

    输入：G 的初始方案 logits + 冲突图
    输出：精修后的方案 logits（在初始 logits 上做残差修正）

训练目标与推理时精修**一致**——最小化组合约束损失 + 最大化判别器评分（骗过 D），
即「把那次博弈学成一个算子」。导出 ONNX 后，Java 端 G → Refiner → D 择优 即可复现自对抗。
"""

from __future__ import annotations

import torch
import torch.nn as nn

from .encoder import GnnEncoder
from .scheme import MAX_SLOTS


class SchemeRefiner(nn.Module):
    def __init__(self, node_feat: int = 8, slots: int = MAX_SLOTS, hidden: int = 64):
        super().__init__()
        self.slots = slots
        self.enc = GnnEncoder(node_feat + slots, hidden)
        # 输出「残差修正量」，在初始 logits 之上精修（初始方案已含大量正确信息）
        self.head = nn.Sequential(
            nn.Linear(hidden + slots, hidden),
            nn.ReLU(),
            nn.Linear(hidden, slots),
        )

    def forward(self, node_feat, adj, mask, init_logits, forbid=None):
        x = torch.cat([node_feat, init_logits], dim=-1)          # [B,N,F+K]
        h = self.enc(x, adj, mask)                               # [B,N,H]
        delta = self.head(torch.cat([h, init_logits], dim=-1))   # [B,N,K]
        logits = init_logits + delta
        if forbid is not None:
            logits = logits.masked_fill(forbid > 0.5, -1e9)
        return logits * mask.unsqueeze(-1)
