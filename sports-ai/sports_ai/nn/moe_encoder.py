"""把生成式链路的编码器升级为专项 MoE（接口与 :class:`GnnEncoder` 完全一致）。

## 为什么这四个模型走「就地替换编码器」而不是外部适配器

生成式四个模型（``SchemeGenerator`` / ``SchemeRefiner`` / ``SchemeDiscriminator``
/ ``SchemeDiffusion``）与前面那些小模型有一个关键差别：
**它们吃多个额外输入**（``z`` 噪声 / ``forbid`` 禁止掩码 / ``init_logits`` / ``scheme``），
而且 ONNX 输入契约是 Java 侧 `AdversarialSchemeService` 硬编码的五个具名张量。
外部适配器要包住它们，就得把「哪些参数怎么传」再描述一遍，等于给每个模型额外维护一份签名表。

而它们的共同点是**都基于同一个编码器** ``GnnEncoder(in_dim, hidden) → [B,N,hidden]``。
所以这里只替换**编码器**：

    self.enc = GnnEncoder(node_feat, hidden)   →   self.enc = MoEEncoder(node_feat, hidden)

好处是三件事**同时成立**：
* 前向签名不变（``(node_feat, adj, mask)``）；
* 输出形状不变（``[B,N,hidden]``）；
* 各训练脚本的 loss 与数据流**一行都不用改**。

等价于「把生成式模型的内部表征层从单路图卷积升级成 9 架构混合专家」，
而对外契约零改动。
"""

from __future__ import annotations

from typing import Optional

import torch
import torch.nn as nn

from sports_ai.nn.blocks import (EXPERT_CYCLE, HierarchicalRouter, ResidualMLPBlock,
                                 build_expert)
from sports_ai.generative.encoder import GnnEncoder, normalized_adj


class MoEEncoder(nn.Module):
    """多架构混合专家编码器（**接口与 GnnEncoder 逐位一致**）。

    ====================  ====================================================
    ``in_dim``           输入节点特征维数
    ``hidden``           隐层维数（也是输出维数，与 GnnEncoder 相同）
    ``n_experts``        稀疏专家数（按 :data:`EXPERT_CYCLE` 轮转分配架构）
    ``n_layers``         融合后的主干层数（用户要求的「≥6 层」在此兑现）
    ``legacy``           原 ``GnnEncoder``：作为**共享专家**保留其全部原能力
    ====================  ====================================================

    ## 为什么必须保留 legacy 作为共享专家

    原 ``GnnEncoder`` 用的是 ``GraphConvBlock`` + **Jumping Knowledge**
    （把每一层的表征拼接后投影）—— 那是这套生成式模型**已被训练验证过**的表征方式。
    直接丢掉换成就地重训，等于把之前所有训练成果清零，还要更长预算才追平。
    保留它当共享专家后，新专家只需学「它漏掉的那部分」，收敛更快也更稳。

    ## 三种输入形态都要能接受

    ``node_feat`` 是 ``[B,N,F]``，``adj`` 在生成式链路里是 **单通道** ``[B,N,N]``
    （不是主 MoE 的 ``[B,E,N,N]``），``mask`` 是 ``[B,N]``。
    图卷积专家按 ``[B,N,N]`` 走，与 :meth:`GraphConvExpert.forward` 的口径一致。
    """

    def __init__(self, in_dim: int, hidden: int = 64, n_layers: int = 6,
                 n_experts: int = 9, n_groups: int = 3, dropout: float = 0.0,
                 legacy: Optional[nn.Module] = None, warmup_steps: int = 200):
        super().__init__()
        self.hidden = hidden
        # 原编码器作为共享专家（恒定激活，不参与门控竞争）
        self.legacy = legacy if legacy is not None else GnnEncoder(in_dim, hidden)
        # 多架构专家池
        self.experts = nn.ModuleList([
            build_expert(EXPERT_CYCLE[i % len(EXPERT_CYCLE)], hidden, 1, dropout)
            for i in range(max(1, n_experts))])
        self.router = HierarchicalRouter(len(self.experts), hidden, n_groups=n_groups,
                                         top_k=2, n_shared=0, warmup_steps=warmup_steps)
        self.in_proj = nn.Linear(in_dim, hidden)
        self.in_norm = nn.LayerNorm(hidden)
        # 融合后主干（≥6 层）
        self.trunk = nn.ModuleList([ResidualMLPBlock(hidden, dropout=dropout)
                                    for _ in range(max(1, n_layers))])
        self.out_norm = nn.LayerNorm(hidden)

    def forward(self, node_feat: torch.Tensor, adj: torch.Tensor,
                mask: torch.Tensor) -> torch.Tensor:
        """``node_feat [B,N,F]`` / ``adj [B,N,N]`` / ``mask [B,N]`` → ``[B,N,hidden]``。"""
        a_norm = normalized_adj(adj, mask)
        h0 = torch.relu(self.in_norm(self.in_proj(node_feat))) * mask.unsqueeze(-1)
        # ① 原编码器（共享专家）
        base = self.legacy(node_feat, adj, mask)
        # ② 多架构专家稠密融合
        w, top_i, _ = self.router(h0)
        acc = torch.zeros_like(h0)
        for i, ex in enumerate(self.experts):
            acc = acc + w[..., i].unsqueeze(-1) * ex(h0, a_norm, mask)
        # ③ 融合 + 主干
        h = self.out_norm(h0 + base + acc)
        for blk in self.trunk:
            h = blk(h)
        h = h * mask.unsqueeze(-1)
        if self.training:
            self._last_idx = top_i.detach()
        return h

    # ------------------------------------------------------------------
    def load_balance_loss(self) -> torch.Tensor:
        return self.router.load_balance_loss()

    @torch.no_grad()
    def update_router_bias(self, rate: float = 0.02):
        """训练期调用：按激活量更新路由偏置（无辅助损失均衡）。"""
        idx = getattr(self, "_last_idx", None)
        if idx is not None:
            self.router.update_bias(idx, rate)

    def expert_usage(self) -> dict:
        return self.router.expert_usage()

    def depth(self) -> int:
        return len(self.trunk) + 2


def make_moe_encoder(in_dim: int, hidden: int, n_layers: int = 6,
                     n_experts: int = 9, **kw) -> MoEEncoder:
    """构造 :class:`MoEEncoder`，**并把原 GnnEncoder 作为共享专家灌进去**。

    这是生成式四个模型类 `__init__` 里的统一入口 ——
    ``moe=True`` 时它们都走这条路径，于是「升级」在四个模型上是同一段代码，
    不存在「改了这个忘了那个」。
    """
    return MoEEncoder(in_dim, hidden, n_layers=n_layers, n_experts=n_experts,
                      legacy=GnnEncoder(in_dim, hidden), **kw)


def upgrade_encoder(module: nn.Module, attr: str = "enc", n_layers: int = 6,
                    n_experts: int = 9, **kw) -> MoEEncoder:
    """把一个生成式模型里的 ``enc`` 就地换成 :class:`MoEEncoder`。

    **旧权重自动迁移**：原 ``GnnEncoder`` 被作为共享专家保留，
    所以它的 ``enc.*`` 权重原样搬进 ``enc.legacy.*``，
    其余（专家池 / 门控 / 主干）是新初始化的 ——
    这样重训是从「原有能力」起步，而不是从零开始。
    """
    old = getattr(module, attr)
    in_dim = old.proj.in_features
    hidden = old.proj.out_features
    new = MoEEncoder(in_dim, hidden, n_layers=n_layers, n_experts=n_experts,
                     legacy=old, **kw)
    setattr(module, attr, new)
    return new
