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
                 pool: bool = False, dropout: float = 0.1, warmup_steps: int = 200,
                 legacy_mode: str = "pair", n_types: int = 1,
                 flat_input: bool = False):
        super().__init__()
        self.flat_input = flat_input
        # legacy_mode 决定怎么调原模型。实测小模型有**两种前向签名**：
        #   "pair"  forward(x, mask)                     —— lane_advisor
        #   "gnn"   forward(x, adj_by_type, type_mask, mask) —— referee_gnn/teacher_gnn/tournament_gnn
        # 早前只按 (x, mask) 调，撞上 GNN 时报 "takes 3 positional arguments but 4 were given"。
        self.legacy_mode = legacy_mode
        self.legacy = legacy
        # 原模型输出维数：构造期探一次，据此建好投影层（不惰性创建，见 _adapt 注释）
        self.n_types = n_types
        self._legacy_out_dim = (self._probe_flat_out_dim(legacy, in_dim)
                                if flat_input
                                else self._probe_out_dim(legacy, in_dim, legacy_mode, n_types))
        # ⚠️ **构造期就建好**，不能惰性创建：
        #    惰性版（第一次 forward 才建）会让「训练过的权重」里带 `_adapt_lin.*`，
        #    而导出时新建的实例还没跑过前向、结构里没有该层 →
        #    load_state_dict 报 "Unexpected key(s): _adapt_lin.weight, _adapt_lin.bias"。
        #    凡是会进 state_dict 的层，都必须在 __init__ 里建。
        self._adapt_lin = nn.Linear(self._legacy_out_dim, hidden) \
            if self._legacy_out_dim != hidden else None
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

    @staticmethod
    def _probe_flat_out_dim(legacy: nn.Module, in_dim: int) -> int:
        """探实例级原模型（吃 ``[B,F]``）的输出维数。"""
        was = legacy.training
        legacy.eval()
        try:
            with torch.no_grad():
                out = legacy(torch.zeros(2, in_dim))
            if isinstance(out, (tuple, list)):
                out = out[0]
            return int(out.reshape(out.shape[0], -1).shape[-1])
        except Exception:
            return 1
        finally:
            legacy.train(was)

    @staticmethod
    def _probe_out_dim(legacy: nn.Module, in_dim: int, legacy_mode: str,
                       n_types: int = 1) -> int:
        """探一次原模型的**特征维**（构造期做，一次前向的代价可接受）。

        用**两个不同节点数**分别探，靠「最后一维是否随节点数变化」区分：

        ==========================  ==================  ==============
        原输出形态                  探针 (N=4)          探针 (N=7)
        ==========================  ==================  ==============
        ``[B,N]``（逐行打分）        末维 = 4（= N）    末维 = 7（= N）
        ``[B,N,K]`` / ``[B,K]``     末维 = K（不变）    末维 = K（不变）
        ==========================  ==================  ==============

        ⚠️ **只探一次会错**：早前固定用 N=4 探 ``lane_advisor``，
        它的输出是 ``[B,N]``（末维就是节点数），于是被判成「特征维 = 4」，
        真实推理时 N=20 → 投影层要吃 20 维而它只建了 4 维，
        报 "mat1 and mat2 shapes cannot be multiplied (20x1 and 4x128)"。
        两次不同 N 相减才能把「N」与「K」分开。
        """
        def once(n: int):
            was = legacy.training
            legacy.eval()
            try:
                x = torch.zeros(1, n, in_dim)
                m = torch.ones(1, n)
                with torch.no_grad():
                    if legacy_mode == "gnn3":
                        out = legacy(x, torch.eye(n).expand(1, n, n), m)
                    elif legacy_mode == "gnn":
                        # ⚠️ 必须给 **4 维** [B,E,N,N]：RefereeGnn 内部
                        # torch.bmm(a, h) 要求邻接是 3 维而 a 是 [B,N,N]，
                        # 传 [1,N,N]（少一个边型维）会报 "batch1 must be a 3D tensor"。
                        # ⚠️ n_types 必须用模型**自己的**边型数（RefereeGnn 是 4），
                        #    给 1 会在内部索引 rel[3] 时报
                        #    "index 3 is out of bounds for dimension 1 with size 1"。
                        adj = torch.eye(n).expand(1, n_types, n, n)
                        out = legacy(x, adj, torch.ones(1, n_types), m)
                    else:
                        try:
                            out = legacy(x, m)
                        except TypeError:
                            out = legacy(x)
                if isinstance(out, (tuple, list)):
                    out = out[0]
                # ① 末维 == 节点数 → [B,N]（逐行打分，特征维为 1）
                if out.dim() >= 2 and out.shape[-1] == n:
                    return 1
                # ② 已经是 [B,K]（实例级，K 与 N 无关）→ 直接取 K。
                #    ⚠️ 不能先 reshape：TournamentGnn 返回 [B,3]，
                #    对 N=7 去 reshape(1,7,-1) 会因 3 不能整除而抛异常，
                #    异常被吞后回退到 in_dim=14 —— 探成 14，
                #    于是投影层建了 14 维，真实输入是 3 维，
                #    报 "mat1 and mat2 shapes cannot be multiplied (56x3 and 14x128)"。
                if out.dim() == 2:
                    return int(out.shape[-1])
                out = out.reshape(out.shape[0], n, -1)
                return int(out.shape[-1])
            except Exception:
                return None
            finally:
                legacy.train(was)

        d4, d7 = once(4), once(7)
        if d4 is None:
            return in_dim
        if d7 is not None and d7 != d4:
            return d4                      # 末维随 N 变 → 是 [B,N]，特征维取 1
        return d4

    # ------------------------------------------------------------------
    def _legacy_forward(self, x: torch.Tensor, mask: torch.Tensor,
                        adj: Optional[torch.Tensor] = None,
                        type_mask: Optional[torch.Tensor] = None) -> torch.Tensor:
        """跑原模型并把输出统一成 ``[B,N,H]``（特征维）以便与专家池拼接。

        ⚠️ 原模型可能是三种形态之一，这里逐一处理且**都不过度假设**：
          ① 返回 ``[B,N]``      逐行打分 → 视作 ``[B,N,1]``
          ② 返回 ``[B,N,K]``    逐行向量
          ③ 返回 ``[B,D]`` / ``[B,1]``  实例级 → 广播回每个节点
          ④ 返回 tuple/list     取第一项后按 ①~③ 处理
        """
        if self.flat_input:
            # 实例级原模型：先把 [B,N,F] 压成 [B,F] 再喂（见 _pooled_legacy）
            out = self._pooled_legacy(x, mask, adj, type_mask)
        elif self.legacy_mode in ("gnn", "gnn3"):
            # GNN 类：邻接缺失时造一个「自聚合」的单位邻接，
            # 不能传 None（模型内部会直接解引用）。
            if adj is None:
                b, n = x.shape[0], x.shape[1]
                adj = torch.eye(n, device=x.device, dtype=x.dtype).expand(b, self.n_types, n, n)
            if self.legacy_mode == "gnn3":
                # ConflictGnn 的签名是 (node_feat, adj, mask) —— **没有 type_mask**。
                # 硬套四参会报 "takes 4 positional arguments but 5 were given"
                # （含 self）。它的邻接是**已压平的 [B,N,N]**，多传一维会静默算错。
                a = adj.sum(dim=1) if adj.dim() == 4 else adj
                out = self.legacy(x, a, mask)
            else:
                if type_mask is None:
                    type_mask = torch.ones(adj.shape[0], adj.shape[1],
                                           device=x.device, dtype=x.dtype)
                out = self.legacy(x, adj, type_mask, mask)
        else:
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
        """把任意维数的原输出投到 ``hidden`` 维。

        ⚠️ 早前是**惰性创建**（第一次 forward 时才建 ``_adapt_lin``），
        后果是：训练能跑、但 ``state_dict`` 里没这个层 →
        导出时 ``load_state_dict`` 报 "Unexpected key(s): _adapt_lin.weight"，
        或者反向的 "Missing key(s)"。
        这是「惰性建模块」这一类写法的通病：**参数不在结构里，存档/加载/导出全都对不上**。
        现在改成构造期建好（输入维数由 legacy 的实际输出决定）。
        """
        if self._adapt_lin is None:
            return out          # 维数天然一致，无需投影
        return self._adapt_lin(out)

    def _pooled_legacy(self, x: torch.Tensor, mask: torch.Tensor,
                       adj: Optional[torch.Tensor] = None,
                       type_mask: Optional[torch.Tensor] = None) -> torch.Tensor:
        """原模型吃的是 **2 维** ``[B,F]``（实例级，没有节点维）。

        这类模型（``AlgorithmSelector`` 系列）本来就把整个实例压成一行特征向量，
        没有「逐行打分」的概念。给它补一个长度 1 的节点维即可复用同一条路径 ——
        而不是在 forward 里到处写 ``if x.dim()==2`` 的判断。
        """
        was3 = x.dim() == 3
        if was3:
            # 取掩码加权平均作为该「实例」的一行特征（mask 长度 == N）
            m = mask.unsqueeze(-1)
            flat = (x * m).sum(dim=1) / m.sum(dim=1).clamp(min=1.0)   # [B,F]
        else:
            flat = x
        out = self.legacy(flat)
        if isinstance(out, (tuple, list)):
            out = out[0]
        return out

    def forward(self, x: torch.Tensor, mask: torch.Tensor,
                adj: Optional[torch.Tensor] = None,
                type_mask: Optional[torch.Tensor] = None):
        """返回 (主输出, 后续步骤预测 ``[B,n_steps]``)。

        ``pool=True`` 时主输出是 ``[B,out_dim]``；否则 ``[B,N,out_dim]``（out_dim=1 时给 ``[B,N]``）。
        GNN 类原模型还需传 ``adj`` / ``type_mask``（``legacy_mode="gnn"``）。
        """
        if self.flat_input and x.dim() == 2:
            # 实例级原模型吃的是 **2 维** ``[B,F]``（没有节点维）。这里统一补成
            # ``N=1`` 的节点序列，让后续（专家池 / 主干 / 池化）走同一条路径 ——
            # 与导出侧 ``_Main.forward`` 的处理逐位一致。
            # ⚠️ 掩码必须**重建**为 ``[B,1]``：批次里传进来的占位掩码长度未必是 1，
            #    直接沿用会与补出的 N=1 广播成错的形状（形状对、结果错）。
            x = x.unsqueeze(1)
            mask = torch.ones(x.shape[0], 1, dtype=x.dtype, device=x.device)
        b, n, _ = x.shape
        if mask is None:
            # 序列型模型（``forecast_*``：输入就是 ``[B,L,F]``，每个时间步都是真实
            # 样本）**没有** padding 概念，原模型也只有一个 ``forward(x)`` 参数。
            # 这里补一张全 1 掩码，让池化 / 专家池的掩码口径统一；
            # 不补的话下面 ``mask.unsqueeze(-1)`` 直接 AttributeError。
            mask = torch.ones(b, n, dtype=x.dtype, device=x.device)
        h0 = self.in_norm(self.in_proj(x))                     # [B,N,H]

        # ① 原模型作为「共享专家」——保留它原有的全部能力
        legacy = self._legacy_forward(x, mask, adj, type_mask)
        # ② 多架构专家：稠密融合（每个专家都算、都拿梯度）
        w, top_i, _ = self.router(h0)
        acc = torch.zeros_like(h0)
        for i, ex in enumerate(self.experts):
            # 邻接压成单张图传给图卷积专家：4 维 [B,E,N,N] → 3 维 [B,N,N]（沿边型求和）
            e_adj = adj.sum(dim=1) if (adj is not None and adj.dim() == 4) else adj
            acc = acc + w[..., i].unsqueeze(-1) * ex(h0, e_adj, mask)
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
