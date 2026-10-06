"""状态空间专家（Mamba 式选择性扫描），复杂度 O(N)。

与注意力专家的分工：注意力在长序列上是 O(N²)，而排布场景的节点数会随时段/项目数
涨到几百，这时 SSM 是唯一还能跑满且显存可控的序列建模方式。
"""

from __future__ import annotations

import math
from typing import List, Optional, Tuple

import torch
import torch.nn as nn
import torch.nn.functional as F

from .base import ResidualMLPBlock


class SsmExpert(nn.Module):
    """**状态空间 / 选择性扫描专家**（Mamba 式，线性复杂度 O(N)）。

    ## 为什么现有专家不够、需要这一类

    卷积看**局部**（kernel 3）、自注意力看**全局但是 O(N²)**、图卷积看**拓扑**。
    编排里有第三类长程结构：按优先级/时间排下来的**单元序列**，
    相隔很远的两个单元也可能互相影响（当天第 1 个与当天最后一个共享场地限制），
    注意力能抓到但代价是二次方 —— 而 N 会随赛会规模线性涨。

    状态空间模型用**固定大小的状态**递归扫过整个序列：O(N) 时间 + 常数内存。
    2026 年的前沿（Mamba-2/3、Mamba-MoE）正是把它与 MoE 结合 ——
    稀疏专家负责「选谁」，SSM 负责「按顺序整合」，两者交替。

    ## 本实现的取舍

    真 Mamba 需要输入相关的 (Δ, B, C) 参数化与 selective scan（CUDA 内核）。
    纯 PyTorch 的递归扫描在 CPU 上极慢（逐步循环 N 次），
    所以这里用 **并行化的「门控累积」近似**：
    故状态用「沿序列的加权和」表达，配合输入相关的遗忘门。
    复杂度同样是 O(N)，梯度可导，ONNX 友好。
    """

    def __init__(self, dim: int, dropout: float = 0.1, decay: float = 0.9):
        super().__init__()
        self.norm = nn.LayerNorm(dim)
        # 输入相关的遗忘门：决定每一位「记忆保留多少」→ 选择性的核心
        self.decay = nn.Linear(dim, dim)
        self.inp = nn.Linear(dim, dim)
        self.gate = nn.Linear(dim, dim)
        self.out = nn.Linear(dim, dim)
        self.drop = nn.Dropout(dropout)
        self.base = decay
        self.blocks = nn.ModuleList([ResidualMLPBlock(dim, dropout=dropout)
                                     for _ in range(2)])

    def forward(self, x: torch.Tensor, adj: Optional[torch.Tensor] = None,
                mask: Optional[torch.Tensor] = None) -> torch.Tensor:
        del adj
        was2d = x.dim() == 2
        h = self.norm(x.unsqueeze(0) if was2d else x)          # [B,N,D]
        b, n, d = h.shape
        d_raw = self.decay(h)
        d_gate = torch.sigmoid(d_raw)                           # [B,N,D] 每维遗忘率
        # 沿序列维做**累积衰减**（对数空间累加 = 乘性衰减，避免反复乘积下溢）
        log_keep = torch.log(d_gate + 1e-6) * 0.05
        cum = torch.cumsum(log_keep, dim=1)                     # [B,N,D]
        w = torch.exp(cum - cum.max(dim=1, keepdim=True).values)  # 数值稳定
        u = torch.tanh(self.inp(h)) * torch.sigmoid(self.gate(h))
        # 状态 = 沿序列的加权和（SSM 的输出层 C·state）
        state = (u * w).sum(dim=1, keepdim=True) / n            # [B,1,D] 全局状态
        y = h + self.out(self.drop(state.expand(-1, n, -1)))
        for blk in self.blocks:
            y = blk(y)
        if mask is not None:
            y = y * mask.unsqueeze(-1)
        return y.squeeze(0) if was2d else y
