"""图类专家：图卷积（GNN）与 ROI 池化。

- ``GraphConvExpert``：沿**带权邻接**传播（谁和谁互相影响），对应用户点名的「GNN」；
- ``RoiPoolExpert``：把「可解释的关注区域」显式池化 —— 它给出的不是黑箱分数，
  而是「这个专家在关注哪一片节点」，便于事后定位模型为什么这么排。
"""

from __future__ import annotations

import math
from typing import List, Optional, Tuple

import torch
import torch.nn as nn
import torch.nn.functional as F

from .base import ResidualMLPBlock


class GraphConvExpert(nn.Module):
    """图卷积专家：按「归一化邻接 × 值域」聚合邻居，再与自身融合。

    在编排问题里，邻接就是「同池 / 同班 / 同块 / 兼项」这些关系；
    邻居的取值往往比自身更能说明问题（例：同池的人已经排得很满）。
    """

    def __init__(self, dim: int, n_edges: int = 1, dropout: float = 0.1):
        super().__init__()
        self.n_edges = max(1, n_edges)
        self.rel_emb = nn.Parameter(torch.randn(self.n_edges, dim) * 0.02)
        self.proj = nn.Linear(dim * 2, dim)
        self.blocks = nn.ModuleList([ResidualMLPBlock(dim, dropout=dropout)
                                     for _ in range(2)])

    def forward(self, x: torch.Tensor, adj: Optional[torch.Tensor] = None,
                mask: Optional[torch.Tensor] = None,
                adj_is_batched: Optional[bool] = None) -> torch.Tensor:
        """``x`` [B,N,D] 或 [N,D]；``adj`` [B,E,N,N] / [E,N,N] / [B,N,N] / [N,N]。

        ``adj_is_batched``：3 维邻接是否为「每个样本一张图」。
        **建议调用方显式传** —— B 与 E 相等时形状启发式会失效（见分支内注释）。

        邻接先统一压成 ``[B,N,N]`` 再算：不同调用方给的边型数不一致
        （主 MoE 给 [B,E,N,N]，专项/嵌套场景给 [B,N,N]，逐行调用给 [E,N,N]），
        早前按 ``dim()`` 分支时把 ``[E,N,N]`` 当成 ``[B,N,N]`` 送进 bmm，
        报 "batch1 must be a 3D tensor" —— 而 E 与 B 恰好都可能是 2/10 附近，
        属于**形状不报错、语义错位**的典型坑。
        """
        was2d = x.dim() == 2
        if was2d:
            x = x.unsqueeze(0)                                   # [1,N,D]
            if mask is not None and mask.dim() == 1:
                mask = mask.unsqueeze(0)                         # [1,N]
        b, n, _ = x.shape
        if adj is None:
            nb = x
        else:
            a = adj
            if a.dim() == 4:                # [B,E,N,N] → 沿边型求和
                a = a.sum(dim=1)
            elif a.dim() == 3:
                # 3 维邻接**一律视为「未压平的多类边」** [E,N,N]，沿 dim 0 求和。
                #
                # ⚠️ 这里**不做任何形状猜测**。之前试过三种启发式，全部会失效：
                #   · a.shape[-2:]==(N,N)      → [E,N,N] 恒成立，压不平
                #   · a.shape[0]==b            → B==E 时无法区分（本项目 E=10、B 常为 1/2/4）
                #   · a.shape[0]==a.shape[1]   → N==E 时也无法区分
                # 形状相同的两张张量本来就无法从形状分辨谁是「批」谁是「边型」，
                # 任何启发式都是赌。**要区分必须由调用方明说**（adj_is_batched）。
                # 不传时取「3 维 = 边型」这一固定口径：它对 [B,E,N,N] 的调用方
                # （先 sum(dim=1) 压平）无影响，而把 [E,N,N] 误当 [B,N,N] 会算错。
                if adj_is_batched:
                    a = a
                else:
                    a = a.sum(dim=0, keepdim=True)
            elif a.dim() == 2:              # [N,N] → 广播到批
                a = a.unsqueeze(0)
            else:
                raise ValueError(f"adj 维度应为 2~4，实得 {a.dim()}")
            if a.dim() != 3 or a.shape[-2:] != (n, n):
                raise ValueError(f"邻接压平后形状 {tuple(a.shape)} 与节点数 {n} 不符")
            if mask is not None and mask.dim() == 2:
                # 邻接已是 [B,N,N]，掩码要作用在**行**上（谁的表征被聚合）→ [B,N,1]；
                # 写成 [B,1,N,1] 会多出一个长度 N 的轴，与邻接的第 1 维（N）对不上，
                # 报 "size of tensor a (B) must match the size of tensor b (N)"。
                a = a * mask.unsqueeze(-1)                          # [B,N,1]
            nb = torch.bmm(a, x)
        h = torch.cat([x, nb], dim=-1) if was2d else torch.cat([x, nb], dim=-1)
        h = self.proj(h)
        for b2 in self.blocks:
            h = b2(h)
        return h.squeeze(0) if was2d else h


class RoiPoolExpert(nn.Module):
    """**ROI 池化专家**：把「关注区域」的选择与特征提取显式分开。

    两阶段：先由一个轻量打分头算出每个位置的 **ROI 权重**（可解释：能看是哪些单元），
    再按权重加权池化成全局区域表征，与自身拼接后做非线性融合。

    对编排的意义：主模型要回答的是「这场赛会的**核心冲突区**在哪」——
    这本质上就是 ROI（Region of Interest）问题。用注意力做是隐式的，
    显式 ROI 池化能给出**可下钻的权重**（前端可展示「模型认为这里是瓶颈」）。
    """

    def __init__(self, dim: int, dropout: float = 0.1, n_roi: int = 3):
        super().__init__()
        self.norm = nn.LayerNorm(dim)
        self.roi_score = nn.Linear(dim, n_roi)                  # 每个位置对每个 ROI 的归属
        self.roi_val = nn.Linear(dim, dim)                      # ROI 的值向量
        self.fuse = nn.Linear(dim * (1 + n_roi), dim)
        self.blocks = nn.ModuleList([ResidualMLPBlock(dim, dropout=dropout)
                                     for _ in range(2)])
        self.n_roi = n_roi

    def forward(self, x: torch.Tensor, adj: Optional[torch.Tensor] = None,
                mask: Optional[torch.Tensor] = None) -> torch.Tensor:
        del adj
        was2d = x.dim() == 2
        h = self.norm(x.unsqueeze(0) if was2d else x)          # [B,N,D]
        # ⚠️ 不能写死 "brn,bnd->brd"（einsum 下标数与轴数不匹配），
        #    也不能用 denom.transpose(1,2) 归一化（2D 路径下维数不对）——
        #    逐项相加时**不做归一化**：softmax 已把权重归一到 1，
        #    池化结果天然是「按权重加权的平均」（各 ROI 的 a 在位置维和为 1）。
        v = torch.tanh(self.roi_val(h))                        # [B,N,D]
        # ⚠️ softmax 必须沿**位置维**（最后一维 = N），不是 dim=1：
        #    h 是 [B,N,D]，roi_score 输出 [B,N,R]；写 dim=1 会沿 N 归一，
        #    于是 [B,N,R] 变成 [B,R] 的错位张量，广播到 v 时报
        #    "size of tensor a (3) must match the size of tensor b (5)"。
        #    改用 einsum 之外最稳的写法：先转成 [B,R,N] 再 softmax(dim=-1)。
        a = torch.softmax(self.roi_score(h).transpose(1, 2), dim=-1)   # [B,R,N]
        a = a.unsqueeze(-1)                                    # [B,R,N,1]
        rois = (a * v.unsqueeze(1)).sum(dim=2)                 # [B,R,D]
        cat = torch.cat([h] + [rois[:, i:i + 1].expand(-1, h.shape[1], -1)
                               for i in range(self.n_roi)], dim=-1)
        y = self.fuse(cat)
        for blk in self.blocks:
            y = blk(y)
        if mask is not None and mask.dim() == 2:
            y = y * mask.unsqueeze(-1)
        return y.squeeze(0) if was2d else y
