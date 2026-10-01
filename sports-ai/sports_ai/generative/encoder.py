"""共享 GNN 编码器：把冲突图（节点特征 + 邻接 + mask）编码成节点嵌入。

生成器与判别器共用同一套编码结构（但各自持有独立参数）——与训练侧
``gnn_io.py`` / Java 端 ``ConflictGraphEncoder`` 的图契约一致：
稠密邻接矩阵乘法实现一阶邻居聚合 + 残差自环，固定 shape，便于 ONNX 导出。
"""

from __future__ import annotations

import torch
import torch.nn as nn


class GnnEncoder(nn.Module):
    def __init__(self, in_dim: int, hidden: int = 64, layers: int = 2):
        super().__init__()
        self.proj = nn.Linear(in_dim, hidden)
        self.convs = nn.ModuleList([nn.Linear(hidden, hidden) for _ in range(layers)])
        self.norm = nn.LayerNorm(hidden)

    def forward(self, node_feat: torch.Tensor, adj: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
        """
        node_feat: [B, N, F]   adj: [B, N, N]（二值）   mask: [B, N]
        return:     [B, N, hidden]
        """
        h = torch.relu(self.proj(node_feat))
        for conv in self.convs:
            h = h + torch.relu(conv(torch.matmul(adj, h)))
        h = self.norm(h)
        return h * mask.unsqueeze(-1)
