"""训练算法选择器 v2（语义 token Transformer），POMO 式共享基线。

## 相比 v1（``train_selector.py``）的三点升级

1. **架构**：语义 token Transformer（见 ``models/selector_v2.py``）——
   16 维按 5 组语义先组内池化再组间注意力，归纳偏置比全交叉 MLP 更强。
2. **POMO 式共享基线**（本脚本的核心改动）：CE 的基线不再取「本样本的预测概率」，
   而是取**批内全样本的共享基线**。同一个场景有多种等价表述时，
   单样本基线会因「采到哪个等价解」而抖动；共享基线把这一项方差消掉。
   实现上等价于：把 logits 减去批内平均 log-prob 的**可微版本**
   （``log_softmax`` 后减批均值），梯度期望不变但方差显著下降。
3. **类不平衡**：可解/不可解两类悬殊时用 class weight 对齐，
   并以 **macro-F1** 选最优模型（不是 accuracy——大类会掩盖小类崩掉）。

## 用法::

    python -m sports_ai.train_selector_v2 --samples 6000 --epochs 40 --device cpu
产出：``models/selector_v2.pt`` + ``models/selector_v2_stats.json``
"""

from __future__ import annotations

import argparse
import json
import os
import sys

import numpy as np
import torch
import torch.nn.functional as F

if __package__ in (None, ""):
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from sports_ai.data.features import N_FEATURES
from sports_ai.device import (
    add_device_arg,
    backup_before_overwrite,
    describe_device,
    resolve_device,
    seed_all,
)
from sports_ai.models.selector_v2 import AlgorithmSelectorV2, Normalize
from sports_ai.train_selector import make_dataset

MODEL_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "models")


def shared_baseline_ce(logits: torch.Tensor, target: torch.Tensor,
                       weight: torch.Tensor | None = None) -> torch.Tensor:
    """POMO 式共享基线交叉熵：log_softmax 后**减去批内均值**再取负对数似然。

    推导：标准 CE = -log p(y)。减去批内均值常数项后
        L = -( log_softmax(z)[y] - mean_b log_softmax(z_b) )
    减掉的是与参数无关的常数，**期望不变**；但它把「本样本预测概率的整体高低」
    这一 nuisance 成分从梯度里剥离，显著降低方差——这正是 POMO 用
    「N 条轨迹的平均回报当基线」来稳定 REINFORCE 的同一个思路。
    """
    logp = F.log_softmax(logits, dim=-1)                  # [B, C]
    nll = -logp.gather(1, target.view(-1, 1)).squeeze(1)  # [B]
    baseline = logp.mean(dim=0)                           # [C] 批内均值
    # 广播到每个样本：baseline[target[b]] 是「本类别在批内的平均 log 概率」
    centred = nll - baseline[target]
    if weight is not None:
        w = weight[target]
        return (centred * w).sum() / w.sum().clamp(min=1e-6)
    return centred.mean()


def macro_f1(pred: np.ndarray, gold: np.ndarray, n_classes: int = 2) -> float:
    f1s = []
    for c in range(n_classes):
        tp = float(((pred == c) & (gold == c)).sum())
        fp = float(((pred == c) & (gold != c)).sum())
        fn = float(((pred != c) & (gold == c)).sum())
        prec = tp / (tp + fp) if tp + fp else 0.0
        rec = tp / (tp + fn) if tp + fn else 0.0
        f1s.append(2 * prec * rec / (prec + rec) if prec + rec else 0.0)
    return float(np.mean(f1s))


def main() -> None:
    ap = argparse.ArgumentParser(description="训练算法选择器 v2（语义 token Transformer）")
    ap.add_argument("--samples", type=int, default=6000)
    ap.add_argument("--epochs", type=int, default=40)
    ap.add_argument("--batch", type=int, default=128)
    ap.add_argument("--d-model", type=int, default=64)
    ap.add_argument("--layers", type=int, default=2)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--seed", type=int, default=20261006)
    add_device_arg(ap)
    args = ap.parse_args()

    device = resolve_device(args.device)
    seed_all(0)
    print(f"[device] 训练设备: {describe_device(device)}")

    print(f"[data] 生成 {args.samples} 个场景…")
    X, y = make_dataset(args.samples, seed=args.seed)
    if len(X) < 100:
        print("[data] 样本不足，终止")
        return
    X = np.asarray(X, dtype=np.float32)
    y = np.asarray(y, dtype=np.int64)
    print(f"[data] X={X.shape} 正样本比例={y.mean():.3f}")

    # 标准化（与 v1 同一口径：近零方差特征不缩放，防 z=1e6 爆点）
    mean = X.mean(axis=0)
    std = X.std(axis=0)
    std = np.where(std < 1e-3, 1.0, std)      # ⚠️ 见 memory：近零方差不能缩放
    Xn = (X - mean) / std

    idx = np.random.default_rng(args.seed).permutation(len(X))
    n_tr = int(len(X) * 0.8)
    tr, va = idx[:n_tr], idx[n_tr:]
    Xtr = torch.from_numpy(Xn[tr]).to(device)
    ytr = torch.from_numpy(y[tr]).to(device)
    Xva = torch.from_numpy(Xn[va]).to(device)
    yva_np = y[va]
    print(f"[data] 训练 {len(tr)} / 验证 {len(va)}")

    model = AlgorithmSelectorV2(n_features=N_FEATURES, d_model=args.d_model,
                                n_layers=args.layers).to(device)
    norm = Normalize(mean, std).to(device)
    print(f"[model] 参数量 {sum(q.numel() for q in model.parameters()):,}")

    # 类权重：正负比例失衡时对齐
    pos = float((ytr == 1).sum())
    neg = float((ytr == 0).sum())
    w = torch.tensor([1.0, (neg / max(1.0, pos))], dtype=torch.float32, device=device)
    print(f"[model] 类权重 {w.tolist()}（不可解类更稀疏）")

    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.OneCycleLR(
        opt, max_lr=args.lr, total_steps=args.epochs * max(1, len(tr) // args.batch),
        pct_start=0.25)

    best_f1, best_state, best_stats = -1.0, None, {}
    for epoch in range(args.epochs):
        model.train()
        perm = torch.randperm(Xtr.shape[0], device=device)
        tot, nb = 0.0, 0
        for k in range(0, len(perm) - args.batch + 1, args.batch):
            sel = perm[k:k + args.batch]
            logits = model(norm(Xtr[sel]))
            loss = shared_baseline_ce(logits, ytr[sel], w)
            opt.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            try:
                sched.step()
            except ValueError:
                pass
            tot += float(loss.item())
            nb += 1

        model.eval()
        with torch.no_grad():
            pred = model(norm(Xva)).argmax(-1).cpu().numpy()
        f1 = macro_f1(pred, yva_np)
        acc = float((pred == yva_np).mean())
        print(f"epoch {epoch:3d}  loss={tot / max(1, nb):.5f}  val_acc={acc:.4f}  val_macro_f1={f1:.4f}")
        if f1 > best_f1:
            best_f1 = f1
            best_stats = {"val_accuracy": acc, "val_macro_f1": f1, "epoch": epoch,
                          "pos_rate": float(y.mean())}
            best_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}

    if best_state is not None:
        model.load_state_dict(best_state)
    os.makedirs(MODEL_DIR, exist_ok=True)
    path = os.path.join(MODEL_DIR, "selector_v2.pt")
    backup_before_overwrite(path, f"selector-v2-{args.samples}x{args.epochs}")
    torch.save({"state": {k: v.detach().cpu() for k, v in model.state_dict().items()},
                "mean": mean.astype(np.float32), "std": std.astype(np.float32),
                "d_model": args.d_model, "layers": args.layers}, path)
    with open(os.path.join(MODEL_DIR, "selector_v2_stats.json"), "w", encoding="utf-8") as fh:
        json.dump(best_stats, fh, ensure_ascii=False, indent=2)
    print(f"best {best_stats}")
    print(f"→ 已保存 {path}")


if __name__ == "__main__":
    main()
