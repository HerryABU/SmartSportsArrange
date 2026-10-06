"""专项 MoE：**就地换编码器**与**就地增强表征**两种升级方式的宿主。

它把「一个可训练的主干」与「层次化门控 + 专家池」装配在一起，是全部 16 个在役
小模型升级后的共同形态。
"""

from __future__ import annotations

import math
from typing import List, Optional, Tuple

import torch
import torch.nn as nn
import torch.nn.functional as F

from .base import ResidualMLPBlock
from .router import HierarchicalRouter
from .experts_registry import EXPERT_CYCLE, build_expert
from .heads import NextStepHead


# ---------------------------------------------------------------------------
# 组装：专项 MoE 的通用骨架
# ---------------------------------------------------------------------------
class SpecialistMoE(nn.Module):
    """专项 MoE 骨架：多架构专家 + 层次两级门控 + 共享专家 + 序列预测头。

    这是**小模型**的统一形态：给定「候选集」逐行打分，输出建议分数；
    调用方再叠加自己的红线过滤。

    ==================  ======================================================
    ``n_layers``        融合后的**专家链深度**（每层一个残差块），
                        用户要求「至少 6 层」即指此
    ``n_experts``       稀疏专家数（按 :data:`EXPERT_CYCLE` 轮转分配架构）
    ``n_groups``        粗路由的组数
    ``top_k``           细路由每行激活几个专家
    ``n_shared``        恒定激活的共享专家数（隔离通用知识）
    ==================  ======================================================

    **深度为什么放在融合之后而不是堆专家**：
    堆专家是「宽」（并行分支），加深是「深」（串行）。用户要求的是
    「更多层 + 多层感知机 + 多层复合」，所以这里两者都要 ——
    宽度由 MoE 提供，深度由 :class:`FusionTrunk` 提供。
    """

    def __init__(self, in_dim: int, hidden: int = 128, n_layers: int = 6,
                 n_experts: int = 6, n_groups: int = 3, top_k: int = 2,
                 n_shared: int = 2, n_edges: int = 1, dropout: float = 0.1,
                 n_steps: int = 3, warmup_steps: int = 200):
        super().__init__()
        self.in_proj = nn.Linear(in_dim, hidden)
        self.in_norm = nn.LayerNorm(hidden)
        # 多架构专家（轮转保证多样性）
        self.experts = nn.ModuleList([
            build_expert(EXPERT_CYCLE[i % len(EXPERT_CYCLE)], hidden, n_edges, dropout)
            for i in range(n_experts)])
        # ⚠️ 路由器只管**稀疏专家**（n_experts 个），共享专家**不进路由器**。
        #    早前误传 `n_experts + n_shared`：路由器认为有 8 个专家，
        #    而 forward 只循环 len(self.experts)=6 个 —— 末两位专家永远没人消费，
        #    既拿不到梯度也不进负载统计，表现为「激活专家数永远少 2 个」且查不出原因。
        self.router = HierarchicalRouter(n_experts, hidden,
                                         n_groups=n_groups, top_k=top_k,
                                         n_shared=n_shared,
                                         warmup_steps=warmup_steps)
        # 共享专家：恒定激活（不进路由器竞争）
        self.shared = nn.ModuleList([ResidualMLPBlock(hidden, dropout=dropout)
                                     for _ in range(max(0, n_shared))])
        # 深度主干（用户要求的「≥6 层」在这里兑现）
        self.trunk = nn.ModuleList([ResidualMLPBlock(hidden, dropout=dropout)
                                    for _ in range(max(1, n_layers))])
        self.out_norm = nn.LayerNorm(hidden)
        self.score = nn.Linear(hidden, 1)
        self.predictor = NextStepHead(hidden, n_steps=n_steps)
        self.hidden = hidden

    def encode(self, x: torch.Tensor, mask: torch.Tensor,
               adj: Optional[torch.Tensor] = None) -> torch.Tensor:
        """只取**融合后的逐行表征** ``[B,N,H]``（不给分数、不给步骤预测）。

        供「把本骨架当作别人家的一个专家」时使用 —— 主 MoE
        （:class:`sports_ai.models.super_moe.NestMoEExpert`）需要的是
        专家输出而不是它内部的建议分数，所以必须留一个「只到表征就停」的入口。
        逻辑与 :meth:`forward` 逐行一致（专家融合 → 共享专家 → 深度主干 → 归一化）。
        """
        h = self.in_norm(self.in_proj(x))
        weights, top_i, _ = self.router(h)
        acc = torch.zeros_like(h)
        for e, ex in enumerate(self.experts):
            acc = acc + weights[..., e].unsqueeze(-1) * ex(h, adj, mask)
        out = acc
        for sh in self.shared:
            out = out + sh(h)
        for blk in self.trunk:
            out = blk(out)
        out = self.out_norm(out)
        # ⚠️ **必须再掩一次**：残差主干是「x + f(x)」，padding 位的 x 虽为 0，
        #    但 LayerNorm(0) 会算出 0/√ε 的非零偏置、后续块再逐层放大。
        #    实测 encode() 在 mask=[1,1,1,1,0,0,0,0] 下 padding 位 absmax 达 4.14。
        #    主 MoE 里 padding 位随后会进池化与 loss，把假节点的梯度灌进真节点 ——
        #    典型「形状没错、结果静默变差」。
        if mask is not None:
            out = out * mask.unsqueeze(-1)
        if self.training:
            self._last_idx = top_i.detach()
        return out

    def forward(self, x: torch.Tensor, mask: torch.Tensor,
                adj: Optional[torch.Tensor] = None) -> Tuple[torch.Tensor, torch.Tensor]:
        """返回 (逐行建议分数 [B,N]、后续步骤预测 [B,n_steps])。"""
        h = self.in_norm(self.in_proj(x))                    # [B,N,H]
        weights, top_i, _ = self.router(h)                    # 层次两级门控
        # 稠密融合：每个专家都算，按门控权重加权（不再有「未选中就不算」的稀疏路径）
        acc = torch.zeros_like(h)
        for e, ex in enumerate(self.experts):
            acc = acc + weights[..., e].unsqueeze(-1) * ex(h, adj, mask)
        out = acc
        # 共享专家：恒定激活
        for sh in self.shared:
            out = out + sh(h)
        # 深度主干
        for blk in self.trunk:
            out = blk(out)
        out = self.out_norm(out)
        # ⚠️ 同 encode()：残差主干会把 padding 位带活，必须在池化**之前**掩掉，
        #    否则 pooled（后续步骤预测的输入）被假节点污染。
        if mask is not None:
            out = out * mask.unsqueeze(-1)
        score = self.score(out).squeeze(-1)
        if mask is not None:
            score = score * mask
        # 池化出实例级表征，供后续步骤预测头使用
        m = mask.unsqueeze(-1) if mask is not None else torch.ones_like(out[..., :1])
        pooled = (out * m).sum(dim=1) / m.sum(dim=1).clamp(min=1.0)
        nxt = self.predictor(pooled)
        self._last_next = nxt.detach()
        # ⚠️ 训练期要用它更新路由偏置：必须在 forward 里**记下本批的 top_i**。
        #    早前把 update_bias 写成占位（拿一个恒为 None 的 _last_idx），
        #    等于「无辅助损失均衡」整条链路从未通电 —— 典型的接线正确但从未生效。
        if self.training:
            self._last_idx = top_i.detach()
        return score, nxt

    @property
    def next_value(self) -> Optional[torch.Tensor]:
        """最近一次 :meth:`forward` 的「后续步骤预测」（实例级 ``[B,n_steps]``）。

        嵌套进主 MoE 时，主模型要读各专家的步骤预测做汇总 ——
        它不是一个前向参数，而是 forward 的副产物，所以走属性而非返回值。
        """
        return getattr(self, "_last_next", None)

    def load_balance_loss(self) -> torch.Tensor:
        """辅助负载均衡损失（小系数加到主损失，见 :meth:`HierarchicalRouter.load_balance_loss`）。"""
        return self.router.load_balance_loss()

    @torch.no_grad()
    def update_router_bias(self, rate: float = 0.02):
        """训练期调用：按激活量更新路由偏置（DeepSeek-V3 式无辅助损失均衡）。"""
        idx = getattr(self, "_last_idx", None)
        if idx is None:
            return
        self.router.update_bias(idx, rate)

    def expert_usage(self) -> dict:
        """专家使用率（可观测；**专家「建了但从不被选中」是 MoE 最常见的隐性失败**）。"""
        return self.router.expert_usage()

    def depth(self) -> int:
        """实际深度（专家链层数 + 输入/输出投影），供日志与预算核算。"""
        return len(self.trunk) + 2
