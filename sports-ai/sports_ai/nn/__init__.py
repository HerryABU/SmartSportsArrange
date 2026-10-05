"""统一算子库：多架构专家 + 层次化 MoE 路由 + 后续步骤预测。

主 MoE（``models/super_moe.py``）与两个专项 MoE
（``heat_stagger_advisor`` / ``slot_split_advisor``）都从这里取积木，
保证三者架构同源、深度一致、可共享演进。
"""

from sports_ai.nn.blocks import (EXPERT_CYCLE, EXPERT_FACTORIES, ConvSeqExpert,
                                 CrossExpert, GraphConvExpert, HierarchicalRouter,
                                 NextStepHead, PointerExpert, ResidualMLPBlock,
                                 RoiPoolExpert, SelfAttnExpert, SpecialistMoE,
                                 SsmExpert, build_expert)

__all__ = [
    "ResidualMLPBlock", "GraphConvExpert", "ConvSeqExpert", "SelfAttnExpert",
    "CrossExpert", "SsmExpert", "RoiPoolExpert", "PointerExpert",
    "HierarchicalRouter", "NextStepHead", "SpecialistMoE",
    "build_expert", "EXPERT_FACTORIES", "EXPERT_CYCLE",
]
