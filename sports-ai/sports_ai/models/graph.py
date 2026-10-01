"""共享图卷积底座：带权邻接 + 自环 + 对称归一化（GCN 式）+ 残差块。

被三处复用，保证「图表示」在训练侧内部完全一致：
- :mod:`sports_ai.models.gnn`（冲突簇着色优先级）
- :mod:`sports_ai.generative.encoder`（GAN 生成器/判别器共享编码器）
- :mod:`sports_ai.generative.refiner`（精修器）

**为什么不用 PyG / DGL**：这两个库带原生扩展与自定义算子，``torch.onnx.export`` 导不出，
而本项目的交付契约是「训练侧导出 ONNX → Java onnxruntime 推理」。所以这里只用
纯张量算子（matmul / pow / layernorm / relu）手写消息传递——虽朴素，但**可导出、可复刻**。

**为什么是带权而非二值**：一条冲突边的真实含义是「两个单元共享多少名运动员」，
共享 20 人的两个单元与共享 1 人的两个单元，着色时该被区别对待。二值邻接把这个信息
压掉了；带回权后，GCN 的度数归一化会让「重边」在聚合中占更大比重，模型能感知冲突强度。
"""

from __future__ import annotations

import torch
import torch.nn as nn


def normalized_adj(adj: torch.Tensor, mask: torch.Tensor,
                   add_self_loops: bool = True) -> torch.Tensor:
    """对称归一化邻接 ``D^-1/2 (A+I) D^-1/2``，只在真实节点之间生效。

    :param adj:  [B, N, N] 邻接（**带权**：共享运动员数的归一化值，0 表示无边）
    :param mask: [B, N] 1=真实节点
    :return:     [B, N, N] 归一化邻接

    <p>padding 节点必须在归一化前被 mask 掉，否则它们会以 0 度参与分母、把真实节点的
    度数拉偏（曾因此让 padding 越多、分数越飘）。自环单独再乘一次 mask，杜绝
    padding 通过自环获得非零激活。</p>
    """
    n = adj.shape[-1]
    m = mask.unsqueeze(-1) * mask.unsqueeze(1)
    a = adj * m
    if add_self_loops:
        eye = torch.eye(n, device=adj.device, dtype=adj.dtype).unsqueeze(0)
        a = a + eye * mask.unsqueeze(-1)
    deg = a.sum(dim=-1).clamp(min=1e-6)
    d_inv_sqrt = torch.pow(deg, -0.5)
    return a * d_inv_sqrt.unsqueeze(-1) * d_inv_sqrt.unsqueeze(1)


class GraphConvBlock(nn.Module):
    """一层图卷积：归一化邻居聚合 → 线性 → LayerNorm → ReLU → Dropout → 残差。

    对比最初的实现（``h = h + relu(W(A·h))``），这里补了三处，都是**为深层网络服务**的：
    - **LayerNorm**：多层堆叠后节点表征尺度会漂移，没有归一化就只能堆 2 层；
    - **残差置于激活之后**：让梯度直达底层，4 层以上仍可训练；
    - **归一化邻接**：度数高的节点（冲突簇中心）不再因为邻居多而数值爆炸。
    """

    def __init__(self, hidden: int, dropout: float = 0.1):
        super().__init__()
        self.lin = nn.Linear(hidden, hidden)
        self.norm = nn.LayerNorm(hidden)
        self.drop = nn.Dropout(dropout)

    def forward(self, h: torch.Tensor, a_norm: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
        agg = torch.matmul(a_norm, h)
        out = torch.relu(self.norm(self.lin(agg)))
        out = self.drop(out)
        out = out * mask.unsqueeze(-1)
        return h + out
