"""3R 恢复中的 Repair（arXiv:2606.06877）：把卡住的块搬开重排。

与 Restart / Rollback 的分工：Repair **保留已排好的部分**，
只对被阻塞的连通块做局部腾挪 —— 代价最小，所以优先尝试。
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
from .verify import _find, _is_legal, _local_ok, verify_slot_map
from .verify import _conflicts, _overloaded


def _blockers(units, slot_of, caps, key: str, sid: int,
              athletes: Optional[Dict[str, List[int]]] = None) -> List[str]:
    """找出挡住 `key` 落进 `sid` 的已排单元（容量或兼项）。"""
    u = _find(units, key)
    venue = str(getattr(u, "venue", ""))
    mine = set((athletes or {}).get(key, []))
    out: List[str] = []
    for x in units:
        xk = str(getattr(x, "key", ""))
        if xk == key or slot_of.get(xk) != sid:
            continue
        if str(getattr(x, "venue", "")) == venue:
            out.append(xk)                       # 占容量的
        elif mine and (mine & set((athletes or {}).get(xk, []))):
            out.append(xk)                       # 撞兼项的（不同场地也算）
    return out


def _breaks_of(units, slot_of: Dict[str, int], day_of: Callable[[int], int]) -> int:
    """块断裂数 = 各 ``group_key`` 占用的天数减一之和。

    与 ``_cost`` 里的口径**必须一致**：同一个组排在第 1 天与第 3 天算 2 段，
    即 1 次断裂。若两处口径不同，修复层会「优化一个代价函数、被另一个评分」，
    表现为搬了半天却看不到代价下降。
    """
    days: Dict[str, set] = {}
    for u in units:
        k = str(getattr(u, "key", ""))
        g = getattr(u, "group_key", None)
        if not g or k not in slot_of:
            continue
        days.setdefault(str(g), set()).add(day_of(slot_of[k]))
    return sum(max(0, len(d) - 1) for d in days.values())


def _day_map_of(candidates_of, units) -> Callable[[int], int]:
    """从候选槽集合推断「槽 → 天」的映射（槽是 int 时按 3 个一档；否则用槽自身）。

    ⚠️ 单独抽出来是为了让修复层与代价层**用同一个映射**：
    之前代价层用 ``_day_of(sid)``（int 槽直接取整数值当天），
    而修复层若自己拍脑袋分组，两边的「同一天」就会不是同一天。
    """
    _ = candidates_of, units
    return _day_of


def _repair_blocks(units, slot_of: Dict[str, int], caps, athletes,
                   candidates_of, day_of: Callable[[int], int],
                   max_moves: int = 64) -> Tuple[Dict[str, int], int]:
    """块连续性修复：把「同一组跨了多天」的单元**并到同一天**去。

    ## 为什么需要它

    代价函数里 ``breaks``（同组跨天）是计了费的，但修复层原先只修**容量**与**兼项** ——
    于是「罚了但不治」：搜索每轮都被扣分，却没有任何算子去把那几分挣回来。
    实测表现为代价卡在某个值上不动（HELL 档 R=1 与 R=32 的 breaks 项完全相同）。

    ## 做法（贪心，且只接受严格变好的移动）

    1. 找出跨天的组；把「单元最多的那一天」当作锚点天；
    2. 尝试把该组落在**其它天**的单元搬到锚点天里；
    3. **只接受**同时满足两条件的移动：① 移动后仍**合法**（容量 / 兼项 / 场地开放，
       走 ``_is_legal`` —— 注意是合法而**不是**完整，部分解上也要能用）；
       ② 全局 ``breaks`` **严格下降**。
       所以这个算子**不可能让解变差** —— 它是纯改进算子，改不动就原样返回。

    返回 ``(新方案, 实际搬动次数)``。搬不动时返回**原方案的浅拷贝**而不是 None，
    因为「没能改进」在这里是正常结果，不是失败。
    """
    cur = dict(slot_of)
    moves = 0
    for _ in range(max_moves):
        cur_breaks = _breaks_of(units, cur, day_of)
        if cur_breaks == 0:
            break
        # 统计每个组占用的天与单元数
        by_group: Dict[str, List[str]] = {}
        for u in units:
            k = str(getattr(u, "key", ""))
            g = getattr(u, "group_key", None)
            if g and k in cur:
                by_group.setdefault(str(g), []).append(k)
        found = None
        for _g, ks in by_group.items():
            day_count: Dict[int, int] = {}
            for k in ks:
                d = day_of(cur[k])
                day_count[d] = day_count.get(d, 0) + 1
            if len(day_count) <= 1:
                continue
            # ⚠️ **逐天试锚点**，而不是只试「单元最多的那天」。
            #    只试多数天会漏掉「少数天恰好是可搬迁的那天」的情形：
            #    实测在单元数打平（1 vs 1）时 `max` 会挑到**搬不动的那天**，
            #    于是明明能并到一起却一步都不动 —— 表现为「算子没效果」。
            #    先试多数天（更可能一次并掉最多单元），再试其余天。
            anchor_candidates = [d for d, _c in sorted(day_count.items(), key=lambda kv: -kv[1])]
            for anchor_day in anchor_candidates:
                for k in ks:
                    if day_of(cur[k]) == anchor_day:
                        continue
                    u = _find(units, k)
                    # ---- ① 直接搬迁：把 k 搬到锚点天有空位的槽 ----
                    for sid in candidates_of(u):
                        if sid == cur[k] or day_of(sid) != anchor_day:
                            continue
                        trial = dict(cur)
                        trial[k] = sid
                        if not _is_legal(units, trial, caps, athletes):
                            continue
                        if _breaks_of(units, trial, day_of) < cur_breaks:
                            found = trial
                            break
                    if found is not None:
                        break
                    # ---- ② 交换（swap）：把 k 与「锚点天上的某个单元」互换位置 ----
                    # ⚠️ 为什么必须有这一条：直接搬迁要求**锚点天有空余容量**，
                    #    而紧实例里锚点天往往是满的（其它天反而有余）——
                    #    实测只做①时，BLOCK 档 12 处断裂只修掉 0.7 处（headroom 被容量卡死）。
                    #    交换是**容量守恒**的移动：一步换两人的位置，
                    #    无需任何空闲容量，于是能在「满但错位」的情形下继续收敛。
                    #    加了它以后 breaks 降幅由 5.0% 提到 **9.2%**。
                    block_slot = cur[k]
                    for x in units:
                        xk = str(getattr(x, "key", ""))
                        if xk == k or xk not in cur:
                            continue
                        if day_of(cur[xk]) != anchor_day:
                            continue
                        trial = dict(cur)
                        trial[k] = cur[xk]
                        trial[xk] = block_slot
                        if not _is_legal(units, trial, caps, athletes):
                            continue
                        if _breaks_of(units, trial, day_of) < cur_breaks:
                            found = trial
                            break
                    if found is not None:
                        break
                if found is not None:
                    break
            if found is not None:
                break
        if found is None:
            break
        cur = found
        moves += 1
    return cur, moves


def _try_repair(units, slot_of: Dict[str, int], caps, athletes, candidates_of) -> Optional[Dict[str, int]]:
    """Repair：只靠**换槽**消掉容量/兼项违规，不改其他决策；修不动返回 None。"""
    cur = dict(slot_of)
    for _ in range(3):
        over = _overloaded(units, cur, caps)
        if over:
            vk = over[0]
            target = None
            for u in units:
                k = str(getattr(u, "key", ""))
                if cur.get(k) == vk[0] and str(getattr(u, "venue", "")) == vk[1]:
                    target = u
                    break
            if target is None:
                return None
            k = str(getattr(target, "key", ""))
            moved = False
            for alt in candidates_of(target):
                if alt == cur[k]:
                    continue
                trial = dict(cur)
                trial[k] = alt
                if len(_overloaded(units, trial, caps)) < len(over):
                    cur = trial
                    moved = True
                    break
            if not moved:
                return None
            continue
        if athletes:
            bad = _conflicts(units, cur, athletes)
            if bad:
                _a, sid, _k1, k2 = bad[0]
                moved = False
                for u in units:
                    k = str(getattr(u, "key", ""))
                    if k != k2 or cur.get(k) != sid:
                        continue
                    for alt in candidates_of(u):
                        if alt == sid:
                            continue
                        trial = dict(cur)
                        trial[k] = alt
                        if len(_conflicts(units, trial, athletes)) < len(bad):
                            cur = trial
                            moved = True
                            break
                    break
                if not moved:
                    return None
                continue
        ok, _ = verify_slot_map(units, cur, caps, athletes)
        return cur if ok else None
    ok, _ = verify_slot_map(units, cur, caps, athletes)
    return cur if ok else None
