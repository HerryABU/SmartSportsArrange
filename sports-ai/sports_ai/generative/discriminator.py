"""判别器 D：**神经网络**判定「方案是否像真实可行解」。

这是「货真价实 GAN」的关键——判别器是**可学习的神经网络**，而不是规则校验器/求解器。
它把着色方案作为额外节点特征与冲突图一起编码，再做 masked 池化得到整图判别分数。

输入：冲突图（节点特征 + 邻接 + mask）+ 方案 [B,N,K]
输出：真/假 logits [B,1]
"""

from __future__ import annotations

import torch
import torch.nn as nn

from .encoder import GnnEncoder
from ..data.features import NODE_FEAT_DIM
from .scheme import MAX_SLOTS


class SchemeDiscriminator(nn.Module):
    def __init__(self, node_feat: int = NODE_FEAT_DIM, hidden: int = 160, slots: int = MAX_SLOTS,
                 dropout: float = 0.2):
        super().__init__()
        # 着色方案拼到节点特征后面
        self.enc = GnnEncoder(node_feat + slots, hidden)
        self.head = nn.Sequential(
            nn.Linear(hidden, hidden),
            nn.ReLU(),
            # dropout 削弱判别器、避免其过快碾压生成器（否则对抗梯度消失、退化成纯规则）
            nn.Dropout(dropout),
            nn.Linear(hidden, 1),
        )

    def forward(self, node_feat, adj, mask, scheme):
        x = torch.cat([node_feat, scheme], dim=-1)           # [B,N,F+K]
        h = self.enc(x, adj, mask)                           # [B,N,H]
        pooled = (h * mask.unsqueeze(-1)).sum(dim=1) / mask.sum(dim=1, keepdim=True).clamp(min=1.0)
        return self.head(pooled)                             # [B,1]
