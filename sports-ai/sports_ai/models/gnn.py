"""冲突簇 GNN：给定冲突图（节点特征 + 邻接），预测每节点的「着色优先级」。

对应架构文档 5.1 第二条「GNN 辅助传统启发式」——预测哪些冲突簇优先着色、
回溯时优先调整哪些项目对。

采用**固定 shape 契约**（MAX_NODES × NODE_FEAT_DIM），用稠密邻接矩阵乘法实现
一阶邻居聚合（等价于简化 GCN），保证 ``torch.onnx.export`` 稳定导出，且 Java 端
只需构造二值邻接即可复刻推理。

输出 [B, MAX_NODES] 的优先级得分（越高越中心 / 越应优先着色），填充节点被 mask 置零。
"""

from __future__ import annotations

import torch
import torch.nn as nn


class ConflictGnn(nn.Module):
    def __init__(self, node_feat: int = 8, hidden: int = 32, layers: int = 2):
        super().__init__()
        self.proj = nn.Linear(node_feat, hidden)
        self.convs = nn.ModuleList([nn.Linear(hidden, hidden) for _ in range(layers)])
        self.out = nn.Linear(hidden, 1)

    def forward(self, node_feat, adj, mask):
        # node_feat: [B, N, F]  adj: [B, N, N]（二值）  mask: [B, N]
        h = torch.relu(self.proj(node_feat))
        for conv in self.convs:
            # 邻居聚合 + 残差自环：h + ReLU(W · A·h)
            h = h + torch.relu(conv(torch.matmul(adj, h)))
        score = self.out(h).squeeze(-1)          # [B, N]
        return score * mask                       # 屏蔽填充节点
