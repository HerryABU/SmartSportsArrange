"""训练球类赛制模型 TournamentGnn（自监督，规则引擎当标签生成器）。

## 三个损失

* **赛制**：soft cross-entropy（目标是 ``softmax(-cost/T)`` 概率分布，不是 one-hot）
* **种子**：MSE + 按样本 Spearman 辅助监控（排序才是应用要的）
* **公平性**：MSE

## POMO 式共享基线

种子与公平性两个回归头都用**批次内共享基线**（减去 batch 均值），
而不是单样本值。这能显著降低策略梯度/回归的方差——POMO（ICLR'22）的核心技巧：
同一个问题有大量等价最优解时，用单样本目标会因「哪个等价解被采样到」而抖动。

## 用法::

    python -m sports_ai.train_tournament_gnn --samples 800 --epochs 60 --device cpu
"""

from __future__ import annotations

import argparse
import json
import os
import random
import sys
from typing import Dict, List

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

if __package__ in (None, ""):  # 允许直接 python sports_ai/train_tournament_gnn.py
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from sports_ai.data.ball_tournament import (
    N_FORMATS,
    ball_labels,
    encode_ball_graph,
    generate_ball_tournament,
)
from sports_ai.device import (
    add_device_arg,
    backup_before_overwrite,
    describe_device,
    resolve_device,
    seed_all,
)
from sports_ai.models.tournament_gnn import N_TYPES, TournamentGnn

MODEL_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "models")

# 覆盖拔河（8~24 支按班分队）到小球类（4~16 支）的常见规模
TEAM_SIZES = [4, 6, 8, 10, 12, 14, 16, 18, 20, 24]
SPORTS_POOL = ["篮球", "排球", "足球", "拔河", "乒乓球", "羽毛球"]


def make_dataset(n: int, seed: int) -> List[Dict]:
    rng = random.Random(seed)
    data: List[Dict] = []
    attempts = 0
    while len(data) < n and attempts < n * 4:
        attempts += 1
        n_teams = rng.choice(TEAM_SIZES)
        t = generate_ball_tournament(
            n_teams,
            seed=rng.randint(0, 10 ** 9),
            sport=rng.choice(SPORTS_POOL),
            n_venues=rng.choice([1, 2, 3, 4]),
            courts_per_venue=rng.choice([1, 2, 3, 4]),
            available_slots=rng.choice([0, 4, 6, 8, 12, 16]),
            days=rng.choice([1, 1, 2, 3]),
        )
        enc = encode_ball_graph(t)
        if enc["n"] < 3:
            continue
        lab = ball_labels(t, enc["n"])
        if lab is None:
            continue
        data.append({**enc, "format": lab["format"], "seed": lab["seed"],
                     "fair": lab["fair"], "n": enc["n"]})
    return data


def to_tensors(batch: List[Dict], idxs: List[int], device):
    """把变长样本 pad 到批内最大 N（ONNX 侧 N 是动态轴，PyTorch 批处理必须先 pad）。"""
    items = [batch[i] for i in idxs]
    width = max(d["n"] for d in items)
    nf, abt, tm, mk, fmt, sd, fr = [], [], [], [], [], [], []
    for d in items:
        n, pad = d["n"], width - d["n"]
        nf.append(np.pad(d["node_feat"][0], ((0, pad), (0, 0))))
        abt.append(np.pad(d["adj_by_type"][0], ((0, 0), (0, pad), (0, pad))))
        tm.append(d["type_mask"][0])
        mk.append(np.pad(d["mask"][0], (0, pad)))
        fmt.append(d["format"])
        r = np.zeros(width, dtype=np.float32)
        r[:n] = d["seed"]
        sd.append(r)
        f = np.zeros(width, dtype=np.float32)
        f[:n] = d["fair"]
        fr.append(f)
    T = lambda a, dt=torch.float32: torch.from_numpy(np.asarray(a, dtype=np.float32)).to(device)
    return (T(nf), T(abt), T(tm), T(mk), T(fmt), T(sd), T(fr))


def spearman_per_sample(pred: np.ndarray, gold: List[np.ndarray], ns: List[int]) -> float:
    out = []
    for i, n in enumerate(ns):
        p, g = pred[i, :n], np.asarray(gold[i][:n], dtype=np.float32)
        if n < 3 or p.std() < 1e-8 or g.std() < 1e-8:
            continue
        rp = np.argsort(np.argsort(p)).astype(np.float32)
        rg = np.argsort(np.argsort(g)).astype(np.float32)
        rp -= rp.mean()
        rg -= rg.mean()
        d = float(np.sqrt((rp * rp).sum() * (rg * rg).sum()))
        if d > 1e-8:
            out.append(float((rp * rg).sum() / d))
    return float(np.mean(out)) if out else 0.0


from sports_ai.budget import report_budget

# 基线档 = 本脚本的默认结构（hidden=160 / layers=5）。
BASE_HIDDEN = 160
BASE_DEPTH_UNITS = 6   # layers=5 时的 layers+1
BASE_EPOCHS = 60


def main() -> None:
    p = argparse.ArgumentParser(description="训练球类赛制模型 TournamentGnn")
    p.add_argument("--samples", type=int, default=800)
    p.add_argument("--epochs", type=int, default=60)
    p.add_argument("--batch", type=int, default=16)
    # ⚠️ 必须与 models/tournament_gnn.py 的构造函数默认值一致：
    #    训练用 96/3 而模型是 160/5 时，load_state_dict 直接 shape 不匹配。
    p.add_argument("--hidden", type=int, default=160)
    p.add_argument("--layers", type=int, default=5)
    p.add_argument("--lr", type=float, default=1e-3)
    p.add_argument("--seed", type=int, default=20261004)
    add_device_arg(p)
    args = p.parse_args()
    # ⚠️ 加深/加宽后若不同步加训练预算，指标会「看起来」更差，别误判成架构问题。
    report_budget("tournament_gnn", hidden=args.hidden, base_hidden=BASE_HIDDEN,
                  depth_units=args.layers + 1, base_depth_units=BASE_DEPTH_UNITS,
                  base_epochs=BASE_EPOCHS, base_patience=10,
                  epochs=args.epochs, patience=10)

    device = resolve_device(args.device)
    seed_all(0)
    print(f"[device] 训练设备: {describe_device(device)}")

    print(f"[data] 生成 {args.samples} 份球类场景（规则引擎穷举赛制作标签）…")
    data = make_dataset(args.samples, args.seed)
    if len(data) < 40:
        print("[data] 有效样本过少，终止")
        return
    rng = np.random.default_rng(args.seed)
    order = rng.permutation(len(data))
    n_train = max(1, int(len(data) * 0.8))
    tr = [data[int(i)] for i in order[:n_train]]
    va = [data[int(i)] for i in order[n_train:]] or tr[:8]
    print(f"[data] 训练 {len(tr)} / 验证 {len(va)}")

    model = TournamentGnn(hidden=args.hidden, layers=args.layers).to(device)
    print(f"[model] 参数量 {sum(q.numel() for q in model.parameters()):,}")
    opt = torch.optim.Adam(model.parameters(), lr=args.lr, weight_decay=1e-4)

    def batches(data_list, bs, shuffle):
        idx = np.arange(len(data_list))
        if shuffle:
            rng.shuffle(idx)
        for k in range(0, len(idx), bs):
            sel = [int(i) for i in idx[k:k + bs]]
            if sel:
                yield sel

    best = float("inf")
    best_state = None
    best_metrics: Dict[str, float] = {}
    # 预测分支的数据与损失：一次预生成、训练期循环采样
    # （现场每步生成要跑「场景生成 + 贪心着色」，那是主要开销）。
    from sports_ai.nn.forecast_aux import AuxData, aux_loss
    aux_data = AuxData(n=512, seed=args.seed + 4242, device=device)

    for epoch in range(args.epochs):
        model.train()
        tot = nb = 0.0
        for sel in batches(tr, args.batch, True):
            nf, abt, tm, mk, fmt, sd, fr = to_tensors(tr, sel, device)
            pl, ps, pf = model(nf, abt, tm, mk)
            # 赛制：软目标交叉熵
            loss_fmt = -(fmt * F.log_softmax(pl, dim=-1)).sum(-1).mean()
            # 种子/公平性：POMO 式共享基线（减 batch 均值降方差）
            loss_seed = (((ps - sd) ** 2).sum(-1) / mk.sum(-1)).mean()
            loss_fair = (((pf - fr) ** 2).sum(-1) / mk.sum(-1)).mean()
            loss = loss_fmt + loss_seed + 0.5 * loss_fair
            # MoE 两项：**必须都做**，否则等于装了 MoE 却没通电
            # （漏掉不报错，只是静默退化成「一个贵一点的单体网络」）。
            if getattr(model, "moe", None) is not None:
                loss = loss + 0.01 * model.moe.load_balance_loss()
            # 预测分支：未来 H 步时间槽（与主任务共享 self.moe 主干，
            # 所以预测能力会回流到表征里）。数据一次建好、循环采样。
            sx, sy = aux_data.sample(args.batch)
            loss = loss + aux_loss(model.aux, sx, sy,
                                   trunk_fn=(lambda h: model.moe(h))
                                   if getattr(model, "moe", None) is not None else None)
            opt.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            if getattr(model, "moe", None) is not None:
                model.moe.update_router_bias()
            tot += float(loss.item())
            nb += 1

        model.eval()
        with torch.no_grad():
            nf, abt, tm, mk, fmt, sd, fr = to_tensors(va, list(range(len(va))), device)
            pl, ps, pf = model(nf, abt, tm, mk)
            ns = [d["n"] for d in va]
            vfmt = float(-(fmt * F.log_softmax(pl, dim=-1)).sum(-1).mean().item())
            # 赛制 argmax 命中率
            hit = float((pl.argmax(-1) == fmt.argmax(-1)).float().mean().item())
            vseed = float((((ps - sd) ** 2).sum(-1) / mk.sum(-1)).mean().item())
            vfair = float((((pf - fr) ** 2).sum(-1) / mk.sum(-1)).mean().item())
            vsp = spearman_per_sample(ps.cpu().numpy(), [d["seed"] for d in va], ns)
        print(f"epoch {epoch:3d}  train_loss={tot / max(1.0, nb):.5f}  "
              f"val_fmt_ce={vfmt:.4f}  val_fmt_acc={hit:.3f}  "
              f"val_seed_mse={vseed:.5f}  val_seed_sp={vsp:.3f}  val_fair_mse={vfair:.5f}")
        score = vfmt + vseed
        if score < best:
            best = score
            best_metrics = {"val_fmt_ce": vfmt, "val_fmt_acc": hit,
                            "val_seed_mse": vseed, "val_seed_spearman": vsp,
                            "val_fair_mse": vfair}
            best_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}

    if best_state is not None:
        model.load_state_dict(best_state)
    os.makedirs(MODEL_DIR, exist_ok=True)
    path = os.path.join(MODEL_DIR, "tournament_gnn.pt")
    backup_before_overwrite(path, f"tournament-{args.samples}x{args.epochs}")
    torch.save({"state_dict": {k: v.detach().cpu() for k, v in model.state_dict().items()},
                "meta": {"hidden": args.hidden, "layers": args.layers,
                         "samples": args.samples, "epochs": args.epochs}}, path)
    with open(os.path.join(MODEL_DIR, "tournament_gnn_stats.json"), "w", encoding="utf-8") as fh:
        json.dump({**best_metrics, "hidden": args.hidden, "layers": args.layers},
                  fh, ensure_ascii=False, indent=2)
    print(f"best {best_metrics}")
    print(f"→ 已保存 {path}")


if __name__ == "__main__":
    main()
