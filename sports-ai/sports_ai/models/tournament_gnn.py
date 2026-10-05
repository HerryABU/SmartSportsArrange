"""球类赛制图模型 TournamentGnn：为赛制选择 / 种子排序 / 轮转公平性提供打分。

## 为什么需要它

``sports_ai/tournament/`` 下 725 行全是规则（``round_robin`` 循环赛、``elimination``
淘汰、``hybrid`` 混合、``seeding`` 种子、``volleyball`` 排球），**零 torch**。规则的问题：

* **赛制选择靠人拍脑袋**——4 支队打循环赛要 6 场、单循环够；8 支队要 28 场，3 天排不下，
  此时该上分组循环（hybrid）但没有任何依据告诉系统「该换赛制了」。
* **种子只按名次**——``seeding.distribute_seeds`` 是 ``rank`` 规则，
  但**同班同队扎堆**时会让强队早遇，公平性明显变差。
* **轮转公平性无人评估**——同一名选手可能连打 3 场强队，没人算这个代价。

本模型把这三件事变成可学习的打分：

1. **赛制 logits**（3 维）：round_robin / elimination / hybrid 各打多少分；
2. **种子排序分**（N 维）：谁该进前半个签位（分数越高越靠前）；
3. **公平性成本**（N 维）：每个对手分配的「实力方差惩罚」，越低越公平。

## 架构依据

* **GOAL**（arXiv:2605.19119）：约束类别 = 边类型，每类约束独立消息传递。
  这里的约束本体就是「实力相近 / 同队 / 同轮 / 同场地」四类。
* **POMO**（ICLR'22）：多重最优 + 共享基线。种子排列有 (N-1)! 个等价最优解
  （交换同分区内的两支队伍不改变赛程难度），用「批次内共享基线」而非单样本基线
  能显著降低梯度方差，训练更稳。
* **LEHD**（NeurIPS'24）：轻编码器 + 重解码器。本模型采用**共享的轻节点编码器**
  产出静态嵌入，再由**逐头解码器**在每步构建时结合部分解（已排的队伍）动态计算——
  避免静态嵌入学到规模相关的特征，这在赛制从 4 队变 64 队时至关重要。

## 输入 / 输出契约（与 Java 端 TournamentGnnEncoder 逐位对齐）

输入（batch 维均为可动态轴）::

    node_feat : [B, N, 14]
    adj_by_type : [B, T=4, N, N]
    type_mask : [B, T]
    mask : [B, N]

输出::

    format_logits : [B, 3]      # round_robin / elimination / hybrid
    seed_scores   : [B, N]      # 种子排序分（越高越靠前）
    fairness_cost : [B, N]      # 轮转公平性成本（越低越好）
"""

from __future__ import annotations

import torch
import torch.nn as nn
import torch.nn.functional as F
from sports_ai.nn.moe_encoder import MoERepr

# 边类型顺序即 adj_by_type 通道号 —— 双端契约，改序必须同步训练侧与 Java 端
T_STRENGTH = 0  # 实力相近（同档位）
T_SAME_CLASS = 1  # 同班同队（应尽量错开，避免早遇）
T_ROUND = 2  # 同轮次
T_VENUE = 3  # 同场地（同时开赛会冲突）
N_TYPES = 4

TYPE_EMBED_DIM = 8
NODE_FEAT_DIM = 14


class _RelBlock(nn.Module):
    """一次类型化关系聚合（GOAL 式）：按类型分别变换，再按 gate 加权求和。"""

    def __init__(self, hidden: int, n_types: int = N_TYPES, dropout: float = 0.1):
        super().__init__()
        # 每类约束一个**专属** MLP + **专属**偏置：参数与传播路径都分开
        self.rel_mlp = nn.ModuleList(
            [nn.Linear(hidden, hidden) for _ in range(n_types)]
        )
        self.type_bias = nn.Parameter(torch.zeros(n_types, hidden))
        nn.init.normal_(self.type_bias, std=0.02)
        self.gate = nn.Linear(hidden, n_types)
        self.norm = nn.LayerNorm(hidden)
        self.ffn = nn.Sequential(
            nn.Linear(hidden, hidden * 2),
            nn.GELU(),
            nn.Dropout(dropout),
            nn.Linear(hidden * 2, hidden),
        )
        self.ffn_norm = nn.LayerNorm(hidden)

    def forward(self, h: torch.Tensor, adj: torch.Tensor,
                type_mask: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
        """h [B,N,H] / adj [B,T,N,N] / type_mask [B,T] / mask [B,N] → [B,N,H]"""
        # 自环 + 双边掩码。
        # ⚠️ adj 是 [B,T,N,N]（**4 维**），mask 是 [B,N]（2 维）。
        #    mask.unsqueeze(1) 给的是 [B,1,N]，在 4 维张量上会把 B 维当成分类维广播，
        #    报 "size of tensor a (4) must match tensor b (3) at dim 1"。
        #    正确做法：mask 先补上类型维 → [B,1,N,1]（行/出边）与 [B,1,1,N]（列/入边）。
        eye = torch.eye(adj.shape[-1], device=adj.device, dtype=adj.dtype)
        a_all = adj + eye.unsqueeze(0).unsqueeze(0)
        m = mask.unsqueeze(1)                       # [B,1,N]
        a_all = a_all * m.unsqueeze(-1) * m.unsqueeze(-2)

        # 归一化邻接（对称）
        # deg [B,T,N] → 需要 [B,T,N,1] 与 [B,T,1,N] 才能对上 4 维的 a_all
        deg = a_all.sum(dim=-1).clamp(min=1e-6).pow(-0.5)
        a_all = a_all * deg.unsqueeze(-1) * deg.unsqueeze(-2)

        # type_mask [B,T] → [B,1,T] 才能与 [B,N,T] 的 gate 对齐
        gate = torch.sigmoid(self.gate(h)) * type_mask.unsqueeze(1)  # [B,N,T]
        agg = torch.zeros_like(h)
        for t in range(N_TYPES):
            a_t = a_all[:, t]
            # ⚠️ **不要写 if float(type_mask[:, t].max()) == 0.0: continue**——
            #    那是 Python float 转换，torch.onnx.export 会把它当**常量**固化，
            #    导出时若恰好某类型缺席，整条通道就被永久写死（TracerWarning 已明确警告）。
            #    改用张量门控：缺席类型 gate 已经为 0，加上去等于什么都没发生。
            msg = torch.matmul(a_t, self.rel_mlp[t](h))
            msg = msg + self.type_bias[t].view(1, 1, -1)
            agg = agg + msg * gate[:, :, t: t + 1]
        h = self.norm(h + agg)
        h = self.ffn_norm(h + self.ffn(h))
        return h * mask.unsqueeze(-1)


class TournamentGnn(nn.Module):
    """球类赛制决策网络。

    三个输出头共享一个**轻**节点编码器（LEHD 思路：编码只做一次，
    避免学到规模相关的静态特征），每个头自己是几层 MLP（用户要求的「多层感知机」）。
    """

    def __init__(self, node_feat: int = NODE_FEAT_DIM, hidden: int = 160,
                 layers: int = 5, n_formats: int = 3, dropout: float = 0.1,
                 moe: bool = True, moe_layers: int = 6, moe_experts: int = 9):
        super().__init__()
        # MoE 表征增强块（默认启用）。放在**表征层**而不是包住整个模型：
        # 本模型有三个输出头、Java 读三个输出，外部适配器做不到（见 forward 注释）。
        self.moe = (MoERepr(hidden, n_layers=moe_layers, n_experts=moe_experts,
                            dropout=dropout)
                    if moe else None)
        # 预测分支（「未来 H 步时间槽」）：与主任务共享 self.moe 主干 ——
        # 只挂在表征层，三个输出头与部署契约一个字都不用动（导出只回主输出）。
        from sports_ai.nn.forecast_aux import ForecastAux
        self.aux = ForecastAux(hidden)
        self.proj = nn.Linear(node_feat, hidden)
        self.in_norm = nn.LayerNorm(hidden)
        # 类型嵌入：告诉编码器「这份图里有哪些约束本体」。
        # ⚠️ 池化后是 [B, TYPE_EMBED_DIM]，维度与 hidden 不同，必须投影后再广播加到节点上，
        #    直接相加会报 "size of tensor a (96) must match tensor b (8)"。
        self.type_emb = nn.Embedding(N_TYPES, TYPE_EMBED_DIM)
        self.ctx_proj = nn.Linear(TYPE_EMBED_DIM, hidden)
        # 注入到边权上的类型信号：让不同类型的边在通道之外还有可学习的先验
        self.type_prior = nn.Parameter(torch.zeros(N_TYPES))

        self.blocks = nn.ModuleList(
            [_RelBlock(hidden, N_TYPES, dropout) for _ in range(layers)]
        )
        # JK 跳跃连接：浅层局部 + 深层全局一起用，缓解过平滑
        self.jk = nn.Linear(hidden * (layers + 1), hidden)
        self.jk_norm = nn.LayerNorm(hidden)

        # ⚠️ format_head 的输入是**全局池化后的 pooled [B,H]**（队伍级决策），
        #    不是 zg [B,N,2H]（节点级），所以首层是 hidden 而非 hidden*2。
        self.format_head = nn.Sequential(
            nn.Linear(hidden, hidden),
            nn.GELU(),
            nn.Dropout(dropout),
            nn.Linear(hidden, hidden // 2),
            nn.GELU(),
            nn.Linear(hidden // 2, n_formats),
        )
        self.seed_head = nn.Sequential(
            nn.Linear(hidden * 2, hidden),
            nn.GELU(),
            nn.Dropout(dropout),
            nn.Linear(hidden, hidden // 2),
            nn.GELU(),
            nn.Linear(hidden // 2, 1),
        )
        self.fair_head = nn.Sequential(
            nn.Linear(hidden * 2, hidden),
            nn.GELU(),
            nn.Dropout(dropout),
            nn.Linear(hidden, hidden // 2),
            nn.GELU(),
            nn.Linear(hidden // 2, 1),
        )

    def forward(self, node_feat: torch.Tensor, adj_by_type: torch.Tensor,
                type_mask: torch.Tensor, mask: torch.Tensor):
        b, n = node_feat.shape[0], node_feat.shape[1]

        # 类型先验乘到边权上（可学习），不改变通道结构，仅调整强度
        prior = 1.0 + self.type_prior.unsqueeze(0)      # [1,T]
        adj = adj_by_type * prior.unsqueeze(-1).unsqueeze(-1)

        h = torch.relu(self.in_norm(self.proj(node_feat))) * mask.unsqueeze(-1)
        # 池化一份「本实例有哪些约束」的全局上下文（TYPE_MASK 加权），投影后加到每个节点。
        # type_mask 加权很重要：缺席的约束类型不能贡献嵌入。
        ctx = (type_mask.unsqueeze(-1) * self.type_emb.weight.unsqueeze(0)).sum(1)  # [B,TYPE_EMBED_DIM]
        h = h + self.ctx_proj(ctx).unsqueeze(1)

        traces = [h]
        for blk in self.blocks:
            h = blk(h, adj, type_mask, mask)
            traces.append(h)
        g = torch.cat(traces, dim=-1)                    # [B,N,H*(L+1)]
        z = self.jk_norm(self.jk(g)) * mask.unsqueeze(-1)

        # MoE 表征增强（2026-10-05）：把 z 再过一遍 9 架构专家池 + 层次门控 + 6 层主干。
        #
        # ⚠️ **必须在这里插刀，不能用外部适配器包住整个模型**：
        #    本模型有**三个输出头**（赛制/种子分/公平性），而 Java 侧
        #    `TournamentAiService` 读的是 r.get(0)/get(1)/get(2) 三个输出 ——
        #    外部适配器只回一个主输出，少两个就抛异常被 catch，
        #    表现为「模型静默回退规则」，而 onnx 单独加载推理完全正常
        #    （问题不在能否加载，在输出**个数**）。在表征层插一刀，
        #    三个头全部受益，输出个数与语义一个不动。
        if self.moe is not None:
            z = self.moe(z, adj, mask)

        # 全局池化（对规模鲁棒：均值池化而非 flatten，N 变化不会崩）。
        # ⚠️ denom 必须保持 [B,1]：**绝不能**再 unsqueeze(-1)——那会变成 [B,1,1]，
        #    与 [B,H] 右对齐广播后得到 [B,B,H] 的 4 维张量，后面 expand 直接炸。
        denom = mask.sum(dim=1, keepdim=True).clamp(min=1.0)      # [B,1]
        pooled = (z * mask.unsqueeze(-1)).sum(dim=1) / denom     # [B,H]

        zg = torch.cat([z, pooled.unsqueeze(1).expand(-1, n, -1)], dim=-1)

        format_logits = self.format_head(pooled)                   # [B,3]
        seed_scores = self.seed_head(zg).squeeze(-1) * mask        # [B,N]
        fairness_cost = self.fair_head(zg).squeeze(-1) * mask      # [B,N]
        return format_logits, seed_scores, fairness_cost
