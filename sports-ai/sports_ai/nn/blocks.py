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

# ⚠️ 本文件曾是 1022 行的单文件实现，2026-10-06 按「一族一文件」拆开。
# 这里做**门面再导出**，因此所有原有 import 路径（from sports_ai.nn.blocks import X）
# 保持不变 —— 拆分的目的是让每个文件只承担一族架构，不是改调用方。
from .base import ResidualMLPBlock  # noqa: F401
from .experts_attn import ConvSeqExpert, CrossExpert, SelfAttnExpert  # noqa: F401
from .experts_graph import GraphConvExpert, RoiPoolExpert  # noqa: F401
from .experts_search import BeamSearchDecoder, PointerExpert, SearchExpert  # noqa: F401
from .experts_ssm import SsmExpert  # noqa: F401
from .experts_registry import (EXPERT_CYCLE, EXPERT_FACTORIES, build_expert,  # noqa: F401
                               _apply_out_mask, _mlp_expert)
from .router import HierarchicalRouter  # noqa: F401
from .heads import NextStepHead  # noqa: F401
from .specialist import SpecialistMoE  # noqa: F401

__all__ = [
    "ResidualMLPBlock", "GraphConvExpert", "SsmExpert", "RoiPoolExpert",
    "SearchExpert", "BeamSearchDecoder", "PointerExpert", "ConvSeqExpert",
    "SelfAttnExpert", "CrossExpert", "EXPERT_FACTORIES", "EXPERT_CYCLE",
    "build_expert", "HierarchicalRouter", "NextStepHead", "SpecialistMoE",
]
