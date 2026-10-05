"""场景生成钩子：让训练循环能取到「本步刚生成的那批场景」。

## 为什么需要它

给专项小模型加「未来 H 步时间槽」的预测能力，监督信号必须来自**它自己那批场景**
（`build_sequence` 的序列视图要贪心着色结果，只有场景本身能给出）。
但各数据源 `make_batch(n, seed)` 返回的是**张量**，场景对象在函数内部就丢了。

把 15 个数据源逐个改成「额外返回场景」既重复又容易漏；而重新用同一个 seed 生成
一份场景是**错的** —— `make_batch` 内部的 rng 调用序列不同，重放不出同一批。

所以在**唯一出口**（`generator.generate_scenario` 的 return 处）埋一个钩子，
训练循环取用即可。

## 设计要点

* **默认零开销**：没调用 :func:`start` 时 `record` 立即返回，不持有任何场景对象
  （场景是重对象，长期持有会吃掉大量内存）。
* **线程私有**：用 ``threading.local``，避免 DataLoader 多 worker 时串台。
* **对齐由调用方校验**：本模块只负责「如实记录」，**不做对齐假设**。
  调用方必须自己比 ``len(samples) == len(scenarios)``，不等就放弃这一步的辅助损失
  —— 数据源里常有「不合格样本被 continue 掉」的过滤，那种情况下按下标配
  等于给样本配了别人的标签（形状对、监督错，最坏的一类 bug）。
"""

from __future__ import annotations

import threading
from typing import Any, List

_local = threading.local()


def start() -> None:
    """开始记录（在本线程内）。"""
    _local.buf = []


def stop() -> None:
    """停止记录并丢弃缓冲。"""
    _local.buf = None


def record(scenario: Any) -> None:
    """记录一个刚生成的场景。未开启记录时是空操作。"""
    buf = getattr(_local, "buf", None)
    if buf is not None:
        buf.append(scenario)


def clear() -> None:
    """清空缓冲（每步训练开始前调用）。"""
    buf = getattr(_local, "buf", None)
    if buf is not None:
        buf.clear()


def collect() -> List[Any]:
    """取回本次缓冲里的场景副本。"""
    buf = getattr(_local, "buf", None)
    return list(buf) if buf else []
