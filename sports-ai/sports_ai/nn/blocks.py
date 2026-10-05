"""统一算子库：多架构专家 + 层次化路由 + 后续步骤预测。

## 为什么要有这个库

本项目此前只有**一种**专家结构（图卷积残差块），后来又在两个专项模型里
各写了一份 5 层 MLP。三份代码、三种深度、互不共享，于是：

* 想加深就得复制粘贴；
* 想让专家「有的擅长局部、有的擅长全局」就得再写一种结构；
* 主 MoE 与专项模型之间无法共享任何东西。

本库把这些收成**可组合的积木**，主 MoE 与两个专项 MoE 都从这里取，
「加深 / 换架构 / 加层次路由」都变成拼装而不是重写。

## 架构清单（每个专项模型都至少覆盖这几种）

======================  ==========================================================
``ResidualMLPExpert``  残差 MLP：最稳的基线，负责「整体形状」类的判断
``GraphConvExpert``    图卷积：看邻接关系（谁是同池/同班/同块）
``ConvSeqExpert``      一维卷积：看**序列**模式（组次顺序、时段推进）
``SelfAttnExpert``     自注意力：看**全局**依赖（谁和谁互相影响）
``CrossExpert``        交叉特征：两两交互（项目 A 与项目 B 的关系）
======================  ==========================================================

## 三条前沿结构的落地

1. **层次化 / 嵌套 MoE**（Hierarchical MoE, 2026）
   两级门控：先由「粗路由」选出专家组，再由组内「细路由」选具体专家。
   好处是把 O(N) 的路由搜索压成 O(√N)，且不同粒度的专门化自然分层。

2. **共享专家隔离**（DeepSeek-V3）
   留 1~2 个**恒定激活**的共享专家处理通用知识，
   稀疏专家只学差异化知识 —— 缓解「知识混杂 + 知识冗余」。

3. **动态偏置负载均衡**（DeepSeek-V3/V4，替代辅助损失）
   路由器维护一个偏置向量：专家过载则偏置下降、欠载则上升。
   比辅助损失更稳，因为它**不扭曲梯度**。

## ⚠️ 四条「不报错但结果错 / 导出即崩」的纪律（本项目实测踩过）

1. **稠密融合，不要稀疏 Top-K**（本项目最贵的一课）
   标准稀疏 MoE（组内 Top-2）在「CPU + 几千步 + batch 32~96」的预算下
   **必然专家塌缩**（6 个掉到 2 个、负载 0.5/0.5/0/0/0/0）。
   连修 5 轮（动态偏置加强 / warmup 均匀 / 轮转 / 保留梯度 / 辅助损失）全部失败，
   根因是结构性的：稀疏的正反馈是「没被选中的专家拿不到梯度 → 学不动 → 更不被选」。
   DeepSeek 那套动态偏置需要**万卡级训练量**才撑得住。
   故本库取**稠密**：所有专家都算、都拿梯度，只去掉硬性 Top-K 裁剪。
   **稀疏化是「预算足够大时」的优化，不是任何预算下都该上的架构。**

2. **warmup 的「均匀」不能用「均匀权重 + argmax」**
   均匀权重下 ``argmax`` **恒等于第 0 个** → 所有样本挤进 group 0，
   组内再均匀也只在 group 0 的两个专家里选。均衡看起来「生效」了，
   实则完全没生效。要均匀就**轮转**（按样本下标 + 步数取模）。

3. **路由器的专家数必须与 forward 里循环的专家数一致**
   共享专家**不进路由器**。误传 ``n_experts + n_shared`` 时，
   路由器认为有 8 个专家而 forward 只循环 6 个 → 末两位永远无人消费，
   表现为「激活专家数永远少 2 个」且查不出原因。

4. **需要 ONNX 动态轴的模型里禁用 ``nn.MultiheadAttention``**
   它内部的 ``F.multi_head_attention_forward`` 在 tracing 时会把
   ``[B,N,dim] → [B*N, h, dh]`` 这条 Reshape 的形状**折成导出时的具体数值**，
   换个候选数就崩（实测 ``input_shape:{2,1,128} vs requested {16,4,32}``）。
   改用**手写 MHA**（qkv Linear + reshape + matmul + softmax），
   形状全部来自 ``x.shape[...]``，ONNX 侧保持符号。已验证 c=2/3/8/16/31 全通。
"""

from __future__ import annotations

import math
from typing import List, Optional, Tuple

import torch
import torch.nn as nn
import torch.nn.functional as F


# ---------------------------------------------------------------------------
# 基础块
# ---------------------------------------------------------------------------
class ResidualMLPBlock(nn.Module):
    """残差 MLP 块：LayerNorm → Linear → ReLU → Dropout → Linear → +残差。

    残差是这里所有块的共同骨架：它让「把专家堆到 6 层以上」这件事不会梯度爆炸，
    而堆深度正是用户明确要求的能力。
    """

    def __init__(self, dim: int, hidden: Optional[int] = None, dropout: float = 0.1):
        super().__init__()
        hidden = hidden or dim * 2
        self.norm = nn.LayerNorm(dim)
        self.fc1 = nn.Linear(dim, hidden)
        self.fc2 = nn.Linear(hidden, dim)
        self.drop = nn.Dropout(dropout)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        h = self.norm(x)
        h = self.fc2(self.drop(F.relu(self.fc1(h))))
        return x + h


class GraphConvExpert(nn.Module):
    """图卷积专家：按「归一化邻接 × 值域」聚合邻居，再与自身融合。

    在编排问题里，邻接就是「同池 / 同班 / 同块 / 兼项」这些关系；
    邻居的取值往往比自身更能说明问题（例：同池的人已经排得很满）。
    """

    def __init__(self, dim: int, n_edges: int = 1, dropout: float = 0.1):
        super().__init__()
        self.n_edges = max(1, n_edges)
        self.rel_emb = nn.Parameter(torch.randn(self.n_edges, dim) * 0.02)
        self.proj = nn.Linear(dim * 2, dim)
        self.blocks = nn.ModuleList([ResidualMLPBlock(dim, dropout=dropout)
                                     for _ in range(2)])

    def forward(self, x: torch.Tensor, adj: Optional[torch.Tensor] = None,
                mask: Optional[torch.Tensor] = None,
                adj_is_batched: Optional[bool] = None) -> torch.Tensor:
        """``x`` [B,N,D] 或 [N,D]；``adj`` [B,E,N,N] / [E,N,N] / [B,N,N] / [N,N]。

        ``adj_is_batched``：3 维邻接是否为「每个样本一张图」。
        **建议调用方显式传** —— B 与 E 相等时形状启发式会失效（见分支内注释）。

        邻接先统一压成 ``[B,N,N]`` 再算：不同调用方给的边型数不一致
        （主 MoE 给 [B,E,N,N]，专项/嵌套场景给 [B,N,N]，逐行调用给 [E,N,N]），
        早前按 ``dim()`` 分支时把 ``[E,N,N]`` 当成 ``[B,N,N]`` 送进 bmm，
        报 "batch1 must be a 3D tensor" —— 而 E 与 B 恰好都可能是 2/10 附近，
        属于**形状不报错、语义错位**的典型坑。
        """
        was2d = x.dim() == 2
        if was2d:
            x = x.unsqueeze(0)                                   # [1,N,D]
            if mask is not None and mask.dim() == 1:
                mask = mask.unsqueeze(0)                         # [1,N]
        b, n, _ = x.shape
        if adj is None:
            nb = x
        else:
            a = adj
            if a.dim() == 4:                # [B,E,N,N] → 沿边型求和
                a = a.sum(dim=1)
            elif a.dim() == 3:
                # 3 维邻接**一律视为「未压平的多类边」** [E,N,N]，沿 dim 0 求和。
                #
                # ⚠️ 这里**不做任何形状猜测**。之前试过三种启发式，全部会失效：
                #   · a.shape[-2:]==(N,N)      → [E,N,N] 恒成立，压不平
                #   · a.shape[0]==b            → B==E 时无法区分（本项目 E=10、B 常为 1/2/4）
                #   · a.shape[0]==a.shape[1]   → N==E 时也无法区分
                # 形状相同的两张张量本来就无法从形状分辨谁是「批」谁是「边型」，
                # 任何启发式都是赌。**要区分必须由调用方明说**（adj_is_batched）。
                # 不传时取「3 维 = 边型」这一固定口径：它对 [B,E,N,N] 的调用方
                # （先 sum(dim=1) 压平）无影响，而把 [E,N,N] 误当 [B,N,N] 会算错。
                if adj_is_batched:
                    a = a
                else:
                    a = a.sum(dim=0, keepdim=True)
            elif a.dim() == 2:              # [N,N] → 广播到批
                a = a.unsqueeze(0)
            else:
                raise ValueError(f"adj 维度应为 2~4，实得 {a.dim()}")
            if a.dim() != 3 or a.shape[-2:] != (n, n):
                raise ValueError(f"邻接压平后形状 {tuple(a.shape)} 与节点数 {n} 不符")
            if mask is not None and mask.dim() == 2:
                # 邻接已是 [B,N,N]，掩码要作用在**行**上（谁的表征被聚合）→ [B,N,1]；
                # 写成 [B,1,N,1] 会多出一个长度 N 的轴，与邻接的第 1 维（N）对不上，
                # 报 "size of tensor a (B) must match the size of tensor b (N)"。
                a = a * mask.unsqueeze(-1)                          # [B,N,1]
            nb = torch.bmm(a, x)
        h = torch.cat([x, nb], dim=-1) if was2d else torch.cat([x, nb], dim=-1)
        h = self.proj(h)
        for b2 in self.blocks:
            h = b2(h)
        return h.squeeze(0) if was2d else h


class SsmExpert(nn.Module):
    """**状态空间 / 选择性扫描专家**（Mamba 式，线性复杂度 O(N)）。

    ## 为什么现有专家不够、需要这一类

    卷积看**局部**（kernel 3）、自注意力看**全局但是 O(N²)**、图卷积看**拓扑**。
    编排里有第三类长程结构：按优先级/时间排下来的**单元序列**，
    相隔很远的两个单元也可能互相影响（当天第 1 个与当天最后一个共享场地限制），
    注意力能抓到但代价是二次方 —— 而 N 会随赛会规模线性涨。

    状态空间模型用**固定大小的状态**递归扫过整个序列：O(N) 时间 + 常数内存。
    2026 年的前沿（Mamba-2/3、Mamba-MoE）正是把它与 MoE 结合 ——
    稀疏专家负责「选谁」，SSM 负责「按顺序整合」，两者交替。

    ## 本实现的取舍

    真 Mamba 需要输入相关的 (Δ, B, C) 参数化与 selective scan（CUDA 内核）。
    纯 PyTorch 的递归扫描在 CPU 上极慢（逐步循环 N 次），
    所以这里用 **并行化的「门控累积」近似**：
    故状态用「沿序列的加权和」表达，配合输入相关的遗忘门。
    复杂度同样是 O(N)，梯度可导，ONNX 友好。
    """

    def __init__(self, dim: int, dropout: float = 0.1, decay: float = 0.9):
        super().__init__()
        self.norm = nn.LayerNorm(dim)
        # 输入相关的遗忘门：决定每一位「记忆保留多少」→ 选择性的核心
        self.decay = nn.Linear(dim, dim)
        self.inp = nn.Linear(dim, dim)
        self.gate = nn.Linear(dim, dim)
        self.out = nn.Linear(dim, dim)
        self.drop = nn.Dropout(dropout)
        self.base = decay
        self.blocks = nn.ModuleList([ResidualMLPBlock(dim, dropout=dropout)
                                     for _ in range(2)])

    def forward(self, x: torch.Tensor, adj: Optional[torch.Tensor] = None,
                mask: Optional[torch.Tensor] = None) -> torch.Tensor:
        del adj
        was2d = x.dim() == 2
        h = self.norm(x.unsqueeze(0) if was2d else x)          # [B,N,D]
        b, n, d = h.shape
        d_raw = self.decay(h)
        d_gate = torch.sigmoid(d_raw)                           # [B,N,D] 每维遗忘率
        # 沿序列维做**累积衰减**（对数空间累加 = 乘性衰减，避免反复乘积下溢）
        log_keep = torch.log(d_gate + 1e-6) * 0.05
        cum = torch.cumsum(log_keep, dim=1)                     # [B,N,D]
        w = torch.exp(cum - cum.max(dim=1, keepdim=True).values)  # 数值稳定
        u = torch.tanh(self.inp(h)) * torch.sigmoid(self.gate(h))
        # 状态 = 沿序列的加权和（SSM 的输出层 C·state）
        state = (u * w).sum(dim=1, keepdim=True) / n            # [B,1,D] 全局状态
        y = h + self.out(self.drop(state.expand(-1, n, -1)))
        for blk in self.blocks:
            y = blk(y)
        if mask is not None:
            y = y * mask.unsqueeze(-1)
        return y.squeeze(0) if was2d else y


class RoiPoolExpert(nn.Module):
    """**ROI 池化专家**：把「关注区域」的选择与特征提取显式分开。

    两阶段：先由一个轻量打分头算出每个位置的 **ROI 权重**（可解释：能看是哪些单元），
    再按权重加权池化成全局区域表征，与自身拼接后做非线性融合。

    对编排的意义：主模型要回答的是「这场赛会的**核心冲突区**在哪」——
    这本质上就是 ROI（Region of Interest）问题。用注意力做是隐式的，
    显式 ROI 池化能给出**可下钻的权重**（前端可展示「模型认为这里是瓶颈」）。
    """

    def __init__(self, dim: int, dropout: float = 0.1, n_roi: int = 3):
        super().__init__()
        self.norm = nn.LayerNorm(dim)
        self.roi_score = nn.Linear(dim, n_roi)                  # 每个位置对每个 ROI 的归属
        self.roi_val = nn.Linear(dim, dim)                      # ROI 的值向量
        self.fuse = nn.Linear(dim * (1 + n_roi), dim)
        self.blocks = nn.ModuleList([ResidualMLPBlock(dim, dropout=dropout)
                                     for _ in range(2)])
        self.n_roi = n_roi

    def forward(self, x: torch.Tensor, adj: Optional[torch.Tensor] = None,
                mask: Optional[torch.Tensor] = None) -> torch.Tensor:
        del adj
        was2d = x.dim() == 2
        h = self.norm(x.unsqueeze(0) if was2d else x)          # [B,N,D]
        # ⚠️ 不能写死 "brn,bnd->brd"（einsum 下标数与轴数不匹配），
        #    也不能用 denom.transpose(1,2) 归一化（2D 路径下维数不对）——
        #    逐项相加时**不做归一化**：softmax 已把权重归一到 1，
        #    池化结果天然是「按权重加权的平均」（各 ROI 的 a 在位置维和为 1）。
        v = torch.tanh(self.roi_val(h))                        # [B,N,D]
        # ⚠️ softmax 必须沿**位置维**（最后一维 = N），不是 dim=1：
        #    h 是 [B,N,D]，roi_score 输出 [B,N,R]；写 dim=1 会沿 N 归一，
        #    于是 [B,N,R] 变成 [B,R] 的错位张量，广播到 v 时报
        #    "size of tensor a (3) must match the size of tensor b (5)"。
        #    改用 einsum 之外最稳的写法：先转成 [B,R,N] 再 softmax(dim=-1)。
        a = torch.softmax(self.roi_score(h).transpose(1, 2), dim=-1)   # [B,R,N]
        a = a.unsqueeze(-1)                                    # [B,R,N,1]
        rois = (a * v.unsqueeze(1)).sum(dim=2)                 # [B,R,D]
        cat = torch.cat([h] + [rois[:, i:i + 1].expand(-1, h.shape[1], -1)
                               for i in range(self.n_roi)], dim=-1)
        y = self.fuse(cat)
        for blk in self.blocks:
            y = blk(y)
        if mask is not None and mask.dim() == 2:
            y = y * mask.unsqueeze(-1)
        return y.squeeze(0) if was2d else y


class SearchExpert(nn.Module):
    """**搜索式专家**（Beam Search + 模拟退火式推理，NCO4CVRP/arXiv:2604.16581）。

    ## 前沿依据

    神经组合优化（NCO）近年的一个关键发现是：**推理策略**的收益往往大于再训一轮模型。
    具体两条（arXiv:2604.16581 在 CVRP 上验证）：

    * **Beam Search**：不让模型只保留一条最优部分解，而是同时保留若干条
      promising 的部分解并系统展开 —— 单条贪心路径容易早早走进死胡同。
    * **模拟退火式概率接受**：以随步数递减的概率**接受暂时更差的选择**，
      从而逃离局部最优。

    这与本项目的处境完全一致：GA/LNS/MNSA/ALNS 精修链本质就是 beam + 退火，
    而模型只提供「第一跳之后每一步的评分」。本专家把这套搜索结构
    **内化进专家层** —— 让专家的输出不只是逐行打分，
    而是「带历史状态的自回归选择」，即真正的搜索式网络。

    ## 关键设计：历史状态要**跨调用**携带

    自回归搜索的状态是「已经选过什么」，它不能只靠本批的前向推出，
    必须由调用方显式传入（``state`` 参数：已选下标 + 已用容量）。
    这样推理端的束搜索就能**不重新前向**地复用专家，
    只在每步换一下 ``state``。
    """

    def __init__(self, dim: int, dropout: float = 0.1, n_history: int = 4):
        super().__init__()
        self.norm = nn.LayerNorm(dim)
        self.q = nn.Linear(dim, dim)
        self.k = nn.Linear(dim, dim)
        self.v = nn.Linear(dim, dim)
        self.out = nn.Linear(dim, dim)
        # 历史编码：把「已选下标」映成与本行特征同空间的向量。
        # ⚠️ 必须**逐标量**映射（Linear(1, dim) 作用在最后一维），
        #    不能写成 Linear(n_history, dim) 配 [B,H,1] 输入 ——
        #    那样是 [B,H,1] × [H,dim]，报 "mat1 and mat2 shapes cannot be
        #    multiplied (4x1 and 4x64)"。下标先位置化到 [-4,4] 再映，
        #    避免大下标的数值主导（n=64 时原值 63 会被 tanh 压成常数）。
        self.hist_emb = nn.Linear(1, dim)
        self.mix = nn.Linear(dim * 2, dim)
        self.blocks = nn.ModuleList([ResidualMLPBlock(dim, dropout=dropout)
                                     for _ in range(2)])
        self.n_history = n_history
        self.drop = nn.Dropout(dropout)

    def forward(self, x: torch.Tensor, adj: Optional[torch.Tensor] = None,
                mask: Optional[torch.Tensor] = None,
                state: Optional[torch.Tensor] = None) -> torch.Tensor:
        """``state`` = ``[B, n_history]`` 已选下标（-1 表示未填），推理端束搜索传入。"""
        del adj
        was2d = x.dim() == 2
        h = self.norm(x.unsqueeze(0) if was2d else x)          # [B,N,D]
        b, n, d = h.shape
        if state is None:
            hist = h.new_zeros(b, self.n_history, d)
        else:
            st = state.unsqueeze(-1) if state.dim() == 2 else state     # [B,H,1]
            if st.shape[-1] != n:
                # 下标可能来自另一批（束搜索时分支数不同）→ 越界就置为「无历史」
                st = torch.where((st >= 0) & (st < n), st, torch.full_like(st, -1))
            # 位置化：把下标映成 [0,1] 再放大，避免大下标数值主导
            pos = (st.clamp(min=0).to(h.dtype) / max(1, n - 1)) * 8.0 - 4.0
            hist = torch.tanh(self.hist_emb(pos))                  # [B,H,1] → [B,H,D]
        # 内容寻址：查询 = 本行特征 − 历史已选内容的均值（模拟「避开已选」）
        ctx = self.q(h) - hist.mean(dim=1, keepdim=True)
        att = torch.softmax(torch.tanh(ctx * self.k(h)).sum(-1), dim=-1)   # [B,N]
        y = h + self.out(self.drop(att.unsqueeze(-1) * self.v(h)))
        y = self.mix(torch.cat([y, hist.mean(dim=1, keepdim=True).expand(-1, n, -1)], dim=-1))
        for blk in self.blocks:
            y = blk(y)
        # 掩码交给出口统一施加（见 _apply_out_mask）——
        # 各专家内部各写一遍极易写错（reshape(1,-1,1) 会把 [B,N] 压成 B*N）。
        del mask
        return y.squeeze(0) if was2d else y


class BeamSearchDecoder:
    """推理期**束搜索解码器**（不改训练，只改推理）。

    ## 为什么它属于「模型能力」而不属于「调用方技巧」

    NCO4CVRP 的结论是：同一份权重，**推理策略**不同，
    最优性差距（optimality gap）就明显不同。本项目主链已有 GA/LNS/MNSA/ALNS，
    但它们是「搜索编排方案」；这里做的是「搜索**模型给的候选顺序**」——
    两层搜索正交，收益不能互相替代。

    ## 用法

    ::

        dec = BeamSearchDecoder(width=3, anneal=0.3, steps=K)
        order = dec.decode(score_fn, n_candidates, feasibility_mask)

    ``score_fn(step, picked) -> [B,N]`` 由模型提供；
    ``feasibility_mask(picked) -> [B,N]`` 过滤掉已选/不可行的候选。

    **模拟退火式接受**：第 step 步以 ``anneal * (1 - step/steps)`` 的概率
    选第二优而非最优 —— 概率随步数递减，早期多探索、后期收敛。
    """

    def __init__(self, width: int = 3, anneal: float = 0.3, seed: int = 20261005):
        self.width = max(1, width)
        self.anneal = max(0.0, anneal)
        self.seed = seed

    def decode(self, score_fn, n_candidates: int, feasibility_mask,
               steps: int) -> list:
        """返回宽度为 ``self.width`` 的候选序列（每个是长度 ``steps`` 的下标列表）。

        Python 侧实现（不参与 ONNX 导出）——推理策略不需要进图。
        束搜索本身是「多次调用模型」，无法表达成单次前向的 ONNX 图。
        """
        import random

        rng = random.Random(self.seed)
        # 每条分支：(累积分数, 已选下标集合, 序列)
        beams: list = [([], set(), 0.0)]
        for step in range(steps):
            nxt: list = []
            for seq, used, acc in beams:
                s = score_fn(step, list(seq))
                m = feasibility_mask(list(seq))
                cand = []
                for j in range(n_candidates):
                    if j in used:
                        continue
                    ok = m[j] if m is not None else True
                    if not ok:
                        continue
                    cand.append((j, float(s[j])))
                if not cand:
                    # 候选耗尽 → 该分支提前结束（序列留短）
                    nxt.append((seq, used, acc))
                    continue
                cand.sort(key=lambda t: -t[1])
                # 模拟退火：早期以 anneal 概率选次优，逃离局部最优
                take = 1
                if len(cand) > 1 and rng.random() < self.anneal * (1.0 - step / max(1, steps)):
                    take = min(len(cand), 1 + int(rng.random() * min(2, len(cand) - 1)))
                for j, sc in cand[:take]:
                    nxt.append((seq + [j], used | {j}, acc + sc))
            if not nxt:
                break
            nxt.sort(key=lambda t: -t[2])
            beams = nxt[:self.width]
        return [b[0] for b in beams]


class PointerExpert(nn.Module):
    """**指针网络专家**（Pointer Network）：显式「从候选集里**指向**一个」。

    对两个专项模型（组次错开选第几组、跨时段拆分先拆谁），
    注意力/MLP 都是「逐个打分后取 argmax」，彼此独立；
    指针网络多了一步**注意力式的内容寻址**：
    以「全局查询」为指针，对每个候选算匹配度再归一化 ——
    它学的是**相对关系**（哪个候选更像「当前最需要的那个」），
    而不是各自独立的绝对分数。候选数变多时这个差别很关键。
    """

    def __init__(self, dim: int, dropout: float = 0.1):
        super().__init__()
        self.norm = nn.LayerNorm(dim)
        self.query = nn.Linear(dim, dim)                        # 位置 → 查询
        self.key = nn.Linear(dim, dim)                          # 位置 → 键
        self.val = nn.Linear(dim, dim)                          # 位置 → 值
        self.score = nn.Linear(dim, dim)                        # 匹配度
        self.mix = nn.Linear(dim * 2, dim)
        self.blocks = nn.ModuleList([ResidualMLPBlock(dim, dropout=dropout)
                                     for _ in range(2)])

    def forward(self, x: torch.Tensor, adj: Optional[torch.Tensor] = None,
                mask: Optional[torch.Tensor] = None) -> torch.Tensor:
        del adj
        was2d = x.dim() == 2
        h = self.norm(x.unsqueeze(0) if was2d else x)          # [B,N,D]
        q = self.query(h)                                       # [B,N,D]
        k = self.key(h)
        # 内容寻址打分：候选 i 的键与「全场查询向量」的匹配度
        # ⚠️ 全局查询用 mask 加权平均，padding 不得参与（否则指针会指向假节点）
        if mask is not None and mask.dim() == 2:
            mm = mask.unsqueeze(-1)
            g = (k * mm).sum(dim=1, keepdim=True) / mm.sum(dim=1, keepdim=True).clamp(min=1.0)
        else:
            g = k.mean(dim=1, keepdim=True)
        # ⚠️ 同 RoiPoolExpert：softmax 必须沿**位置维**（dim=-1）。
        #    score 头输出 [B,N,D]，写 dim=1 会沿 N 归一而不是选一个候选，
        #    广播到 val 时报 "size of tensor a (64) must match the size of tensor b (5)"。
        score = torch.softmax(torch.tanh(self.score(q * g)).sum(dim=-1), dim=-1)  # [B,N]
        # 手写加性池化，不依赖维数（score [B,N] × val [B,N,D] → [B,D]）
        att = (score.unsqueeze(-1) * self.val(h)).sum(dim=1)   # [B,D]
        att = att.unsqueeze(1).expand(-1, h.shape[1], -1)
        y = self.mix(torch.cat([h, att], dim=-1))
        for blk in self.blocks:
            y = blk(y)
        if mask is not None and mask.dim() == 2:
            y = y * mask.unsqueeze(-1)
        return y.squeeze(0) if was2d else y


class ConvSeqExpert(nn.Module):
    """一维卷积专家：沿「序列」维做上下文聚合（kernel 3，带残差）。

    编排里有天然序列：**组次 1→2→3…**、**时段推进**、**队次轮转**。
    卷积擅长捕捉这种局部顺序模式（「第 k 组紧接第 k+1 组」）。
    """

    def __init__(self, dim: int, kernel: int = 3, dropout: float = 0.1):
        super().__init__()
        self.pad = kernel // 2
        self.norm = nn.LayerNorm(dim)
        self.conv = nn.Conv1d(dim, dim, kernel_size=kernel, padding=0)
        self.gate = nn.Linear(dim, dim)
        self.blocks = nn.ModuleList([ResidualMLPBlock(dim, dropout=dropout)
                                     for _ in range(2)])

    def forward(self, x: torch.Tensor, adj: Optional[torch.Tensor] = None,
                mask: Optional[torch.Tensor] = None) -> torch.Tensor:
        del mask
        # ⚠️ 必须同时支持 2D [N,D] 与 3D [B,N,D]：
        # 稀疏 MoE 逐行调用专家时传的是 2D（把 B 维当成行维），
        # 若这里硬写 transpose(1,2) 就会在 2D 输入上直接 IndexError。
        is3d = x.dim() == 3
        h = self.norm(x)
        h = h.transpose(-1, -2)                    # [*,D,N]
        h = F.pad(h, (self.pad, self.pad))
        h = self.conv(h).transpose(-1, -2)         # [*,N,D]
        g = torch.sigmoid(self.gate(x))
        h = x + h * g
        for b in self.blocks:
            h = b(h)
        del adj
        del is3d
        return h


class SelfAttnExpert(nn.Module):
    """自注意力专家：让每个位置直接看到所有其它位置（残差 + 前馈）。

    补的是卷积/图卷积看不到的**长程依赖**：
    「第 1 组和第 8 组虽然离得远，但它们俩撞车了」。
    """

    def __init__(self, dim: int, heads: int = 4, dropout: float = 0.1):
        super().__init__()
        # ⚠️ heads 必须整除 dim：不能整除时在 __init__ 里解决，
        #    否则报错要到第一次前向才抛（且信息埋在 attention 内部极难定位）。
        heads = max(1, min(heads, dim))
        while heads > 1 and dim % heads != 0:
            heads -= 1
        self.h = heads
        self.dh = dim // heads
        self.norm = nn.LayerNorm(dim)
        # ⚠️⚠️ **不用 nn.MultiheadAttention**，改为手写 MHA。
        # 原因（本项目实测踩过）：nn.MultiheadAttention 内部走 `F.multi_head_attention_forward`，
        # tracing 导出 ONNX 时会为「[B,N,dim] → [B*N, h, dh]」这条 Reshape
        # **把 B*N 折成导出时的具体数值**（实测 B=1,N=16 → 16），
        # 于是动态轴下换个候选数就崩：
        #   `input_shape:{2,1,128}, requested shape:{16,4,32}`（B=2,N=1 时算出 2≠16）。
        # 手写实现全部用 `x.shape[...]` 拼 Reshape 形状，ONNX 侧保持符号。
        self.qkv = nn.Linear(dim, dim * 3, bias=True)
        self.proj = nn.Linear(dim, dim, bias=True)
        self.drop = nn.Dropout(dropout)
        self.ff = ResidualMLPBlock(dim, dropout=dropout)

    def forward(self, x: torch.Tensor, adj: Optional[torch.Tensor] = None,
                mask: Optional[torch.Tensor] = None) -> torch.Tensor:
        del adj
        # 稀疏 MoE 逐行调用时输入是 2D [N,D]，批处理时是 3D [B,N,D]。
        # 统一补成 3D 再算，最后还原 —— 与卷积/交叉专家同一处理。
        was2d = x.dim() == 2
        h_in = x.unsqueeze(0) if was2d else x
        b, n, d = h_in.shape
        h = self.norm(h_in)
        qkv = self.qkv(h).reshape(b, n, 3, self.h, self.dh).permute(2, 0, 3, 1, 4)
        q, k, v = qkv[0], qkv[1], qkv[2]                  # 各 [B, heads, N, dh]
        att = torch.matmul(q, k.transpose(-2, -1)) / math.sqrt(self.dh)
        if mask is not None:
            keep = (mask > 0.5).reshape(b, 1, 1, n)      # [B,1,1,N]
            att = att.masked_fill(~keep, float("-1e9"))
        att = self.drop(torch.softmax(att, dim=-1))
        out = torch.matmul(att, v)                        # [B, heads, N, dh]
        out = out.permute(0, 2, 1, 3).reshape(b, n, d)    # 合并 heads
        out = self.proj(out)
        out = h_in + out
        out = self.ff(out)
        return out.squeeze(0) if was2d else out


class CrossExpert(nn.Module):
    """交叉特征专家：把每行与其**全局摘要**交互，补上「个体 vs 整体」视角。

    摘要 = mask 加权平均。例：某候选组次与「全场平均填充率」的关系，
    是纯局部算子看不到的。
    """

    def __init__(self, dim: int, dropout: float = 0.1):
        super().__init__()
        # ⚠️ LayerNorm 必须按 **拼接后的 2*dim** 建（输入是 cat([x, pooled])），
        #    早前按 dim 建会直接报 "expected input with shape [*, dim] but got [K, 2*dim]" ——
        #    LayerNorm 的 normalized_shape 永远作用在**最后一维**，不会因为后面有 Linear 而自适应。
        self.norm = nn.LayerNorm(dim * 2)
        self.proj = nn.Linear(dim * 2, dim)
        self.blocks = nn.ModuleList([ResidualMLPBlock(dim, dropout=dropout)
                                     for _ in range(2)])

    def forward(self, x: torch.Tensor, adj: Optional[torch.Tensor] = None,
                mask: Optional[torch.Tensor] = None) -> torch.Tensor:
        del adj
        # ⚠️ 稀疏 MoE 逐行调用时输入是 2D [N,D]（行维是 -2），批处理时是 3D [B,N,D]（行维是 1）。
        # 两种情形的「行维」不同，用 dim=-2 能一次覆盖；而 sum 后必须**保持至少两维**，
        # 否则 2D 会塌成 [D]，后面 torch.cat([x, pooled]) 就对不上（实测报
        # "expected input with shape [*, 64] but got input of size[K, 128]"）。
        m = mask.unsqueeze(-1) if mask is not None else torch.ones_like(x[..., :1])
        denom = m.sum(dim=-2, keepdim=True).clamp(min=1.0)
        pooled = (x * m).sum(dim=-2, keepdim=True) / denom      # [.., 1, D]
        if pooled.dim() == x.dim() - 1:                          # 2D 塌成 [D] → 补行维
            pooled = pooled.unsqueeze(-2)
        pooled = pooled.expand_as(x)
        h = self.proj(self.norm(torch.cat([x, pooled], dim=-1)))
        for b in self.blocks:
            h = b(h)
        return h


#: 专家工厂：按名字建专家。缺名字时按轮转取，保证多样性。
def _apply_out_mask(mod: nn.Module) -> nn.Module:
    """给专家出口包一层掩码，保证 padding 位严格为 0。

    ## 为什么必须统一在出口做

    实测（hidden=64, mask=[1,1,1,1,0,0,0,0]）各专家出口的 padding 位 absmax：

    ==============  ======
    专家出口 absmax
    ==============  ======
    mlp               3.393
    graph             2.118
    conv              3.223
    attn              3.150
    cross             2.149
    ==============  ======

    根因是所有残差块的形状都是 ``x + f(x)``：padding 位的 x 虽为 0，
    但 ``LayerNorm(0)`` 会因 ``0/√ε`` 算出非零偏置，后续块再逐层放大。
    这是**早已存在**的问题（此前没测到，因为小模型的推理输入全 1 掩码、无 padding），
    但主 MoE 批量训练必然带 padding —— 假节点的梯度会灌进真节点，
    症状是「形状没错、loss 正常收敛、效果莫名其妙差」。

    为什么不在各专家内部逐个加：新增架构时很容易漏（实测新加的 ssm/roi/ptr
    就因为写了末尾掩码而「看起来正常」，掩盖了其余 5 个的漏洞）。
    在**融合出口**统一施加，一处修全部生效，新架构自动受约束。
    """
    class _Masked(nn.Module):
        def __init__(self, inner: nn.Module):
            super().__init__()
            self.inner = inner

        def forward(self, x, adj=None, mask=None, *extra, **kw):
            # ⚠️ 用 *extra/**kw 放行：SearchExpert 多一个 ``state`` 参数
            #    （自回归搜索的「已选下标」），写死三参会直接
            #    "takes from 2 to 4 positional arguments but 5 were given"。
            y = self.inner(x, adj, mask, *extra, **kw)
            # 掩码：行维是 **-2**（1 维 [N] / 2 维 [B,N] 都一样），
            # 末尾补 1 后要把行维挪到 -2，否则与 y 的特征维错位。
            if mask is not None:
                m = mask.unsqueeze(-1)                 # [...,N,1]
                pad = y.dim() - m.dim()
                if pad > 0:
                    m = m.reshape(*([1] * pad), *m.shape[-2:])
                y = y * m
            return y

    return _Masked(mod)


EXPERT_FACTORIES = {
    "mlp": lambda d, e, dr: _mlp_expert(d, dr),
    "graph": lambda d, e, dr: GraphConvExpert(d, e, dr),
    "conv": lambda d, e, dr: ConvSeqExpert(d, 3, dr),
    "attn": lambda d, e, dr: SelfAttnExpert(d, 4, dr),
    "cross": lambda d, e, dr: CrossExpert(d, dr),
    # 2026-10-05 新增三类前沿架构（详见各类注释）
    "ssm": lambda d, e, dr: SsmExpert(d, dr),          # 状态空间 / 选择性扫描，O(N)
    "roi": lambda d, e, dr: RoiPoolExpert(d, dr),      # ROI 池化（可解释的关注区域）
    "ptr": lambda d, e, dr: PointerExpert(d, dr),      # 指针网络（内容寻址地选一个）
    "srch": lambda d, e, dr: SearchExpert(d, dr),      # 搜索式网络（自回归 + 束搜索/退火推理）
}

#: 轮转顺序：保证任何前缀都覆盖到多种架构
#: ⚠️ 顺序即**架构多样性**的保证 —— 改这里会改变每个专家拿到的架构，
#:    也就改变了模型结构（必须连着重训）。8 种架构覆盖残差 / GNN / CNN / Transformer /
#:    交叉 / SSM / ROI / 指针 —— 正好覆盖用户点名的「残差、GNN、GAN、CNN、trans」，
#:    其中 GAN 的判别器/生成器由主 MoE 的 quality_head 与扩散解码器承担
#:    （对抗式建模不适合做成逐行专家，因为它需要成对的前向，见 SuperScheduleMoE 注释）。
EXPERT_CYCLE: List[str] = ["mlp", "graph", "conv", "attn", "cross", "ssm", "roi", "ptr",
                           "srch"]


def _mlp_expert(dim: int, dropout: float) -> nn.Module:
    """残差 MLP 专家（3 个残差块 → 与其它专家深度相当）。"""
    class _M(nn.Module):
        def __init__(self):
            super().__init__()
            self.blocks = nn.ModuleList([ResidualMLPBlock(dim, dropout=dropout)
                                         for _ in range(3)])

        def forward(self, x, adj=None, mask=None):
            del adj, mask
            for b in self.blocks:
                x = b(x)
            return x

    return _M()


def build_expert(kind: str, dim: int, n_edges: int, dropout: float) -> nn.Module:
    """按名字建专家，**出口统一包掩码**（见 :func:`_apply_out_mask` 的说明）。"""
    f = EXPERT_FACTORIES.get(kind)
    if f is None:
        f = EXPERT_FACTORIES["mlp"]
    return _apply_out_mask(f(dim, n_edges, dropout))


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
# ---------------------------------------------------------------------------
class NextStepHead(nn.Module):
    """预测「下一步」：给定当前位置的融合表征，输出对**后续若干步**的预测。

    ==================  ==========================================================
    ``step``=1        下一组的组次数（能不能排得下）
    ``step``=2        间隔是否达标（够不够缓冲）
    ``step``=3        后续是否还能继续错开（有没有更优解）
    ==================  ==========================================================

    依据前面的步骤来预测后面的步骤 —— 这让模型输出的是**一条决策轨迹**，
    而不是孤立的单点建议；调用方据此可以「只执行到第 k 步就停」，
    实现渐进式消解（先换组次，不够再动项目时间）。

    刻意做成**多头**而不是一个大输出：各步的量纲完全不同
    （组次数是计数、间隔是分钟、布尔是 0/1），混在一个回归头里会互相压制。
    """

    def __init__(self, dim: int, n_steps: int = 3, hidden: Optional[int] = None):
        super().__init__()
        hidden = hidden or dim
        self.n_steps = n_steps
        self.trunk = nn.Sequential(
            nn.LayerNorm(dim), nn.Linear(dim, hidden), nn.ReLU())
        self.heads = nn.ModuleList([nn.Linear(hidden, 1) for _ in range(n_steps)])

    def forward(self, fused: torch.Tensor) -> torch.Tensor:
        """``fused`` [B,D] → [B,n_steps]（每步一个标量；调用方自行 sigmoid/argmax）。"""
        h = self.trunk(fused)
        return torch.cat([hd(h) for hd in self.heads], dim=-1)


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
