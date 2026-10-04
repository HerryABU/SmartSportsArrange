"""真 GAN 对抗训练：生成器 G 与判别器 D 的 minimax 博弈。

目标函数（文档第八节 GenCO 式）：
- 判别器：最大化 ``log D(真实可行解) + log(1 - D(G(噪声)))``；
- 生成器：最大化 ``log D(G(噪声))``（骗过判别器）+ 最小化**组合约束损失**
  （兼项冲突 / 行政时间保护禁止列表 / 槽容量）。

真样本由 ``oracle.greedy_coloring`` 产生（可行硬着色），假样本由 G 用
straight-through Gumbel-Softmax 产生（同为 one-hot 形态）——判别器只能靠「约束是否真的满足」
区分，因此这是一个**货真价实的 GAN**，而非「生成器 + 规则校验」的降级版。

用法：
    python -m sports_ai.generative.train_gan --iters 1200
产出：
    models/scheme_generator.pt / scheme_discriminator.pt
"""

from __future__ import annotations
from ..data.gnn_io import TRAIN_PAD_TO

import argparse
import os
import random

import numpy as np
import torch
import torch.nn as nn

from sports_ai.data.generator import generate_scenario
from sports_ai.data.gnn_io import encode_gnn_inputs
from sports_ai.data.features import MAX_NODES, NODE_FEAT_DIM

from .discriminator import SchemeDiscriminator
from .generator import SchemeGenerator
from .oracle import oracle_batch
from .scheme import MAX_SLOTS, combination_loss

MODEL_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "models")


def make_batch(n_samples: int, seed: int):
    """构建一批冲突图：node_feat / adj / mask。

    刻意偏向「紧张」实例（n_days 偏 1、人数偏多），让生成器也见到困难样本——
    否则它只在易实例上表现好，一遇容量紧张的实例残余冲突就高。
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
            event_drop_prob=0.3,
            track_lanes=rng.choice([1, 2, 3]),
            field_lanes=rng.choice([2, 3, 4, 5]),
            day_windows=rng.choice([(180, 150), (240, 240), (210, 210)]),
        )
        nf, adj, mk, _ = encode_gnn_inputs(s, pad_to=TRAIN_PAD_TO)
        nfs.append(nf)
        adjs.append(adj)
        mks.append(mk)
    return (
        torch.from_numpy(np.concatenate(nfs, axis=0)),
        torch.from_numpy(np.concatenate(adjs, axis=0)),
        torch.from_numpy(np.concatenate(mks, axis=0)),
    )


def make_forbid(mask: torch.Tensor, n_slots: int, prob: float, seed: int = 0) -> torch.Tensor:
    """随机生成禁止列表（行政时间保护）：每个节点以 prob 概率被禁一个槽。"""
    torch.manual_seed(seed)
    b, n = mask.shape
    forbid = torch.zeros((b, n, n_slots), dtype=torch.float32)
    pick = torch.rand((b, n)) < prob
    slot = torch.randint(0, n_slots, (b, n))
    idx = torch.arange(n_slots).view(1, 1, -1)
    forb = pick.unsqueeze(-1) & (idx == slot.unsqueeze(-1))
    forbid = forb.float() * mask.unsqueeze(-1)
    return forbid


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
    # ⚠️ 本轮真实教训：hidden 从 64 提到 160 之后仍按旧的 1200 迭代去跑，
    #    模型**没训够**，产物在「对抗生成应降低冲突」这类断言上直接失败 ——
    #    表现像「深层架构更差」，真因是预算没跟上。
    from sports_ai.budget import report_budget
    report_budget(
        "schemegenerator",
        hidden=_model_defaults(SchemeGenerator)["hidden"],
        base_hidden=64,
        depth_units=1,
        base_depth_units=1,
        base_epochs=1200,
        base_patience=1200,
        epochs=args.iters,
        patience=args.iters,
    )
    torch.manual_seed(0)
    np.random.seed(0)
    random.seed(0)
    os.makedirs(MODEL_DIR, exist_ok=True)

    node_feat, adj, mask = make_batch(args.batch, seed=args.seed)
    forbid = make_forbid(mask, MAX_SLOTS, prob=args.forbid_prob, seed=1)
    n_used = mask.sum(dim=1).mean().item()
    capacity = max(1.0, n_used / MAX_SLOTS * args.capacity_slack)

    G = SchemeGenerator()
    D = SchemeDiscriminator()
    opt_g = torch.optim.Adam(G.parameters(), lr=args.lr, betas=(0.5, 0.999))
    # 判别器学习率略低（d_lr_scale），配合标签平滑，避免 D 过快碾压 G 导致对抗梯度消失
    opt_d = torch.optim.Adam(D.parameters(), lr=args.lr * args.d_lr_scale, betas=(0.5, 0.999))
    bce = nn.BCEWithLogitsLoss()

    # 标签平滑：真=0.9、假=0.1（而非 1/0），是稳定 GAN 对抗的常用手法
    real_lbl, fake_lbl = args.label_smooth, 1.0 - args.label_smooth
    real = oracle_batch(adj, mask, forbid, MAX_SLOTS)          # 真实可行解（真样本）

    for it in range(args.iters):
        # ============ ① 训练判别器 D ============
        z = torch.randn(node_feat.shape[0], node_feat.shape[1], args.noise)
        _, fake = G(node_feat, adj, mask, z, forbid, hard=True)
        d_real = D(node_feat, adj, mask, real)
        d_fake = D(node_feat, adj, mask, fake.detach())
        loss_d = (bce(d_real, torch.full_like(d_real, real_lbl))
                  + bce(d_fake, torch.full_like(d_fake, fake_lbl)))
        opt_d.zero_grad()
        loss_d.backward()
        opt_d.step()

        # ============ ② 训练生成器 G ============
        z = torch.randn(node_feat.shape[0], node_feat.shape[1], args.noise)
        _, fake = G(node_feat, adj, mask, z, forbid, hard=True)
        d_fake = D(node_feat, adj, mask, fake)
        loss_adv = bce(d_fake, torch.ones_like(d_fake))        # 骗过判别器
        loss_comb, parts = combination_loss(fake, adj, mask, forbid, capacity)
        loss_g = loss_adv + args.lambda_comb * loss_comb
        opt_g.zero_grad()
        loss_g.backward()
        opt_g.step()

        if (it + 1) % args.log_every == 0:
            D.eval()
            with torch.no_grad():
                d_real_e = D(node_feat, adj, mask, real)
                d_fake_e = D(node_feat, adj, mask, fake)
                # 判别器准确率：真样本判真、假样本判假
                acc = 0.5 * ((d_real_e > 0).float().mean().item()
                             + (d_fake_e < 0).float().mean().item())
                # 生成方案的真实冲突（硬化后按边统计）
                hard = fake.argmax(dim=-1)
                same = (hard.unsqueeze(2) == hard.unsqueeze(1)).float()
                adj_m = adj * mask.unsqueeze(-1) * mask.unsqueeze(1)
                gen_conf = ((same * adj_m).sum() / adj_m.sum().clamp(min=1)).item()
            D.train()
            print(f"iter {it+1:5d}  lossD={loss_d.item():.3f}  lossG={loss_g.item():.3f}  "
                  f"D_acc={acc:.3f}  G_adv={loss_adv.item():.3f}  "
                  f"comb(conflict={parts['conflict']:.3f}, forbid={parts.get('forbid', 0):.3f})  "
                  f"gen_hard_conflict={gen_conf:.3f}")

    torch.save(G.state_dict(), os.path.join(MODEL_DIR, "scheme_generator.pt"))
    torch.save(D.state_dict(), os.path.join(MODEL_DIR, "scheme_discriminator.pt"))
    print("完成：已保存 models/scheme_generator.pt + scheme_discriminator.pt")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--iters", type=int, default=1200)
    p.add_argument("--batch", type=int, default=16)
    p.add_argument("--noise", type=int, default=8)
    p.add_argument("--lr", type=float, default=2e-4)
    p.add_argument("--d-lr-scale", type=float, default=0.4, help="判别器学习率相对生成器的比例")
    p.add_argument("--label-smooth", type=float, default=0.9, help="真样本标签（假=1-该值）")
    p.add_argument("--lambda-comb", type=float, default=8.0, help="组合约束损失权重")
    p.add_argument("--forbid-prob", type=float, default=0.3, help="禁止列表命中概率")
    p.add_argument("--capacity-slack", type=float, default=1.5)
    p.add_argument("--log-every", type=int, default=100)
    p.add_argument("--seed", type=int, default=20260918)
    train(p.parse_args())


if __name__ == "__main__":
    main()
