"""训练对抗精修器（Refiner），把推理时自对抗精修蒸馏成一次前向。

用法：
    python -m sports_ai.generative.train_refiner --iters 2000

产出：
    models/scheme_refiner.pt
"""

from __future__ import annotations

import argparse
import os
import random

import numpy as np
import torch
import torch.nn as nn

from sports_ai.data.features import MAX_NODES
from sports_ai.generative.discriminator import SchemeDiscriminator
from sports_ai.generative.generator import SchemeGenerator
from sports_ai.generative.refiner import SchemeRefiner
from sports_ai.generative.scheme import MAX_SLOTS, combination_loss
from sports_ai.generative.train_gan import make_batch, make_forbid

MODEL_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "models")


def _onehot(logits):
    idx = logits.argmax(dim=-1, keepdim=True)
    return torch.zeros_like(logits).scatter_(-1, idx, 1.0)


def train(args):
    torch.manual_seed(0); np.random.seed(0); random.seed(0)
    os.makedirs(MODEL_DIR, exist_ok=True)

    G = SchemeGenerator()
    G.load_state_dict(torch.load(os.path.join(MODEL_DIR, "scheme_generator.pt"), map_location="cpu"))
    D = SchemeDiscriminator()
    D.load_state_dict(torch.load(os.path.join(MODEL_DIR, "scheme_discriminator.pt"), map_location="cpu"))
    for p in G.parameters():
        p.requires_grad_(False)
    for p in D.parameters():
        p.requires_grad_(False)
    G.eval(); D.eval()

    R = SchemeRefiner()
    opt = torch.optim.Adam(R.parameters(), lr=3e-4, weight_decay=1e-4)

    node_feat, adj, mask = make_batch(args.batch, seed=args.seed)
    forbid = make_forbid(mask, MAX_SLOTS, prob=args.forbid_prob, seed=1)
    cap = max(1.0, float(mask.sum(1).mean()) / MAX_SLOTS * args.capacity_slack)

    def hard_conflict(logits):
        hard = logits.argmax(-1)
        same = (hard.unsqueeze(2) == hard.unsqueeze(1)).float()
        am = adj * mask.unsqueeze(-1) * mask.unsqueeze(1)
        return ((same * am).sum() / am.sum().clamp(min=1)).item()

    for it in range(args.iters):
        z = torch.randn(node_feat.shape[0], MAX_NODES, args.noise)
        with torch.no_grad():
            init = G.logits_of(node_feat, adj, mask, z, forbid)      # G 的初始方案
        logits = R(node_feat, adj, mask, init, forbid)               # 精修
        probs = torch.softmax(logits, dim=-1)
        loss_comb, parts = combination_loss(probs, adj, mask, forbid, cap)
        d_score = D(node_feat, adj, mask, _onehot(logits)).mean()
        loss = args.lambda_comb * loss_comb - args.adv_weight * d_score   # 约束↓ + 骗 D↑
        opt.zero_grad()
        loss.backward()
        opt.step()

        if (it + 1) % args.log_every == 0:
            with torch.no_grad():
                c0 = hard_conflict(init)
                c1 = hard_conflict(logits)
            print(f"iter {it+1:5d}  loss={loss.item():.3f}  "
                  f"conflict: 初始 {c0:.4f} → 精修 {c1:.4f}  (↓{100*(1-c1/max(1e-9,c0)):.1f}%)  "
                  f"D={d_score.item():.3f}")

    torch.save(R.state_dict(), os.path.join(MODEL_DIR, "scheme_refiner.pt"))
    print("完成：models/scheme_refiner.pt")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--iters", type=int, default=2000)
    p.add_argument("--batch", type=int, default=24)
    p.add_argument("--noise", type=int, default=8)
    p.add_argument("--lambda-comb", type=float, default=15.0)
    p.add_argument("--adv-weight", type=float, default=0.5)
    p.add_argument("--forbid-prob", type=float, default=0.3)
    p.add_argument("--capacity-slack", type=float, default=1.5)
    p.add_argument("--log-every", type=int, default=250)
    p.add_argument("--seed", type=int, default=20260918)
    train(p.parse_args())


if __name__ == "__main__":
    main()
