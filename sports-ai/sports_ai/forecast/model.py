"""多步预测模型：Direct / Recursive / MIMO 三种策略（架构文档 5.2 节）。

- **Direct**：GRU 编码输入窗口 → 一次性输出未来 H 步（单头、直接多步）；
- **Recursive**：GRU 逐步自回归，用上一步预测喂入下一步，滚动 H 次（误差会累积）；
- **MIMO**：GRU 编码 → H 个独立预测头（每步一个），一次性输出未来 H 步完整序列，做主预测器。
"""

from __future__ import annotations

import torch
import torch.nn as nn


class _GruEncoder(nn.Module):
    def __init__(self, in_dim: int, hidden: int = 160):
        super().__init__()
        self.gru = nn.GRU(in_dim, hidden, batch_first=True)
        self.hidden = hidden

    def forward(self, x):
        out, h = self.gru(x)
        return out, h.squeeze(0)     # out [B,L,H], h [B,H]


class DirectForecaster(nn.Module):
    """一次性输出未来 H 步（单头）。"""

    def __init__(self, in_dim: int = 4, hidden: int = 160, h: int = 8):
        super().__init__()
        self.enc = _GruEncoder(in_dim, hidden)
        self.head = nn.Linear(hidden, h)

    def forward(self, x):
        _, h = self.enc(x)
        return self.head(h)          # [B, H]


class MimoForecaster(nn.Module):
    """H 个独立预测头（多输入多输出），一次性输出完整 H 步序列。"""

    def __init__(self, in_dim: int = 4, hidden: int = 160, h: int = 8):
        super().__init__()
        self.enc = _GruEncoder(in_dim, hidden)
        self.heads = nn.ModuleList([nn.Linear(hidden, 1) for _ in range(h)])

    def forward(self, x):
        _, h = self.enc(x)
        return torch.cat([head(h) for head in self.heads], dim=-1)   # [B, H]


class RecursiveForecaster(nn.Module):
    """逐步自回归：用上一步预测值（标量）作为下一步输入的最后一位，滚动 H 次。"""

    def __init__(self, in_dim: int = 4, hidden: int = 64, h: int = 8):
        super().__init__()
        self.enc = _GruEncoder(in_dim, hidden)
        self.step = nn.GRUCell(in_dim + 1, hidden)
        self.out = nn.Linear(hidden, 1)
        self.h = h

    def forward(self, x):
        _, h = self.enc(x)
        last_feat = x[:, -1, :]                  # [B,IN]
        prev = torch.zeros(x.shape[0], 1, device=x.device)
        preds = []
        for _ in range(self.h):
            inp = torch.cat([last_feat, prev], dim=-1)
            h = self.step(inp, h)
            prev = self.out(h)                   # [B,1]
            preds.append(prev)
        return torch.cat(preds, dim=-1)          # [B, H]
