"""魔鬼场景实测：把超级模型输出解码成**真实排程**，再数违规。

## 为什么必须这样评
训练 loss 降到 0.67 只说明「模型拟合了标签」，**不说明排得开**。
真正要回答的问题是（用户点名的魔鬼条件）：

* 500 人 / 15 项目 / 3 项兼项 —— 兼项运动员会不会被排到同时段？
* 300 人 / 10 项目 / 限 2~3 天 —— 工期压不压得住？
* 项目块能不能**成块**（不许见缝插针乱塞）？
* 淘汰赛晋级 + 二次编排有没有被当成一类单元排？

## 评测口径（全部是硬违规计数，不是相似度）

* **兼项冲突**：同一运动员的两个单元被排进**重叠时段**（同一时间桶）。
* **容量超占**：同一时段同一场地的已排时长 > 该场地容量。
* **块完整性**：同 ``group_key`` 的单元占据的时间桶必须是**一段连续区间**，
  且区间内不得混入其它块的单元 —— 这就是「禁止见缝插针」的形式化定义。
* **未排单元**：任何可行时段都放不下的单元（硬失败）。
* **工期**：实际占用天数（对比 ``days_limit`` 三态）。
* **道次冲突**：同批（同项目+同年级）单元被排进同一时间桶。

## 三个基线（用来证明模型真的学到了东西）

1. ``random``：随机顺序 + 第一可行桶（下界）
2. ``greedy``：按训练时那个贪心函数的顺序（模型要拟合的目标）
3. ``model``：按模型 priority 降序（模型实际给出的顺序）

只有 model 明显优于 greedy，才说明自监督标签是**可学的**；
如果 model ≈ greedy，说明模型只学到了标签、没学到更优策略。
"""

from __future__ import annotations

import argparse
import json
import math
import os
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np
import torch

from sports_ai.data.super_encode import MAX_SLOTS, encode_super_graph
from sports_ai.data.super_scenarios import (
    TASK_BALL,
    TASK_KNOCKOUT,
    TASK_LANE,
    TASK_RESECOND,
    SuperScenario,
    SuperUnit,
    generate_super_scenario,
)
from sports_ai.models.super_moe import SuperScheduleMoE

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TIERS = ("HELL", "REGULAR", "BLOCK", "LANE", "TEAM")


# ---------------------------------------------------------------- 时段建模
def build_buckets(scen: SuperScenario) -> Tuple[List[Tuple[int, int]], Dict[int, List]]:
    """把场景的 windows 压成「时间桶」。

    时间桶 = (day, window_idx)，与场地无关 —— 同一时间桶内不同场地是**并行**的。
    这是评测口径的关键：**兼项冲突看时间桶（并行不同场地也算撞），
    容量超占看具体到哪个场地**。混在一起数会既重复计数又漏掉真冲突。

    ⚠️ 桶内容量是**同桶各场地容量之和**（并行开的场地上能同时排多个单元），
       不是取最大值 —— 早期写成 max 会把并行场地当成"只开了一个场地"，
       容量被系统性低估，评测出的未排数是假象。

    ⚠️ 桶数超过 MAX_SLOTS(16) 时保留**总容量最大**的 16 个：
    随机截断会让大量单元直接无可行桶，那是评测假象不是模型能力。
    """
    cap_of: Dict[Tuple[int, int], int] = {}
    for w in scen.windows:
        k = (w.day, w.window_idx)
        cap_of[k] = cap_of.get(k, 0) + int(w.capacity)
    buckets = sorted(cap_of.keys())
    if len(buckets) > MAX_SLOTS:
        buckets = sorted(sorted(buckets, key=lambda k: -cap_of[k])[:MAX_SLOTS])
    bidx = {b: i for i, b in enumerate(buckets)}
    win_by_bucket: Dict[int, List] = {i: [] for i in range(len(buckets))}
    for w in scen.windows:
        i = bidx.get((w.day, w.window_idx))
        if i is not None:
            win_by_bucket[i].append(w)
    return buckets, win_by_bucket


def lower_bound(scen: SuperScenario) -> Dict[str, float]:
    """场景的**可解性下界**：先算明底，再谈模型。

    不先算这个，一旦「未排 27 个」出来，根本分不清是模型不行还是场景本身排不下 ——
    那才是最大的假象。三个下界都是硬数：

    * ``cap_slack`` = 总容量(min) - 总需求(min)              容量维度（<0 直接无解）
    * ``ath_slack`` = 桶数 × 运动员数 - 总人次                兼项维度（<0 直接无解）
      —— 每个运动员在每个时间桶里最多出现一次，桶数×人数是人次上界；
         这是「500 人 / 15 项目 / 兼项率 100%」这一档最容易踩的死穴。
    * ``avg_dur``    = 单元平均时长，用来解释「一个桶塞得下几块」
    """
    buckets, _ = build_buckets(scen)
    nb = max(1, len(buckets))
    cap = sum(int(w.capacity) for w in scen.windows)
    demand = sum(u.duration for u in scen.units)
    athletes = {a for u in scen.units for a in u.athletes}
    slots = sum(len(u.athletes) for u in scen.units)
    return {
        "n_buckets": float(nb),
        "cap_slack": float(cap - demand),
        "ath_slack": float(nb * len(athletes) - slots),
        "avg_dur": round(demand / max(1, len(scen.units)), 1),
        "units_per_bucket_max": round(cap / max(1, demand) * nb, 2),
    }


# ---------------------------------------------------------------- 解码
def decode(
    scen: SuperScenario,
    n: int,
    priority: np.ndarray,
    slot_logits: np.ndarray,
    mode: str,
) -> Dict[str, object]:
    """按给定顺序把单元摆进时间桶，返回每条单元的落点与违规统计。

    ``mode``: ``model``（priority 降序）/ ``greedy``（按装箱紧张度降序）
              / ``random``（随机顺序）。
    """
    units: List[SuperUnit] = scen.units[:n]
    buckets, win_by_bucket = build_buckets(scen)
    nb = len(buckets)
    if nb == 0:
        return {"unplaced": len(units), "placed": 0}

    # ---- 排序 ----
    if mode == "model":
        order = sorted(range(n), key=lambda i: -float(priority[i]))
    elif mode == "random":
        order = list(range(n))
        np.random.shuffle(order)
    else:  # greedy：与 super_encode.greedy_targets 同一套紧张度
        cap_by_venue: Dict[str, int] = {}
        for w in scen.windows:
            cap_by_venue[w.venue] = max(cap_by_venue.get(w.venue, 0), int(w.capacity))
        order = sorted(
            range(n),
            key=lambda i: -((units[i].duration + units[i].interval)
                            / max(1, cap_by_venue.get(units[i].venue, 1))))

    load: Dict[Tuple[int, str], int] = {}          # (时间桶, 场地) -> 已排时长
    bucket_athletes: Dict[int, set] = {b: set() for b in range(nb)}
    bucket_units: Dict[int, List[int]] = {b: [] for b in range(nb)}
    assign: Dict[int, Optional[int]] = {}          # unit idx -> 时间桶

    for i in order:
        u = units[i]
        best: Optional[Tuple[float, int]] = None
        best_window = None
        for b in range(nb):
            if slot_logits is not None and n > 0:
                sc = float(slot_logits[i, b]) if b < slot_logits.shape[1] else -1e9
            else:
                sc = 0.0
            # 场地必须在该时间桶里存在
            cands = [w for w in win_by_bucket[b] if w.venue == u.venue]
            if not cands:
                continue
            for w in cands:
                key = (b, w.venue)
                if load.get(key, 0) + u.duration > w.capacity:
                    continue
                if bucket_athletes[b] & set(u.athletes) and u.athletes:
                    continue
                if best is None or sc > best[0]:
                    best = (sc, b)
                    best_window = w
        if best is None:
            assign[i] = None
            continue
        b = best[1]
        assign[i] = b
        bucket_units[b].append(i)
        bucket_athletes[b].update(u.athletes)
        load[(b, best_window.venue)] = load.get((b, best_window.venue), 0) + u.duration

    # ---- 修复重插（对三种模式完全公平，只补不换）----
    # ⚠️ 为什么必须补这一趟：朴素 first-fit 在随机分数下会把后半段单元挤成
    #    「无桶可放」——那 24 个未排是**解码器**的锅，不是模型的锅。
    #    三个模式共用同一套修复，才比得出「排序质量」而不是「谁运气好」。
    #    真实编排器也是这么干的（先落位，再针对漏网单元回填）。
    def _try_place(i: int) -> Optional[int]:
        u = units[i]
        best: Optional[Tuple[float, int, object]] = None
        best_window = None
        for b in range(nb):
            if slot_logits is not None and slot_logits.size:
                sc = float(slot_logits[i, b]) if b < slot_logits.shape[1] else -1e9
            else:
                sc = 0.0
            for w in [w for w in win_by_bucket[b] if w.venue == u.venue]:
                if load.get((b, w.venue), 0) + u.duration > w.capacity:
                    continue
                if bucket_athletes[b] & set(u.athletes) and u.athletes:
                    continue
                if best is None or sc > best[0]:
                    best = (sc, b, w)
                    best_window = w
        if best is None:
            return None
        b, w = best[1], best[2]
        assign[i] = b
        bucket_units[b].append(i)
        bucket_athletes[b].update(u.athletes)
        load[(b, w.venue)] = load.get((b, w.venue), 0) + u.duration
        return b

    for _ in range(3):
        left = [i for i, b in assign.items() if b is None]
        if not left:
            break
        for i in left:
            _try_place(i)

    # ---- 违规统计 ----
    # ① 兼项冲突：同桶且共享运动员（解码阶段已经避开了，这里复算以独立验证）
    clash = 0
    for b in range(nb):
        ids = bucket_units[b]
        seen: Dict[int, int] = {}
        for i in ids:
            for a in units[i].athletes:
                if a in seen:
                    clash += 1
                seen[a] = i
    # ② 容量超占
    overflow = 0
    for (b, ven), ln in load.items():
        cap = max((w.capacity for w in win_by_bucket[b] if w.venue == ven), default=0)
        if cap and ln > cap:
            overflow += ln - cap
    # ③ 块完整性：同 group_key 的时间桶必须连续、且中间不夹别的块
    frag_blocks = 0
    blocks: Dict[str, List[int]] = {}
    for i, b in assign.items():
        if b is None:                      # 未排单元不参与块完整性统计
            continue
        gk = units[i].group_key
        if gk:
            blocks.setdefault(gk, []).append(b)
    for gk, bs in blocks.items():
        if len(bs) < 2:
            continue
        lo, hi = min(bs), max(bs)
        span = set(range(lo, hi + 1))
        if len(bs) != len(span):
            frag_blocks += 1                      # 中间有空洞 = 被别的单元插进来了
        else:
            # 区间内混进了别的块 → 见缝插针
            other = 0
            for b in span:
                for i in bucket_units.get(b, []):
                    if units[i].group_key != gk:
                        other += 1
            if other:
                frag_blocks += 1
    # ④ 道次冲突：同批（同项目+同年级）单元落同一桶
    lane_clash = 0
    lane_buck: Dict[str, List[int]] = {}
    for i, b in assign.items():
        u = units[i]
        if u.heat_capacity > 0:
            lane_buck.setdefault(f"{u.name}|{u.grade}", []).append(b)
    for bs in lane_buck.values():
        if len(bs) != len(set(bs)):
            lane_clash += 1
    # ⑤ 工期
    days_used = len({buckets[b][0] for b in assign.values() if b is not None})
    unplaced = sum(1 for v in assign.values() if v is None)

    return {
        "n_units": n,
        "placed": n - unplaced,
        "unplaced": unplaced,
        "athlete_clash": clash,
        "capacity_overflow": overflow,
        "frag_blocks": frag_blocks,
        "lane_clash": lane_clash,
        "days_used": days_used,
        "days_limit": scen.days_limit,
    }


# ---------------------------------------------------------------- 主流程
def evaluate(model: SuperScheduleMoE, tier: str, seeds: Sequence[int],
             device: str = "cpu") -> List[Dict[str, object]]:
    model.eval()
    rows: List[Dict[str, object]] = []
    for sd in seeds:
        scen = generate_super_scenario(tier, seed=sd)
        data = encode_super_graph(scen)
        if data is None:
            continue
        n = int(data["n"])
        # ⚠️ batch 轴约定（编码器 / 训练 / Java 三端一致）：
        #    ``encode_super_graph`` 返回的四个张量**都已经带了 batch 轴**
        #    （node_feat [1,n,20]、adj_by_type [1,E,n,n]、type_mask [1,E]、mask [1,n]）。
        #    第一次写这里时又给四个都 unsqueeze 了一次：type_mask 变 [1,1,E]、node_feat 变
        #    [1,1,47,20]，ctx 被抬成四维，模型直接报
        #    "size of tensor a (47) must match tensor b (8) at non-singleton dimension 2"。
        #    判据：拿编码器原样喂，一个 unsqueeze 都不要加。
        #    这里的断言把该坑钉住：形状不对就直接炸，不要等训练跑完才发现。
        nf = torch.from_numpy(data["node_feat"]).to(device)                   # [1,n,20]
        ab = torch.from_numpy(data["adj_by_type"]).to(device)                 # [1,E,n,n]
        tm = torch.from_numpy(data["type_mask"]).to(device)                   # [1,E] 已带批
        mk = torch.from_numpy(data["mask"]).to(device)                        # [1,n] 已带批
        assert tm.dim() == 2, f"type_mask 应为 [B,E]，实际 {tuple(tm.shape)}"
        assert mk.dim() == 2, f"mask 应为 [B,N]，实际 {tuple(mk.shape)}"
        assert nf.dim() == 3, f"node_feat 应为 [B,N,20]，实际 {tuple(nf.shape)}"
        assert ab.dim() == 4, f"adj 应为 [B,E,N,N]，实际 {tuple(ab.shape)}"
        with torch.no_grad():
            pri, slot, _task, _fmt, _days = model(nf, ab, tm, mk)
        pri_np = pri[0].cpu().numpy()
        slot_np = slot[0].cpu().numpy()

        row: Dict[str, object] = {"tier": tier, "seed": sd, "n_units": n,
                                  "lower_bound": lower_bound(scen),
                                  "tiers_tasks": sorted({u.task for u in scen.units})}
        for mode in ("random", "greedy", "model"):
            row[mode] = decode(scen, n, pri_np, slot_np, mode)
        rows.append(row)
    return rows


def summarize(rows: List[Dict[str, object]]) -> Dict[str, object]:
    out: Dict[str, object] = {}
    for mode in ("random", "greedy", "model"):
        acc: Dict[str, List[float]] = {}
        for r in rows:
            d = r[mode]                     # type: ignore[index]
            for k, v in d.items():
                if isinstance(v, (int, float)) and not isinstance(v, bool):
                    acc.setdefault(k, []).append(float(v))
        out[mode] = {k: round(float(np.mean(v)), 3) for k, v in acc.items()}
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", default=os.path.join(ROOT, "models", "super_moe.pt"))
    ap.add_argument("--seeds", type=int, default=3)
    ap.add_argument("--device", default="cpu")
    ap.add_argument("--out", default=os.path.join(ROOT, "models",
                                                 "super_moe_eval.json"))
    args = ap.parse_args()

    if not os.path.exists(args.ckpt):
        raise SystemExit(f"缺少权重 {args.ckpt}，先跑 train_super_moe")
    model = SuperScheduleMoE()
    ck = torch.load(args.ckpt, map_location="cpu")
    # 兼容两种落盘格式：{"state_dict":…, "meta":…} 与旧版裸 state_dict
    model.load_state_dict(ck["state_dict"] if isinstance(ck, dict) and "state_dict" in ck else ck)
    model.to(args.device)

    all_rows: List[Dict[str, object]] = []
    for tier in TIERS:
        rows = evaluate(model, tier, range(args.seeds), args.device)
        s = summarize(rows)
        all_rows.extend(rows)
        r = s["model"]
        g = s["greedy"]
        lb = rows[0]["lower_bound"]                        # type: ignore[index]
        flag = "" if (lb["cap_slack"] >= 0 and lb["ath_slack"] >= 0) else "  ⚠️场景本身无解"
        print(f"{tier:<8} 下界 桶={lb['n_buckets']:<4.0f} 容量余={lb['cap_slack']:<7.0f} "
              f"人次余={lb['ath_slack']:<8.0f} 均时长={lb['avg_dur']}{flag}")
        print(f"{tier:<8} 单元={s['model']['n_units']:<4} "
              f"未排 {r['unplaced']:<5.1f} 兼项撞 {r['athlete_clash']:<5.1f} "
              f"超占 {r['capacity_overflow']:<6.1f} 碎块 {r['frag_blocks']:<4.1f} "
              f"道次撞 {r['lane_clash']:<4.1f} 工期 {r['days_used']}/{r['days_limit']}")
        print(f"{'':<8} 对比 greedy → 未排 {r['unplaced']}-{g['unplaced']:<5.1f} "
              f"兼项撞 {r['athlete_clash']}-{g['athlete_clash']:<5.1f} "
              f"超占 {r['capacity_overflow']}-{g['capacity_overflow']:<6.1f} "
              f"碎块 {r['frag_blocks']}-{g['frag_blocks']}")

    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(all_rows, fh, ensure_ascii=False, indent=2)
    print(f"\n明细 -> {args.out}")


if __name__ == "__main__":
    main()
