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
from sports_ai.nn.moe_encoder import MoEEncoder, make_moe_encoder
from ..data.features import NODE_FEAT_DIM
from .scheme import MAX_SLOTS


class SchemeRefiner(nn.Module):
    """安全精修器：**默认什么都不改**，只在确有收益时小幅修正。

    两条防线（都是被实测逼出来的）：
    1. **输出层零初始化** —— 训练起点是恒等映射（``logits = init``）。梯度只有在
       「改动能降低组合约束损失」时才会把权重推离 0。若初始方案本就不错，精修器自然
       近似不动，而不是像随机初始化那样一上来就乱改（实测把冲突 0.0068 改到 0.0740）。
    2. **tanh 限幅** —— 单点 logits 的修正量被限制在 ``±delta_scale`` 内，
       即便训练把权重推大，也不可能翻天覆地改变 argmax 结果。

    这表达了一个正确的工程姿态：**精修器是「锦上添花」，不是「推倒重来」**。
    """

    def __init__(self, node_feat: int = NODE_FEAT_DIM, slots: int = MAX_SLOTS,
                 hidden: int = 160, delta_scale: float = 0.6, moe: bool = True,
                 moe_layers: int = 6, moe_experts: int = 9):
        super().__init__()
        self.slots = slots
        self.delta_scale = delta_scale
        self.enc = (make_moe_encoder(node_feat + slots, hidden, n_layers=moe_layers,
                                     n_experts=moe_experts)
                    if moe else GnnEncoder(node_feat + slots, hidden))
        # 输出「残差修正量」，在初始 logits 之上精修（初始方案已含大量正确信息）
        self.head = nn.Sequential(
            nn.Linear(hidden + slots, hidden),
            nn.ReLU(),
            nn.Linear(hidden, slots),
        )
        # 零初始化输出层 → 训练起点 = 恒等映射（安全精修的关键）
        nn.init.zeros_(self.head[-1].weight)
        nn.init.zeros_(self.head[-1].bias)

    def forward(self, node_feat, adj, mask, init_logits, forbid=None):
        x = torch.cat([node_feat, init_logits], dim=-1)          # [B,N,F+K]
        h = self.enc(x, adj, mask)                               # [B,N,H]
        raw = self.head(torch.cat([h, init_logits], dim=-1))     # [B,N,K]
        delta = torch.tanh(raw) * self.delta_scale               # 限幅残差
        logits = init_logits + delta
        if forbid is not None:
            logits = logits.masked_fill(forbid > 0.5, -1e9)
        return logits * mask.unsqueeze(-1)
