"""验证「推理时自对抗」的效果：单次生成 vs 对抗精修。

用法：
    python -m sports_ai.generative.validate_refine

对比同一个实例上：
- 基线：G 一次前向（z=0）产出的方案残余冲突；
- 精修：冻结 G/D 参数，在潜在空间做「骗判别器 + 降约束」的梯度上升后的残余冲突。
"""

from __future__ import annotations

import os
import random

import numpy as np
import torch

from sports_ai.data.features import MAX_NODES, NODE_FEAT_DIM
from sports_ai.data.generator import generate_scenario
from sports_ai.data.gnn_io import encode_gnn_inputs
from sports_ai.generative.discriminator import SchemeDiscriminator
from sports_ai.generative.generator import SchemeGenerator
from sports_ai.generative.refine import AdversarialRefiner

MODEL_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "models")


def main(samples: int = 24, steps: int = 80, restarts: int = 3):
    torch.manual_seed(0)
    g = SchemeGenerator()
    g.load_state_dict(torch.load(os.path.join(MODEL_DIR, "scheme_generator.pt"), map_location="cpu"))
    d = SchemeDiscriminator()
    d.load_state_dict(torch.load(os.path.join(MODEL_DIR, "scheme_discriminator.pt"), map_location="cpu"))
    refiner = AdversarialRefiner(g, d)

    rng = random.Random(20260918)
    nfs, adjs, mks = [], [], []
    for _ in range(samples):
        s = generate_scenario(seed=rng.randint(0, 10 ** 9), n_athletes=rng.randint(180, 400),
                              n_days=1, multi_event_prob=rng.uniform(0.6, 0.95),
                              grades=["高一", "高二", "高三"],
                              track_lanes=rng.choice([1, 2, 3]),
                              field_lanes=rng.choice([2, 3, 4, 5]),
                              day_windows=rng.choice([(180, 150), (240, 240), (210, 210)]))
        nf, adj, mk, _ = encode_gnn_inputs(s)
        nfs.append(nf); adjs.append(adj); mks.append(mk)
    nf = torch.from_numpy(np.concatenate(nfs, 0))
    adj = torch.from_numpy(np.concatenate(adjs, 0))
    mask = torch.from_numpy(np.concatenate(mks, 0))

    _, info = refiner.refine(nf, adj, mask, forbid=None, steps=steps, restarts=restarts)

    b = info["conflict_before"].numpy()
    a = info["conflict_after"].numpy()
    db = info["d_before"].numpy()
    da = info["d_after"].numpy()
    print(f"样本数 {samples}（难度：1 天 / 3 年级 / 兼项密集）")
    print(f"  残余冲突  单次生成 {b.mean():.4f}  →  对抗精修 {a.mean():.4f}"
          f"   （下降 {100 * (1 - a.mean() / max(1e-9, b.mean())):.1f}%）")
    print(f"  判别器分  单次生成 {db.mean():.4f}  →  对抗精修 {da.mean():.4f}"
          f"   （越接近真实解越高）")
    print(f"  逐样本：精修更好 {int((a < b).sum())} / 持平 {int((a == b).sum())} / 更差 {int((a > b).sum())}")


if __name__ == "__main__":
    main()
