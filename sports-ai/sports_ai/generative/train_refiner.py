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
from sports_ai.device import (add_device_arg, backup_before_overwrite, describe_device,
                             resolve_device, seed_all, to_device)

MODEL_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "models")


def _onehot(logits):
    idx = logits.argmax(dim=-1, keepdim=True)
    return torch.zeros_like(logits).scatter_(-1, idx, 1.0)


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
    # ---- 训练预算 ↔ 深度检查（机制性防呆，不是一次性手调）----
    # ⚠️ 本轮真实教训：hidden 从 64 提到 160 之后仍按旧的 2000 迭代去跑，
    #    模型**没训够**，产物在「对抗生成应降低冲突」这类断言上直接失败 ——
    #    表现像「深层架构更差」，真因是预算没跟上。
    from sports_ai.budget import report_budget
    report_budget(
        "schemerefiner",
        hidden=_model_defaults(SchemeRefiner)["hidden"],
        base_hidden=64,
        depth_units=1,
        base_depth_units=1,
        base_epochs=2000,
        base_patience=2000,
        epochs=args.iters,
        patience=args.iters,
    )
    device = resolve_device(getattr(args, "device", "auto"))
    seed_all(0)
    print(f"[device] 训练设备: {describe_device(device)}")
    os.makedirs(MODEL_DIR, exist_ok=True)

    G = SchemeGenerator()
    G.load_state_dict(torch.load(os.path.join(MODEL_DIR, "scheme_generator.pt"), map_location="cpu"))
    D = SchemeDiscriminator()
    D.load_state_dict(torch.load(os.path.join(MODEL_DIR, "scheme_discriminator.pt"), map_location="cpu"))
    G = G.to(device); D = D.to(device)
    for p in G.parameters():
        p.requires_grad_(False)
    for p in D.parameters():
        p.requires_grad_(False)
    G.eval(); D.eval()

    R = SchemeRefiner().to(device)
    opt = torch.optim.Adam(R.parameters(), lr=3e-4, weight_decay=1e-4)

    node_feat, adj, mask = make_batch(args.batch, seed=args.seed)
    node_feat, adj, mask = to_device((node_feat, adj, mask), device)
    forbid = to_device(make_forbid(mask.cpu(), MAX_SLOTS, prob=args.forbid_prob, seed=1), device)
    cap = max(1.0, float(mask.sum(1).mean()) / MAX_SLOTS * args.capacity_slack)

    def hard_conflict(logits):
        hard = logits.argmax(-1)
        same = (hard.unsqueeze(2) == hard.unsqueeze(1)).float()
        am = adj * mask.unsqueeze(-1) * mask.unsqueeze(1)
        return ((same * am).sum() / am.sum().clamp(min=1)).item()

    for it in range(args.iters):
        # ⚠️ 初始解必须与**推理时一致**：推理端用 z=0（确定性），早期训练却用随机 z，
        # 于是精修器学到的是「修正一个推理时根本不会出现的初始解」——实测把好解改坏
        # （冲突 0.0076 → 0.0198）。这里改用确定性初始解，并加两条保底约束。
        z = torch.zeros(node_feat.shape[0], node_feat.shape[1], args.noise, device=device)
        with torch.no_grad():
            init = G.logits_of(node_feat, adj, mask, z, forbid)      # G 的初始方案
            base_probs = torch.softmax(init, dim=-1)
            base_comb, _ = combination_loss(base_probs, adj, mask, forbid, cap)

        logits = R(node_feat, adj, mask, init, forbid)               # 精修
        probs = torch.softmax(logits, dim=-1)
        loss_comb, parts = combination_loss(probs, adj, mask, forbid, cap)
        d_score = D(node_feat, adj, mask, _onehot(logits)).mean()

        # 保底（单调性）约束：精修**不应让组合约束损失变大**——只有在变差时才产生梯度。
        # 这是「精修器不会比不精修更差」的可导代理指标。
        loss_guard = torch.relu(loss_comb - base_comb)
        # delta 正则：鼓励最小改动。初始方案已含大量正确信息，大改往往是破坏。
        delta = logits - init
        loss_reg = delta.pow(2).mean()

        loss = (args.lambda_comb * loss_comb
                - args.adv_weight * d_score
                + args.guard_weight * loss_guard
                + args.reg_weight * loss_reg)
        opt.zero_grad()
        loss.backward()
        opt.step()

        if (it + 1) % args.log_every == 0:
            with torch.no_grad():
                c0 = hard_conflict(init)
                c1 = hard_conflict(logits)
            print(f"iter {it+1:5d}  loss={loss.item():.3f}  "
                  f"conflict: 初始 {c0:.4f} → 精修 {c1:.4f}  (↓{100*(1-c1/max(1e-9,c0)):.1f}%)  "
                  f"D={d_score.item():.3f}  guard={loss_guard.item():.4f}  |Δ|={delta.abs().mean().item():.3f}")

    # 覆盖前备份 + 权重存 CPU（跨设备加载更稳）
    backup_before_overwrite(os.path.join(MODEL_DIR, "scheme_refiner.pt"), f"smoke-{args.iters}")
    torch.save({k: v.detach().cpu() for k, v in R.state_dict().items()},
               os.path.join(MODEL_DIR, "scheme_refiner.pt"))
    print("完成：models/scheme_refiner.pt")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--iters", type=int, default=2000)
    p.add_argument("--batch", type=int, default=24)
    p.add_argument("--noise", type=int, default=8)
    p.add_argument("--lambda-comb", type=float, default=15.0)
    p.add_argument("--adv-weight", type=float, default=0.5)
    p.add_argument("--guard-weight", type=float, default=8.0,
                   help="保底约束权重：惩罚「精修后比精修前更差」")
    p.add_argument("--reg-weight", type=float, default=0.05,
                   help="delta 正则权重：鼓励最小改动（初始方案已含大量正确信息）")
    p.add_argument("--forbid-prob", type=float, default=0.3)
    p.add_argument("--capacity-slack", type=float, default=1.5)
    p.add_argument("--log-every", type=int, default=250)
    p.add_argument("--seed", type=int, default=20260918)
    add_device_arg(p)
    train(p.parse_args())


if __name__ == "__main__":
    main()
