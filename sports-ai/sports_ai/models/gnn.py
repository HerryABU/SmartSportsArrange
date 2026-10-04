"""冲突簇 GNN：给定冲突图（节点特征 + 带权邻接 + mask），预测每节点的「着色优先级」。

对应架构文档 5.1 第二条「GNN 辅助传统启发式」——预测哪些冲突簇优先着色、
回溯时优先调整哪些项目对。

**结构（相比最初的 2 层朴素版本全面加强）**

```
输入 [B,N,16]                          ← 16 维节点特征（含轮次/年级/时长占比/冲突暴露量）
  ↓ proj + LayerNorm + ReLU
  初始嵌入 h0 [B,N,hidden]
  ↓ ×L 层 GraphConvBlock                ← 带权邻接(D^-1/2 A D^-1/2，含自环) + 残差 + LayerNorm
  h0 ─┬─ h1 ─┬─ … ─┬─ hL                ← 逐层留痕
      └──────┴──────┴─→ cat → Linear    ← 跳跃连接（JK）：浅层局部信号与深层全局信号一起用
  ↓ head（2 层 MLP + dropout）
  优先级得分 [B,N] × mask
```

三处关键升级及理由：

1. **带权邻接**：边的权重 = 两单元共享运动员数（归一化）。共享 20 人与共享 1 人的冲突
   强度不同，二值邻接把这个信息压掉了，会让「度数中心度」虚高。
2. **4 层 + 残差 + LayerNorm**：1 跳邻居只能看到直接冲突；真实编排里「冲突簇」往往是
   3~4 跳可达（短跑→接力→跳远→…）。深层网络才能传递这种长程结构，而残差与归一化是
   让 4 层以上仍能训练的前提（否则梯度消失/尺度漂移）。
3. **跳跃连接（JK）**：深层聚合容易过度平滑（所有节点表征趋同）。把每层输出拼接后再投影，
   等价于让模型自己决定「这个实例更依赖局部还是全局结构」——小实例重局部、大实例重全局。

输出 [B, MAX_NODES] 的优先级得分（越高越中心 / 越应优先着色），填充节点被 mask 置零。
"""

from __future__ import annotations

import torch
import torch.nn as nn

from .graph import GraphConvBlock, normalized_adj


class ConflictGnn(nn.Module):
    def __init__(self, node_feat: int = 17, hidden: int = 160, layers: int = 6,
                 dropout: float = 0.1):
        super().__init__()
        self.proj = nn.Linear(node_feat, hidden)
        self.in_norm = nn.LayerNorm(hidden)
        self.blocks = nn.ModuleList([GraphConvBlock(hidden, dropout) for _ in range(layers)])
        # 跳跃连接：把「初始嵌入 + 每一层输出」拼接后投影回 hidden
        self.jk = nn.Linear(hidden * (layers + 1), hidden)
        self.jk_norm = nn.LayerNorm(hidden)
        self.head = nn.Sequential(
            nn.Linear(hidden, hidden // 2),
            nn.ReLU(),
            nn.Dropout(dropout),
            nn.Linear(hidden // 2, 1),
        )

    def forward(self, node_feat: torch.Tensor, adj: torch.Tensor,
                mask: torch.Tensor) -> torch.Tensor:
        # node_feat: [B, N, F]  adj: [B, N, N]（带权）  mask: [B, N]
        a_norm = normalized_adj(adj, mask)
        h = torch.relu(self.in_norm(self.proj(node_feat))) * mask.unsqueeze(-1)
        traces = [h]
        for blk in self.blocks:
            h = blk(h, a_norm, mask)
            traces.append(h)
        h = self.jk_norm(self.jk(torch.cat(traces, dim=-1)))
        score = self.head(h).squeeze(-1)          # [B, N]
        return score * mask                       # 屏蔽填充节点
