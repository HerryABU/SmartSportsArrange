"""训练约束分型异构图网络（ConstraintGnn）——**全新架构**，自监督伪标签。

用法（在 sports-ai/ 下）：
    python -m sports_ai.train_constraint_gnn --samples 800 --epochs 40

产出：
    models/constraint_gnn.pt        权重
    models/constraint_gnn_stats.json 训练统计（val_loss / Spearman 等）

为什么用「自监督伪标签」而不是模仿最优解
----------------------------------------
主流神经组合优化（模仿学习）需要**标注的最优解**，但现实里没人有。
参考 **IC/DC**（arXiv:2411.00003）的自监督思路：直接最小化「代价 + 约束违反」，
把**求解过程本身**当训练信号。

这里的落地方式：用「装箱 + 兼项感知的贪心 + 随机重启」在训练场景上现场生成高质量解，
把该顺序转成每个单元的目标名次（伪标签），再用 pairwise 排序损失训练。
**不需要任何人工标注解**，且随着贪心质量提升，标签质量自动提升。
"""

from __future__ import annotations

import argparse
import json
import os
import random
from typing import Dict, List, Optional, Tuple

import numpy as np
import torch
import torch.nn as nn

from sports_ai.data.constraint_gnn_io import N_TYPES, encode_constraint_gnn_inputs
from sports_ai.data.generator import Scenario, generate_scenario
from sports_ai.data.scenarios import TIERS, generate_tier_scenario
from sports_ai.device import add_device_arg, backup_before_overwrite, describe_device, resolve_device, seed_all
from sports_ai.models.constraint_gnn import ConstraintGnn

MODEL_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "models")


# ---------------------------------------------------------------------------
# 自监督信号：贪心 + 随机重启 生成高质量顺序 → 转成目标名次
# ---------------------------------------------------------------------------
def greedy_order(scenario: Scenario, rng: random.Random) -> List[int]:
    """装箱 + 兼项感知的贪心顺序（自对抗式：多次重启取最好的一次）。"""
    best: List[int] = []
    best_cost = float("inf")
    units = scenario.units

    for _ in range(6):                       # 随机重启
        idxs = list(range(len(units)))
        rng.shuffle(idxs)
        # 启发式打分：冲突面小、体积大、装得下 的优先（瓶颈前置）
        def score(i: int) -> float:
            u = units[i]
            people = len(getattr(u, "athletes", []) or [])
            return people * 3.0 + int(u.raw_duration) * 0.01

        idxs.sort(key=score, reverse=True)
        order: List[int] = []
        used_bins: Dict[str, int] = {}
        busy: set = set()
        cost = 0.0
        for i in idxs:
            u = units[i]
            pool = getattr(u, "pool_label", None)
            cands = [w for w in scenario.placements if getattr(w, "pool_label", None) == pool]
            if not cands:
                continue
            placed = False
            for w in cands:
                bk = w.bin_key
                if used_bins.get(bk, 0) + int(u.raw_duration) <= int(w.window_capacity):
                    used_bins[bk] = used_bins.get(bk, 0) + int(u.raw_duration)
                    placed = True
                    break
            if not placed:
                cost += 100.0                # 装箱失败重罚
                continue
            for a in (getattr(u, "athletes", []) or []):
                if a in busy:
                    cost += 10.0             # 兼项冲突
                busy.add(a)
            order.append(i)
        cost += (len(units) - len(order)) * 100.0
        if cost < best_cost:
            best_cost, best = cost, order
    return best


def urgency_targets(scenario: Scenario, n: int) -> np.ndarray:
    """**约束驱动**的回归目标（取代早期的「贪心顺序」伪标签）。

    ⚠️ **为什么不能用贪心顺序当标签**：贪心的打分只由
    ``people*3 + duration*0.01`` 决定，**完全不依赖图结构**。拿它当标签，
    模型只能学到一个近似常数映射——实测 val_spearman 仅 0.08（≈随机），
    外加 train_loss 0.007 / val_mse 0.146 的明显过拟合。等于白训。

    这里改用两个**真实约束量**合成标签，它们都需要看图才能算出来：

    1. **装箱紧张度**：``cap / dur``（能装下几次）。容量越紧、自身越长 → 越该先放；
       贪心式「瓶颈前置」的理论依据，也正是 Java 端装箱约束的镜像。
    2. **冲突暴露度**：该单元的运动员里，已经有别的单元在报的占比。
       兼项冲突面越大 → 越该先放（否则后面无处安放）。

    两者都归一到 [0,1] 后取 0.6/0.4 加权。目标是连续量而非名次，
    因为连续目标能提供更密的梯度信号，也更贴合「估算难度」的物理含义。
    """
    units = scenario.units[:n]
    if not units:
        return np.zeros(0, dtype=np.float32)

    # 冲突暴露：运动员出现在多少个单元里
    cnt: Dict[int, int] = {}
    for u in units:
        for a in (getattr(u, "athletes", []) or []):
            cnt[a] = cnt.get(a, 0) + 1
    # 各池/时段的最大容量
    cap_by_pool: Dict[str, int] = {}
    for w in scenario.placements:
        pool = getattr(w, "pool_label", None)
        cap_by_pool[pool] = max(cap_by_pool.get(pool, 0), int(w.window_capacity))

    tight = np.zeros(n, dtype=np.float32)
    expo = np.zeros(n, dtype=np.float32)
    for i, u in enumerate(units):
        pool = getattr(u, "pool_label", None)
        cap = max(1, cap_by_pool.get(pool, 1))
        dur = max(1, int(u.raw_duration))
        # 相对紧张度：1 表示「这个单元单独就占满整个时段」
        tight[i] = min(1.0, dur / cap)
        ath = getattr(u, "athletes", []) or []
        if ath:
            expo[i] = sum((cnt[a] - 1) for a in ath) / len(ath)
            expo[i] = min(1.0, expo[i] / 3.0)
    if tight.max() > 0:
        tight = tight / tight.max()
    if expo.max() > 0:
        expo = expo / expo.max()
    return (0.6 * tight + 0.4 * expo).astype(np.float32)


def spearman(pred: np.ndarray, gold: np.ndarray) -> float:
    """Spearman 秩相关——比 MSE 更能反映「排序」这个任务的真实质量。"""
    if len(pred) < 2:
        return 0.0
    def rankify(x: np.ndarray) -> np.ndarray:
        o = np.argsort(np.argsort(x))
        return o.astype(np.float32)
    a, b = rankify(pred), rankify(gold)
    a = a - a.mean()
    b = b - b.mean()
    d = float(np.sqrt((a * a).sum() * (b * b).sum()))
    # 分母防零：常数序列（模型无信号）视为相关性 0，而不是 nan
    return float((a * b).sum() / d) if d > 1e-8 else 0.0


# ---------------------------------------------------------------------------
# 数据集
# ---------------------------------------------------------------------------
def make_dataset(n: int, seed: int, tiers: Optional[List[str]] = None) -> List[Dict]:
    """生成训练集。

    ``tiers`` 给定时按**档位**混合采样（REGULAR/HELL/BLOCK/LANE/TEAM），
    对应用户点名的四类场景 + 球类；不给定则沿用通用场景生成器（历史行为）。
    """
    rng = random.Random(seed)
    data = []
    if tiers:
        for i in range(n):
            tier = tiers[i % len(tiers)]
            sc = generate_tier_scenario(tier, seed=rng.randint(0, 10 ** 9))
            enc = encode_tier_inputs(sc)
            if enc is None or enc["n"] < 4:
                continue
            y = urgency_targets_tier(sc, enc["n"])
            if float(y.std()) < 1e-4:
                continue
            data.append({**enc, "rank": y, "tier": tier})
        return data
    for _ in range(n):
        s = generate_scenario(
            seed=rng.randint(0, 10 ** 9),
            n_athletes=rng.randint(150, 800),
            n_days=rng.randint(2, 6),
            multi_event_prob=rng.uniform(0.3, 0.95),
            grades=["高一", "高二", "高三"],
            event_drop_prob=rng.choice([0.0, 0.2, 0.3, 0.5]),
            track_lanes=rng.choice([1, 2, 3]),
            field_lanes=rng.choice([2, 3, 4, 5]),
            day_windows=rng.choice([(180, 150), (240, 240), (210, 210)]),
        )
        enc = encode_constraint_gnn_inputs(s)
        if enc["n"] < 4:
            continue
        y = urgency_targets(s, enc["n"])
        if float(y.std()) < 1e-4:
            continue                       # 标签几乎无方差 → 学不到东西，丢弃
        data.append({**enc, "rank": y})
    return data


def encode_tier_inputs(sc) -> Optional[Dict]:
    """把档位场景编码成 6 通道图（字段名与通用生成器不同，故单独一份适配）。"""
    units = sc.units
    n = len(units)
    if n == 0:
        return None
    from sports_ai.data.constraint_gnn_io import T_IDX
    adj = np.zeros((N_TYPES, n, n), dtype=np.float32)
    tmask = np.zeros(N_TYPES, dtype=np.float32)

    # ATHLETE
    by_ath: Dict[int, List[int]] = {}
    for i, u in enumerate(units):
        for a in u.athletes:
            by_ath.setdefault(a, []).append(i)
    shared: Dict[Tuple[int, int], int] = {}
    for idxs in by_ath.values():
        for x in range(len(idxs)):
            for y in range(x + 1, len(idxs)):
                k = (idxs[x], idxs[y]) if idxs[x] <= idxs[y] else (idxs[y], idxs[x])
                shared[k] = shared.get(k, 0) + 1
    if shared:
        mx = float(max(shared.values()))
        for (a, b), c in shared.items():
            adj[T_IDX["ATHLETE"], a, b] = adj[T_IDX["ATHLETE"], b, a] = c / mx
        tmask[T_IDX["ATHLETE"]] = 1.0

    def grp(attr, t):
        buck: Dict[str, List[int]] = {}
        for i, u in enumerate(units):
            k = getattr(u, attr, None)
            if k:
                buck.setdefault(str(k), []).append(i)
        for idxs in buck.values():
            if len(idxs) < 2:
                continue
            press = min(1.0, len(idxs) / max(1, n))
            for x in range(len(idxs)):
                for y in range(x + 1, len(idxs)):
                    a, b = idxs[x], idxs[y]
                    adj[t, a, b] = max(adj[t, a, b], press)
                    adj[t, b, a] = adj[t, a, b]
            tmask[t] = 1.0

    grp("pool_label", T_IDX["POOL"])
    grp("venue", T_IDX["VENUE"])          # ★ 现在有真实场地维度了
    grp("group_key", T_IDX["GROUP"])
    grp("grade", T_IDX["GRADE"])

    # TIME：装箱 + 间隔（★ 现在有 interval 了）
    cap_by_venue: Dict[str, int] = {}
    for w in sc.placements:
        cap_by_venue[w.venue] = max(cap_by_venue.get(w.venue, 0), int(w.window_capacity))
    for i, u in enumerate(units):
        cap = max(1, cap_by_venue.get(u.venue, 1))
        for j in range(i + 1, n):
            v = units[j]
            need = u.raw_duration + v.raw_duration + max(u.interval, v.interval)
            if need > cap:
                w = min(1.0, need / cap)
                adj[T_IDX["TIME"], i, j] = max(adj[T_IDX["TIME"], i, j], w)
                adj[T_IDX["TIME"], j, i] = adj[T_IDX["TIME"], i, j]
        tmask[T_IDX["TIME"]] = 1.0

    feat = np.zeros((n, 16), dtype=np.float32)
    total = float(sum(u.raw_duration for u in units)) or 1.0
    max_expo = max((len(u.athletes) * u.raw_duration for u in units), default=1) or 1
    evs = sorted(set(u.event_id for u in units))
    venues = sorted(set(u.venue for u in units))
    for i, u in enumerate(units):
        people = len(u.athletes)
        feat[i] = [
            1.0 if u.track else 0.0,
            min(people, 512) / 512.0,
            min(int(sum(x.raw_duration for x in units)), 600) / 600.0,
            1.0 if u.group_key else 0.0,
            min(len([x for x in units if x.group_key == u.group_key]), 16) / 16.0 if u.group_key else 0.0,
            0.0,
            (evs.index(u.event_id) / max(1, len(evs) - 1)) if len(evs) > 1 else 0.0,
            np.log1p(people) / np.log(513),
            0.0,
            0.0,
            u.raw_duration / total,
            (people * u.raw_duration) / max_expo,
            len([x for x in units if x.event_id == u.event_id]) / max(1, n),
            len([x for x in units if x.pool_label == u.pool_label]) / max(1, n),
            1.0 if u.raw_duration >= 300 else 0.0,
            (i / max(1, n - 1)) if n > 1 else 0.0,
        ]
    mask = np.ones(n, dtype=np.float32)
    return {"node_feat": feat[None], "adj_by_type": adj[None], "type_mask": tmask[None],
            "mask": mask[None], "n": n}


def urgency_targets_tier(sc, n: int) -> np.ndarray:
    """档位场景的约束驱动标签：在通用口径上**额外考虑间隔与项目块**。"""
    units = sc.units[:n]
    if not units:
        return np.zeros(0, dtype=np.float32)
    cnt: Dict[int, int] = {}
    for u in units:
        for a in u.athletes:
            cnt[a] = cnt.get(a, 0) + 1
    cap_by_venue: Dict[str, int] = {}
    for w in sc.placements:
        cap_by_venue[w.venue] = max(cap_by_venue.get(w.venue, 0), int(w.window_capacity))
    block_size: Dict[str, int] = {}
    for u in units:
        if u.group_key:
            block_size[u.group_key] = block_size.get(u.group_key, 0) + 1

    tight = np.zeros(n, dtype=np.float32)
    expo = np.zeros(n, dtype=np.float32)
    for i, u in enumerate(units):
        cap = max(1, cap_by_venue.get(u.venue, 1))
        dur = max(1, int(u.raw_duration))
        # 间隔越大越紧：把 interval 计入分母
        tight[i] = min(1.0, (dur + u.interval) / cap)
        if u.athletes:
            expo[i] = min(1.0, (sum(cnt[a] - 1 for a in u.athletes) / len(u.athletes)) / 3.0)
    if tight.max() > 0:
        tight /= tight.max()
    if expo.max() > 0:
        expo /= expo.max()
    return (0.6 * tight + 0.4 * expo).astype(np.float32)


# ---------------------------------------------------------------------------
# 训练
# ---------------------------------------------------------------------------
def train(args) -> None:
    device = resolve_device(getattr(args, "device", "auto"))
    seed_all(0)
    print(f"[device] 训练设备: {describe_device(device)}")

    tiers = None
    if getattr(args, "tier", "mixed") != "generic":
        if getattr(args, "tier", "mixed") == "mixed":
            tiers = list(TIERS.keys())
        else:
            tiers = [args.tier]
        print(f"[data] 档位混合训练: {tiers}（样本 {args.samples}）")
    print(f"[data] 生成 {args.samples} 个场景（约束驱动自监督标签）…")
    data = make_dataset(args.samples, args.seed, tiers=tiers)
    if not data:
        print("没有可用样本，训练终止")
        return
    print(f"[data] 有效样本 {len(data)} 个，"
          f"平均节点数 {np.mean([d['n'] for d in data]):.1f}")

    idx = np.random.permutation(len(data))
    n_train = int(len(data) * 0.8)
    # ⚠️ data 是 list：numpy 数组可以花式索引，但 **Python list 不接受 list 索引**
    #    （list[int] 报 "list indices must be integers or slices, not list"），
    #    所以必须走列表推导逐个取，不能 data[idx_list]。
    tr = [data[int(i)] for i in idx[:n_train]]
    va = [data[int(i)] for i in idx[n_train:]]

    def to_tensors(batch, idxs):
        """把一批变长样本 pad 成同尺寸张量。

        ⚠️ 每个场景的单元数不同（实测 24/27/…），**不能直接 np.concatenate**——
        维度不一致会报 "all the input array dimensions ... must match exactly"。
        ONNX 侧的 N 是动态轴，但 PyTorch 批处理必须先 pad 到批内最大 n。
        """
        items = [batch[i] for i in idxs]
        width = max(d["n"] for d in items)
        nf, abt, tm, mk, ranks = [], [], [], [], []
        for d in items:
            n, m = d["n"], width
            pad = m - n
            nf.append(np.pad(d["node_feat"][0], ((0, pad), (0, 0))))
            abt.append(np.pad(d["adj_by_type"][0], ((0, 0), (0, pad), (0, pad))))
            tm.append(d["type_mask"][0])
            mk.append(np.pad(d["mask"][0], (0, pad)))
            r = np.zeros(m, dtype=np.float32)
            r[:n] = d["rank"][:n]
            ranks.append(r)
        return (
            torch.from_numpy(np.stack(nf)).to(device),
            torch.from_numpy(np.stack(abt)).to(device),
            torch.from_numpy(np.stack(tm)).to(device),
            torch.from_numpy(np.stack(mk)).to(device),
            ranks,
        )

    tr_t = to_tensors(tr, range(len(tr)))
    va_t = to_tensors(va, range(len(va)))

    model = ConstraintGnn(node_feat=16, hidden=args.hidden, layers=args.layers,
                          n_types=N_TYPES, dropout=args.dropout).to(device)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=args.epochs)
    # 排序损失：MSE 回归到目标名次（比分类更稳，且直接优化「次序」）
    loss_fn = nn.MSELoss()

    print(f"[model] 参数量 {sum(p.numel() for p in model.parameters()):,}")
    best_val, best_state, best_sp, best_r2 = float("inf"), None, -1.0, float("nan")

    for epoch in range(args.epochs):
        model.train()
        nf, abt, tm, mk, ranks = tr_t
        perm = torch.randperm(nf.shape[0], device=device)
        total = 0.0
        nb = 0
        for b in range(0, nf.shape[0], args.batch):
            sel = perm[b: b + args.batch]
            if sel.numel() < 2:
                continue
            pred = model(nf[sel], abt[sel], tm[sel], mk[sel])       # [B,N]
            gold = _pad_ranks(ranks, sel.cpu().numpy(), nf.shape[1], device)
            loss = loss_fn(pred, gold)
            opt.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            total += float(loss.item())
            nb += 1
        sched.step()

        # 验证：MSE + R² + **按样本**的 Spearman
        model.eval()
        with torch.no_grad():
            nf, abt, tm, mk, ranks = va_t
            pred = model(nf, abt, tm, mk).cpu().numpy()
            mask_np = mk.cpu().numpy()
            ns = [int(d["n"]) for d in va]
            width = pred.shape[1]
            per_sample_sp, sq_err, sq_tot = [], 0.0, 0.0
            for bi, n in enumerate(ns):
                p_i = pred[bi, :n]
                g_i = np.asarray(ranks[bi][:n], dtype=np.float32)
                sq_err += float(((p_i - g_i) ** 2).sum())
                sq_tot += float(((g_i - g_i.mean()) ** 2).sum())
                per_sample_sp.append(spearman(p_i, g_i))
            vmse = sq_err / max(1, sum(ns))
            # R² = 1 - SSE/SST：<0 说明还不如直接预测均值
            vr2 = 1.0 - (sq_err / sq_tot) if sq_tot > 1e-8 else float("nan")
            vsp = float(np.mean(per_sample_sp)) if per_sample_sp else 0.0
        if vmse < best_val:
            best_val, best_sp, best_r2 = vmse, vsp, vr2
            best_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}
        if epoch % 5 == 0 or epoch == args.epochs - 1:
            print(f"epoch {epoch:3d}  train_loss={total / max(1, nb):.5f}  "
                  f"val_mse={vmse:.5f}  val_r2={vr2:.3f}  val_spearman={vsp:.3f}")

    os.makedirs(MODEL_DIR, exist_ok=True)
    tag = f"smoke-{args.samples}x{args.epochs}"
    # 覆盖前备份：小样本冒烟训练会把正式权重冲掉，而 .pt 不在 git 跟踪内
    backup_before_overwrite(os.path.join(MODEL_DIR, "constraint_gnn.pt"), tag)
    model.load_state_dict(best_state)
    torch.save({k: v.cpu() for k, v in model.state_dict().items()},
               os.path.join(MODEL_DIR, "constraint_gnn.pt"))
    with open(os.path.join(MODEL_DIR, "constraint_gnn_stats.json"), "w", encoding="utf-8") as fh:
        json.dump({"val_mse": best_val, "val_spearman": best_sp, "val_r2": best_r2,
                   "samples": len(data), "hidden": args.hidden,
                   "layers": args.layers, "n_types": N_TYPES},
                  fh, ensure_ascii=False, indent=2)
    print(f"best val_mse={best_val:.5f}  val_r2={best_r2:.3f}  "
          f"val_spearman={best_sp:.3f}  → 已保存 models/constraint_gnn.pt")
    if best_r2 == best_r2 and best_r2 < 0.2:
        print("⚠️ R² 偏低：模型尚未学到有效的约束信号。建议加样本/轮次，"
              "或检查标签是否与输入特征同源（不要指望从图里猜出与图无关的量）。")


def _pad_ranks(ranks, sel, total_n: int, device):
    """取出本批样本的名次张量（``to_tensors`` 里已 pad 到统一宽度）。"""
    sel = [int(i) for i in sel]
    return torch.from_numpy(np.stack([ranks[i] for i in sel])).to(device)


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--samples", type=int, default=800)
    p.add_argument("--epochs", type=int, default=40)
    p.add_argument("--batch", type=int, default=16)
    p.add_argument("--lr", type=float, default=2e-3)
    # ⚠️ 必须与 models/constraint_gnn.py 的构造函数默认值一致：
    #    训练用 96/4 而模型是 160/6 时，load_state_dict 直接 shape 不匹配。
    p.add_argument("--hidden", type=int, default=160)
    p.add_argument("--layers", type=int, default=6)
    p.add_argument("--dropout", type=float, default=0.1)
    p.add_argument("--seed", type=int, default=20261003)
    p.add_argument("--tier", default="mixed",
                   help="训练档位：mixed(四类场景+球类) / generic(通用) / REGULAR / HELL / BLOCK / LANE / TEAM")
    add_device_arg(p)
    train(p.parse_args())


if __name__ == "__main__":
    main()
