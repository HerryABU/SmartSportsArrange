"""训练算法选择器（硬解 vs 取消路径）。

用法（在 sports-ai/ 下，先激活 venv）：
    python -m sports_ai.train_selector --samples 4000 --epochs 30

产出：
    models/selector.pt        模型权重
    models/selector_stats.json 特征标准化常数（mean/std），供 export_onnx 固化进模型
"""

from __future__ import annotations

import argparse
import json
import os
import random

import numpy as np
import torch
import torch.nn as nn
from sklearn.metrics import accuracy_score, f1_score

from sports_ai.data.features import N_FEATURES, extract_features
from sports_ai.data.generator import generate_scenario
from sports_ai.models.selector import AlgorithmSelector
from sports_ai.solve.feasibility import analyze_bounds

MODEL_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "models")


def solvable(scenario) -> bool:
    """真实可解性判据（与 :mod:`sports_ai.solve` 同口径）。

    ⚠️ 不能用「tension ≥ 1」当标签：容量够也可能排不下——**团下界**（两两互相冲突、
    必须错开时段的单元数 > 可用时段数）是独立的不可解成因。只用 tension 阈值会让选择器
    在「容量够但团超时段」的实例上误判为「硬解」（曾在地狱场景 2 天情形上踩到）。
    """
    b = analyze_bounds(scenario.units, scenario.placements)
    return bool(b["capacityFeasible"]) and bool(b["cliqueFeasible"])


def make_dataset(n: int, seed: int):
    """合成训练集：标签 = 该实例**是否可解**（容量够 且 团不超过可用时段数）。"""
    X, y = [], []
    rng = random.Random(seed)
    for _ in range(n):
        s = generate_scenario(
            seed=rng.randint(0, 10 ** 9),
            n_athletes=rng.randint(150, 800),
            n_days=rng.randint(2, 6),
            multi_event_prob=rng.uniform(0.3, 0.9),
            grades=["高一", "高二", "高三"],
            event_drop_prob=0.3,
            track_lanes=rng.choice([1, 2, 3]),
            field_lanes=rng.choice([2, 3, 4, 5]),
            day_windows=rng.choice([(180, 150), (240, 240), (210, 210)]),
        )
        X.append(extract_features(s))
        y.append(0 if solvable(s) else 1)      # 0 = 硬解，1 = 取消路径
    return np.asarray(X, dtype=np.float32), np.asarray(y, dtype=np.int64)


def train(args):
    torch.manual_seed(0)
    np.random.seed(0)
    random.seed(0)

    X, y = make_dataset(args.samples, seed=args.seed)
    # 标准化：mean/std 存下来供导出固化。
    # ⚠️ 近零方差特征（训练里近乎恒定，如 group_count）**不缩放**——否则真实数据上的一点差异
    #    会被除以 ~0 放大成天文数字，模型在真实场景直接崩（曾出现 z=1e6 的爆点）。
    mean = X.mean(axis=0)
    std = X.std(axis=0)
    std = np.where(std < 1e-3, 1.0, std)
    Xn = (X - mean) / std

    # 划分
    idx = np.random.permutation(len(X))
    n_train = int(len(X) * 0.8)
    tr_idx, va_idx = idx[:n_train], idx[n_train:]
    Xtr = torch.from_numpy(Xn[tr_idx])
    ytr = torch.from_numpy(y[tr_idx])
    Xva = torch.from_numpy(Xn[va_idx])
    yva = y[va_idx]

    model = AlgorithmSelector(n_features=N_FEATURES)
    opt = torch.optim.Adam(model.parameters(), lr=1e-3, weight_decay=1e-4)
    loss_fn = nn.CrossEntropyLoss()

    best_f1, best_state = 0.0, None
    for epoch in range(args.epochs):
        model.train()
        perm = torch.randperm(len(Xtr))
        for b in range(0, len(Xtr), args.batch):
            batch = perm[b:b + args.batch]
            xb, yb = Xtr[batch], ytr[batch]
            opt.zero_grad()
            loss = loss_fn(model(xb), yb)
            loss.backward()
            opt.step()

        model.eval()
        with torch.no_grad():
            logits = model(Xva)
            pred = logits.argmax(dim=1).numpy()
        acc = accuracy_score(yva, pred)
        f1 = f1_score(yva, pred, zero_division=0)
        if f1 > best_f1:
            best_f1, best_state = f1, {k: v.clone() for k, v in model.state_dict().items()}
        print(f"epoch {epoch:3d}  loss={loss.item():.4f}  val_acc={acc:.3f}  val_f1={f1:.3f}")

    model.load_state_dict(best_state)
    os.makedirs(MODEL_DIR, exist_ok=True)
    torch.save(model.state_dict(), os.path.join(MODEL_DIR, "selector.pt"))
    with open(os.path.join(MODEL_DIR, "selector_stats.json"), "w", encoding="utf-8") as fh:
        json.dump({"mean": mean.tolist(), "std": std.tolist()}, fh, ensure_ascii=False, indent=2)
    print(f"best_val_f1={best_f1:.3f}  → 已保存 models/selector.pt + selector_stats.json")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--samples", type=int, default=4000)
    p.add_argument("--epochs", type=int, default=30)
    p.add_argument("--batch", type=int, default=128)
    p.add_argument("--seed", type=int, default=20260918)
    train(p.parse_args())


if __name__ == "__main__":
    main()
