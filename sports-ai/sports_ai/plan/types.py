"""搜索过程的记录类型：可诊断的搜索日志与最终结果。

「三动作回退」（arXiv:2607.07492 的 continue / complete / backtrack）：
每一步都归为三种动作之一并记录回退原因 —— 于是「为什么这次排不出来」
可以从日志直接读出来，而不是靠重跑加打印。
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Sequence, Tuple

import numpy as np



@dataclass
class SearchLog:
    """搜索过程的可诊断日志（三动作 + 3R）。"""

    continues: int = 0
    completes: int = 0
    backtracks: int = 0
    repairs: int = 0
    restarts: int = 0
    rollbacks: int = 0
    # ---- 搜索量指标（对应 arXiv:2606.04860 的核心评价维度）----
    # ``expansions`` = 实际做过的**候选落位检查**次数（前向检查的调用次数）。
    # 这就是分支定界里的「节点扩展数」在本问题上的对应物：
    # 每检查一个「当前单元 → 某个候选槽」的组合，就等于扩展了一个搜索节点。
    # ⚠️ 它是**真正的搜索成本**：``continues`` 只数了成功的落位，
    #    而预测器的作用恰恰是「少检查那些注定失败的槽」—— 收益体现在 expansions 上，
    #    不是 continues 上。用 continues 当指标会把预测器的收益完全看不出来。
    expansions: int = 0
    # ``pruned`` = 被预测器**拦下未做前向检查**的候选数（真正的省）
    pruned: int = 0
    # ``pruned_retried`` = 拦下后又因「其它候选全失败」被追回来重试的数量。
    # 它衡量「保守剪枝的代价」：理想情况接近 0。
    pruned_retried: int = 0
    # ``predictor_queries`` = 调用预测网络的次数（它的推理成本，用于算性价比）
    predictor_queries: int = 0
    # ``block_moves`` = 块连续性修复实际搬动的单元数。
    # 它回答「『同组跨天』这个代价项有没有对应的**修复动作**」——
    # 没有它的话，代价里罚了块断裂却没人去修，等于「罚了但不治」。
    block_moves: int = 0
    # ``restart_improved`` = **重启里有多少次刷新了历史最优**。
    # ⚠️ 这是判定「多样化算子是否有效」的唯一直接指标：
    #    如果重启次数翻 4 倍而它恒为 0，说明算子只是重复走同一条路，
    #    「多跑几次」纯属浪费预算 —— 加预算救不了坏算子。
    restart_improved: int = 0
    backtrack_reasons: List[str] = field(default_factory=list)

    def as_dict(self) -> Dict[str, object]:
        return {
            "continues": self.continues,
            "completes": self.completes,
            "backtracks": self.backtracks,
            "repairs": self.repairs,
            "restarts": self.restarts,
            "rollbacks": self.rollbacks,
            "expansions": self.expansions,
            "pruned": self.pruned,
            "pruned_retried": self.pruned_retried,
            "predictor_queries": self.predictor_queries,
            "restart_improved": self.restart_improved,
            "block_moves": self.block_moves,
            "reasons": self.backtrack_reasons[:10],
        }


@dataclass
class PlanResult:
    """规划结果。

    ## ⚠️ ``violations`` 与 ``blocked`` 必须是两件事（曾混在一起）

    ``violations`` 只承载**非法落位**（超容 / 兼项撞 / 场地未开），
    ``blocked`` 只承载**排不下**。

    为什么必须分开：这两者的处置完全不同 ——
    前者说明**方案本身是错的**（实现有缺陷，应当立刻暴露），
    后者是**客观差多少**（容量不够，应当如实上报并给出可行的那部分）。
    把它们混在一起，会出现「所有单元都排下了，violations 却非空」的假警报，
    或者反过来 —— 「violations 为空」被误读成「方案合法」，
    而实际上全部 50 个单元一个都没排。

    ``verify_slot_map`` 为了能做整解可行性判定，仍会返回带 ``未排:`` 的完整原因列表；
    这里按前缀把「未排」剥离到 ``blocked`` 语义之外，与 Java 侧
    ``PredictivePlanner.verify()``（只报超容/兼项/场地未开）**逐字对齐**。
    """

    slot_of: Dict[str, int]
    feasible: bool
    violations: List[str]
    blocked: List[str]
    log: SearchLog
    value: float

    def as_dict(self) -> Dict[str, object]:
        return {
            "feasible": self.feasible,
            "violations": self.violations[:6],
            "blocked": self.blocked[:6],
            "value": round(self.value, 4),
            "log": self.log.as_dict(),
        }
