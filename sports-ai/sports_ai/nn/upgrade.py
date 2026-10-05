"""把小模型升级为专项 MoE 的**统一适配层**。

## 为什么不逐个手改

各小模型的原始结构差异很大（``lane_advisor`` 是纯 MLP、``referee_gnn`` 是
带关系注意力的 GNN、``teacher_advisor`` 又是一种），逐个手改成 MoE
既重复又容易改错 —— 尤其要保住**特征契约**（维数与逐位含义）不变，
否则 Java 侧会静默读到错位的输入。

所以这里走**适配器**路线：保留原模型整体不动，把它作为新 MoE 的
「共享专家 + 初始骨干」，在外面套上多架构专家池与层次两级门控。
这样：

* 特征契约零改动（输入维数、输出形状都不变）；
* 旧权重仍可作为初始化（``load_state_dict(strict=False)``）；
* 迁移成本从「改 11 个模型」降到「包一层」。

## 迁移后的模型长什么样

::

    输入 [B,N,F]
      ├─ 共享专家：原模型（referee_gnn / lane_advisor / ...）  ← 保留全部原能力
      ├─ 稀疏专家：n_experts 个多架构专家（残差/GNN/CNN/Trans/交叉/SSM/ROI/指针/搜索）
      │             稠密融合（每专家都算、都拿梯度，见 blocks.py 的复盘）
      ├─ 层次两级门控：组权重 × 组内权重
      ├─ 深度主干：n_layers 层残差块（≥6）
      └─ 输出头：原输出形状（[B,N] 打分 / [B,N,K] logits / [B,1] 标量）

    额外输出：后续步骤预测 [B, n_steps]
"""

from __future__ import annotations

import os
from typing import Optional

import torch
import torch.nn as nn
import torch.nn.functional as F

from sports_ai.nn.blocks import HierarchicalRouter, NextStepHead, ResidualMLPBlock, build_expert, EXPERT_CYCLE


class UpgradedMoE(nn.Module):
    """把一个既有小模型升级为专项 MoE（多架构 + 层次门控 + ≥6 层 + 后续步骤预测）。

    :param legacy: 原模型。要求 ``forward(x, mask)`` 或 ``forward(x)`` 能跑通，
                   且输出第一维是节点维（``[B,N,...]``）。允许返回 tuple（取第一项）。
    :param in_dim:  输入特征维数（必须与原模型一致，否则契约就破了）
    :param out_dim: 输出维数。``1`` 表示逐行打分（``[B,N]``）；>1 表示 ``[B,N,out_dim]``
    :param pool:    是否走**池化**分支（``[B,N,D] → [B,D] → [B,out_dim]``）。
                    用于「整个实例出一个标量」的场景（如工期预测、质量评判）。
    """

    def __init__(self, legacy: nn.Module, in_dim: int, out_dim: int = 1,
                 hidden: int = 128, n_layers: int = 6, n_experts: int = 9,
                 n_groups: int = 3, n_shared: int = 2, n_steps: int = 3,
                 pool: bool = False, dropout: float = 0.1, warmup_steps: int = 200):
        super().__init__()
        self.legacy = legacy
        self.in_dim = in_dim
        self.out_dim = out_dim
        self.pool = pool
        self.hidden = hidden
        self.n_steps = n_steps

        # 多架构专家：按 EXPERT_CYCLE 轮转，保证每种架构至少出现一次
        self.experts = nn.ModuleList([
            build_expert(EXPERT_CYCLE[i % len(EXPERT_CYCLE)], hidden, 1, dropout)
            for i in range(n_experts)])
        # 层次两级门控 + 稠密融合（见 blocks.py 的稀疏塌缩复盘）
        self.router = HierarchicalRouter(n_experts, hidden, n_groups=n_groups,
                                         top_k=2, n_shared=0, warmup_steps=warmup_steps)
        # 深度主干：用户要求的「≥6 层」
        self.trunk = nn.ModuleList([ResidualMLPBlock(hidden, dropout=dropout)
                                    for _ in range(max(1, n_layers))])
        self.in_proj = nn.Linear(in_dim, hidden)
        self.in_norm = nn.LayerNorm(hidden)
        self.out_norm = nn.LayerNorm(hidden)

        # 输出头：**保持与原模型相同的形状**，Java 侧契约零改动
        if pool:
            self.head = nn.Sequential(
                nn.Linear(hidden, hidden), nn.LayerNorm(hidden), nn.GELU(),
                nn.Dropout(dropout), nn.Linear(hidden, out_dim))
        else:
            self.head = nn.Sequential(
                nn.Linear(hidden, hidden), nn.LayerNorm(hidden), nn.GELU(),
                nn.Dropout(dropout), nn.Linear(hidden, out_dim))
        # 后续步骤预测头
        self.predictor = NextStepHead(hidden, n_steps=n_steps)

    # ------------------------------------------------------------------
    def _legacy_forward(self, x: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
        """跑原模型并把输出统一成 ``[B,N,H]``（特征维）以便与专家池拼接。

        ⚠️ 原模型可能是三种形态之一，这里逐一处理且**都不过度假设**：
          ① 返回 ``[B,N]``      逐行打分 → 视作 ``[B,N,1]``
          ② 返回 ``[B,N,K]``    逐行向量
          ③ 返回 ``[B,D]`` / ``[B,1]``  实例级 → 广播回每个节点
          ④ 返回 tuple/list     取第一项后按 ①~③ 处理
        """
        try:
            out = self.legacy(x, mask)
        except TypeError:
            out = self.legacy(x)
        if isinstance(out, (tuple, list)):
            out = out[0]
        if out.dim() == 1:
            out = out.unsqueeze(-1)
        if out.dim() == 2:
            # [B,N] → [B,N,1]；[B,D] → [B,1,D] 再广播
            if out.shape[1] == x.shape[1]:
                out = out.unsqueeze(-1)
            else:
                out = out.unsqueeze(1).expand(-1, x.shape[1], -1)
        if out.shape[-1] != self.hidden:
            out = self._adapt(out)
        return out

    def _adapt(self, out: torch.Tensor) -> torch.Tensor:
        """把任意维数的原输出投到 ``hidden`` 维（1×1 卷积语义，全局逐行）。"""
        d = out.shape[-1]
        if not hasattr(self, "_adapt_lin") or self._adapt_lin.in_features != d:
            self._adapt_lin = nn.Linear(d, self.hidden).to(out.device)
        return self._adapt_lin(out)

    def forward(self, x: torch.Tensor, mask: torch.Tensor):
        """返回 (主输出, 后续步骤预测 ``[B,n_steps]``)。

        ``pool=True`` 时主输出是 ``[B,out_dim]``；否则 ``[B,N,out_dim]``（out_dim=1 时给 ``[B,N]``）。
        """
        b, n, _ = x.shape
        h0 = self.in_norm(self.in_proj(x))                     # [B,N,H]

        # ① 原模型作为「共享专家」——保留它原有的全部能力
        legacy = self._legacy_forward(x, mask)
        # ② 多架构专家：稠密融合（每个专家都算、都拿梯度）
        w, top_i, _ = self.router(h0)
        acc = torch.zeros_like(h0)
        for i, ex in enumerate(self.experts):
            acc = acc + w[..., i].unsqueeze(-1) * ex(h0, None, mask)
        # ③ 原模型输出与专家池融合（相加后过 LayerNorm，避免量纲打架）
        h = self.in_norm(h0 + acc + legacy)
        # ④ 深度主干（≥6 层）
        for blk in self.trunk:
            h = blk(h)
        h = self.out_norm(h)
        if self.training:
            self._last_idx = top_i.detach()

        if self.pool:
            m = mask.unsqueeze(-1)
            pooled = (h * m).sum(dim=1) / m.sum(dim=1).clamp(min=1.0)
            out = self.head(pooled)
            nxt = self.predictor(pooled)
        else:
            out = self.head(h)                                   # [B,N,out_dim]
            # ⚠️ 这里 out 已知是 3 维 [B,N,D]，所以掩码直接补成 [B,N,1] ——
            #    用「按 out.dim() 差值补前导 1」的通用写法时，mask.dim() 也是 2，
            #    pad = 0，于是原样返回 [B,N] 与 [B,N,1] 广播成 [B,B,N] 而报
            #    "size of tensor a (N) must match the size of tensor b (B*N)"。
            if mask is not None:
                out = out * mask.unsqueeze(-1)
            m = mask.unsqueeze(-1)
            pooled = (h * m).sum(dim=1) / m.sum(dim=1).clamp(min=1.0)
            nxt = self.predictor(pooled)
        if not self.pool and self.out_dim == 1:
            out = out.squeeze(-1)                                # [B,N] 与原模型一致
        return out, nxt

    # ------------------------------------------------------------------
    def load_legacy_state(self, state: dict) -> tuple:
        """把旧权重灌进 ``legacy`` 子模块。返回 (missing, unexpected)。"""
        target = {k[len("legacy."):]: v for k, v in self.state_dict().items()
                  if k.startswith("legacy.")}
        missing, unexpected = self.legacy.load_state_dict(state, strict=False)
        return missing, unexpected

    def depth(self) -> int:
        """实际深度（主干层数 + 输入/输出投影）。"""
        return len(self.trunk) + 2

    def expert_usage(self) -> dict:
        return self.router.expert_usage()

    def load_balance_loss(self) -> torch.Tensor:
        return self.router.load_balance_loss()

    @torch.no_grad()
    def update_router_bias(self, rate: float = 0.02):
        idx = getattr(self, "_last_idx", None)
        if idx is not None:
            self.router.update_bias(idx, rate)


class LegacyShim(nn.Module):
    """训练期**并行跑原模型**的辅助包装：让旧训练脚本能继续算 loss。

    用途：升级后做「同输入下新旧对照」——若新模型的主输出与原模型
    在未训练时差异过大，说明适配层接错了，必须先查清再训。
    """

    def __init__(self, legacy: nn.Module, feat: int, pad: int, mask_mode: str = "ones"):
        super().__init__()
        self.legacy = legacy
        self.feat = feat
        self.pad = pad
        self.mask_mode = mask_mode

    def forward(self, x: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
        out = self.legacy(x, mask)
        if isinstance(out, (tuple, list)):
            out = out[0]
        if out.dim() == 3 and out.shape[1] != x.shape[1]:
            out = out[:, :x.shape[1]]
        return out
