"""SuperScheduleMoE —— 统一编排超级模型（多门混合专家 + 异构消息传递 + 扩散解码）。

一个模型覆盖：**项目编排 / 道次编排 / 球类赛制（小组·循环·淘汰·混合）/
淘汰赛晋级 / 项目块完整性 / 兼项避让 / 装箱容量 / 工期压缩 / 二次编排**。

## 架构依据（四篇前沿，各自贡献一块）

===  ==========================  ==========================================
#    工作                        本类借鉴点
===  ==========================  ==========================================
1    CoEKS（中山大学 2026-03）   **组合专家 + 多视角知识共享**：按任务约束
                                 激活对应专家并**动态组合**；48 类任务单一
                                 模型，未见约束组合上提升 18.3%/13%
2    MMOE / MTL4TSP（Appl.Int.  **多门（multi-gate）混合专家** + 不确定性
     2026, s10489-026-07091-7）  损失加权；单模型覆盖 91 种规模，参数只要 1/3
3    MPI（人大高岭+腾讯           路由器**真正「读懂」每位专家**（专家嵌入参与
     arXiv:2606.12397）           打分），而非只靠训练自然磨合
4    HM-MATAS（NeurIPS 2026）    **残差异构 Transformer**：边级 + 节点级双注意力，
                                 小规模训练→大规模泛化
===  ==========================  ==========================================

在此之上加 **扩散解码器**：GNN 给出全局表征后，用条件扩散逐步去噪出
「时间槽 × 单元」的分配方案——逐步去噪天然向可行域靠，且步数可按预算取舍。

## 为什么是「多门」而不是标准 MoE

标准 MoE 是「一个门挑 k 个专家」。但编排任务的专家是**组合关系**：
「道次编排」既要装箱专家、又要兼项避让专家、还要项目块专家。
所以用 **多门（MMOE）**：每个门学一个任务域的专家组合权重，
最后把所有门的输出加权融合。这就是 MMOE4TSP 在 91 种规模上验证过的做法。

## 负载均衡与专家可观测

* 门控加 **load-balancing loss**（Switch Transformer 风格），防止专家塌缩；
* 暴露 {@link expert_usage()} 供训练日志与 /api 观测——
  **专家「建了但从不被选中」是 MoE 最常见的隐性失败**，必须能看见。

## 契约（与 Java SuperScheduleEncoder 逐位对齐）

输入::

    node_feat   [B, N, 20]
    adj_by_type [B, E=8, N, N]
    type_mask   [B, E]
    mask        [B, N]

输出::

    priority       [B, N]        单元调度优先级
    slot_logits    [B, N, K]     时间槽分配 logits（扩散解码后的方案）
    task_probs     [B, 9]        九类任务的存在概率（多门权重）
    format_logits  [B, 4]        球类赛制 logits（group/rr/ko/hybrid）
    days_estimate  [B, 1]        预计所需天数（工期压缩专家读出）
"""

from __future__ import annotations

import math
from typing import Dict, Optional, Tuple

import torch
import torch.nn as nn
import torch.nn.functional as F

# ---- 九类编排任务（与 super_scenarios.TASK_* 严格一致，双端契约）----
TASK_PROJECT, TASK_LANE, TASK_BALL, TASK_KNOCKOUT = 0, 1, 2, 3
TASK_BLOCK, TASK_CONFLICT, TASK_CAPACITY, TASK_MAKESPAN, TASK_RESECOND = 4, 5, 6, 7, 8
# ⚠️ 任务数**必须**与数据侧 sports_ai.data.super_scenarios.N_TASKS 一致（唯一真相源）。
#    这里曾经硬编码 9：改了数据侧的 N_TASKS 之后，模型的专家数没有跟着变，
#    症状是「参数量一模一样、expert_usage 里只出现 9 个专家」，排查时毫无线索。
#    所以改成直接导入，让两处不可能再漂移。
from sports_ai.data.super_scenarios import N_TASKS, N_UNIT_TASKS  # noqa: E402

# ---- 边类型（与 super_scenarios.E_* 严格一致）----
N_EDGES = 8

NODE_FEAT_DIM = 20
MAX_SLOTS = 16          # 时间槽数上限（与 generative/scheme.MAX_SLOTS 对齐）
N_FORMATS = 4           # group / round_robin / knockout / hybrid

TYPE_EMBED_DIM = 8

# ---- 图级（实例级）特征 —— 路由器的「问题结构」感知（GPINN / Router-experts 思路）----
# 过去路由器只看节点表征，等于「不管赛会多大、多挤，都走同一套专家组合」。
# 编排里真正决定该用哪种专家的是**实例结构**：冲突面广不广、几天、时间目标
# （x 天硬约束 / 0 不限 / -1 尽量压缩）、并行度高低。这八维就是它。
GRAPH_FEAT_DIM = 8
G_CONFLICT, G_UNITS, G_VENUES, G_DAYS, G_TIME_GOAL, G_PARALLEL, G_FILL, G_BLOCK = range(8)


class HeadAttention(nn.Module):
    """手写多头自注意力 —— **ONNX 导出友好版**。

    ⚠️ 为什么不用 ``nn.MultiheadAttention``（血的教训，务必保留）：

    PyTorch 导出 ONNX 时，``nn.MultiheadAttention`` 内部的 reshape 会被
    **常量折叠**成导出那一刻的形状（B / N 写死）。导出脚本用 ``B=2, N=24``
    造假数据，服务端喂 ``N=3`` 就直接在
    ``/moe/experts.8/node_att/Reshape_4`` 炸出
    ``input_shape_size == requested_shape_size was false:
    Input shape {3,1,128}, requested shape {24,4,32}``。

    最阴的地方：**模型能加载成功、推理必失败**，异常在 ``advise()`` 里被
    ``catch (Throwable)`` 吞掉后走「回退规则」—— 日志里只有一行 WARN，
    线上表现就是「AI 功能没生效但也不报错」，比直接崩溃难查十倍。

    这里的每个 reshape 都**从 ``x.shape`` 现算**，导出后是动态算子，
    任意 N / B 都能推理。数学上与 MHA 完全等价（同 qkv 投影、同缩放点积）。
    """

    def __init__(self, hidden: int, heads: int = 4, dropout: float = 0.1):
        super().__init__()
        self.heads = heads
        self.head_dim = hidden // heads
        self.qkv = nn.Linear(hidden, hidden * 3)
        self.proj = nn.Linear(hidden, hidden)
        self.dropout = nn.Dropout(dropout)

    def forward(self, x: torch.Tensor, key_padding_mask=None):
        b, n, _ = x.shape
        qkv = self.qkv(x).reshape(b, n, 3, self.heads, self.head_dim)
        q, k, v = qkv[:, :, 0], qkv[:, :, 1], qkv[:, :, 2]     # [B,N,h,hd]
        q, k, v = q.transpose(1, 2), k.transpose(1, 2), v.transpose(1, 2)
        scores = (q @ k.transpose(-2, -1)) / (self.head_dim ** 0.5)   # [B,h,N,N]
        if key_padding_mask is not None:
            # ⚠️ 必须补成四维 [B,1,1,N]：mask 是 [B,N]（二维），
            #    只 unsqueeze(1) 会变成 [B,1,N]，广播时前面补 1 变成 [1,1,B,N]，
            #    与 scores [B,h,N,N] 按**尾对齐**会拿 B 去比 N，
            #    报 "size of tensor a (2) must match tensor b (4) at dim 1"。
            scores = scores.masked_fill(key_padding_mask[:, None, None, :], -1e4)
        ctx = torch.softmax(scores, dim=-1) @ v                        # [B,h,N,hd]
        ctx = ctx.transpose(1, 2).reshape(b, n, self.heads * self.head_dim)
        return self.dropout(self.proj(ctx)), None


class _ExpertStep(nn.Module):
    """专家的**单独一层**：边级注意力聚合 + 节点级自注意力 + FFN（HM-MATAS 双注意力）。"""

    def __init__(self, hidden: int, n_edges: int = N_EDGES, dropout: float = 0.1):
        super().__init__()
        # 每类边一个专属关系变换（GOAL：约束类别 = 边类型）
        self.rel = nn.ModuleList([nn.Linear(hidden, hidden) for _ in range(n_edges)])
        self.edge_bias = nn.Parameter(torch.zeros(n_edges, hidden))
        nn.init.normal_(self.edge_bias, std=0.02)
        # 边级注意力：每对节点算一个标量权重（HM-MATAS 的 edge-level attention）
        self.edge_att = nn.Sequential(
            nn.Linear(hidden * 2, hidden // 2), nn.GELU(), nn.Linear(hidden // 2, 1)
        )
        # 节点级更新（手写 HeadAttention：导出 ONNX 时 reshape 形状是动态的）
        self.node_att = HeadAttention(hidden, 4, dropout=dropout)
        self.norm1 = nn.LayerNorm(hidden)
        self.ffn = nn.Sequential(
            nn.Linear(hidden, hidden * 2), nn.GELU(),
            nn.Dropout(dropout), nn.Linear(hidden * 2, hidden),
        )
        self.norm2 = nn.LayerNorm(hidden)

    def forward(self, h: torch.Tensor, adj: torch.Tensor,
                type_mask: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
        """h [B,N,H] / adj [B,E,N,N] / type_mask [B,E] / mask [B,N] → [B,N,H]"""
        b, n = h.shape[0], h.shape[1]
        # ⚠️ mask 不要提前 unsqueeze：邻接是 **[B,N,N] 三维**，
        #    提前升成 [B,1,N] 再 unsqueeze 两次会变成 [B,1,N,1]，
        #    与三维邻接相乘时按 batch 维广播出 [B,B,N,N]，
        #    下游 MultiheadAttention 报 "query ... received 4-D tensor"。
        #    正确：保持 [B,N]，掩码在用时各自补一个长度为 3 的视图。
        m = mask                                                 # [B,N]
        # ---- 边级注意力：w[b,i,j] = f([h_i, h_j])，逐边型计算 ----
        hi = h.unsqueeze(2).expand(b, n, n, h.shape[-1])
        hj = h.unsqueeze(1).expand(b, n, n, h.shape[-1])
        w_all = self.edge_att(torch.cat([hi, hj], dim=-1)).squeeze(-1)   # [B,N,N]
        w_all = torch.sigmoid(w_all)

        agg = torch.zeros_like(h)
        for e in range(N_EDGES):
            # ⚠️ 不要写 if float(type_mask[:, e].max()) == 0.0: continue——
            #    那是 Python float，torch.onnx.export 会当**常量固化**，
            #    某约束类型恰好缺席就永久写死。改用张量门控。
            a = adj[:, e] * w_all                                 # [B,N,N]
            a = a * m.unsqueeze(-1) * m.unsqueeze(-2)             # 双边掩码（行/列）
            deg = a.sum(dim=-1).clamp(min=1e-6).pow(-0.5)        # [B,N]
            a = a * deg.unsqueeze(-1) * deg.unsqueeze(-2)
            msg = torch.matmul(a, self.rel[e](h)) + self.edge_bias[e].view(1, 1, -1)
            # type_mask 门控：缺席的边型整条通道不参与
            agg = agg + msg * type_mask[:, e].view(b, 1, 1)

        h = self.norm1(h + agg)
        # ---- 节点级注意力（残差）----
        # ⚠️ 始终传 key_padding_mask：导出 ONNX 时 if 分支会被固化，
        #    恒真/恒假都会写死一条路径，那就白改了。
        a2, _ = self.node_att(h, key_padding_mask=(mask < 0.5))
        h = self.norm2(h + a2)
        h = h + self.ffn(h)
        return h * mask.unsqueeze(-1)


class Expert(nn.Module):
    """单个专家：``depth`` 层异构消息传递栈（HM-MATAS 的边级+节点级双注意力）。

    **为什么必须深**：编排的冲突簇常常 3~4 跳可达 —— 短跑（第 1 天 09:00）
    → 接力（同人，第 1 天 15:00）→ 跳远（同一批人）→ …。单层专家只看得到
    「直接邻居」，等于把间接兼项当无约束，于是排出来的赛程在**三步之外**炸开。
    ``depth=2`` 起能看到 2~3 跳；配合残差与 LayerNorm 可以堆到 4 层仍不退化。
    """

    def __init__(self, hidden: int, n_edges: int = N_EDGES, dropout: float = 0.1, depth: int = 2):
        super().__init__()
        self.depth = depth
        self.steps = nn.ModuleList([
            _ExpertStep(hidden, n_edges, dropout) for _ in range(depth)
        ])
        self.out_norm = nn.LayerNorm(hidden)

    def forward(self, h: torch.Tensor, adj: torch.Tensor,
                type_mask: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
        for s in self.steps:
            h = s(h, adj, type_mask, mask)
        return self.out_norm(h)


class SharedKnowledge(nn.Module):
    """多视角知识共享层（CoEKS 的 multi-view knowledge sharing）。

    关键设计：**专家之间不直接通信**，而是都读同一份「共享知识」，
    共享知识又由所有专家的输出汇聚更新。这样专家保持独立（可解释、可回退），
    又能通过共享层间接互补——CoEKS 论文指出这比让专家互相注意力更稳。
    """

    def __init__(self, hidden: int, n_experts: int, n_views: int = 3, dropout: float = 0.1):
        super().__init__()
        self.n_views = n_views
        # 多个视角的投影（不同视角看同一份表征的不同子空间）
        self.view_proj = nn.ModuleList([nn.Linear(hidden, hidden) for _ in range(n_views)])
        self.merge = nn.Linear(hidden * n_views, hidden)
        self.norm = nn.LayerNorm(hidden)
        # 专家输出 → 共享知识的汇聚权重（每视角一个）
        self.view_gate = nn.ModuleList([nn.Linear(hidden, n_experts) for _ in range(n_views)])
        self.drop = nn.Dropout(dropout)

    def forward(self, h: torch.Tensor, expert_out: torch.Tensor,
                mask: torch.Tensor) -> torch.Tensor:
        """h [B,N,H] / expert_out [B,N,E,H] → 共享知识 [B,N,H]（加回 h）。"""
        views = []
        for v in range(self.n_views):
            pv = self.view_proj[v](h)
            gate = torch.softmax(self.view_gate[v](h), dim=-1)          # [B,N,E]
            # 每视角按 gate 加权汇聚所有专家。
            # ⚠️ 不要用 einsum("bne,bnhe->bnh", ...)：einsum 的下标是**位置无关**的，
            #    当 hidden==n_experts（本项目两处都是 9/128 附近）时容易与
            #    hidden 维的隐式广播撞上，报
            #    "subscript e has size 128 ... does not broadcast with previously
            #     seen size 9"。改用显式 einsum 但把专家维命名为 k_x。
            pooled = torch.einsum("bnk,bnkh->bnh", gate, expert_out)
            views.append(pooled * gate.shape[-1] + pv)
        return self.norm(h + self.drop(self.merge(torch.cat(views, dim=-1)))) * mask.unsqueeze(-1)


class LocalConvBlock(nn.Module):
    """CNN 分支：沿节点邻接序做 1-D 卷积，抽出「项目块」的局部结构描述。

    ## 为什么编排需要 CNN 这一路

    GNN 回答的是「谁和谁冲突」（关系），扩散回答的是「逐槽去噪出方案」（生成），
    但还有一件事它们都不直接管：**一个项目必须在时间上成块连续，不能今天排一节、
    明天挤一节「见缝插针」**。这是排班网格上的**空间局部性**——
    相邻位置的单元属于同一项目，这一先验用卷积表达最直接。

    ## 为什么是 Conv1d 而不是 Conv2d

    Conv2d 要把 [B,N,H] 摊成 [B,H,R,C] 方阵，过程需要 ``int(sqrt(n))`` 这类
    **Python 整数运算**去算边，而导出 ONNX 时它会被**常量折叠**成导出那一刻的 N——
    正是我们上一轮在 ``nn.MultiheadAttention`` 上被坑过一次的同型错误
    （服务端喂 N=3 直接 Reshape 崩溃，异常还被 ``catch(Throwable)`` 吞掉，
    表现是「AI 没生效但也不报错」）。

    ``Conv1d`` 直接在 [B,H,N] 上卷积，**N 是动态维度、无需任何 reshape/crop**，
    ONNX 导出天然安全，数学上也等价（节点邻接序 = 网格扫描序）。

    输出是该实例的**局部块结构向量** [B,H]，与全局池化向量拼接后再送路由与输出头。
    """

    def __init__(self, hidden: int, k: int = 5, dropout: float = 0.1):
        super().__init__()
        self.net = nn.Sequential(
            nn.Conv1d(hidden, hidden, kernel_size=k, padding=k // 2, bias=False),
            nn.GELU(),
            nn.Conv1d(hidden, hidden, kernel_size=k, padding=k // 2, bias=False),
            nn.GELU(),
            nn.Conv1d(hidden, hidden, kernel_size=k, padding=k // 2, bias=False),
        )
        self.proj = nn.Linear(hidden, hidden)
        self.drop = nn.Dropout(dropout)

    def forward(self, x: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
        """x [B,N,H] / mask [B,N] → 局部块结构向量 [B,H]"""
        h = x.transpose(1, 2)                                   # [B,H,N]
        h = self.net(h)                                         # [B,H,N]
        h = h.transpose(1, 2) * mask.unsqueeze(-1)              # [B,N,H]
        g = h.sum(dim=1) / mask.sum(dim=1, keepdim=True).clamp(min=1.0)   # [B,H]
        return self.drop(torch.tanh(self.proj(g)))


class MultiGateMoE(nn.Module):
    """多门混合专家（MMOE）—— **异构门控**版。

    专家池分成两层，**路由粒度不同**（Graph-MoE 综述里「路由粒度」那一轴的落地）：

    * **任务专家** `0..N_UNIT_TASKS-1`（项目/道次/球类/…/裁判/教师）
      —— 走**节点级**路由：每个节点各自决定该请哪几位专家。
      它们回答的是「**这个单元**该先排还是后排、该落哪个桶」。

    * **能力专家** `N_UNIT_TASKS..N_TASKS-1`（生成/精修/扩散/派遣/预测/判别）
      —— 走**图级**路由：同一实例内所有节点拿到**同一个**门控权重。
      它们回答的是「**这个赛会**需要多少方案生成、多少精修、要预测得多准」。

    ⚠️ 为什么必须异构：上一轮把专家从 9 扩到 11 时全是同构的——所有专家
    读同样的输入、只看节点表征。那样扩出来的专家只是在**摊薄容量**：
    `load_balancing_loss` 会把使用率强行拉平均，可每个专家学到的几乎是同一件事。
    本轮把新来的 6 位改用「只由图级上下文决定」的门控（去掉节点表征项），
    它们才真正练成**实例级**专家，与节点级任务专家形成分工。

    * load-balancing loss 防止专家塌缩
    """

    def __init__(self, hidden: int, n_experts: int = N_TASKS, n_edges: int = N_EDGES,
                 dropout: float = 0.1, expert_depth: int = 2,
                 use_graph: bool = True, graph_dim: int = GRAPH_FEAT_DIM,
                 use_cnn: bool = True, n_views: int = 3,
                 n_unit_tasks: int = N_UNIT_TASKS):
        super().__init__()
        self.n_experts = n_experts
        self.n_unit_tasks = min(n_unit_tasks, n_experts)
        self.use_graph = use_graph
        self.use_cnn = use_cnn
        self.experts = nn.ModuleList(
            [Expert(hidden, n_edges, dropout, depth=expert_depth) for _ in range(n_experts)])
        # 多门：每门一个 Linear(hidden → n_unit_tasks)。
        # ⚠️ 只对**任务专家**建门：能力专家的门控完全由图级上下文给出（见 forward），
        #    给它们配门等于白建一组永不被读的参数。
        self.gates = nn.ModuleList(
            [nn.Linear(hidden, self.n_unit_tasks) for _ in range(self.n_unit_tasks)])
        # 路由器专家嵌入（MPI：让路由器「读懂」每位专家）
        self.expert_embed = nn.Parameter(torch.randn(n_experts, hidden) * 0.02)
        # 图级特征投影：把「赛会结构」翻译成与专家嵌入同一空间的向量，
        # 路由器据此判断「这种规模/这种紧迫度该派谁上」。
        self.graph_proj = nn.Linear(graph_dim, hidden)
        # CNN 分支：专家 consensus 表征 → 项目块局部结构（见 LocalConvBlock 注释）
        self.moe_cnn = LocalConvBlock(hidden, 5, dropout)
        # 知识共享层
        self.shared = SharedKnowledge(hidden, n_experts, n_views, dropout)
        self.norm = nn.LayerNorm(hidden)
        # 门控的负载均衡统计（供观测）
        self._last_gate_probs: Optional[torch.Tensor] = None

    def forward(self, h: torch.Tensor, adj: torch.Tensor,
                type_mask: torch.Tensor, mask: torch.Tensor,
                graph_feat: Optional[torch.Tensor] = None
                ) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        """返回 (融合表征 [B,N,H], 平均门控概率 [B,n_experts], 结构上下文 [B,H])。

        ``graph_feat`` 为 None 时退化为全零图级上下文（**保持旧契约可跑**，
        Java 端升级到新 ONNX 之前不会炸）。
        """
        b, n, hid = h.shape
        outs = []
        for e in range(self.n_experts):
            outs.append(self.experts[e](h, adj, type_mask, mask))
        expert_out = torch.stack(outs, dim=2)                    # [B,N,E,H]

        # ---- CNN 分支：专家 consensus 上的局部块结构 ----
        if self.use_cnn:
            ctx = self.moe_cnn(expert_out.mean(dim=2), mask)     # [B,H]
        else:
            ctx = h.new_zeros(b, h.shape[-1])
        # 图级（实例级）上下文：冲突密度 / 天数 / 时间目标 / 并行度 / 填充率 …
        if self.use_graph:
            gf = graph_feat if graph_feat is not None else h.new_zeros(b, self.graph_proj.in_features)
            gctx = torch.tanh(self.graph_proj(gf))               # [B,H]
            ctx = ctx + gctx
            ctx = self.norm(ctx).unsqueeze(1).expand(b, n, hid)  # 广播到每个节点

        # ---- 多门：每门一个组合权重 ----
        # MPI 思路：把「专家嵌入」与节点表征做内积后再 softmax，
        # 路由器因此显式感知每位专家的特化方向，而不是盲学；
        # 这里再把「结构上下文」也投影到同一空间做内积——
        # 于是路由不再是「看节点长相」，而是「看整个赛会长什么样」。
        q_base = (h @ self.expert_embed.t() + ctx @ self.expert_embed.t())   # [B,N,E]
        # 多门各给一份 [B,N,U]（U = 任务专家数），取平均得到该节点的任务专家组合权重
        gate_term = torch.stack([g(h) for g in self.gates], dim=0).mean(dim=0)   # [B,N,U]
        # ⚠️ 拼接而不是相加：gate_term 是 [B,N,U]，q_base 是 [B,N,E]（E>U），
        #    直接相加会按尾维广播 → 静默算错（且 batch=1 时不报错）。
        q = torch.cat([q_base[:, :, :self.n_unit_tasks] + gate_term,
                       q_base[:, :, self.n_unit_tasks:]], dim=-1)          # [B,N,E]
        if self.n_unit_tasks < self.n_experts:
            # 能力专家：门控**只由图级上下文决定**（去掉节点表征与门），
            # 于是同一实例内所有节点对这些专家的权重相同 —— 它们成了实例级专家。
            # ⚠️ expert_embed 是 [E, H]：要取**能力专家那几行**，所以切 dim0
            #    （self.expert_embed[n_unit:, :]）。写成 [:, n_unit:] 切的是 hidden 维，
            #    会变成 [17, H-11] 相乘直接报 shapes cannot be multiplied。
            cap = (ctx[:, :1, :] @ self.expert_embed[self.n_unit_tasks:, :].t())  # [B,1,C]
            q = torch.cat([q[:, :, :self.n_unit_tasks], cap.expand(b, n, -1)], dim=-1)
        gates = torch.softmax(q, dim=-1)                         # [B,N,E]
        self._last_gate_probs = gates.detach()

        # MMOE 融合：全局组合权重 = 各节点门控的均值 → [B,E]
        # ⚠️ 必须 mean(dim=1)（对 N 求平均）；写 mean(dim=0) 会把 batch 平均掉，
        #    得到 [N,E] 再与 [B,N,E,H] 做 einsum 就会报
        #    "subscript e has size 128 ... does not broadcast with previously seen size 9"。
        glob = gates.mean(dim=1)                                 # [B,E]

        # 知识共享层：它内部已经按 gate 加权汇聚了所有专家，
        # 所以这里**不需要**再做一次 einsum 融合。
        # ⚠️ 之前那句 einsum("be,bnhe->bnh", glob, expert_out) 是死代码
        #    （返回值立刻被下面这行覆盖），却会在 hidden==n_experts 时
        #    撞上 einsum 的 e 维歧义报错。直接删掉。
        fused = self.shared(h, expert_out, mask)
        fused = self.norm(fused)
        # ⚠️ keepdim=True 是必须的：ctx.sum(dim=1) 得到 [B,H]，
        #    而 mask.sum(dim=1) 是 [B] —— 广播按**尾维对齐**会变成 [1,B] 与 [B,H]
        #    在最后一位上比 H 与 B。单样本时 B=1，[1] 与 H 恰好有一个是 1 能广播过去
        #    （于是冒烟测试完全测不出来）；batch=16 时直接报
        #    "size of tensor a (128) must match the size of tensor b (16)"。
        return fused, glob, ctx.sum(dim=1) / mask.sum(dim=1, keepdim=True).clamp(min=1.0)

    def load_balancing_loss(self) -> torch.Tensor:
        """Switch Transformer 风格负载均衡：鼓励各专家被均匀使用。

        没有这一项，MoE 会退化成「只有 1~2 个专家在干活」，
        而**表面上 loss 照样下降**——这是 MoE 最常见的隐性失败。
        """
        g = self._last_gate_probs
        if g is None:
            return torch.zeros((), device=next(self.parameters()).device)
        # g: [B,N,E] → 每专家的平均使用率
        frac = g.mean(dim=(0, 1))                                # [E]
        # 每样本专家使用熵
        ent = -(g.clamp_min(1e-8).log() * g).sum(dim=-1).mean()   # [B]
        return self.n_experts * (frac * frac).sum() + ent

    def expert_usage(self) -> Dict[str, float]:
        """各专家的实际使用率（0~1）。**专家建了但从不被选中时这里会显示 0**。"""
        g = self._last_gate_probs
        if g is None:
            return {}
        frac = g.mean(dim=(0, 1))                                # [E]
        n = float(g.shape[-1])
        # 路由熵（bits）：softmax 输出越尖峰熵越低。
        # 使用率只看「有没有人干活」，熵看出「是不是所有活都堆在一个人身上」——
        # 两个指标一起看才抓得住 MoE 的隐性失败。
        ent = float(-(frac.clamp_min(1e-8) * frac.clamp_min(1e-8).log()).sum()) / math.log(max(2.0, n))
        return {f"E{i}": round(float(v), 5) for i, v in enumerate(frac.tolist())}

    def route_entropy(self) -> float:
        """归一化路由熵（0~1，越大越均衡）。"""
        g = self._last_gate_probs
        if g is None:
            return 0.0
        p = g.mean(dim=(0, 1)).clamp_min(1e-8)
        n = max(2, int(g.shape[-1]))
        return float(-(p * p.log()).sum()) / math.log(n)


class DiffusionDecoder(nn.Module):
    """条件扩散解码器：从节点表征逐步去噪出「时间槽 × 单元」分配。

    GNN/专家负责「理解全局」（谁和谁冲突、哪些必须成块），
    扩散负责「产出具体方案」——两者分工明确，也比让一个头硬扛两件事更稳。
    """

    def __init__(self, hidden: int, n_slots: int = MAX_SLOTS, steps: int = 8,
                 dropout: float = 0.1):
        super().__init__()
        self.n_slots = n_slots
        self.steps = steps
        self.inp = nn.Linear(n_slots + hidden, hidden)
        # 时间步嵌入：输入是**一个标量 t**，输出再投到 hidden 才能加到 h 上。
        # ⚠️ 末层必须是 hidden（原写成 hidden//2，加上去会报
        #    "size of tensor a (128) must match tensor b (64)"）。
        self.temb = nn.Sequential(nn.Linear(1, hidden), nn.SiLU(), nn.Linear(hidden, hidden))
        self.blocks = nn.ModuleList([
            nn.Sequential(nn.LayerNorm(hidden), nn.Linear(hidden, hidden * 2),
                          nn.GELU(), nn.Dropout(dropout), nn.Linear(hidden * 2, hidden))
            for _ in range(3)
        ])
        self.out = nn.Sequential(nn.LayerNorm(hidden), nn.Linear(hidden, n_slots))
        # abar 直接由日程给出（已带下限），不再 cumprod
        self.register_buffer("alphas_cumprod", self._cosine(steps), persistent=False)

    @staticmethod
    def _cosine(steps: int, s: float = 0.008, abar_min: float = 0.15) -> torch.Tensor:
        """构造噪声日程，返回**逐步的 alpha_bar**（长度 steps+1，单调递减）。

        ⚠️ **直接返回 alphas_cumprod，不再返回 betas**。这是本轮踩的坑：
        最初返回 ``betas``（增量），下游 ``cumprod(1-betas)`` 于是把 0.85
        连乘 8 次 → alpha_bar 末项 0.85^8 = 0.27，看起来正常；
        但真正致命的是**下一步** ``(x - sqrt(1-a)*eps)/sqrt(a)`` 里的 1/sqrt(a)，
        只要 a 掉到 1e-4 量级就会把 logits 放大上百倍，loss 冲到 1e4。

        所以这里直接产出 abar 并**夹在 abar_min 之上**，
        保证任何一步的 1/sqrt(a) <= 1/sqrt(0.15) ≈ 2.6。
        """
        t = torch.linspace(0, steps, steps + 1, dtype=torch.float64)
        f = torch.cos(((t / steps) + s) / (1 + s) * math.pi * 0.5) ** 2
        abar = (f / f[0]).clamp(min=abar_min, max=1.0)
        return abar.float()

    def predict_noise(self, x: torch.Tensor, cond: torch.Tensor, t_idx: torch.Tensor,
                      mask: torch.Tensor) -> torch.Tensor:
        te = self.temb(t_idx.float().unsqueeze(-1) / max(1, self.steps))
        h = self.inp(torch.cat([x, cond], dim=-1)) + te.unsqueeze(1)
        for b in self.blocks:
            h = h + b(h)
        return self.out(h) * mask.unsqueeze(-1)

    def forward(self, cond: torch.Tensor, mask: torch.Tensor,
                steps: Optional[int] = None) -> torch.Tensor:
        """DDIM 确定性去噪（eta=0），从全 0 起点出 logits——可导出 ONNX。"""
        steps = steps or self.steps
        b, n, _ = cond.shape
        x = torch.zeros(b, n, self.n_slots, device=cond.device, dtype=cond.dtype)
        seq = [min(int(round(i * (self.steps - 1) / max(1, steps - 1))), self.steps - 1)
               for i in range(steps)][::-1]
        for i in seq:
            t = torch.full((b,), i, device=cond.device, dtype=torch.long)
            eps = self.predict_noise(x, cond, t, mask)
            a = self.alphas_cumprod[i]
            x = (x - (1 - a).sqrt() * eps) / a.sqrt()
            x = x * mask.unsqueeze(-1)
        return x


class GlobalBlock(nn.Module):
    """主干深层推理块（Pre-LN 残差 FFN）。

    作用是把 MoE 的融合表征**再想几步**再交给输出头。原来 proj→MoE→head 只有
    一跳，等于「看到冲突图就立刻拍板」——而编排的正确解往往是二跳推理
    （A 与 B 冲突、B 与 C 冲突 ⇒ 三者必须同批但内部串行，且 C 的场地在第三天被占）。
    """

    def __init__(self, hidden: int, dropout: float = 0.1, expand: int = 2):
        super().__init__()
        self.norm1 = nn.LayerNorm(hidden)
        self.ffn = nn.Sequential(
            nn.Linear(hidden, hidden * expand), nn.GELU(),
            nn.Dropout(dropout), nn.Linear(hidden * expand, hidden))
        self.norm2 = nn.LayerNorm(hidden)
        self.drop = nn.Dropout(dropout)

    def forward(self, h: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
        # Pre-LN：先归一化再进 FFN，残差加回 —— 深层不塌的前提
        h = h + self.drop(self.ffn(self.norm1(h)))
        h = self.norm2(h)
        return h * mask.unsqueeze(-1)


class SuperScheduleMoE(nn.Module):
    """统一编排超级模型（深层版）。"""

    def __init__(self, node_feat: int = NODE_FEAT_DIM, hidden: int = 192,
                 n_experts: int = N_TASKS, n_edges: int = N_EDGES,
                 n_slots: int = MAX_SLOTS, steps: int = 8, dropout: float = 0.1,
                 expert_depth: int = 2, n_global: int = 3,
                 use_graph: bool = True, use_cnn: bool = True):
        super().__init__()
        self.hidden = hidden
        self.expert_depth = expert_depth
        self.n_global = n_global
        self.proj = nn.Linear(node_feat, hidden)
        self.in_norm = nn.LayerNorm(hidden)
        self.type_emb = nn.Embedding(n_edges, TYPE_EMBED_DIM)
        self.ctx_proj = nn.Linear(TYPE_EMBED_DIM, hidden)

        self.moe = MultiGateMoE(hidden, n_experts, n_edges, dropout,
                                expert_depth=expert_depth,
                                use_graph=use_graph, use_cnn=use_cnn)
        # 主干深层推理栈
        self.globals = nn.ModuleList(
            [GlobalBlock(hidden, dropout) for _ in range(n_global)])

        # 输出头（加深：局部+全局+结构上下文三路融合后再 MLP）
        self.priority_head = nn.Sequential(
            nn.Linear(hidden * 3, hidden), nn.LayerNorm(hidden), nn.GELU(),
            nn.Dropout(dropout),
            nn.Linear(hidden, hidden // 2), nn.GELU(),
            nn.Dropout(dropout), nn.Linear(hidden // 2, 1))
        self.format_head = nn.Sequential(
            nn.Linear(hidden * 2, hidden), nn.GELU(), nn.LayerNorm(hidden),
            nn.GELU(), nn.Linear(hidden, N_FORMATS))
        self.days_head = nn.Sequential(
            nn.Linear(hidden * 2, hidden), nn.GELU(), nn.LayerNorm(hidden),
            nn.GELU(), nn.Linear(hidden, 1))
        # 道次派遣头（替代原 lane_advisor.onnx）：[B,N,K] 的「该单元派到哪条道/哪一批次」。
        # 形状与 slot_logits 相同但**目标不同**：只有径赛类单元（lane_mask=1）参与损失，
        # 田赛/球类/裁判单元没有「第几道」可分，硬给它们派道只会污染梯度。
        self.lane_head = nn.Sequential(
            nn.Linear(hidden * 3, hidden), nn.LayerNorm(hidden), nn.GELU(),
            nn.Dropout(dropout),
            nn.Linear(hidden, hidden // 2), nn.GELU(),
            nn.Dropout(dropout), nn.Linear(hidden // 2, n_slots))
        # 方案判别头（替代原 scheme_discriminator.onnx / GAN 判别器）：
        # 给整个实例打一个 [B,1] 的「方案质量」分，目标是负载均衡度 1/(1+CV)。
        # 用 Sigmoid 把输出压进 (0,1)，与质量目标同量纲 —— 免去输出侧再做裁剪。
        self.quality_head = nn.Sequential(
            nn.Linear(hidden * 2, hidden), nn.GELU(), nn.LayerNorm(hidden),
            nn.GELU(), nn.Linear(hidden, 1), nn.Sigmoid())

        self.decoder = DiffusionDecoder(hidden, n_slots, steps, dropout)

    def forward(self, node_feat: torch.Tensor, adj_by_type: torch.Tensor,
                type_mask: torch.Tensor, mask: torch.Tensor,
                graph_feat: Optional[torch.Tensor] = None):
        h = torch.relu(self.in_norm(self.proj(node_feat))) * mask.unsqueeze(-1)
        ctx = (type_mask.unsqueeze(-1) * self.type_emb.weight.unsqueeze(0)).sum(1)
        h = h + self.ctx_proj(ctx).unsqueeze(1)

        fused, gate_probs, struct_ctx = self.moe(h, adj_by_type, type_mask, mask,
                                                 graph_feat=graph_feat)
        # 主干深层推理
        for blk in self.globals:
            fused = blk(fused, mask)

        denom = mask.sum(dim=1, keepdim=True).clamp(min=1.0)      # [B,1]
        pooled = (fused * mask.unsqueeze(-1)).sum(dim=1) / denom  # [B,H]
        zg = torch.cat([fused, pooled.unsqueeze(1).expand(-1, fused.shape[1], -1),
                        struct_ctx.unsqueeze(1).expand(-1, fused.shape[1], -1)], dim=-1)

        gc = torch.cat([pooled, struct_ctx], dim=-1)
        priority = self.priority_head(zg).squeeze(-1) * mask     # [B,N]
        format_logits = self.format_head(gc)                      # [B,4]
        days_estimate = self.days_head(gc)                        # [B,1]
        slot_logits = self.decoder(fused, mask)                   # [B,N,K]
        lane_logits = self.lane_head(zg)                          # [B,N,K]
        quality_score = self.quality_head(gc)                     # [B,1] ∈ (0,1)
        # 返回顺序即 ONNX 输出顺序（export_super_moe_onnx 的 output_names 必须同步）：
        #   0 priority / 1 slot_logits / 2 task_probs / 3 format_logits
        #   4 days_estimate / 5 lane_logits / 6 quality_score
        # ⚠️ **新输出只能往尾部追加**：Java 侧按名读取（见 SuperMoeService），
        #    但历史版本是按索引读的，插在中间会让老代码静默读错张量。
        return (priority, slot_logits, gate_probs, format_logits,
                days_estimate, lane_logits, quality_score)

    def training_loss(self, priority_target: torch.Tensor, slot_target: torch.Tensor,
                      node_feat: torch.Tensor, adj_by_type: torch.Tensor,
                      type_mask: torch.Tensor, mask: torch.Tensor,
                      format_target: Optional[torch.Tensor] = None,
                      w_lb: float = 0.01,
                      graph_feat: Optional[torch.Tensor] = None,
                      lane_mask: Optional[torch.Tensor] = None,
                      days_target: Optional[torch.Tensor] = None,
                      quality_target: Optional[torch.Tensor] = None
                      ) -> Tuple[torch.Tensor, Dict[str, float]]:
        """自监督损失：优先级回归 + 槽位分配去噪 + 负载均衡。

        ``graph_feat`` 来自 {@code super_encode}（冲突密度/场地数/天数/时间目标/…）。
        不喂它，路由器的「图级」分支就永远是零向量——
        等于花了一倍参数买了个恒不激活的模块。
        """
        (priority, slot_logits, _, format_logits, days_estimate,
         lane_logits, quality_score) = self(
            node_feat, adj_by_type, type_mask, mask, graph_feat=graph_feat)
        m = mask.unsqueeze(-1)
        l_pri = (((priority - priority_target) ** 2) * mask).sum() / mask.sum().clamp(min=1.0)
        l_slot = (((slot_logits - slot_target) ** 2) * m).sum() / m.sum().clamp(min=1.0)
        loss = l_pri + l_slot + w_lb * self.moe.load_balancing_loss()
        parts = {"priority_mse": float(l_pri.item()), "slot_mse": float(l_slot.item())}
        if format_target is not None:
            # ⚠️ 用**软目标** CE（目标为 one-hot 概率）而不是 argmax 索引：
            #    ① 无球类的场景 all-zero one-hot 表示「不确定」，软目标给出均匀分布，
            #       不会像硬 argmax 那样强行把 0 当成一个真实类别；
            #    ② F.cross_entropy 只接受 Long 类别索引，传 one-hot 会报
            #       "Expected floating point type for target with class probabilities"。
            tgt = format_target.float()
            tgt = tgt / tgt.sum(dim=-1, keepdim=True).clamp(min=1e-6)
            logp = F.log_softmax(format_logits, dim=-1)
            l_fmt = -(tgt * logp).sum(dim=-1).mean()
            loss = loss + 0.3 * l_fmt
            parts["format_ce"] = float(l_fmt.item())
        if lane_mask is not None:
            # 道次派遣：只在 lane_mask=1 的单元上算（田赛/球类无道可分）。
            lm = lane_mask.unsqueeze(-1) * m
            l_lane = (((lane_logits - slot_target) ** 2) * lm).sum() / lm.sum().clamp(min=1.0)
            loss = loss + 0.3 * l_lane
            parts["lane_mse"] = float(l_lane.item())
        if days_target is not None:
            # ⚠️ 修掉一个真缺口：days_head 此前**从未被训练过** ——
            #    forward 里算出来了，training_loss 却把它丢弃（`_`），
            #    等于「工期预测」这个能力一直是个随机初始化的头。
            l_days = ((days_estimate.squeeze(-1) - days_target.squeeze(-1)) ** 2).mean()
            loss = loss + 0.3 * l_days
            parts["days_mse"] = float(l_days.item())
        if quality_target is not None:
            l_q = ((quality_score.squeeze(-1) - quality_target.squeeze(-1)) ** 2).mean()
            loss = loss + 0.3 * l_q
            parts["quality_mse"] = float(l_q.item())
        return loss, parts

    def expert_usage(self) -> Dict[str, float]:
        return self.moe.expert_usage()

    def route_entropy(self) -> float:
        """归一化路由熵（0~1）。**越接近 1 说明 9 位专家分工越均匀**。"""
        return self.moe.route_entropy()
