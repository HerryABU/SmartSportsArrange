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
            # ⚠️ 规模必须**覆盖到极小赛会**：原先写死 150~800 人，
            #    而推理侧完全可能是「一个年级 4 个项目、十来个学生」的小型运动会。
            #    实测：仅用 150~800 人训练时，模型在 4 节点星形图上输出
            #    [0.080,0.064,0.069,0.080]（几乎无区分、量级也对不上），
            #    而正确标签是 [1.0,0.667,0.667,0.333] —— 因为标签是
            #    `度数/(n-1)`，**n 越小标签越大**，模型没见过大标签就永远输出小值。
            #    这就是「训练分布没覆盖推理分布」的典型后果：
            #    模型不报错，只是在那个区间完全失效。
            n_athletes=rng.randint(4, 800),
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


def _model_defaults(cls, *keys) -> dict:
    """读取模型类的构造默认值（用于预算换算，避免把维度再抄一遍）。

    ⚠️ 为什么不在训练脚本里写死 160/6：写死就等于**又造了一个真相源**，
    下次有人把模型默认维度改大，这里不会跟着变 ——
    「改了默认维度但训练预算没跟上」正是本轮踩的坑。
    """
    import inspect
    sig = inspect.signature(cls.__init__)
    out = {}
    for name, p in sig.parameters.items():
        if name == "self" or not isinstance(p.default, (int, float, bool)):
            continue
        # ⚠️ 不传 keys 时返回**全部**数值默认值。第一版写成"只在 keys 里找"，
        #    而调用处 `_model_defaults(ConflictGnn)` 根本没传 keys →
        #    keys 为空 → 返回空字典 → KeyError: 'hidden'（训练直接崩）。
        if keys and name not in keys:
            continue
        out[name] = p.default
    return out


def train(args):
    torch.manual_seed(0)

    # ---- 训练预算 ↔ 深度检查（机制性防呆，不是一次性手调）----
    # ⚠️ 本轮真实教训：把 hidden 从 64 提到 160、layers 从 4 提到 6 之后，
    #    仍按旧的 20 轮去跑，模型**没训够**，于是产物在
    #    「中心节点应得最高着色优先级」这类泛化断言上直接失败 ——
    #    表现像「深层架构更差」，真因是预算没跟上。用共享模块把这条钉死。
    #    ⚠️ 维度不写死：从模型类的构造默认值读（_model_defaults），
    #    否则「改了默认维度、预算没跟着变」会再次发生。
    from sports_ai.budget import report_budget
    _gnn_dims = _model_defaults(ConflictGnn)
    report_budget(
        "conflict_gnn",
        hidden=_gnn_dims["hidden"],
        base_hidden=64,
        depth_units=_gnn_dims["layers"] + 1,
        base_depth_units=5,
        base_epochs=20,
        base_patience=20,
        epochs=args.epochs,
        patience=args.epochs,
    )

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
