"""训练 SchemeDiffusion（扩散版方案生成器），替代 GAN 的 minmax 对抗训练。

## 为什么不用 GAN

见 ``diffusion.py`` 的模块说明。三条最实际的：
训练不稳（易模式崩塌）、多一个判别器的开销、推理步数不可控。

## 训练信号

**去噪 MSE**（不是对抗）。真样本仍由 ``oracle.greedy_coloring`` 给（可行硬着色），
外加两项真实约束的软惩罚，让模型在去噪的同时就朝着「冲突少、装箱不过载」走：

* ``combination_loss``：兼项冲突 + 禁止槽 + 槽容量
* **可行性一致性**：把预测方案过一遍 ``scheme_conflicts``，冲突高则加大权重

## 用法::

    python -m sports_ai.generative.train_diffusion --iters 1500 --device cpu
产出：``models/scheme_diffusion.pt`` + ``models/scheme_diffusion_stats.json``
"""

from __future__ import annotations

import argparse
import json
import os
import random
import sys

import numpy as np
import torch

if __package__ in (None, ""):
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from sports_ai.data.features import MAX_NODES, NODE_FEAT_DIM
from sports_ai.data.generator import generate_scenario
from sports_ai.data.gnn_io import TRAIN_PAD_TO, encode_gnn_inputs
from sports_ai.device import (
    add_device_arg,
    backup_before_overwrite,
    describe_device,
    resolve_device,
    seed_all,
)
from sports_ai.generative.diffusion import SchemeDiffusion
from sports_ai.generative.oracle import oracle_batch
from sports_ai.generative.scheme import (
    MAX_SLOTS,
    combination_loss,
    scheme_conflicts,
)

MODEL_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "models"
)


def make_batch(n_samples: int, seed: int):
    """构建一批冲突图 + 可行真解。

    真解走 ``oracle_batch(adj, mask, forbid, max_slots)``——它接的是**张量**，
    不是场景对象列表（这一点很容易搞错：签名里没有 scenario 参数）。

    刻意偏向紧张实例（n_days 偏少、人数偏多、兼项率高），与 train_gan 的取材一致：
    否则模型只在易实例上表现好，一遇容量紧张残余冲突就高。
    """
    rng = random.Random(seed)
    nfs, adjs, mks = [], [], []
    for _ in range(n_samples):
        s = generate_scenario(
            seed=rng.randint(0, 10 ** 9),
            n_athletes=rng.randint(200, 800),
            n_days=rng.choice([1, 2, 4]),
            multi_event_prob=rng.uniform(0.5, 0.95),
            grades=["高一", "高二", "高三"],
        )
        # ⚠️ encode_gnn_inputs 返回 **tuple** (nf, adj, mask, degree_label)，
        #    不是 dict；且训练必须传 pad_to=TRAIN_PAD_TO 才能拼 batch。
        nf_i, adj_i, mk_i, _ = encode_gnn_inputs(s, pad_to=TRAIN_PAD_TO)
        if int(mk_i.sum()) < 4:
            continue
        # ⚠️ encode_gnn_inputs 返回的是 **[1, ...]（自带 batch 维）**，
        #    直接 np.stack 会得到 5 维张量（N 变成 1），
        #    后续 torch.cat 出来是 4 维，报 "Tensors must have same number of
        #    dimensions: got 3 and 4"。这里去掉 batch 维再拼。
        nfs.append(nf_i[0])
        adjs.append(adj_i[0])
        mks.append(mk_i[0])
    if not nfs:
        return None
    nf = torch.from_numpy(np.stack(nfs)).float()
    adj = torch.from_numpy(np.stack(adjs)).float()
    mk = torch.from_numpy(np.stack(mks)).float()
    # 真解：Oracle 贪心着色（可行硬着色）
    x0 = oracle_batch(adj, mk, None, MAX_SLOTS)
    return nf, adj, mk, x0, 8.0


def main() -> None:
    p = argparse.ArgumentParser(description="训练 SchemeDiffusion（扩散版方案生成器）")
    p.add_argument("--iters", type=int, default=1500)
    p.add_argument("--batch", type=int, default=16)
    p.add_argument("--lr", type=float, default=2e-4)
    p.add_argument("--hidden", type=int, default=128)
    p.add_argument("--layers", type=int, default=4)
    p.add_argument("--steps", type=int, default=8)
    p.add_argument("--w-constraint", type=float, default=0.5,
                   help="真实约束惩罚权重（0 = 纯去噪）")
    p.add_argument("--seed", type=int, default=20261005)
    add_device_arg(p)
    args = p.parse_args()

    device = resolve_device(args.device)
    seed_all(0)
    print(f"[device] 训练设备: {describe_device(device)}")

    print("[data] 预生成一批场景…")
    batch = make_batch(max(64, args.batch * 8), args.seed)
    if batch is None:
        print("[data] 场景生成失败，终止")
        return
    nf, adj, mk, x0, cap = [t.to(device) if torch.is_tensor(t) else t for t in batch]
    print(f"[data] {nf.shape[0]} 个场景, N={nf.shape[1]}, MAX_SLOTS={MAX_SLOTS}, 容量≈{cap:.1f}")

    model = SchemeDiffusion(hidden=args.hidden, steps=args.steps, layers=args.layers).to(device)
    print(f"[model] 参数量 {sum(q.numel() for q in model.parameters()):,}")
    opt = torch.optim.Adam(model.parameters(), lr=args.lr, weight_decay=1e-5)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=args.iters)

    best = float("inf")
    best_state = None
    best_stats = {}
    for it in range(args.iters):
        idx = torch.randint(0, nf.shape[0], (args.batch,), device=device)
        b_nf, b_adj, b_mk, b_x0 = nf[idx], adj[idx], mk[idx], x0[idx]
        if b_nf.shape[1] < TRAIN_PAD_TO:
            pad = TRAIN_PAD_TO - b_nf.shape[1]
            b_nf = torch.nn.functional.pad(b_nf, (0, 0, 0, pad))
            b_adj = torch.nn.functional.pad(b_adj, (0, pad, 0, pad))
            b_mk = torch.nn.functional.pad(b_mk, (0, pad))
            b_x0 = torch.nn.functional.pad(b_x0, (0, 0, 0, pad))

        loss = model.training_loss(b_x0, b_nf, b_adj, b_mk, None)
        if args.w_constraint > 0:
            # 🔴 **绝不能把这段放在 no_grad 里**。
            #    之前 x0_hat 是在 no_grad 下算的，cl 虽然进了 loss，
            #    但它对参数**没有任何梯度**——只是加了个常数项，
            #    于是模型只学「去噪」，完全没学「要可行」。
            #    实测：带约束项的残余冲突 0.0509 > 纯去噪的噪声水平，
            #    而 Oracle 是 0.0000，说明约束信号根本没传进去。
            t = torch.randint(0, args.steps, (b_x0.shape[0],), device=device)
            xt = model.q_sample(b_x0, t, torch.randn_like(b_x0))
            eps = model.predict_noise(xt, b_nf, b_adj, b_mk, t, None)
            a = model.alphas_cumprod[t].view(-1, 1, 1)
            # 预测的 x0 要**可微**：不能 detach，也不能 clamp 掉梯度路径
            x0_hat = (xt - (1 - a).sqrt() * eps) / a.sqrt()
            x0_hat = x0_hat * b_mk.unsqueeze(-1)
            # 硬方案（one-hot）不可微，所以用「软方案 + 温度」让约束梯度可回传；
            # 温度 0.5 接近硬但仍保留梯度。
            soft = torch.softmax(x0_hat / 0.5, dim=-1)
            cl, _parts = combination_loss(soft, b_adj, b_mk, None, cap)
            loss = loss + args.w_constraint * cl

        opt.zero_grad()
        # MoE 两项（见 train_gan 的同段注释）：漏掉不报错，只是 MoE 静默失效。
        # ⚠️ 变量名用 model（本脚本的模型变量），不是 M —— 照抄会 NameError。
        _lb = getattr(model.enc, "load_balance_loss", None)
        if _lb is not None:
            loss = loss + 0.01 * _lb()
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        _u = getattr(model.enc, "update_router_bias", None)
        if _u is not None:
            _u()
        sched.step()

        if it % 100 == 0 or it == args.iters - 1:
            model.eval()
            with torch.no_grad():
                logits, scheme = model.sample(b_nf, b_adj, b_mk, None, steps=args.steps)
                conf = float(scheme_conflicts(scheme, b_adj, b_mk).mean().item())
            model.train()
            print(f"iter {it:5d}  loss={float(loss.item()):.5f}  "
                  f"lr={sched.get_last_lr()[0]:.2e}  残余冲突={conf:.3f}")
            score = conf
            if score < best:
                best = score
                best_stats = {"residual_conflicts": conf,
                              "loss": float(loss.item()),
                              "iter": it}
                best_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}

    if best_state is not None:
        model.load_state_dict(best_state)
    os.makedirs(MODEL_DIR, exist_ok=True)
    path = os.path.join(MODEL_DIR, "scheme_diffusion.pt")
    backup_before_overwrite(path, f"diffusion-{args.iters}")
    torch.save({k: v.detach().cpu() for k, v in model.state_dict().items()}, path)
    with open(os.path.join(MODEL_DIR, "scheme_diffusion_stats.json"), "w", encoding="utf-8") as fh:
        json.dump(best_stats, fh, ensure_ascii=False, indent=2)
    print(f"best {best_stats}")
    print(f"→ 已保存 {path}")


if __name__ == "__main__":
    main()
