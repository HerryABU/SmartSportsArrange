"""共享 GNN 编码器：把冲突图（节点特征 + 带权邻接 + mask）编码成节点嵌入。

生成器与判别器共用同一套编码结构（各自持有独立参数）——与训练侧 ``gnn_io.py`` /
Java 端 ``ConflictGraphEncoder`` 的图契约一致：**带权**稠密邻接 + 对称归一化 +
多层残差消息传递 + 跳跃连接，固定 shape，便于 ONNX 导出。

与 :class:`sports_ai.models.gnn.ConflictGnn` 的区别只在**输出**：这里输出节点嵌入
（交给生成器/判别器的下游头），那里直接输出优先级得分；图算力结构完全复用
:mod:`sports_ai.models.graph`，避免两条路径的图表示悄悄分叉。
"""

from __future__ import annotations

import torch
import torch.nn as nn

from ..models.graph import GraphConvBlock, normalized_adj


class GnnEncoder(nn.Module):
    def __init__(self, in_dim: int, hidden: int = 64, layers: int = 3, dropout: float = 0.0):
        super().__init__()
        self.proj = nn.Linear(in_dim, hidden)
        self.in_norm = nn.LayerNorm(hidden)
        self.blocks = nn.ModuleList([GraphConvBlock(hidden, dropout) for _ in range(layers)])
        self.jk = nn.Linear(hidden * (layers + 1), hidden)
        self.out_norm = nn.LayerNorm(hidden)

    def forward(self, node_feat: torch.Tensor, adj: torch.Tensor,
                mask: torch.Tensor) -> torch.Tensor:
        """
        node_feat: [B, N, F]   adj: [B, N, N]（带权）   mask: [B, N]
        return:     [B, N, hidden]
        """
        a_norm = normalized_adj(adj, mask)
        h = torch.relu(self.in_norm(self.proj(node_feat))) * mask.unsqueeze(-1)
        traces = [h]
        for blk in self.blocks:
            h = blk(h, a_norm, mask)
            traces.append(h)
        h = self.out_norm(self.jk(torch.cat(traces, dim=-1)))
        return h * mask.unsqueeze(-1)
