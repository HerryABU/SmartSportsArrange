"""算法选择器 v2：**语义 token Transformer** + POMO 式共享基线训练策略。

## 为什么从残差 MLP 升级到 Transformer

v1（``models/selector.py``）是 16 维表格特征 → 残差 MLP → 2 分类。判据本身
（容量够不够、团会不会超时段）确实是特征间的**非线性交互**（如「团下界 × 可用时段数」），
残差 MLP 够用。但有三个它做不到的：

1. **特征是分组的，交互应发生在组内与组间**——16 维里有 5 组语义
   （规模 / 需求 / 装箱 / 人员 / 索引），MLP 必须从头学全交叉；
   Transformer 用 **5 个可学习的语义 token** 先做组内聚合，再做组间注意力，
   归纳偏置正确，样本效率更高。
2. **特征缺失/无意义时应能忽略**——近零方差特征、填充位、pad 位置，
   注意力可以学会把权重给到有信息的 token 上；MLP 只能靠权重衰减硬压。
3. **同分布内的类不平衡**——多数实例可解、少数不可解，v1 的 CE 会被
   大类主导。v2 训练用 **POMO 式共享基线**（见 ``train_selector_v2.py``）降方差。

⚠️ **向后兼容**：``models/selector.py`` 原样保留（v1 权重与 ONNX 仍在用），
本类是 v2，通过 ``sports.schedule.ai.selector-version: v1|v2`` 切换。

## 归一化仍在模型内

与 v1 一致：``Normalize`` 层固化 mean/std 作为第一层，Java 端只喂原始 16 维。
"""

from __future__ import annotations

import torch
import torch.nn as nn


class Normalize(nn.Module):
    """特征标准化层：作为导出模型第一个子模块固化 mean/std。"""

    def __init__(self, mean, std):
        super().__init__()
        self.register_buffer("mean", torch.as_tensor(mean, dtype=torch.float32))
        self.register_buffer("std", torch.as_tensor(std, dtype=torch.float32))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return (x - self.mean) / self.std


# 16 维特征 → 5 个语义组（这是归纳偏置的来源，改动需同步训练侧）
FEATURE_GROUPS = [
    (0, 4, "scale"),        # 规模：单元数 / 人数 / 场地 / 时段
    (4, 8, "demand"),       # 需求：需求时长 / 容量利用率 / 紧张度 / 装箱余量
    (8, 12, "people"),      # 人员：兼项占比 / 平均每项人数 / 团下界 / 冲突边
    (12, 14, "structure"),  # 结构：项目数占比 / 是否大单元
    (14, 16, "index"),      # 索引：顺序 / 组内位置
]


class SemanticGroupAttention(nn.Module):
    """5 个语义 token + 交叉注意力 + FFN。"""

    def __init__(self, d_model: int = 64, n_heads: int = 4, dropout: float = 0.15,
                 n_tokens: int = len(FEATURE_GROUPS)):
        super().__init__()
        self.d_model = d_model
        # 每个组一个专属的「组内聚合器」：把 (lo,hi) 维投成 d_model。
        # ⚠️ 不要用 repeat/切片去凑维度——那样 d_model 必须被 2×组宽整除，
        #    换个 d_model 就崩。这里每组一个 Linear，形状无关。
        # 每组池化后固定是 2 列（mean, max），所以 in_features 恒为 2——
        # 与组宽无关，改 FEATURE_GROUPS 的分组也不会破坏层构造。
        self.grouper = nn.ModuleList([nn.Linear(2, d_model) for _ in FEATURE_GROUPS])
        self.query = nn.Parameter(torch.randn(n_tokens, d_model) * 0.02)
        self.attn = nn.MultiheadAttention(d_model, n_heads, dropout=dropout, batch_first=True)
        self.norm1 = nn.LayerNorm(d_model)
        self.ffn = nn.Sequential(
            nn.Linear(d_model, d_model * 2),
            nn.GELU(),
            nn.Dropout(dropout),
            nn.Linear(d_model * 2, d_model),
        )
        self.norm2 = nn.LayerNorm(d_model)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        """x: [B, 16]（已标准化）→ [B, d_model]。

        ⚠️ 每层都从**原始 16 维**重新分组，而不是接上一层的 d_model——
        grouper 的输入维度是按 FEATURE_GROUPS 的 (hi-lo)*2 定义的，
        喂 d_model 进去会报 "mat1 and mat2 shapes cannot be multiplied"。
        """
        toks = []
        for i, (lo, hi, _name) in enumerate(FEATURE_GROUPS):
            g = x[:, lo:hi]                                   # [B, hi-lo]
            # 组内池化成**两列**（均值、最大）：极值能保留「某维特别极端」的信息。
            # ⚠️ 必须压到标量再 cat——若写成 keepdim 的 mean/maxam 再 cat，
            #    宽度会等于原组宽而不是 2，grouper 的 in_features 对不上，
            #    报 "mat1 and mat2 shapes cannot be multiplied (4x2 and 8x32)"。
            pooled = torch.stack([g.mean(dim=1), g.amax(dim=1)], dim=1)   # [B, 2]
            t = self.grouper[i](pooled) + self.query[i].view(1, -1)
            toks.append(t)
        h = torch.stack(toks, dim=1)                       # [B, T, d_model]
        a, _ = self.attn(h, h, h, need_weights=False)     # 自注意力
        h = self.norm1(h + a)
        h = self.norm2(h + self.ffn(h))
        return h.mean(dim=1)                              # 池化成实例级表示


class AlgorithmSelectorV2(nn.Module):
    """16 维 → 5 语义 token → Transformer → 2 logits（硬解 / 取消）。"""

    def __init__(self, n_features: int = 16, d_model: int = 64, n_heads: int = 4,
                 n_layers: int = 2, dropout: float = 0.15, n_classes: int = 2):
        super().__init__()
        if n_features != 16:
            raise ValueError(f"语义分组按 16 维定义，收到 n_features={n_features}")
        self.d_model = d_model
        self.proj = nn.Linear(n_features, d_model)
        self.blocks = nn.ModuleList([
            SemanticGroupAttention(d_model, n_heads, dropout) for _ in range(n_layers)
        ])
        self.norm = nn.LayerNorm(d_model)
        # head 的输入是 h（[B,d_model]），不是拼接过的东西，首层就是 d_model
        self.head = nn.Sequential(
            nn.Linear(d_model, d_model),
            nn.GELU(),
            nn.Dropout(dropout),
            nn.Linear(d_model, n_classes),
        )

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        # x: [B,16]（已标准化）→ [B,2]
        # 每层都从原始 16 维提取语义 token，层间用残差累加（梯度更稳）
        h = self.proj(x)
        for blk in self.blocks:
            h = h + blk(x)
        h = self.norm(h)
        return self.head(h)
