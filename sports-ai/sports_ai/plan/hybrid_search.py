"""启发式 + 神经搜索 + 预测（可回退）—— 面向「预测后面的、发现不行就回退」的规划层。

## 这个模块解决什么

原有的求解链是「先排完，再校验，不行就整体重排」。它对现场有两个痛点：

1. **不知道该不该继续**：排到一半发现走不通时，只能整轮重来，前面白排；
2. **不敢信模型的预测**：`days_estimate` / `quality_score` 是 MSE 训练的，
   **倾向高估**（乐观）。若拿它们去剪枝，会「莫名其妙剪掉可行解」。

本模块把规划做成**逐步预测 + 确定性校验 + 可回退**的搜索：

```
启发式序（紧度/MSBF，确定性）           ← 「启发式」
  → 候选按 神经策略 prior × 启发式分 排序  ← 「搜索神经网络」只负责排序
    → 预测器判断「当前状态能否完成」      ← 「预测网络」只负责提示
      → 校验器裁决可行性（容量/兼项）     ← 唯一有裁决权的是它
        → 不通过则回溯（撤销上一步，试其余候选）
```

## 三条与 2026 前沿对应的设计（都在本文件落地）

* **状态局部化**（对应 arXiv:2605.22221 的 scattered retrieval / history entanglement）：
  预测器**只吃当前状态的紧凑特征**，绝不喂「走了哪条路」的历史轨迹。
  两个到达同一状态的不同路径必然给出同一预测 —— 这是靠**接口约束**达成的结构隔离，
  不需要改注意力或训练目标。
* **三动作回退**（对应 arXiv:2607.07492 的 continue / complete / backtrack）：
  搜到的每一步都归为三种动作之一，并记录回退原因，形成可诊断的搜索日志。
* **可采纳性保护**（对应 arXiv:2606.04860）：
  预测值经**保守化**（低估 + 偏移）后**只用于排序**；
  可行性**只由确定性校验器裁决**。缺任一条，高估就会以
  「剪掉了可行解」的形式出现，且极难定位。

另配套 **3R 恢复**（对应 arXiv:2606.06877）：Repair / Restart / Rollback。
"""

from __future__ import annotations

# ---- 门面再导出：本文件曾是 1256 行的单文件实现，2026-10-06 按依赖分层拆开。
# 这里保留全部公开名（含测试直接引用的私有名），因此调用方零改动。
from ._common import STATE_FEAT_DIM  # noqa: F401
from .state import StateView  # noqa: F401
from .verify import (verify_slot_map, _illegal_only, _is_legal, _find, _local_ok,  # noqa: F401
                     _overloaded, _conflicts, _cost)
from .types import SearchLog, PlanResult  # noqa: F401
from .predictor import (conservative_value, is_admissible, CompletionPredictor,  # noqa: F401
                        _state_feat, _greedy_complete, collect_training_pairs)
from .repair import _blockers, _breaks_of, _day_map_of, _repair_blocks, _try_repair  # noqa: F401
from .search import plan_predictive  # noqa: F401

__all__ = [
    "STATE_FEAT_DIM",
    "CompletionPredictor",
    "PlanResult",
    "StateView",
    "SearchLog",
    "collect_training_pairs",
    "conservative_value",
    "is_admissible",
    "plan_predictive",
    "verify_slot_map",
]
