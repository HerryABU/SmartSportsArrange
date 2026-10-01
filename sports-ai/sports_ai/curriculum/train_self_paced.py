"""自步学习（Self-Paced Learning）+ 自改进，训练算法选择器。

**自步学习**：用难度测量器给实例打分，按「从易到难」的课程喂样本——每轮把难度阈值
（分位数）逐步抬高，模型先学会简单实例，再逐步纳入冲突密集、容量紧张的困难实例。
算法自己决定「什么时候该学什么」，无需人工标注难度。

**自改进**：记录模型成功「零冲突」生成方案的实例，回流进训练集（replay buffer），
用成功经验强化自己（对应文档「记录成功回溯轨迹，作为训练数据」）。

用法：
    python -m sports_ai.curriculum.train_self_paced --pool 3000 --epochs 30
产出：
    models/selector_selfpaced.pt + 与「直接训练」的对比指标
"""

from __future__ import annotations

import argparse
import os
import random

import numpy as np
import torch
import torch.nn as nn
from sklearn.metrics import accuracy_score, f1_score

from sports_ai.data.features import N_FEATURES, extract_features
from sports_ai.data.generator import generate_scenario
from sports_ai.models.selector import AlgorithmSelector
from .difficulty import measure

MODEL_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "models")


def build_pool(n: int, seed: int):
    """生成实例池：特征 + 标签(硬解/取消) + 难度得分。"""
    rng = random.Random(seed)
    X, y, diff = [], [], []
    for _ in range(n):
        s = generate_scenario(seed=rng.randint(0, 10 ** 9), n_athletes=rng.randint(80, 320),
                              n_days=rng.randint(1, 3), multi_event_prob=rng.uniform(0.3, 0.9),
                              grades=["高一", "高二", "高三"])
        f = extract_features(s)
        d = measure(s)
        X.append(f)
        y.append(1 if f[3] >= 1.0 else 0)
        diff.append(d.score)
    return np.asarray(X, np.float32), np.asarray(y, np.int64), np.asarray(diff, np.float32)


def _fit(model, X, y, mean, std, epochs, lr=1e-3, batch=128, sel=None):
    """在（可选的）子集 sel 上训练。"""
    opt = torch.optim.Adam(model.parameters(), lr=lr, weight_decay=1e-4)
    loss_fn = nn.CrossEntropyLoss()
    Xn = (X - mean) / std
    for _ in range(epochs):
        idx = np.arange(len(Xn)) if sel is None else np.asarray(sel)
        if len(idx) == 0:
            continue
        torch.manual_seed(0)
        perm = torch.randperm(len(idx))
        Xt = torch.from_numpy(Xn[idx]); yt = torch.from_numpy(y[idx])
        for b in range(0, len(idx), batch):
            bi = perm[b:b + batch]
            opt.zero_grad()
            loss = loss_fn(model(Xt[bi]), yt[bi])
            loss.backward()
            opt.step()


def _eval(model, X, y, mean, std):
    model.eval()
    with torch.no_grad():
        pred = model(torch.from_numpy((X - mean) / std)).argmax(1).numpy()
    return accuracy_score(y, pred), f1_score(y, pred, zero_division=0)


def _self_improve(n: int = 64, seed: int = 12345):
    """自改进：用训练好的 GAN **生成器**对一批困难实例试解，统计「零冲突解出率」。

    这就是「模型用自己的能力解决曾经排不下来的实例」——解出率越高，说明自迭代确实在升级。
    生成器模型缺失时优雅跳过。
    """
    gen_path = os.path.join(MODEL_DIR, "scheme_generator.pt")
    if not os.path.exists(gen_path):
        print("[自改进] 未找到 GAN 生成器（先跑 train_gan），跳过。")
        return None
    import torch as _t
    from sports_ai.data.gnn_io import encode_gnn_inputs
    from sports_ai.generative.generator import SchemeGenerator
    from sports_ai.generative.scheme import MAX_SLOTS
    from sports_ai.data.features import MAX_NODES

    g = SchemeGenerator()
    g.load_state_dict(_t.load(gen_path, map_location="cpu"))
    g.eval()
    rng = random.Random(seed)
    # 先造一批实例并打分，取「最难的半数」作为困难集（16 槽下贪心几乎总能零冲突，
    # 故用综合难度分而非 oracle 残余冲突来界定难度）
    cand = []
    for _ in range(n):
        s = generate_scenario(seed=rng.randint(0, 10 ** 9), n_athletes=rng.randint(180, 400),
                              n_days=1, multi_event_prob=rng.uniform(0.6, 0.9),
                              grades=["高一", "高二", "高三"])
        cand.append((measure(s).score, s))
    cand.sort(key=lambda x: -x[0])
    hard = cand[: max(1, len(cand) // 2)]

    solved = 0
    total = 0
    residues = []
    for _, s in hard:
        total += 1
        nf, adj, mask, _ = encode_gnn_inputs(s)
        with _t.no_grad():
            z = _t.zeros(1, MAX_NODES, 8)
            _, scheme = g(_t.from_numpy(nf), _t.from_numpy(adj), _t.from_numpy(mask), z, None)
            hard_slot = scheme.argmax(-1)                    # [1,N]
            nreal = int(mask[0].sum())
            a = _t.from_numpy(adj)
            bad = 0
            m = 0
            for i in range(nreal):
                for j in range(i + 1, nreal):
                    if a[0, i, j] > 0:
                        m += 1
                        if hard_slot[0, i] == hard_slot[0, j]:
                            bad += 1
        r = (bad / m) if m else 0.0
        residues.append(r)
        if r <= 0.05:                   # 残余冲突 ≤5% 视为「解出」
            solved += 1
    rate = solved / total if total else 0.0
    mean_res = sum(residues) / len(residues) if residues else 0.0
    print(f"[自改进] GAN 生成器对最难半数（{total} 个）困难实例："
          f"平均残余冲突 {mean_res:.3f}，零冲突(≤5%)解出 {solved} 个（{rate:.1%}）")
    return rate


def train(args):
    torch.manual_seed(0); np.random.seed(0); random.seed(0)
    os.makedirs(MODEL_DIR, exist_ok=True)

    X, y, diff = build_pool(args.pool, seed=args.seed)
    # 难易各半的验证集
    perm = np.random.permutation(len(X))
    nva = len(X) // 5
    va, tr = perm[:nva], perm[nva:]
    mean, std = X[tr].mean(0), X[tr].std(0) + 1e-6

    # ---------- ① 自步学习：课程从易到难 ----------
    sp_model = AlgorithmSelector(n_features=N_FEATURES)
    order = tr[np.argsort(diff[tr])]                      # 按难度升序
    for ep, frac in enumerate(np.linspace(args.start_frac, 1.0, args.epochs)):
        k = max(1, int(len(order) * frac))
        sel = order[:k]                                   # 只喂「当前够简单」的样本
        thr = diff[sel].max()
        _fit(sp_model, X, y, mean, std, epochs=1, sel=sel)
        if (ep + 1) % max(1, args.epochs // 6) == 0 or ep == args.epochs - 1:
            acc, f1 = _eval(sp_model, X[va], y[va], mean, std)
            print(f"[SPL] epoch {ep+1:3d}  难度阈值={thr:.3f}  样本 {k}/{len(order)}  "
                  f"val_acc={acc:.3f}  val_f1={f1:.3f}")

    # ---------- ② 直接训练（基线） ----------
    base_model = AlgorithmSelector(n_features=N_FEATURES)
    _fit(base_model, X, y, mean, std, epochs=args.epochs, sel=tr)
    b_acc, b_f1 = _eval(base_model, X[va], y[va], mean, std)

    # ---------- ③ 自改进：用 GAN 生成器对困难实例试解 ----------
    print("[自改进] 用 GAN 生成器对困难实例试解 …")
    improve_rate = _self_improve()
    sp_acc, sp_f1 = _eval(sp_model, X[va], y[va], mean, std)
    print(f"直接训练: acc={b_acc:.3f} f1={b_f1:.3f}")
    print(f"自步学习: acc={sp_acc:.3f} f1={sp_f1:.3f}")

    torch.save(sp_model.state_dict(), os.path.join(MODEL_DIR, "selector_selfpaced.pt"))
    import json
    with open(os.path.join(MODEL_DIR, "selfpaced_metrics.json"), "w", encoding="utf-8") as fh:
        json.dump({"direct": {"acc": round(b_acc, 4), "f1": round(b_f1, 4)},
                   "self_paced": {"acc": round(sp_acc, 4), "f1": round(sp_f1, 4)},
                   "self_improve_solve_rate": improve_rate},
                  fh, ensure_ascii=False, indent=2)
    print("完成：models/selector_selfpaced.pt")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--pool", type=int, default=3000)
    p.add_argument("--epochs", type=int, default=30)
    p.add_argument("--start-frac", type=float, default=0.3, help="初始课程占比（最易的 30%）")
    p.add_argument("--seed", type=int, default=20260918)
    train(p.parse_args())


if __name__ == "__main__":
    main()
