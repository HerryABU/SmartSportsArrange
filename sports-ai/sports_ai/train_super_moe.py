"""训练 SuperScheduleMoE（统一编排超级模型）。

五档场景混合（HELL/REGULAR/BLOCK/LANE/TEAM），自监督标签来自
{@code super_encode.greedy_targets}（约束驱动，不需标注的最优解）。

## 训练时必看的两个指标

1. **专家使用率**（{@code model.expert_usage()}）——MoE 最常见的隐性失败是
   「专家建了但从不被选中」，而此时 loss 照样下降。必须盯着它。
2. **负载均衡损失**——不接近均匀就说明专家塌缩了。

## 用法::

    python -m sports_ai.train_super_moe --samples 1200 --epochs 40 --device cpu
产出：``models/super_moe.pt`` + ``models/super_moe_stats.json``
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

if __package__ in (None, ""):
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from sports_ai.data.super_encode import MAX_SLOTS, encode_super_graph, greedy_targets
from sports_ai.data.super_scenarios import (
    N_EDGES,
    generate_super_scenario,
    validate_scenario,
)
from sports_ai.device import (
    add_device_arg,
    backup_before_overwrite,
    describe_device,
    resolve_device,
    seed_all,
)
from sports_ai.models.super_moe import NODE_FEAT_DIM, SuperScheduleMoE

MODEL_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "models")
TIERS = ["HELL", "REGULAR", "BLOCK", "LANE", "TEAM"]


def make_dataset(n: int, seed: int) -> List[Dict]:
    """五档混合采样。**样本不通过 validate 就丢弃**（不修数据）。"""
    rng = random.Random(seed)
    data: List[Dict] = []
    rejected: Dict[str, int] = {}
    attempts = 0
    while len(data) < n and attempts < n * 5:
        attempts += 1
        tier = TIERS[len(data) % len(TIERS)]
        scen = generate_super_scenario(tier, seed=rng.randint(0, 10 ** 9))
        ok, why = validate_scenario(scen)
        if not ok:
            rejected[why] = rejected.get(why, 0) + 1
            continue
        enc = encode_super_graph(scen)
        if enc is None:
            rejected["编码失败"] = rejected.get("编码失败", 0) + 1
            continue
        tg = greedy_targets(scen, enc["n"])
        if float(tg["priority"].std()) < 1e-4:
            rejected["目标无方差"] = rejected.get("目标无方差", 0) + 1
            continue
        data.append({**enc, "tier": tier, **tg})
    if rejected:
        print(f"[data] 判废统计: {rejected}")
    return data


def to_tensors(batch: List[Dict], idxs: List[int], device):
    """把变长样本 pad 到批内最大 N（ONNX 侧 N 动态，PyTorch 批处理必须先 pad）。"""
    items = [batch[i] for i in idxs]
    width = max(d["n"] for d in items)
    nf, abt, tm, mk, pri, slot, fmt = [], [], [], [], [], [], []
    for d in items:
        n, pad = d["n"], width - d["n"]
        nf.append(np.pad(d["node_feat"][0], ((0, pad), (0, 0))))
        abt.append(np.pad(d["adj_by_type"][0], ((0, 0), (0, pad), (0, pad))))
        tm.append(d["type_mask"][0])
        mk.append(np.pad(d["mask"][0], (0, pad)))
        pri.append(np.pad(d["priority"], (0, pad)))
        sl = np.zeros((width, MAX_SLOTS), dtype=np.float32)
        sl[:n] = d["slot"]
        slot.append(sl)
        fmt.append(d["format"])
    T = lambda a: torch.from_numpy(np.asarray(a, dtype=np.float32)).to(device)
    return (T(nf), T(abt), T(tm), T(mk), T(pri), T(slot), T(fmt).long())


def main() -> None:
    ap = argparse.ArgumentParser(description="训练统一编排超级模型 SuperScheduleMoE")
    ap.add_argument("--samples", type=int, default=1200)
    ap.add_argument("--epochs", type=int, default=40)
    ap.add_argument("--batch", type=int, default=8)
    ap.add_argument("--hidden", type=int, default=128)
    ap.add_argument("--steps", type=int, default=8)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--w-lb", type=float, default=0.01, help="负载均衡损失权重")
    ap.add_argument("--seed", type=int, default=20261007)
    add_device_arg(ap)
    args = ap.parse_args()

    device = resolve_device(args.device)
    seed_all(0)
    print(f"[device] 训练设备: {describe_device(device)}")

    print(f"[data] 生成 {args.samples} 个五档混合场景…")
    data = make_dataset(args.samples, args.seed)
    if len(data) < 40:
        print("[data] 有效样本不足，终止")
        return
    rng = np.random.default_rng(args.seed)
    order = rng.permutation(len(data))
    n_tr = max(1, int(len(data) * 0.8))
    tr = [data[int(i)] for i in order[:n_tr]]
    va = [data[int(i)] for i in order[n_tr:]] or tr[:8]
    tiers = {}
    for d in data:
        tiers[d["tier"]] = tiers.get(d["tier"], 0) + 1
    print(f"[data] 训练 {len(tr)} / 验证 {len(va)}，档位分布 {tiers}")

    model = SuperScheduleMoE(node_feat=NODE_FEAT_DIM, hidden=args.hidden,
                             steps=args.steps).to(device)
    print(f"[model] 参数量 {sum(q.numel() for q in model.parameters()):,}")
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-5)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=args.epochs)

    best = float("inf")
    best_state = None
    best_stats: Dict[str, object] = {}
    for epoch in range(args.epochs):
        model.train()
        tot, nb = 0.0, 0
        idx = np.arange(len(tr))
        rng.shuffle(idx)
        for k in range(0, len(idx) - args.batch + 1, args.batch):
            sel = [int(i) for i in idx[k:k + args.batch]]
            if not sel:
                continue
            nf, abt, tm, mk, pri, slot, fmt = to_tensors(tr, sel, device)
            loss, parts = model.training_loss(pri, slot, nf, abt, tm, mk, fmt, w_lb=args.w_lb)
            opt.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            tot += float(loss.item())
            nb += 1
        sched.step()

        model.eval()
        with torch.no_grad():
            vs, vp = [], []
            for k in range(0, len(va), max(1, args.batch)):
                sel = list(range(k, min(len(va), k + max(1, args.batch))))
                if not sel:
                    continue
                nf, abt, tm, mk, pri, slot, fmt = to_tensors(va, sel, device)
                loss, parts = model.training_loss(pri, slot, nf, abt, tm, mk, fmt, w_lb=args.w_lb)
                vs.append(float(loss.item()))
                vp.append(parts)
        vloss = float(np.mean(vs)) if vs else float("inf")
        usage = model.expert_usage()
        usage_min = min(usage.values()) if usage else 0.0
        print(f"epoch {epoch:3d}  train={tot / max(1, nb):.5f}  val={vloss:.5f}  "
              f"pri_mse={np.mean([p['priority_mse'] for p in vp]):.5f}  "
              f"slot_mse={np.mean([p['slot_mse'] for p in vp]):.5f}  "
              f"专家最低使用率={usage_min:.4f}")
        if vloss < best:
            best = vloss
            best_stats = {
                "val_loss": vloss,
                "val_priority_mse": float(np.mean([p["priority_mse"] for p in vp])),
                "val_slot_mse": float(np.mean([p["slot_mse"] for p in vp])),
                "expert_usage": usage,
                "min_expert_usage": usage_min,
                "epoch": epoch,
            }
            best_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}

    if best_state is not None:
        model.load_state_dict(best_state)
    os.makedirs(MODEL_DIR, exist_ok=True)
    path = os.path.join(MODEL_DIR, "super_moe.pt")
    backup_before_overwrite(path, f"super-moe-{args.samples}x{args.epochs}")
    torch.save({k: v.detach().cpu() for k, v in model.state_dict().items()}, path)
    with open(os.path.join(MODEL_DIR, "super_moe_stats.json"), "w", encoding="utf-8") as fh:
        json.dump(best_stats, fh, ensure_ascii=False, indent=2)
    print(f"best {json.dumps(best_stats, ensure_ascii=False)}")
    print(f"→ 已保存 {path}")


if __name__ == "__main__":
    main()
