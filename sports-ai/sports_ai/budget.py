"""训练预算 ↔ 网络深度的换算（用踩坑换来的机制）。

## 为什么需要这个东西

2026-10-03 的实测教训：把 super_moe 的 hidden 从 96 提到 128 并加深
（expert_depth=2 / n_global=2）之后，仍然按旧的 5-6 轮去训，结果

    val_loss 0.951 → 1.136，pri_mse 0.0035 → 0.0168

看起来像「深层架构不如浅层」，差点去改架构。补训 3 段（累计约 18 轮）后
val_loss 回到 1.022、pri_mse 0.00357 —— **纯粹是训练预算没跟上**。

所以：**加深网络必须同步加训练预算**。本模块把这条经验做成代码，
让「没训够」在训练日志里一眼可见，而不是靠人记得。

## 用法

    from sports_ai.budget import budget_for, report_budget

    want_e, want_p = budget_for(hidden=128, base_hidden=128,
                                depth_units=5, base_depth_units=4,
                                base_epochs=24, base_patience=8)
    report_budget("super_moe", hidden=..., depth_units=..., epochs=epochs, patience=patience, ...)

`depth_units` 由各模型自己定义口径（super_moe 用 expert_depth+n_global+1，
GNN 用 layers+1），只要同一模型内口径一致即可。
"""

from __future__ import annotations

BASE_HIDDEN = 128
"""基线档宽度：换算宽度的平方系数时以它为 1.0。"""


def depth_scale(
    hidden: int,
    base_hidden: int,
    depth_units: int,
    base_depth_units: int,
) -> float:
    """相对基线档的训练预算倍数。

    参数量近似 ∝ 宽度² × 深度，所以宽度按**平方**放大、深度按**线性**放大。
    下限 0.5：再浅也别把预算压到没有意义的量级。
    """
    width = (max(16, int(hidden)) / max(1, int(base_hidden))) ** 2
    depth = max(1, int(depth_units)) / max(1, int(base_depth_units))
    return max(0.5, width * depth)


def budget_for(
    hidden: int,
    base_hidden: int,
    depth_units: int,
    base_depth_units: int,
    base_epochs: int,
    base_patience: int,
) -> tuple:
    """返回 (建议 epochs, 建议 patience)。"""
    scale = depth_scale(hidden, base_hidden, depth_units, base_depth_units)
    return (
        max(int(base_epochs), int(round(base_epochs * scale))),
        max(int(base_patience), int(round(base_patience * scale))),
    )


def report_budget(
    tag: str,
    *,
    hidden: int,
    base_hidden: int,
    depth_units: int,
    base_depth_units: int,
    base_epochs: int,
    base_patience: int,
    epochs: int,
    patience: int,
) -> bool:
    """打印预算对照，并在轮数明显不足时告警。返回 True 表示已告警。"""
    want_epochs, want_patience = budget_for(
        hidden, base_hidden, depth_units, base_depth_units, base_epochs, base_patience
    )
    print(
        f"[budget] {tag}: hidden={hidden} 深度单位={depth_units} → "
        f"建议 epochs={want_epochs} patience={want_patience}；"
        f"本次 epochs={epochs} patience={patience}"
    )
    if epochs < want_epochs * 0.6:
        print(
            f"[budget] ⚠️ 警告：本次训练轮数明显低于该深度的建议预算"
            f"（{epochs} < {want_epochs}）。深层网络未同步加预算时，val 指标可能尚未收敛，"
            "极易被误读成「深层架构更差」—— 此事已踩过一次坑"
            "（浅层 val 0.95 → 深层初训 1.14 → 补训后 1.02）。"
            "若确实只想跑短程冒烟，忽略本警告即可。"
        )
        return True
    return False
