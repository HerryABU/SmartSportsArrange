"""专家注册表：架构名 → 工厂，以及**出口统一包掩码**。

⚠️ 出口包掩码（``_apply_out_mask``）不是装饰性的：残差块的 ``x + f(x)`` 会把
padding 位的 LayerNorm(0) 重新"复活"，使 padding 位泄漏进池化。所以掩码必须加在
**每个专家的出口**，而不是只加在模型入口 —— 这是「形状全对、数值全错」的典型。
"""

from __future__ import annotations

import math
from typing import List, Optional, Tuple

import torch
import torch.nn as nn
import torch.nn.functional as F

from .base import ResidualMLPBlock
from .experts_attn import ConvSeqExpert, CrossExpert, SelfAttnExpert
from .experts_graph import GraphConvExpert, RoiPoolExpert
from .experts_search import PointerExpert, SearchExpert
from .experts_ssm import SsmExpert


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
