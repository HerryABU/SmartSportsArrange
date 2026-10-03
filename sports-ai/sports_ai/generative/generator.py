"""生成器 G：噪声 + 冲突图条件 → 节点着色方案（时间槽分配）。

条件 = 冲突图（节点特征 + 邻接 + mask），噪声 = 每节点的高斯噪声（提供解的多样性）。
输出 = 每个节点在 K 个时间槽上的 logits。禁止列表（行政时间保护）在输出前屏蔽。

与判别器组成真 GAN 的对抗对：G 力求生成「判别器无法与 Oracle 真实可行解区分」的方案。
"""

from __future__ import annotations

import torch
import torch.nn as nn

from .encoder import GnnEncoder
from ..data.features import NODE_FEAT_DIM
from .scheme import MAX_SLOTS, gumbel_scheme


class SchemeGenerator(nn.Module):
    def __init__(self, node_feat: int = NODE_FEAT_DIM, hidden: int = 160, noise: int = 8,
                 slots: int = MAX_SLOTS):
        super().__init__()
        self.slots = slots
        self.enc = GnnEncoder(node_feat, hidden)
        self.head = nn.Sequential(
            nn.Linear(hidden + noise, hidden),
            nn.ReLU(),
            nn.Linear(hidden, hidden),
            nn.ReLU(),
            nn.Linear(hidden, slots),
        )

    def forward(self, node_feat, adj, mask, z, forbid=None, tau: float = 1.0, hard: bool = True):
        """返回 (logits [B,N,K], scheme [B,N,K])；scheme 为 Gumbel-Softmax 采样的硬/软方案。"""
        h = self.enc(node_feat, adj, mask)                   # [B,N,H]
        hz = torch.cat([h, z], dim=-1)                       # [B,N,H+Z]
        logits = self.head(hz)                               # [B,N,K]
        if forbid is not None:
            # 禁止槽 logits 置 -1e9（softmax 后概率≈0）
            logits = logits.masked_fill(forbid > 0.5, -1e9)
        logits = logits * mask.unsqueeze(-1)                 # 填充节点清零
        scheme = gumbel_scheme(logits, tau=tau, hard=hard) * mask.unsqueeze(-1)
        return logits, scheme

    def logits_of(self, node_feat, adj, mask, z, forbid=None):
        """只算 logits（不采样），供推理/导出复用。"""
        h = self.enc(node_feat, adj, mask)
        logits = self.head(torch.cat([h, z], dim=-1))
        if forbid is not None:
            logits = logits.masked_fill(forbid > 0.5, -1e9)
        return logits * mask.unsqueeze(-1)

    def generate(self, node_feat, adj, mask, z, forbid=None):
        """确定性推理：返回 (logits, one-hot 方案)。

        ONNX 导出走这条路径——推理阶段不需要 Gumbel 采样，直接取 argmax 作为「最优着色方案」，
        既确定性又可导出（避免 RandomUniform 带来的导出/复现问题）。
        """
        logits = self.logits_of(node_feat, adj, mask, z, forbid)
        idx = logits.argmax(dim=-1, keepdim=True)
        scheme = torch.zeros_like(logits).scatter_(-1, idx, 1.0)
        return logits, scheme * mask.unsqueeze(-1)
