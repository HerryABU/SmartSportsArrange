"""搜索类专家：自回归生成 + 束搜索解码 + 指针网络。

这一族是用户点名的「搜索式神经网络」：它**不是**一次性打分，而是先自回归地
「一步步产出排布」，再在推理期用束搜索 / 退火挑更好的那条 ——
于是训练期学到的分布与推理期的搜索能各自发挥。
"""

from __future__ import annotations

import math
from typing import List, Optional, Tuple

import torch
import torch.nn as nn
import torch.nn.functional as F

from .base import ResidualMLPBlock


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
