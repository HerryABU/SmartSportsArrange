"""约束分型异构图网络（Constraint-Typed Relational GNN）——**全新架构**。

对比上一代 ``ConflictGnn`` 的根本区别
------------------------------------
``ConflictGnn`` 用**一张**加权邻接 ``adj[N,N]``，边权只有「共享运动员数」。
于是径赛并发位竞争、场地独占、田赛同组开赛、兼项串行、装箱间隔五类性质迥异的约束
被同一个标量表示——模型分不清「因为兼项要错开」与「因为场地被占要错开」。

本模型按 **GOAL**（arXiv:2605.19119）的思路：*distinct edge types, corresponding to
different classes of constraints, define the message passing structure of the GNN*，
每类约束一个**专属通道 + 专属参数**，信息按约束本体选择性传播。

架构::

    输入 node_feat     [B,N,16]
        adj_by_type    [B,T,N,N]     T=6 类约束，各自归一化
        type_mask      [B,T]         该实例实际存在哪些约束类型
        mask           [B,N]
      ↓ proj + LayerNorm + ReLU
    h0 [B,N,H]
      ↓ ×L 层  RelationalBlock
        每层内部：
          for t in range(T):                       ← 类型专属消息传递
              m_t = MLP_t(h) @ A_t                 ← A_t = 该类型的归一化邻接
          h' = LayerNorm( h + Σ_t g_t · m_t )      ← g_t = type_mask 门控
          h' = h' + FFN(h')                        ← 前馈
      ↓ JK 跳跃连接
    head（多层 MLP）
      优先级得分 [B,N] × mask

三处关键设计
------------
1. **类型专属参数**（``self.rel_mlp[t]``）：每类约束有独立的权重，
   「兼项的传播方式」与「场地占用的传播方式」不再共用同一组参数。
2. **type_mask 门控**：某些赛会没有兼项（type_mask[ATHLETE]=0），
   该通道整体不参与求和——既省算力，也避免让模型去拟合「不存在的约束」。
3. **多层 + 残差 + LayerNorm + FFN**：4 层足够让信息跨越 3~4 跳的冲突簇
   （短跑→接力→跳远→…），残差与归化是让 4 层以上仍能训练的前提。
   用户要的「多层感知机」体现在每类约束的 ``MLP_t`` 与最后的 FFN/head 上。
"""

from __future__ import annotations

from typing import List

import torch
import torch.nn as nn

# 约束边类型——顺序必须与 Java 端 ConstraintAwareGraphEncoder.TYPES **逐位对齐**
CONSTRAINT_TYPES: List[str] = [
    "ATHLETE",   # 0 共享运动员（兼项）：同一人的项目必须串行
    "POOL",      # 1 同并发池：径赛/田赛各自的并发位竞争
    "VENUE",     # 2 同场地：场地在同一时段只能被一个单元占用
    "GROUP",     # 3 同分组：田赛「同组同时开赛」
    "GRADE",     # 4 同年级：冲突面更集中
    "TIME",      # 5 时间邻接：装箱与间隔耦合
]
N_TYPES = len(CONSTRAINT_TYPES)
assert N_TYPES == 6, "约束类型数与 Java 端契约不符"

TYPE_EMBED_DIM = 8


def _normalize_adj(adj: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
    """带掩码的对称归一化：``D^-1/2 (A + I) D^-1/2``。

    加自环是必须的：自环让「节点保留自身特征」成为可能，
    否则纯消息传递会把节点表征收敛到邻居的均值（过平滑）。
    掩码保证填充节点既不贡献度数也不被读出。
    """
    b, n = adj.shape[0], adj.shape[1]
    eye = torch.eye(n, device=adj.device, dtype=adj.dtype).unsqueeze(0)
    a = adj + eye
    # 双边掩码：a 是 [B,N,N]，mask 是 [B,N]
    #   mask.unsqueeze(2) -> [B,N,1] 按行（贡献出边）
    #   mask.unsqueeze(1) -> [B,1,N] 按列（贡献入边）
    # ⚠️ 别写成 mask.unsqueeze(1).unsqueeze(2)——那会变成 4D，与 3D 的 a 广播不上。
    a = a * mask.unsqueeze(2) * mask.unsqueeze(1)
    deg = a.sum(dim=-1)                          # [B,N]
    d_inv_sqrt = deg.clamp(min=1e-6).pow(-0.5)
    d_inv_sqrt = d_inv_sqrt * mask
    return a * d_inv_sqrt.unsqueeze(1) * d_inv_sqrt.unsqueeze(2)


class RelationalBlock(nn.Module):
    """一层「类型化关系消息传递 + 前馈」。

    每类约束 t 走自己的 MLP 与自己的邻接，输出用 type_mask 加权求和。
    这样「不同约束类别」在**参数与传播路径**两个层面都是分开的。
    """

    def __init__(self, hidden: int, n_types: int = N_TYPES, dropout: float = 0.1):
        super().__init__()
        # 类型专属的消息变换（每类一个小型 MLP）
        self.rel_mlp = nn.ModuleList([
            nn.Sequential(
                nn.Linear(hidden, hidden),
                nn.ReLU(),
                nn.Linear(hidden, hidden),
            )
            for _ in range(n_types)
        ])
        # 类型偏置：让不同约束类型的消息带上**可学习的、可区分的**偏移。
        # 这比「拼接 embedding 再投影」更直接——消息本身已经过了该类型专属的 MLP，
        # 再叠加一个类型专属偏置，就能让下游 LayerNorm 区分消息的来源类别。
        self.type_bias = nn.Parameter(torch.zeros(n_types, hidden))
        nn.init.normal_(self.type_bias, std=0.02)
        self.rel_norm = nn.LayerNorm(hidden)
        self.ffn = nn.Sequential(
            nn.Linear(hidden, hidden * 2),
            nn.ReLU(),
            nn.Dropout(dropout),
            nn.Linear(hidden * 2, hidden),
        )
        self.ffn_norm = nn.LayerNorm(hidden)
        self.drop = nn.Dropout(dropout)

    def forward(self, h: torch.Tensor, adj_by_type: torch.Tensor,
                type_mask: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
        # h [B,N,H]  adj_by_type [B,T,N,N]  type_mask [B,T]  mask [B,N]
        agg = torch.zeros_like(h)
        gate = type_mask.unsqueeze(-1).unsqueeze(-1)       # [B,T,1,1]
        for t in range(adj_by_type.shape[1]):
            a = _normalize_adj(adj_by_type[:, t], mask)   # 每类各自归一化
            msg = torch.matmul(a, self.rel_mlp[t](h))    # [B,N,H] 该类型的消息
            msg = msg + self.type_bias[t].view(1, 1, -1)  # 注入类型专属偏置
            agg = agg + msg * gate[:, t]
        h = self.rel_norm(h + self.drop(agg))
        h = self.ffn_norm(h + self.ffn(h))                # 前馈（FFN）
        return h * mask.unsqueeze(-1)


class ConstraintGnn(nn.Module):
    """约束分型异构图网络（全新架构）。

    与 ``ConflictGnn`` 的输入差异：多两个张量 ``adj_by_type`` 与 ``type_mask``。
    **因此导出的 ONNX 与旧模型不兼容，必须重新训练。**
    """

    def __init__(self, node_feat: int = 16, hidden: int = 96, layers: int = 4,
                 n_types: int = N_TYPES, dropout: float = 0.1):
        super().__init__()
        self.proj = nn.Linear(node_feat, hidden)
        self.in_norm = nn.LayerNorm(hidden)
        self.blocks = nn.ModuleList([
            RelationalBlock(hidden, n_types, dropout) for _ in range(layers)
        ])
        # 跳跃连接：缓解深层过平滑（小实例重局部、大实例重全局）
        self.jk = nn.Linear(hidden * (layers + 1), hidden)
        self.jk_norm = nn.LayerNorm(hidden)
        # 输出头：多层感知机
        self.head = nn.Sequential(
            nn.Linear(hidden, hidden),
            nn.ReLU(),
            nn.Dropout(dropout),
            nn.Linear(hidden, hidden // 2),
            nn.ReLU(),
            nn.Linear(hidden // 2, 1),
        )

    def forward(self, node_feat: torch.Tensor, adj_by_type: torch.Tensor,
                type_mask: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
        h = torch.relu(self.in_norm(self.proj(node_feat))) * mask.unsqueeze(-1)
        traces = [h]
        for blk in self.blocks:
            h = blk(h, adj_by_type, type_mask, mask)
            traces.append(h)
        h = self.jk_norm(self.jk(torch.cat(traces, dim=-1)))
        score = self.head(h).squeeze(-1)        # [B,N]
        return score * mask                     # 屏蔽填充节点
