"""地狱级模型测试：用真实规模场景（3 年级 × 8 班 × 30 人 = 720 人）压测 AI 编排模型。

两种时间情形：
- **不限运动会时间**：按需求反推需要几天（本场景 6 天）；
- **限定运动会时间（2 天）**：容量紧张。

对每种情形，依次跑：
1. 场景统计（单元数 / 需求 / 供给 / 紧张度 / 冲突图密度）；
2. 算法选择器（硬解 vs 取消路径 + 置信度）；
3. 冲突簇 GNN（节点着色优先级，检查中心簇是否被优先着色）；
4. GAN 生成 → 对抗精修 → 推理时自对抗精修（残余冲突对比）；
5. 复赛冲突检查（预赛 vs 决赛同项目必须错开）。

用法：
    python -m sports_ai.hell_test
"""

from __future__ import annotations

import json
import os

import numpy as np
import torch

from sports_ai.data.features import MAX_NODES, N_FEATURES
from sports_ai.data.gnn_io import encode_gnn_inputs
from sports_ai.data.hell import EVENTS, generate_hell
from sports_ai.data.features import extract_features
from sports_ai.generative.discriminator import SchemeDiscriminator
from sports_ai.generative.generator import SchemeGenerator
from sports_ai.generative.refine import AdversarialRefiner, hard_conflict
from sports_ai.generative.refiner import SchemeRefiner
from sports_ai.models.gnn import ConflictGnn
from sports_ai.models.selector import AlgorithmSelector

MODEL_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "models")


def _load_selector():
    sel = AlgorithmSelector(n_features=N_FEATURES)
    sel.load_state_dict(torch.load(os.path.join(MODEL_DIR, "selector.pt"), map_location="cpu"))
    sel.eval()
    with open(os.path.join(MODEL_DIR, "selector_stats.json"), encoding="utf-8") as fh:
        stats = json.load(fh)
    return sel, np.asarray(stats["mean"], np.float32), np.asarray(stats["std"], np.float32)


def run_case(title: str, days):
    print("=" * 78)
    print(f"【{title}】")
    print("=" * 78)
    meet = generate_hell(seed=20260918, days=days)
    s = meet.stats
    print(f"场景：{s['athleteCount']} 名运动员 / {s['classCount']} 个班 / {s['eventCount']} 个项目"
          f" → {s['unitCount']} 个单元（预赛 {s['prelimUnits']} + 决赛 {s['finalUnits']}，共 {s['heatTotal']} 组次）")
    print(f"需求 {s['demandMinutes']} 分钟；每天供给 {s['dailyCapacity']} 分钟；"
          f"反推需要 {s['estimatedDays']} 天；本次使用 {s['usedDays']} 天；紧张度 {s['tension']}")
    f = extract_features(meet.scenario)
    print(f"特征：单元 {f[0]:.0f} 冲突边 {f[6]:.0f} 密度 {f[7]:.4f} 连通分量 {f[8]:.0f} "
          f"最大度 {f[9]:.0f} 兼项占比 {f[4]:.2f} 平均单元时长 {f[13]:.0f}min")

    # ---- ① 选择器 ----
    sel, mean, std = _load_selector()
    with torch.no_grad():
        logits = sel(torch.from_numpy(((np.asarray(f, np.float32) - mean) / std)[None, :]))
        p = torch.softmax(logits, dim=-1)[0]
    strategy = "取消路径" if p[1] >= p[0] else "硬解"
    print(f"① 选择器：{strategy}（硬解 {p[0]:.3f} / 取消 {p[1]:.3f}）")

    # ---- ② GNN 优先级 ----
    nf, adj, mask, _ = encode_gnn_inputs(meet.scenario)
    nf_t, adj_t, mask_t = map(torch.from_numpy, (nf, adj, mask))
    gnn = ConflictGnn()
    gnn.load_state_dict(torch.load(os.path.join(MODEL_DIR, "gnn.pt"), map_location="cpu"))
    gnn.eval()
    with torch.no_grad():
        prio = gnn(nf_t, adj_t, mask_t)[0]
    n = int(mask[0].sum())
    top = torch.topk(prio[:n], k=min(3, n)).indices.tolist()
    top_names = [meet.scenario.units[i].key for i in top]
    print(f"② GNN 最高优先级单元（应为中心冲突簇）：{top_names}")

    # ---- ③ GAN / 精修 / 推理时自对抗 ----
    G = SchemeGenerator(); G.load_state_dict(torch.load(os.path.join(MODEL_DIR, "scheme_generator.pt"), map_location="cpu")); G.eval()
    D = SchemeDiscriminator(); D.load_state_dict(torch.load(os.path.join(MODEL_DIR, "scheme_discriminator.pt"), map_location="cpu")); D.eval()
    R = SchemeRefiner(); R.load_state_dict(torch.load(os.path.join(MODEL_DIR, "scheme_refiner.pt"), map_location="cpu")); R.eval()

    z = torch.zeros(1, MAX_NODES, 8)
    with torch.no_grad():
        init = G.logits_of(nf_t, adj_t, mask_t, z, None)
        refined = R(nf_t, adj_t, mask_t, init, None)
        c_single = hard_conflict(init, adj_t, mask_t).item()
        c_refined = hard_conflict(refined, adj_t, mask_t).item()

    refiner = AdversarialRefiner(G, D)
    _, info = refiner.refine(nf_t, adj_t, mask_t, forbid=None, steps=80, restarts=3)
    c_adv = float(info["conflict_after"][0])
    print(f"③ GAN 残余冲突：单次生成 {c_single:.4f} → 精修器 {c_refined:.4f}（↓{100*(1-c_refined/max(1e-9,c_single)):.1f}%）"
          f" → 推理时自对抗精修 {c_adv:.4f}（↓{100*(1-c_adv/max(1e-9,c_single)):.1f}%）")

    # ---- ④ 复赛冲突检查 ----
    finals = [u for u in meet.scenario.units if "决赛" in u.event_name]
    prelims = {u.event_id: u for u in meet.scenario.units if "预赛" in u.event_name}
    shared = 0
    checked = 0
    for fu in finals:
        pu = prelims.get(fu.event_id)
        if pu and pu.grade == fu.grade:
            checked += 1
            if set(fu.athletes) & set(pu.athletes):
                shared += 1
    print(f"④ 复赛约束：{checked} 组「预赛→决赛」同项目同年级，其中 {shared} 组共享运动员"
          f"（须排到不同时间槽，否则运动员赶不上）")
    print()


def main():
    run_case("情形 A：不限运动会时间（按需求反推天数）", None)
    run_case("情形 B：限定运动会时间 = 2 天（容量紧张）", 2)


if __name__ == "__main__":
    main()
