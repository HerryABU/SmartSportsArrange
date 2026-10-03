"""多步预测训练：对比 Direct / Recursive / MIMO 三种策略。

用法：
    python -m sports_ai.forecast.train_forecast --samples 3000 --epochs 25

产出：
    models/forecast_direct.pt / forecast_recursive.pt / forecast_mimo.pt（+ 各自 val MSE）
"""

from __future__ import annotations

import argparse
import json
import os
import random

import numpy as np
import torch
import torch.nn as nn

from .dataset import H_OUT, IN_DIM, L_IN, make_dataset
from .model import DirectForecaster, MimoForecaster, RecursiveForecaster
from sports_ai.device import add_device_arg, backup_before_overwrite, describe_device, resolve_device, seed_all, to_device

MODEL_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "models")


def _train_one(model, Xtr, Ytr, Xva, Yva, epochs, lr=1e-3, batch=128, device=None):
    opt = torch.optim.Adam(model.parameters(), lr=lr)
    loss_fn = nn.MSELoss()
    best = float("inf")
    for ep in range(epochs):
        model.train()
        perm = torch.randperm(len(Xtr))
        for b in range(0, len(Xtr), batch):
            idx = perm[b:b + batch]
            opt.zero_grad()
            loss = loss_fn(model(Xtr[idx]), Ytr[idx])
            loss.backward()
            opt.step()
        model.eval()
        with torch.no_grad():
            v = loss_fn(model(Xva), Yva).item()
        best = min(best, v)
    return best


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--samples", type=int, default=3000)
    p.add_argument("--epochs", type=int, default=25)
    p.add_argument("--seed", type=int, default=20260918)
    add_device_arg(p)
    args = p.parse_args()

    torch.manual_seed(0)
    np.random.seed(0)
    random.seed(0)
    os.makedirs(MODEL_DIR, exist_ok=True)

    X, Y, _ = make_dataset(args.samples, seed=args.seed)
    n = len(X)
    ntr = int(n * 0.8)
    idx = np.random.permutation(n)
    tr, va = idx[:ntr], idx[ntr:]
    device = resolve_device(getattr(args, "device", "auto"))
    print(f"[device] 训练设备: {describe_device(device)}")
    Xtr, Ytr = torch.from_numpy(X[tr]).to(device), torch.from_numpy(Y[tr]).to(device)
    Xva, Yva = torch.from_numpy(X[va]).to(device), torch.from_numpy(Y[va]).to(device)
    print(f"数据集: {n} 条（特征 {L_IN}×{IN_DIM} → 预测 {H_OUT} 步）")

    results = {}
    for name, model, fname in [
        ("Direct", DirectForecaster(IN_DIM, 64, H_OUT), "forecast_direct.pt"),
        ("Recursive", RecursiveForecaster(IN_DIM, 64, H_OUT), "forecast_recursive.pt"),
        ("MIMO", MimoForecaster(IN_DIM, 64, H_OUT), "forecast_mimo.pt"),
    ]:
        model = model.to(device)
        mse = _train_one(model, Xtr, Ytr, Xva, Yva, args.epochs, device=device)
        # 覆盖前备份 + 权重存 CPU（跨设备加载更稳）
        backup_before_overwrite(os.path.join(MODEL_DIR, fname), f"smoke-{args.samples}")
        torch.save({k: v.detach().cpu() for k, v in model.state_dict().items()},
                   os.path.join(MODEL_DIR, fname))
        results[name] = round(mse, 6)
        print(f"[{name:9s}] val_MSE={mse:.6f}  → {fname}")

    backup_before_overwrite(os.path.join(MODEL_DIR, "forecast_metrics.json"), f"smoke-{args.samples}")
    with open(os.path.join(MODEL_DIR, "forecast_metrics.json"), "w", encoding="utf-8") as fh:
        json.dump(results, fh, ensure_ascii=False, indent=2)
    print("完成：三种策略已训练并保存。")


if __name__ == "__main__":
    main()
