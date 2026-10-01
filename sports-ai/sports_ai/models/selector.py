"""算法选择器（加深残差 MLP）：给定 16 维实例特征，预测「硬解」还是「取消路径」。

对应架构文档 5.1 第三条「神经网络做算法选择」——根据报名数据结构特征，
预测该走硬解（继续搜索、不取消任何项目）还是取消（批量退报名 + 通知）。

输出 2 维 logits：
- index 0 = 硬解（hard-solve）
- index 1 = 取消路径（cancel）

**为什么从 2 层直连 MLP 升级为残差 MLP**：判据本身并不复杂（容量够不够、团会不会超时段），
但它依赖的是 16 维特征之间**非线性的组合关系**（例如「团下界 × 可用时段数」的交叉）。
窄而浅的网络在这类交叉判据上拟合不足——实测地狱场景 2 天的实例（tension 0.89 却不可解）
就是早期版本的误判点。加宽到 96、堆 3 个残差块后，模型有余力去拟合这类交互项。

注意：归一化**不在**本模块内——训练时对标准化后的特征训练；导出 ONNX 时
由 ``export_onnx.py`` 把 ``Normalize`` 层作为模型第一层固化，Java 端喂原始特征。
"""

from __future__ import annotations

import torch
import torch.nn as nn


class _ResidualBlock(nn.Module):
    """两倍宽度的瓶颈残差块 + LayerNorm（对表格特征足够，且导出友好）。"""

    def __init__(self, hidden: int, dropout: float = 0.15):
        super().__init__()
        self.lin1 = nn.Linear(hidden, hidden * 2)
        self.lin2 = nn.Linear(hidden * 2, hidden)
        self.norm = nn.LayerNorm(hidden)
        self.drop = nn.Dropout(dropout)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        h = torch.relu(self.lin1(x))
        h = self.drop(h)
        h = self.lin2(h)
        return torch.relu(self.norm(x + h))


class AlgorithmSelector(nn.Module):
    def __init__(self, n_features: int = 16, hidden: int = 96, blocks: int = 3,
                 dropout: float = 0.15):
        super().__init__()
        self.proj = nn.Sequential(nn.Linear(n_features, hidden), nn.ReLU())
        self.blocks = nn.ModuleList([_ResidualBlock(hidden, dropout) for _ in range(blocks)])
        self.head = nn.Sequential(
            nn.Linear(hidden, hidden // 2),
            nn.ReLU(),
            nn.Dropout(dropout),
            nn.Linear(hidden // 2, 2),
        )

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        # x: [B, n_features]（标准化后） → [B, 2] logits
        h = self.proj(x)
        for blk in self.blocks:
            h = blk(h)
        return self.head(h)


class Normalize(nn.Module):
    """特征标准化层：作为导出模型的第一个子模块固化 mean/std。

    Java 端只喂**原始 16 维特征**，归一化由模型内部完成，避免跨语言复刻
    归一化常数导致的契约漂移。
    """

    def __init__(self, mean, std):
        super().__init__()
        self.register_buffer("mean", torch.as_tensor(mean, dtype=torch.float32))
        self.register_buffer("std", torch.as_tensor(std, dtype=torch.float32))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return (x - self.mean) / self.std
