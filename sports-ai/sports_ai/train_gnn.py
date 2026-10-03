"""训练冲突簇 GNN（预测节点着色优先级 = 归一化度数中心度）。

用法：
    python -m sports_ai.train_gnn --samples 1500 --epochs 20

产出：
    models/gnn.pt   模型权重（节点特征已在 [0,1]，无需额外标准化常数）
"""

from __future__ import annotations
from sports_ai.data.gnn_io import TRAIN_PAD_TO

import argparse
import os
import random

import numpy as np
import torch
import torch.nn as nn

from sports_ai.data.generator import generate_scenario
from sports_ai.data.gnn_io import encode_gnn_inputs
from sports_ai.device import (add_device_arg, backup_before_overwrite, describe_device,
                             resolve_device, seed_all, to_device)
from sports_ai.models.gnn import ConflictGnn

MODEL_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "models")


def make_batch(samples: int, seed: int):
    """返回 (node_feat, adj, mask, label) 的批量张量。"""
    rng = random.Random(seed)
    nfs, adjs, mks, lbs = [], [], [], []
    for _ in range(samples):
        s = generate_scenario(
            seed=rng.randint(0, 10 ** 9),
            n_athletes=rng.randint(150, 800),
            n_days=rng.randint(2, 6),
            multi_event_prob=rng.uniform(0.4, 0.85),
            grades=["高一", "高二", "高三"],
            event_drop_prob=0.3,
            track_lanes=rng.choice([1, 2, 3]),
            field_lanes=rng.choice([2, 3, 4, 5]),
            day_windows=rng.choice([(180, 150), (240, 240), (210, 210)]),
        )
        nf, adj, mk, lb = encode_gnn_inputs(s, pad_to=TRAIN_PAD_TO)
        nfs.append(nf)
        adjs.append(adj)
        mks.append(mk)
        lbs.append(lb)
    return (
        torch.from_numpy(np.concatenate(nfs, axis=0)),
        torch.from_numpy(np.concatenate(adjs, axis=0)),
        torch.from_numpy(np.concatenate(mks, axis=0)),
        torch.from_numpy(np.stack(lbs, axis=0)),   # 标签是 1D [MAX]，stack 成 [samples, MAX]
    )


def train(args):
    torch.manual_seed(0)
    np.random.seed(0)
    random.seed(0)

    device = resolve_device(getattr(args, "device", "auto"))
    seed_all(0)
    print(f"[device] 训练设备: {describe_device(device)}")

    node_feat, adj, mask, label = make_batch(args.samples, seed=args.seed)
    node_feat, adj, mask, label = to_device((node_feat, adj, mask, label), device)

    n = len(node_feat)
    n_train = int(n * 0.8)
    tr = (node_feat[:n_train], adj[:n_train], mask[:n_train], label[:n_train])
    va = (node_feat[n_train:], adj[n_train:], mask[n_train:], label[n_train:])

    model = ConflictGnn().to(device)
    opt = torch.optim.Adam(model.parameters(), lr=1e-3, weight_decay=1e-4)
    loss_fn = nn.MSELoss(reduction="none")

    def masked_mse(pred, target, m):
        return (loss_fn(pred, target) * m).sum() / m.sum().clamp(min=1)

    best = float("inf")
    _gnn_backed_up = False
    for epoch in range(args.epochs):
        model.train()
        # 索引张量必须与训练张量同设备（CPU 索引索引 CUDA 张量会抛 device mismatch）
        perm = torch.randperm(n_train, device=device)
        total = 0.0
        for b in range(0, n_train, args.batch):
            bi = perm[b:b + args.batch]
            pred = model(tr[0][bi], tr[1][bi], tr[2][bi])
            loss = masked_mse(pred, tr[3][bi], tr[2][bi])
            opt.zero_grad()
            loss.backward()
            opt.step()
            total += loss.item()
        model.eval()
        with torch.no_grad():
            vpred = model(va[0], va[1], va[2])
            vloss = masked_mse(vpred, va[3], va[2]).item()
        print(f"epoch {epoch:3d}  train_loss={total / max(1, n_train // args.batch):.4f}  val_loss={vloss:.4f}")
        if vloss < best:
            best = vloss
            # 覆盖前备份：冒烟训练同样会冲掉正式权重（.pt 一般不在 git 跟踪内）
            if not _gnn_backed_up:
                backup_before_overwrite(os.path.join(MODEL_DIR, "gnn.pt"),
                                        f"smoke-{args.samples}x{args.epochs}")
                _gnn_backed_up = True
            torch.save(model.state_dict(), os.path.join(MODEL_DIR, "gnn.pt"))

    print(f"best_val_loss={best:.4f}  → 已保存 models/gnn.pt")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--samples", type=int, default=1500)
    p.add_argument("--epochs", type=int, default=20)
    p.add_argument("--batch", type=int, default=16)
    p.add_argument("--seed", type=int, default=20260918)
    add_device_arg(p)
    train(p.parse_args())


if __name__ == "__main__":
    main()
