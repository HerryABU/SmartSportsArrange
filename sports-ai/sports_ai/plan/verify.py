"""确定性校验与状态度量：唯一有「裁决权」的一层。

预测器只提供**排序提示**，可行性一律由这里的校验器裁决 ——
这条界线是「可采纳性保护」（arXiv:2606.04860）的落地方式。
缺了它，模型的高估就会以「莫名剪掉了可行解」的形式出现，且极难定位。
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Sequence, Tuple

import numpy as np

from ._common import (STATE_FEAT_DIM, S_FILL, S_SLACK, S_EXPO, S_BLOCK,
                     S_DAYS, S_REMAIN, S_SPREAD, S_FEAS)

from .state import _day_of


def verify_slot_map(
    units: Sequence,
    slot_of: Dict[str, int],
    caps: Dict[Tuple[int, str], int],
    athletes: Optional[Dict[str, List[int]]] = None,
    need_fn: Optional[Callable[[object], int]] = None,
) -> Tuple[bool, List[str]]:
    """校验「单元 → 槽」方案是否满足**硬约束**。

    只判两件「能不能」：**容量**与**兼项**。不判「好不好」——
    碎片度、工期属于优化目标，交给上层代价函数。

    返回 ``(是否可行, 违规原因列表)``。
    """
    need = need_fn or (lambda u: int(getattr(u, "duration", 0)))
    reasons: List[str] = []

    load: Dict[Tuple[int, str], int] = {}
    for u in units:
        key = str(getattr(u, "key", ""))
        if key not in slot_of:
            reasons.append(f"未排:{key}")
            continue
        sid = slot_of[key]
        vk = (sid, str(getattr(u, "venue", "")))
        load[vk] = load.get(vk, 0) + need(u)
    for vk, used in load.items():
        c = caps.get(vk)
        # ⚠️ caps 里没有该 (槽, 场地) 组合 = 该时段这个场地**不开**，
        #    这是硬不可行，不能当作"容量无限"放过。
        if c is None:
            reasons.append(f"场地未开:{vk[1]}@{vk[0]}")
        elif used > c:
            reasons.append(f"超容:{vk[1]}@{vk[0]} {used}>{c}")

    if athletes:
        # 同一运动员不得在同一槽出现两次（槽 = 时间桶，同桶即同时段）
        seen: Dict[Tuple[int, int], str] = {}
        for u in units:
            key = str(getattr(u, "key", ""))
            sid = slot_of.get(key)
            if sid is None:
                continue
            for a in athletes.get(key, []):
                prev = seen.get((a, sid))
                if prev is not None:
                    reasons.append(f"兼项撞:{a}@{sid}({prev},{key})")
                else:
                    seen[(a, sid)] = key
    return (len(reasons) == 0), reasons


def _illegal_only(reasons: Sequence[str]) -> List[str]:
    """从 ``verify_slot_map`` 的完整原因列表里**只保留非法落位**。

    剥掉 ``未排:<key>`` 这类条目 —— 「排不下」由 ``PlanResult.blocked`` 表达，
    不属于 violations（见 ``PlanResult`` 的说明）。
    """
    return [r for r in reasons if not r.startswith("未排:")]


def _is_legal(units, slot_of: Dict[str, int], caps, athletes) -> bool:
    """**只判合法性**（容量 / 兼项 / 场地是否开），**不判完整性**。

    ⚠️ 为什么不直接用 ``verify_slot_map(...)[0]``：那会把「未排」也算作不可行。
    在**部分解**上（规划过程中很常见 —— 有些单元还没排下），
    任何移动都会被误判成非法，于是修复算子一步都动不了。

    实测表现：块连续性修复在「尚有单元未排」的实例上完全失效，
    调用方只看到「算子没效果」，看不出是**判定口径用错**。
    这与刚修的「``violations`` 不该混入未排」是同一条道理：
    **合法性 ≠ 完整性** —— 前者是「方案对不对」，后者是「方案全不全」。
    """
    _, reasons = verify_slot_map(units, slot_of, caps, athletes)
    return not _illegal_only(reasons)


def _find(units, key: str):
    for u in units:
        if str(getattr(u, "key", "")) == key:
            return u
    return None


def _local_ok(units, slot_of: Dict[str, int], caps: Dict[Tuple[int, str], int],
              key: str, sid: int, athletes: Optional[Dict[str, List[int]]] = None) -> bool:
    """**前向检查**：这一步落子是否已经违反硬约束（容量 or 兼项）。

    ⚠️ 兼项检查必须在这里，而不只是放在候选排序里：
    只排序不剪枝时，搜索会反复生成「同槽同人」的**完整方案** → 校验失败 →
    Repair 也修不动 → 回退后又走同一条路。兼项是可确定性判定的硬约束，
    就该像 CSP 的前向检查一样在**落子时**剪掉。
    """
    u = _find(units, key)
    venue = str(getattr(u, "venue", ""))
    need = int(getattr(u, "duration", 0))
    mine = set((athletes or {}).get(key, []))
    load = 0
    for x in units:
        xk = str(getattr(x, "key", ""))
        if xk == key or slot_of.get(xk) != sid:
            continue
        if str(getattr(x, "venue", "")) == venue:
            load += int(getattr(x, "duration", 0))
        if mine and (mine & set((athletes or {}).get(xk, []))):
            return False                      # 同槽同人：硬不可行（不看场地）
    c = caps.get((sid, venue))
    # ⚠️ caps 缺该组合 = 该时段该场地不开，属硬不可行（不是"无限容量"）
    return c is not None and load + need <= c


def _overloaded(units, slot_of, caps) -> List[Tuple[int, str]]:
    load: Dict[Tuple[int, str], int] = {}
    for u in units:
        k = str(getattr(u, "key", ""))
        if k not in slot_of:
            continue
        vk = (slot_of[k], str(getattr(u, "venue", "")))
        load[vk] = load.get(vk, 0) + int(getattr(u, "duration", 0))
    return [vk for vk, used in load.items() if caps.get(vk) is None or used > caps[vk]]


def _conflicts(units, slot_of, athletes) -> List[Tuple[int, int, str, str]]:
    seen: Dict[Tuple[int, int], str] = {}
    out: List[Tuple[int, int, str, str]] = []
    for u in units:
        k = str(getattr(u, "key", ""))
        sid = slot_of.get(k)
        if sid is None:
            continue
        for a in (athletes or {}).get(k, []):
            prev = seen.get((a, sid))
            if prev is not None:
                out.append((a, sid, prev, k))
            else:
                seen[(a, sid)] = k
    return out


def _cost(units, slot_of, caps, exposure_of, by_key=None) -> float:
    """统一的「越小越好」代价：未排数 ×10 + 超占 + 块断裂 + 工期跨度。"""
    unplaced = sum(1 for u in units if str(getattr(u, "key", "")) not in slot_of)
    load: Dict[Tuple[int, str], int] = {}
    for u in units:
        k = str(getattr(u, "key", ""))
        if k not in slot_of:
            continue
        vk = (slot_of[k], str(getattr(u, "venue", "")))
        load[vk] = load.get(vk, 0) + int(getattr(u, "duration", 0))
    over = sum(max(0, used - caps[vk]) for vk, used in load.items() if vk in caps)
    by_group: Dict[str, set] = {}
    for u in units:
        k = str(getattr(u, "key", ""))
        g = getattr(u, "group_key", None)
        if k in slot_of and g:
            by_group.setdefault(str(g), set()).add(_day_of(slot_of[k]))
    breaks = sum(max(0, len(d) - 1) for d in by_group.values())
    days = {_day_of(s) for s in slot_of.values()}
    return (float(unplaced) * 10.0 + float(over) / 60.0 + float(breaks)
            + float(len(days) - 1 if days else 0))
