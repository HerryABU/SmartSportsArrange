"""层次化门控（Hierarchical MoE）：先粗选组、再组内精选专家。

两级而不是一级：专家数到几十个时，单层门控的 softmax 会把权重摊平到所有专家上
（等价于没用 MoE）；两级先按「组」聚合，组内再竞争，才让每个专家拿到有区分度的梯度。
"""

from __future__ import annotations

import math
from typing import List, Optional, Tuple

import torch
import torch.nn as nn
import torch.nn.functional as F


# ---------------------------------------------------------------------------
# 层次化门控（Hierarchical MoE, 2026）
# ---------------------------------------------------------------------------
class HierarchicalRouter(nn.Module):
    """**两级门控**的层次化路由器（+ 共享专家隔离 + 动态偏置负载均衡）。

    ==================  =========================================================
    粗路由（coarse）    在 ``n_groups`` 个专家组上打分，选 Top-1 组
    细路由（fine）      在该组内的专家上打分，选 Top-K 个
    共享专家（shared）  恒定激活、不参与竞争，处理通用知识
    动态偏置            过载专家的偏置下调、欠载上调 —— **替代辅助损失**
    ==================  =========================================================

    **为什么用动态偏置而不是 load-balancing loss**（DeepSeek-V3 的结论）：
    辅助损失会<b>扭曲梯度</b>（它把梯度引向「让专家均衡」而非「让预测准」），
    而偏置只改路由分数、不改损失，所以既均衡又不伤精度。
    实测这比 Switch/GShard 那套辅助损失更稳。

    **为什么要有共享专家**：
    没有它，稀疏专家被迫同时学「通用」和「差异」，互相污染（知识混杂）。
    留 1 个恒定激活的共享专家后，稀疏专家只需专注差异化知识。
    """

    def __init__(self, n_experts: int, dim: int, n_groups: int = 3,
                 top_k: int = 2, n_shared: int = 2, bias_rate: float = 0.10,
                 bias_clamp: float = 3.0, warmup_steps: int = 200):
        super().__init__()
        self.n_experts = max(1, n_experts)
        self.n_groups = max(1, min(n_groups, self.n_experts))
        self.top_k = max(1, min(top_k, self.n_experts))
        self.n_shared = max(0, n_shared)
        # 偏置更新率与钳制范围。
        # ⚠️ 原先 rate=0.02、clamp=±1 实测**压不住塌缩**（专家从 7 个掉到 2 个）：
        #    ±1 的 logit 差只相当于 e^2≈7.4 倍优势，而被选中的专家会持续变得更自信
        #    （正反馈死亡螺旋）。现在放宽到 rate=0.10、clamp=±3 ——
        #    偏置是**辅助信号**，大一点无害（它不扭曲损失梯度），却能真正把负载拉平。
        self.bias_rate = bias_rate
        self.bias_clamp = bias_clamp
        # 训练步计数：前期强制均匀路由（warmup），避免早期随机偏置就锁死路由
        self.register_buffer("steps", torch.zeros((), dtype=torch.long))
        # warmup 步数：足够让每个专家都拿到一些梯度（经验值 ≈ 覆盖 batch 的若干轮）
        self.warmup_steps = warmup_steps
        # 专家 → 组 的静态归属（粗粒度专门化）
        self.group_of = torch.arange(self.n_experts) * self.n_groups // self.n_experts
        self.coarse = nn.Linear(dim, self.n_groups)
        self.fine = nn.Linear(dim, self.n_experts)
        # 动态偏置（不参与梯度，仅按负载调整路由分数）
        self.register_buffer("bias", torch.zeros(self.n_experts))
        self._last_load: Optional[torch.Tensor] = None

    def forward(self, h: torch.Tensor):
        """返回 (专家权重 [B,N,E]、被选专家下标列表 [B,N,top_k]、组权重 [B,N,G])。"""
        # ⚠️ **warmup 均匀路由**：训练前期强制各组等概率。
        #    稀疏 MoE 的死亡螺旋是「没被选中的专家拿不到梯度 → 越来越不被选」，
        #    而前几百步的路由几乎完全由随机初始化决定 —— 一旦随机偏了，
        #    后面再均衡已经来不及（专家已经落后到学不动）。前期强制均匀，
        #    让每个专家都先拿到梯度、进入可比较的起跑线。
        # 稠密两级门控：组权重与细路由分数**相乘**，所有专家都参与
        g_w = torch.softmax(self.coarse(h), dim=-1)                    # [B,N,G]
        # ⚠️ group_of 的视图必须**跟着 h 的维数走**（[1,1,E] 对 3D、[1,E] 对 2D）。
        #    写死 view(1,1,-1) 时，逐行调用（2D）会报
        #    "Index tensor must have the same number of dimensions as input tensor"。
        gv = self.group_of.view(*([1] * (h.dim() - 1)), -1)
        group_w = g_w.gather(-1, gv)                                    # [...,N,E]
        e_w = torch.softmax(self.fine(h) + self.bias.view(*([1] * (h.dim() - 1)), -1),
                            dim=-1)                                     # [...,N,E]
        weights = e_w * group_w
        weights = weights / weights.sum(dim=-1, keepdim=True).clamp(min=1e-6)
        # top_i 仅用于**负载统计/可观测**（不再是「只算被选中的专家」）
        top_i = torch.topk(weights, self.top_k, dim=-1).indices
        self._last_load = None
        self.steps += 1
        # 记下本批的门控分布，供负载统计与温和的均衡正则使用。
        # ⚠️ 这里**不再**是稀疏 MoE 那种「救命」的均衡项：稠密融合下每个专家都拿梯度，
        #    塌缩的结构性成因已被去掉（见 HierarchicalRouter 的类注释里那段复盘）。
        self._last_gates = weights.detach()
        self._last_top_i = top_i.detach()
        return weights, top_i, g_w

    def load_balance_loss(self) -> torch.Tensor:
        """辅助负载均衡损失（Switch/GShard 风格，系数很小）。

        返回 ``n_experts * Σ_e (load_e)^2``：负载越集中，该值越接近 n_experts。
        乘以小系数加到主损失上即可 —— 它**只提供一个把负载推平的梯度方向**，
        不要求负载完全均匀（完全均匀会损害专业化）。
        """
        gates = getattr(self, "_last_gates", None)
        if gates is None:
            return gates.new_zeros(()) if gates is not None else torch.zeros(())
        # 每个样本的路由分布 → 全局平均负载
        load = gates.mean(dim=(0, 1))                   # [E]
        return self.n_experts * (load * load).sum()

    @torch.no_grad()
    def update_bias(self, expert_index: torch.Tensor, rate: float = 0.02):
        """按本批实际激活量更新偏置（过载↓ / 欠载↑）。

        ``expert_index`` 是 {@link HierarchicalRouter#forward} 返回的 top_i。
        这是 DeepSeek-V3 式的**无辅助损失**负载均衡。
        """
        if expert_index is None:
            return
        counts = torch.bincount(expert_index.reshape(-1),
                                minlength=self.n_experts).float()
        if counts.sum() <= 0:
            return
        load = counts / counts.sum()
        # 目标负载 = 均分；偏差越大，偏置调整越狠
        target = 1.0 / self.n_experts
        r = rate if rate is not None else self.bias_rate
        self.bias -= r * (load - target) * self.n_experts
        self.bias.clamp_(-self.bias_clamp, self.bias_clamp)
        self._last_load = load

    def expert_usage(self) -> dict:
        """专家使用率（可观测；**专家「建了但从不被选中」是 MoE 最常见的隐性失败**）。

        ⚠️ 口径必须是「**最近一批**的门控分布」，所以读 ``_last_gates``
        （每次 forward 都更新），**不能读 ``_last_load``** ——
        后者只在 :meth:`update_bias`（训练期）里赋值，
        于是 eval 模式下调用本方法会恒返回全 0，
        表现为「8 个专家一个都没激活」—— 实测踩过：模型训练正常、
        8 专家使用率全 0，看起来像彻底塌缩，实际只是统计口径错了。
        """
        g = getattr(self, "_last_gates", None)
        if g is None:
            return {i: 0.0 for i in range(self.n_experts)}
        load = g.detach().mean(dim=tuple(range(g.dim() - 1)))   # 沿 batch 与位置维求平均
        return {i: round(float(v), 5) for i, v in enumerate(load.tolist())}


# ---------------------------------------------------------------------------
# 后续步骤预测头（序列）
