"""算法选择器（MLP）：给定 16 维实例特征，预测「硬解」还是「取消路径」。

对应架构文档 5.1 第三条「神经网络做算法选择」——根据报名数据结构特征，
预测该走硬解（继续搜索、不取消任何项目）还是取消（批量退报名 + 通知）。

输出 2 维 logits：
- index 0 = 硬解（hard-solve）
- index 1 = 取消路径（cancel）

注意：归一化**不在**本模块内——训练时对标准化后的特征训练；导出 ONNX 时
由 ``export_onnx.py`` 把 ``Normalize`` 层作为模型第一层固化，Java 端喂原始特征。
"""

from __future__ import annotations

import torch
import torch.nn as nn


class AlgorithmSelector(nn.Module):
    def __init__(self, n_features: int = 16, hidden: int = 48):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(n_features, hidden),
            nn.ReLU(),
            nn.Dropout(0.1),
            nn.Linear(hidden, hidden),
            nn.ReLU(),
            nn.Linear(hidden, 2),
        )

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        # x: [B, n_features]（标准化后） → [B, 2] logits
        return self.net(x)


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
