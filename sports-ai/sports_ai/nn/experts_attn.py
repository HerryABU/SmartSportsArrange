"""序列与交互类专家：卷积（CNN）、自注意力（Transformer）、交叉特征。

三者都回答「节点之间怎么互相看」：CNN 看**局部相邻**、自注意力看**全局依赖**、
交叉看**两两交互**（项目 A 与项目 B 的关系）。
"""

from __future__ import annotations

import math
from typing import List, Optional, Tuple

import torch
import torch.nn as nn
import torch.nn.functional as F

from .base import ResidualMLPBlock


class ConvSeqExpert(nn.Module):
    """一维卷积专家：沿「序列」维做上下文聚合（kernel 3，带残差）。

    编排里有天然序列：**组次 1→2→3…**、**时段推进**、**队次轮转**。
    卷积擅长捕捉这种局部顺序模式（「第 k 组紧接第 k+1 组」）。
    """

    def __init__(self, dim: int, kernel: int = 3, dropout: float = 0.1):
        super().__init__()
        self.pad = kernel // 2
        self.norm = nn.LayerNorm(dim)
        self.conv = nn.Conv1d(dim, dim, kernel_size=kernel, padding=0)
        self.gate = nn.Linear(dim, dim)
        self.blocks = nn.ModuleList([ResidualMLPBlock(dim, dropout=dropout)
                                     for _ in range(2)])

    def forward(self, x: torch.Tensor, adj: Optional[torch.Tensor] = None,
                mask: Optional[torch.Tensor] = None) -> torch.Tensor:
        del mask
        # ⚠️ 必须同时支持 2D [N,D] 与 3D [B,N,D]：
        # 稀疏 MoE 逐行调用专家时传的是 2D（把 B 维当成行维），
        # 若这里硬写 transpose(1,2) 就会在 2D 输入上直接 IndexError。
        is3d = x.dim() == 3
        h = self.norm(x)
        h = h.transpose(-1, -2)                    # [*,D,N]
        h = F.pad(h, (self.pad, self.pad))
        h = self.conv(h).transpose(-1, -2)         # [*,N,D]
        g = torch.sigmoid(self.gate(x))
        h = x + h * g
        for b in self.blocks:
            h = b(h)
        del adj
        del is3d
        return h


class SelfAttnExpert(nn.Module):
    """自注意力专家：让每个位置直接看到所有其它位置（残差 + 前馈）。

    补的是卷积/图卷积看不到的**长程依赖**：
    「第 1 组和第 8 组虽然离得远，但它们俩撞车了」。
    """

    def __init__(self, dim: int, heads: int = 4, dropout: float = 0.1):
        super().__init__()
        # ⚠️ heads 必须整除 dim：不能整除时在 __init__ 里解决，
        #    否则报错要到第一次前向才抛（且信息埋在 attention 内部极难定位）。
        heads = max(1, min(heads, dim))
        while heads > 1 and dim % heads != 0:
            heads -= 1
        self.h = heads
        self.dh = dim // heads
        self.norm = nn.LayerNorm(dim)
        # ⚠️⚠️ **不用 nn.MultiheadAttention**，改为手写 MHA。
        # 原因（本项目实测踩过）：nn.MultiheadAttention 内部走 `F.multi_head_attention_forward`，
        # tracing 导出 ONNX 时会为「[B,N,dim] → [B*N, h, dh]」这条 Reshape
        # **把 B*N 折成导出时的具体数值**（实测 B=1,N=16 → 16），
        # 于是动态轴下换个候选数就崩：
        #   `input_shape:{2,1,128}, requested shape:{16,4,32}`（B=2,N=1 时算出 2≠16）。
        # 手写实现全部用 `x.shape[...]` 拼 Reshape 形状，ONNX 侧保持符号。
        self.qkv = nn.Linear(dim, dim * 3, bias=True)
        self.proj = nn.Linear(dim, dim, bias=True)
        self.drop = nn.Dropout(dropout)
        self.ff = ResidualMLPBlock(dim, dropout=dropout)

    def forward(self, x: torch.Tensor, adj: Optional[torch.Tensor] = None,
                mask: Optional[torch.Tensor] = None) -> torch.Tensor:
        del adj
        # 稀疏 MoE 逐行调用时输入是 2D [N,D]，批处理时是 3D [B,N,D]。
        # 统一补成 3D 再算，最后还原 —— 与卷积/交叉专家同一处理。
        was2d = x.dim() == 2
        h_in = x.unsqueeze(0) if was2d else x
        b, n, d = h_in.shape
        h = self.norm(h_in)
        qkv = self.qkv(h).reshape(b, n, 3, self.h, self.dh).permute(2, 0, 3, 1, 4)
        q, k, v = qkv[0], qkv[1], qkv[2]                  # 各 [B, heads, N, dh]
        att = torch.matmul(q, k.transpose(-2, -1)) / math.sqrt(self.dh)
        if mask is not None:
            keep = (mask > 0.5).reshape(b, 1, 1, n)      # [B,1,1,N]
            att = att.masked_fill(~keep, float("-1e9"))
        att = self.drop(torch.softmax(att, dim=-1))
        out = torch.matmul(att, v)                        # [B, heads, N, dh]
        out = out.permute(0, 2, 1, 3).reshape(b, n, d)    # 合并 heads
        out = self.proj(out)
        out = h_in + out
        out = self.ff(out)
        return out.squeeze(0) if was2d else out


class CrossExpert(nn.Module):
    """交叉特征专家：把每行与其**全局摘要**交互，补上「个体 vs 整体」视角。

    摘要 = mask 加权平均。例：某候选组次与「全场平均填充率」的关系，
    是纯局部算子看不到的。
    """

    def __init__(self, dim: int, dropout: float = 0.1):
        super().__init__()
        # ⚠️ LayerNorm 必须按 **拼接后的 2*dim** 建（输入是 cat([x, pooled])），
        #    早前按 dim 建会直接报 "expected input with shape [*, dim] but got [K, 2*dim]" ——
        #    LayerNorm 的 normalized_shape 永远作用在**最后一维**，不会因为后面有 Linear 而自适应。
        self.norm = nn.LayerNorm(dim * 2)
        self.proj = nn.Linear(dim * 2, dim)
        self.blocks = nn.ModuleList([ResidualMLPBlock(dim, dropout=dropout)
                                     for _ in range(2)])

    def forward(self, x: torch.Tensor, adj: Optional[torch.Tensor] = None,
                mask: Optional[torch.Tensor] = None) -> torch.Tensor:
        del adj
        # ⚠️ 稀疏 MoE 逐行调用时输入是 2D [N,D]（行维是 -2），批处理时是 3D [B,N,D]（行维是 1）。
        # 两种情形的「行维」不同，用 dim=-2 能一次覆盖；而 sum 后必须**保持至少两维**，
        # 否则 2D 会塌成 [D]，后面 torch.cat([x, pooled]) 就对不上（实测报
        # "expected input with shape [*, 64] but got input of size[K, 128]"）。
        m = mask.unsqueeze(-1) if mask is not None else torch.ones_like(x[..., :1])
        denom = m.sum(dim=-2, keepdim=True).clamp(min=1.0)
        pooled = (x * m).sum(dim=-2, keepdim=True) / denom      # [.., 1, D]
        if pooled.dim() == x.dim() - 1:                          # 2D 塌成 [D] → 补行维
            pooled = pooled.unsqueeze(-2)
        pooled = pooled.expand_as(x)
        h = self.proj(self.norm(torch.cat([x, pooled], dim=-1)))
        for b in self.blocks:
            h = b(h)
        return h


#: 专家工厂：按名字建专家。缺名字时按轮转取，保证多样性。
