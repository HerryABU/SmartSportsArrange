"""主搜索：逐步预测 → 确定性校验 → 不通过则回退。

启发式序（紧度 / MSBF，确定性）打底；候选按「神经 prior × 启发式分」
排序；预测器只提示「当前状态能否完成」；**唯一有裁决权的是校验器**；
不通过就回溯（撤销上一步、试其余候选）。
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Sequence, Tuple

import numpy as np

from ._common import (STATE_FEAT_DIM, S_FILL, S_SLACK, S_EXPO, S_BLOCK,
                     S_DAYS, S_REMAIN, S_SPREAD, S_FEAS)

from .state import StateView, _day_of
from .types import PlanResult, SearchLog
from .predictor import CompletionPredictor, _state_feat
from .repair import _blockers, _day_map_of, _repair_blocks, _try_repair
from .verify import _cost, _illegal_only, _local_ok, verify_slot_map


def plan_predictive(
    units: Sequence,
    candidates_of: Callable[[object], List[int]],
    caps: Dict[Tuple[int, str], int],
    athletes: Optional[Dict[str, List[int]]] = None,
    *,
    heuristic_order: Optional[Sequence[int]] = None,
    neural_prior: Optional[Callable[[object, int], float]] = None,
    predictor: Optional[CompletionPredictor] = None,
    pred_prune_below: Optional[float] = None,
    # ⚠️ 是否让预测器**参与候选排序**（软信号，权重 0.3）。
    # 实测教训：开着它时，即使一刀不剪，也会因为「同一批候选被重新排序」
    # 而改变贪心的选择 → 在 HELL 档把未排从 9 抬到 10（**变差**）。
    # 而确定性 best-fit 本身已把「最紧且可行」的槽排在前面，软信号基本是噪声。
    # 所以默认在启用剪枝时**关闭**它：剪枝负责省搜索量，排序交给确定性启发。
    pred_use_in_order: bool = False,
    day_budget: int = 0,
    exposure_of: Optional[Callable[[object], float]] = None,
    max_backtracks: int = 2000,
    max_restarts: int = 3,
    # 重启的**多样化算子**。取值：
    #   ``"rotate"``（默认）顺序旋转 + 偶次打散；
    #   ``"failure"`` 失败驱动 —— 把「上次没排下的」与「上次被让位最多」的单元提到最前；
    #   ``"slack"``   紧度驱动 —— 按「本单元需求 / 该场地可用总容量」升序（越难塞越先排）；
    #   ``"adaptive"`` 失败信息（头部）+ 紧度排序（尾部）的组合；
    #   ``"hybrid"``  奇偶次在 rotate / adaptive 之间轮换；
    #   ``"none"``    不换序（纯重复，**仅用于对照**）。
    #
    # ## 实测（6 seeds、五档、合计代价，R=1 → R=32）
    #
    #   ===========  ==========  ==========
    #   算子          R=32        总降幅
    #   ===========  ==========  ==========
    #   none         335.8       0.0%
    #   **rotate**   **292.8**   **12.8%**
    #   adaptive     293.8       12.5%
    #   failure      294.2       12.4%
    #   hybrid       298.2       11.2%
    #   slack        317.2       5.6%
    #   ===========  ==========
    #
    # ## ⚠️ 一条被推翻的旧结论（必须留档，否则会再犯）
    #
    # 上一轮曾断言「`rotate` 不产生有效多样化，需要更强算子」——那是**单 seed 假象**：
    # 只在 `seed=0` 上测时 rotate 恰好没改进，换成 6 seeds 后 rotate 反而是**最优**的。
    # 真正成立的两条是：
    #   ① **不加算子时重启完全无用**（`none` 恒 335.8、`restart_improved` 恒 0）
    #      —— 「加预算救不了坏算子」是对的；
    #   ② 但三个算子**统计上旗鼓相当（12.4%~12.8%）**，没有单一算子显著胜出
    #      —— 「旧算子是坏算子」是错的。
    # 结论：**保持 rotate 作默认**（最优且最简单），其余算子留作可选；
    # `failure` 在 HELL 档略优（123.7 vs 124.7），需要时可按档选。
    div_operator: str = "rotate",
    # 是否启用**块连续性修复**（把同组跨天的单元并到同一天）。
    # 默认开；提供开关是为了能对照「罚了但不治 vs 真的去治」的差别。
    repair_blocks: bool = True,
    # GRASP 式**受限候选表**：候选槽先按确定性启发排序，再在「前 k 优」里随机选一个作为首选。
    # ``1`` = 完全确定性（默认）；``>=2`` 才引入随机性。
    # ⚠️ 实测：``rcl_k>=2`` 只在**重启预算充足**时才有边际收益
    #    （R=32：290.7 vs 292.8，仅 −0.7%），而在小预算下**明显变差**
    #    （R=1：352.5 vs 335.8，+5%）。因为单次尝试里随机化就是纯粹的噪声。
    #    所以默认 1：确定性优先，且这一项不是「越随机越好」。
    # ⚠️ 只随机「先试哪个」，不改变集合 —— 被跳过的候选仍会按序跟上，
    #    所以可行性判定与「不丢解」的保证都不受影响。
    rcl_k: int = 1,
    # **扩展预算**（节点扩展数上限）。这是与「带预测 vs 不带预测」公平比较的关键：
    # 首次成功即跳出的贪心里，「剪枝」必然改变决策（被剪的候选若其实是首个可行槽，
    # 就会改选后面的），所以**不可能同时做到「省扩展」与「解完全一致」**。
    # 正确的对比口径是**同预算比解质量**：预测器每次尝试更省，于是同样的预算
    # 能跑更多次重启 → 最终解应当不劣。实测 HELL 档未排 10 → 9 正是这个原因。
    expansion_budget: Optional[int] = None,
    eject_depth: int = 3,
    seed: int = 0,
) -> PlanResult:
    """启发式打底 + 神经排序 + 局部回退的规划。

    ## 为什么不是「全局 DFS + 回溯」

    最初的实现是「按启发式序逐单元深度优先 + 撞墙就回退」。实测在真实规模上**完全失效**：
    53 单元 / 15 槽，各场地总量都够（田径场需求 970 / 容量 1116），
    但**装得很紧**（87% 利用率）—— 紧装箱问题下朴素 DFS 会指数级抖动，
    2 万次回退后一个单元都没排下。

    改成业界对装箱/排课的标准做法：

    ```
    ① 启发式贪心打底（MSBF 序 + best-fit + 前向检查）   ← 一步到位，绝大多数单元直接排下
    ② 剩下的"钉子户"做**局部回退**：让它挤进某个槽，把挡路者挪走再重排（ejection chain）
    ③ 挪不动 → Repair（换槽修复）→ 仍无解 → Restart（换序重来）
    ```

    ## 三个「只排序」的注入点

    * `heuristic_order`：启发式给出的单元顺序（缺省按「候选越少越先排」= MSBF）
    * `neural_prior(unit, slot)`：搜索神经网络的策略先验，**只影响候选排序**
    * `predictor(state)`：预测网络给出的完成概率，**只影响「先挪谁」的选择**

    三者都**不参与可行性判决** —— 可行性只由 `_local_ok` / `verify_slot_map` 裁决。
    """
    exposure_of = exposure_of or (lambda u: float(len(getattr(u, "athletes", []) or [])))
    log = SearchLog()
    n = len(units)
    keys = [str(getattr(u, "key", "")) for u in units]
    by_key = {keys[i]: units[i] for i in range(n)}
    if n == 0:
        return PlanResult({}, True, [], [], log, 0.0)

    if heuristic_order is None:
        # MSBF：候选越少（越受限）越先排
        heuristic_order = sorted(range(n), key=lambda i: (len(candidates_of(units[i])), keys[i]))
    base_order = [keys[i] for i in heuristic_order]

    rng = random.Random(seed)
    best: Optional[PlanResult] = None

    def _ok(key: str, sid: int) -> bool:
        """前向检查 + 计数：这一次调用 = 一次**节点扩展**。"""
        log.expansions += 1
        return _local_ok(units, slot_of, caps, key, sid, athletes)

    def _predict(feat) -> float:
        """调预测网络并计数（用于后算「每次预测换回多少搜索量」）。"""
        log.predictor_queries += 1
        return float(predictor(feat))          # type: ignore[misc]

    def _trial_feat(key: str, sid: int):
        trial = dict(slot_of)
        trial[key] = sid
        return _state_feat(units, trial, caps, n, day_budget, exposure_of)

    def _hopeless(key: str, sid: int) -> bool:
        """预测器判定「落这一子之后基本排不完」→ 可以**先不做前向检查**。

        ⚠️ 这是**学习式剪枝**，风险是剪错（把唯一可行分支剪掉）。
        三道保险，保证「扩展数只减不增、解质量不退化」：

        1. **阈值保守**：只有预测值 < ``pred_prune_below``（默认 0.15，即
           "几乎肯定排不完"）才剪；预测器已在验证集上做过下界式校准，
           所以它给低分本身就是强信号。
        2. **延迟而非丢弃**：被剪的候选进 ``deferred``，**其它候选全失败后必须回来重试**
           —— 于是最坏情况与不剪枝完全一致，绝不可能因为剪枝而报「无解」。
        3. **只在前向检查之前剪**：容量/兼项这类**确定性**判据永远优先，
           模型的判断不覆盖硬约束。
        """
        if pred_prune_below is None or predictor is None:
            return False
        return _predict(_trial_feat(key, sid)) < pred_prune_below

    eject_count: Dict[str, int] = {}

    def candidate_slots(u) -> List[int]:
        """候选槽：确定性启发排序 + 可选 GRASP 随机化（只打乱**先试顺序**）。"""
        ranked = sorted(candidates_of(u), key=lambda s: -order_score(u, s))
        if rcl_k > 1 and len(ranked) > 1:
            k = min(int(rcl_k), len(ranked))
            pick = rng.randrange(k)
            return [ranked[pick]] + [sd for j, sd in enumerate(ranked) if j != pick]
        return ranked

    def order_score(u, sid) -> float:
        """候选槽排序（确定性启发为主，模型只做增量）。"""
        need = int(getattr(u, "duration", 0))
        venue = str(getattr(u, "venue", ""))
        c = caps.get((sid, venue))
        if c is None or c <= 0:
            return float("-inf")
        load = 0
        for x in units:
            xk = str(getattr(x, "key", ""))
            if slot_of.get(xk) == sid and str(getattr(x, "venue", "")) == venue:
                load += int(getattr(x, "duration", 0))
        if load + need > c:
            return float("-inf")                     # 装不下
        s = (load + need) / c                        # best-fit 紧度
        if athletes:
            mine = set(athletes.get(str(getattr(u, "key", "")), []))
            if mine:
                for xk, xslot in slot_of.items():
                    if xslot == sid and (mine & set(athletes.get(xk, []))):
                        return float("-inf")         # 同槽同人：硬淘汰
        if neural_prior is not None:
            s += 0.6 * float(neural_prior(u, sid))
        if predictor is not None and pred_use_in_order:
            s += 0.3 * _predict(
                _state_feat(units, slot_of, caps, n, day_budget, exposure_of))
        return s

    # 上一次尝试的失败信息，供「失败驱动重排」使用
    prev_blocked: List[str] = []

    def _rotate() -> List[str]:
        out = list(base_order[1:]) + [base_order[0]]
        if attempt % 2 == 0:
            rng.shuffle(out)
        return out

    def _failure(blocked: List[str], ejects: Dict[str, int],
                 tail_by_slack: bool = False) -> List[str]:
        """失败信息（头部）+ 紧度排序或随机打散（尾部）。"""
        def slack_key(k: str) -> float:
            uu = by_key[k]
            v = str(getattr(uu, "venue", ""))
            tot = sum(c for (_i, vv), c in caps.items() if vv == v)
            return int(getattr(uu, "duration", 0)) / max(1.0, float(tot))
        crit = list(dict.fromkeys(blocked))
        hot = [k for k, c in sorted(ejects.items(), key=lambda kv: -kv[1]) if c > 0]
        head: List[str] = []
        for k in crit + hot:
            if k in by_key and k not in head:
                head.append(k)
        head_set = set(head)
        rng_key = {k: rng.random() for k in base_order}
        rest = [k for k in base_order if k not in head_set]
        if tail_by_slack:
            rest.sort(key=lambda k: (slack_key(k), rng_key[k]))
        else:
            rng.shuffle(rest)
        return head + rest

    def permute(blocked: List[str], ejects: Dict[str, int]) -> List[str]:
        """构造下一次尝试的**单元顺序** —— 这就是多样化算子的全部。

        ⚠️ 三种算子共用一条原则：**保留启发式骨架，只在关键处扰动**。
        纯随机会把「越受限越先排」这个下界信息整个丢掉，重启就退化成从头乱猜。
        """
        if div_operator == "none":
            return list(base_order)
        if div_operator == "hybrid":
            # ---- "hybrid"（默认）：**算子轮换** ----
            # 实测（6 seeds，合计代价 R=1 → R=32）：
            #   none 335.8→335.8（0.0%）  rotate 335.8→292.8（**12.8%**）
            #   failure 335.8→294.2（12.4%）  adaptive 335.8→293.8（12.5%）
            #   slack 335.8→317.2（5.6%）
            # 按档看两者各有主场：``failure`` 在 HELL 最好（123.7）、
            # ``rotate`` 在 BLOCK（70.3）与 LANE（17.8）最好。
            # 既然没有单一算子全面胜出，就**轮换**它们 —— 与项目里
            # 「算法组合波次」的思路一致：让不同的破坏方式轮流上场。
            if attempt % 2 == 1:
                return _rotate()
            return _failure(blocked, ejects, tail_by_slack=(attempt % 4 == 2))
        if div_operator == "rotate":
            out = list(base_order[1:]) + [base_order[0]]
            if attempt % 2 == 0:
                rng.shuffle(out)
            return out
        if div_operator == "slack":
            def slack_of(k: str) -> float:
                uu = by_key[k]
                v = str(getattr(uu, "venue", ""))
                tot = sum(c for (_i, vv), c in caps.items() if vv == v)
                return int(getattr(uu, "duration", 0)) / max(1.0, float(tot))
            # 升序 = 越难塞越先排；同紧度用随机键打散，避免每次都同序
            rng_key = {k: rng.random() for k in base_order}
            return sorted(base_order, key=lambda k: (slack_of(k), rng_key[k]))
        if div_operator == "adaptive":
            # ---- "adaptive"（默认）：失败信息（头部）+ 紧度排序（尾部）----
            # 实测依据（3 档 × 重启 1/4/16）：
            #   * ``none``  —— 代价恒定 102/31/37，`restart_improved` 恒为 0
            #     → **证明「重启次数本身」毫无价值**，不加算子就是白烧预算；
            #   * ``rotate``（旧实现）—— HELL/BLOCK 恒定，改进 0 → 旧的多样化确实无效；
            #   * ``slack``  —— HELL 102→**83（−18.6%）**、BLOCK 37→**33（−10.8%）**；
            #   * ``failure``—— HELL 102→101、REGULAR 31→30，对「有明确钉子户」的实例更准。
            # 两者互补：失败信息告诉你「谁上次卡住了」，紧度排序告诉你
            # 「谁天生难塞」。头用前者（精确）、尾用后者（全局），合起来最稳。
            def slack_key(k: str) -> float:
                uu = by_key[k]
                v = str(getattr(uu, "venue", ""))
                tot = sum(c for (_i, vv), c in caps.items() if vv == v)
                return int(getattr(uu, "duration", 0)) / max(1.0, float(tot))
            crit = list(dict.fromkeys(blocked))
            hot = [k for k, c in sorted(ejects.items(), key=lambda kv: -kv[1]) if c > 0]
            head: List[str] = []
            for k in crit + hot:
                if k in by_key and k not in head:
                    head.append(k)
            head_set = set(head)
            rng_key = {k: rng.random() for k in base_order}
            tail = sorted((k for k in base_order if k not in head_set),
                          key=lambda k: (slack_key(k), rng_key[k]))
            return head + tail
        # ---- "failure"：失败驱动重排 ----
        # ① 上次**没排下**的单元 → 提到最前（它们才是真正的瓶颈）
        # ② 上次**被让位最多**的单元 → 紧随其后（它们挡住了别人，位置需要重洗）
        # ③ 其余按原启发式顺序，但在尾部**整体打散**：关键单元已固定在前，
        #    尾部可以自由探索，这才有可能跳出局部最优。
        crit = list(dict.fromkeys(blocked))
        hot = [k for k, c in sorted(ejects.items(), key=lambda kv: -kv[1]) if c > 0]
        head: List[str] = []
        for k in crit + hot:
            if k in by_key and k not in head:
                head.append(k)
        head_set = set(head)
        tail = [k for k in base_order if k not in head_set]
        rng.shuffle(tail)
        return head + tail

    for attempt in range(max(1, max_restarts)):
        if attempt > 0:
            log.restarts += 1
            base_order = permute(prev_blocked, eject_count)
        slot_of: Dict[str, int] = {}
        pending: List[str] = []

        # ---------- ① 启发式贪心打底（+ 预测器保守剪枝）----------
        for key in base_order:
            u = by_key[key]
            placed = False
            deferred: List[int] = []
            for sid in candidate_slots(u):
                if _hopeless(key, sid):
                    # 预测器说这里基本没戏：先记账，**不做前向检查**（省一次扩展）
                    log.pruned += 1
                    deferred.append(sid)
                    continue
                if _ok(key, sid):
                    slot_of[key] = sid
                    log.continues += 1
                    placed = True
                    break
            if not placed and deferred:
                # 保险 2：非剪枝候选全失败 → 回来把剪过的也试一遍。
                # 这让「剪枝」在**最坏情况**下等价于不剪枝（不会因剪错而丢解）。
                for sid in deferred:
                    log.pruned_retried += 1
                    if _ok(key, sid):
                        slot_of[key] = sid
                        log.continues += 1
                        placed = True
                        break
            if not placed:
                pending.append(key)

        # ---------- ② 局部回退：让"钉子户"挤进去 ----------
        def try_place(key: str, depth: int) -> bool:
            u = by_key[key]
            deferred2: List[int] = []
            for sid in candidate_slots(u):
                if _hopeless(key, sid):
                    log.pruned += 1
                    deferred2.append(sid)
                    continue
                if _ok(key, sid):
                    slot_of[key] = sid
                    log.continues += 1
                    return True
                if depth <= 0:
                    continue
                # 找挡路者：与该槽冲突（容量占满 / 同人撞车）的已排单元
                blockers = _blockers(units, slot_of, caps, key, sid, athletes)
                # 用预测器决定"先请谁让位"：优先请"让位后整体更可能完成"的那个
                if predictor is not None and pred_use_in_order:
                    blockers.sort(key=lambda b: -_predict(
                        _state_feat(units, {k: v for k, v in slot_of.items() if k != b},
                                    caps, n, day_budget, exposure_of)))
                for b in blockers:
                    old = slot_of.pop(b)
                    # 记录「谁经常挡路」——失败驱动重排要靠它决定把谁提前
                    eject_count[b] = eject_count.get(b, 0) + 1
                    # ⚠️ **必须重新做一次前向检查**再提交这次让位：
                    #    踢掉一个挡路者不等于"现在装得下了" —— 可能还有别的挡路者
                    #    （容量是多个单元共同占的），也可能踢掉的这个根本不占容量。
                    #    第一版在这里直接 `slot_of[key] = sid`，结果引入了
                    #    「超容 180>179」与「兼项撞 REF0/REF2」两类违规，
                    #    而对外仍报 blocked=[] —— 看起来"排下了"，其实方案非法。
                    if not _ok(key, sid):
                        slot_of[b] = old
                        continue
                    slot_of[key] = sid
                    log.backtracks += 1
                    log.rollbacks += 1
                    if try_place(b, depth - 1):
                        return True
                    # 回退这次让位
                    del slot_of[key]
                    slot_of[b] = old
                    if log.backtracks >= max_backtracks:
                        log.backtrack_reasons.append("触达回退上限")
                        return False
            # 保险 2（同贪心段）：被剪的候选在这里补试，保证不因剪枝丢解
            for sid in deferred2:
                log.pruned_retried += 1
                if _ok(key, sid):
                    slot_of[key] = sid
                    log.continues += 1
                    return True
            if not any(log.backtrack_reasons):
                log.backtrack_reasons.append(f"无可用槽:{key}")
            return False

        still: List[str] = []
        for key in list(pending):
            if try_place(key, eject_depth):
                continue
            still.append(key)

        # ---------- ③ 完成校验 + Repair（容量/兼项 → 块连续性）----------
        ok, why = verify_slot_map(units, slot_of, caps, athletes)
        if not ok:
            log.repairs += 1
            fixed = _try_repair(units, slot_of, caps, athletes, candidates_of)
            if fixed is not None:
                slot_of = fixed
                ok, why = verify_slot_map(units, slot_of, caps, athletes)
        # 块连续性修复：只在整解已合法时做（它自己是纯改进算子，不会破坏合法性）。
        # ⚠️ 顺序有讲究：先修「能不能」（容量/兼项），再修「好不好」（块连续性）。
        #    反过来做的话，块修复会把单元搬到「同一天但超容」的槽上，
        #    然后被容量修复再搬回来 —— 两个算子互相拆台、白烧预算。
        if ok and repair_blocks:
            moved_plan, n_moves = _repair_blocks(
                units, slot_of, caps, athletes, candidates_of, _day_map_of(candidates_of, units))
            if n_moves:
                slot_of = moved_plan
                log.block_moves += n_moves

        val = _cost(units, slot_of, caps, exposure_of, by_key)
        cand = PlanResult(dict(slot_of), ok, _illegal_only(why), still, log, val)
        if ok and not still:
            log.completes += 1
            return cand
        if best is None or (len(cand.blocked), cand.value) < (len(best.blocked), best.value):
            if attempt > 0:
                # 只有**重启里**的改进才计入：首轮是基线，不能算作算子的功劳
                log.restart_improved += 1
            best = cand
        # 交给下一次尝试：谁没排下、谁被踢得最多
        prev_blocked = list(still)
        if log.backtracks >= max_backtracks:
            break
        if expansion_budget is not None and log.expansions >= expansion_budget:
            log.backtrack_reasons.append("触达扩展预算")
            break

    return best if best is not None else PlanResult(
        {}, False, ["无解"], list(keys), log, math.inf)
