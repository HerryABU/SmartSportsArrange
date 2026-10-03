"""硬约束修复层：神经网络输出 → 规则兜底 → 可交付方案。

## 为什么必须有这一层

深度模型学的是**排序/打分**，它没有任何机制保证「同一个人不会在两个项目里
同一时间开赛」这种硬约束成立。把模型输出直接当赛程交付，等于让概率模型的
近似误差去承担 100% 不能错的事故风险 —— 这是整个超级 MoE 架构里最容易被
忽视、一旦出错最致命的一环。

所以分层：

* **模型**负责「先排谁、往哪个槽放更划算」（全局搜索、长程结构）；
* **修复层**负责「放不下就挪、撞了就错开」（硬约束 100% 成立）；
* **传统算子**（Fix-and-Optimize / ALNS）在修复后的解上继续压缩工期。

## 只修「能不能」，不修「好不好」

⚠️ 这里刻意**不做**「项目块归拢」的搬移动作。同块单元朝彼此靠拢看似该做，
但搬动必然重算容量与兼项，而块形状是质量指标（由场景生成与模型负责），
在修复层里硬搬只会在「挪过来撞、挪过去超」之间震荡。
块形状作为**评测指标**（碎片度）单独报出来，交给上层算子优化。

## 修不动就老实认

装不下的孤儿单元记入 ``blocked``，调用方应退回贪心基线 ——
**修不好就诚实报 blocked，不要假装可行**。
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional, Tuple

MAX_ROUNDS = 4      # 修复迭代上限，防止「挪过来撞、挪过去超」的死循环


def need_of(u: Any) -> int:
    """单元占用量 = 时长 + 最小间隔。

    ⚠️ 必须与场景生成 / 贪心落位用**同一个口径**。早期两处一边按 ``duration``
    一边按 ``duration + interval`` 算，于是模型以为装得下的位置在修复层被判
    超载，来回搬几十次也不收敛。
    """
    return int(getattr(u, "duration", 0) or 0) + int(getattr(u, "interval", 0) or 0)


def _slot_key(sid: Tuple[int, int]) -> Tuple[int, int]:
    return (sid[0], sid[1])


def repair_assignment(units: List[Any], slot_of: Dict[str, Tuple[int, int]],
                      cap_by_slot_venue: Dict[Tuple[Tuple[int, int], str], int],
                      athlete_of: Optional[Dict[str, List[int]]] = None,
                      max_rounds: int = MAX_ROUNDS
                      ) -> Tuple[Dict[str, Tuple[int, int]], Dict[str, Any]]:
    """把一份槽位分配修成硬约束可行，返回 ``(修复后分配, 报告)``。

    修复顺序：先容量（能不能放），再兼项（同人能不能同槽）。

    :param slot_of:           单元 key → 槽 id ``(day, window_idx)``
    :param cap_by_slot_venue: ``(槽 id, 场地)`` → 该槽该场地的容量
    :param athlete_of:        单元 key → 参与者 id 列表；None 表示无兼项约束
    """
    report: Dict[str, Any] = {"capacity_fixes": 0, "conflict_fixes": 0,
                              "blocked": [], "rounds": 0, "feasible": True}
    slot_of = dict(slot_of)
    # ⚠️ 可用槽必须取自**容量表的全部槽**，不能从 slot_of 的当前值推 ——
    #    模型把全部单元塞进同一个槽时，slot_of 里就只有那一个槽，
    #    据此得到的「后续槽」为空，于是明明有空场却报「无空槽」。
    order = sorted({s for (s, _v), c in cap_by_slot_venue.items() if c > 0}, key=_slot_key)
    if not order:
        report["blocked"].append("没有任何可用槽")
        report["feasible"] = False
        return slot_of, report

    for rnd in range(max_rounds):
        report["rounds"] = rnd + 1
        moved_capacity = _fix_capacity(units, slot_of, cap_by_slot_venue, order, report)
        moved_conflict = _fix_conflict(units, slot_of, athlete_of,
                                       cap_by_slot_venue, order, report)
        if not moved_capacity and not moved_conflict:
            break

    # ---- 收尾自检：还超标就是真修不动了，别蒙混过关 ----
    un = {getattr(u, "key", None): u for u in units}
    load: Dict[Tuple[Tuple[int, int], str], int] = {}
    for key, sid in slot_of.items():
        u = un.get(key)
        if u is None:
            continue
        v = getattr(u, "venue", "V0")
        load[(sid, v)] = load.get((sid, v), 0) + need_of(u)
    for (sid, venue), used in load.items():
        cap = cap_by_slot_venue.get((sid, venue), 0)
        if used > cap:
            report["feasible"] = False
            report["blocked"].append(f"容量仍超载 {sid}/{venue}: {used}>{cap}")

    if athlete_of:
        # ⚠️ seen 必须连**槽**一起记：同一个人在不同槽的各一个单元是完全合法的，
        #    只按运动员 id 判重会把「A 与 B 不同槽」误报成撞车 ——
        #    自检虚报 makes 明明可行却 degraded，反而把好解判死。
        seen: Dict[int, Tuple[str, Tuple[int, int]]] = {}
        for key, sid in slot_of.items():
            for a in athlete_of.get(key, []):
                prev = seen.get(a)
                if prev is not None and prev[1] == sid:
                    report["feasible"] = False
                    report["blocked"].append(
                        f"兼项仍撞车: {prev[0]} 与 {key} 同在 {sid}")
                else:
                    seen[a] = (key, sid)

    return slot_of, report


def _relocate(units: List[Any], slot_of: Dict[str, Tuple[int, int]], key: str,
              cap_by_slot_venue: Dict[Tuple[Tuple[int, int], str], int],
              load: Dict[Tuple[Tuple[int, int], str], int],
              order: List[Tuple[int, int]]) -> bool:
    """把单元挪到第一个**装得下**的后续槽（往后找，保持时间单调）。"""
    cur = slot_of.get(key)
    if cur is None:
        return False
    unit = None
    for u in units:
        if getattr(u, "key", None) == key:
            unit = u
            break
    if unit is None:
        return False
    venue = getattr(unit, "venue", "V0")
    need = need_of(unit)
    try:
        start = order.index(cur)
    except ValueError:
        start = -1
    for sid in order[start + 1:]:
        cap = cap_by_slot_venue.get((sid, venue), 0)
        if cap <= 0:
            continue
        if load.get((sid, venue), 0) + need <= cap:
            slot_of[key] = sid
            load[(sid, venue)] = load.get((sid, venue), 0) + need
            load[(cur, venue)] = load.get((cur, venue), 0) - need
            return True
    return False


def _fix_capacity(units: List[Any], slot_of: Dict[str, Tuple[int, int]],
                  cap_by_slot_venue: Dict[Tuple[Tuple[int, int], str], int],
                  order: List[Tuple[int, int]], report: Dict[str, Any]) -> bool:
    """超载槽里挑「最占地方」的单元挪走（挪走收益最大，收敛最快）。"""
    un = {getattr(u, "key", None): u for u in units}
    load: Dict[Tuple[Tuple[int, int], str], int] = {}
    for key, sid in slot_of.items():
        u = un.get(key)
        if u is None:
            continue
        v = getattr(u, "venue", "V0")
        load[(sid, v)] = load.get((sid, v), 0) + need_of(u)

    for (sid, venue), used in sorted(load.items(), key=lambda kv: -kv[1]):
        cap = cap_by_slot_venue.get((sid, venue), 0)
        if used <= cap:
            continue
        occupants = [k for k, s in slot_of.items()
                     if s == sid and getattr(un.get(k), "venue", None) == venue]
        occupants.sort(key=lambda k: -need_of(un.get(k)))
        for k in occupants:
            if _relocate(units, slot_of, k, cap_by_slot_venue, load, order):
                report["capacity_fixes"] += 1
                return True
        report["blocked"].append(f"{sid}/{venue} 超载 {used}>{cap} 且无空槽")
    return False


def _fix_conflict(units: List[Any], slot_of: Dict[str, Tuple[int, int]],
                  athlete_of: Optional[Dict[str, List[int]]],
                  cap_by_slot_venue: Dict[Tuple[Tuple[int, int], str], int],
                  order: List[Tuple[int, int]], report: Dict[str, Any]) -> bool:
    """同一人在同一槽 → 把**时间靠后**的那个往后挪（不动已稳定的早场安排）。"""
    if not athlete_of:
        return False
    key_of = {getattr(u, "key", None): u for u in units}
    load: Dict[Tuple[Tuple[int, int], str], int] = {}
    with_slot: Dict[Tuple[int, int], List[str]] = {}
    for key, sid in slot_of.items():
        u = key_of.get(key)
        if u is None:
            continue
        v = getattr(u, "venue", "V0")
        load[(sid, v)] = load.get((sid, v), 0) + need_of(u)
        with_slot.setdefault(sid, []).append(key)

    for sid, keys in with_slot.items():
        seen: Dict[int, str] = {}
        for k in sorted(keys, key=lambda x: _slot_key(slot_of[x])):
            for a in athlete_of.get(k, []):
                if a in seen:
                    if _relocate(units, slot_of, k, cap_by_slot_venue, load, order):
                        report["conflict_fixes"] += 1
                        return True
                    report["blocked"].append(
                        f"兼项撞车且无空槽: {seen[a]} 与 {k} 在 {sid}")
                else:
                    seen[a] = k
    return False


def fragmentation(slot_of: Dict[str, Tuple[int, int]],
                  group_of: Dict[str, str]) -> float:
    """项目块碎片度：每块占用的**不同槽数** ÷ 该块单元数，再取平均。

    取值 ``(0, 1]``：**越小越紧凑**——

    * ``1/块大小`` = 整块塞在一个槽（最理想，如 10 单元的块 = 0.1）；
    * ``1.0``       = 块内每个单元各占一个槽（最「见缝插针」）。

    ⚠️ 第一版把分子写成了 ``len(slots)``、分母 ``max(1, len(slots))``，
    于是整个比值**恒等于 1**，这条指标形同虚设（任何方案都报同样的 1.0）。
    分母必须是**块内单元数**而不是槽数——分母取槽数在数学上就消掉了。
    这是修复层刻意不优化、只负责**测量**的那一维质量指标。
    """
    slots_of: Dict[str, set] = {}
    size_of: Dict[str, int] = {}
    for key, g in group_of.items():
        if not g:
            continue
        slots_of.setdefault(g, set()).add(slot_of.get(key))
        size_of[g] = size_of.get(g, 0) + 1
    if not size_of:
        return 0.0
    frag = 0.0
    for g, size in size_of.items():
        slots = {s for s in slots_of.get(g, ()) if s is not None}
        if not slots:
            continue
        frag += len(slots) / max(1, size)
    return frag / len(size_of)
