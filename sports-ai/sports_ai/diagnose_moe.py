"""专家分工诊断：使用率 / 路由熵 / **专家表征两两相似度**。

## 为什么需要它

``route_entropy`` 只说明**门控分布均匀**。它高，可能是「真的分工」，
也可能是「每位专家算的东西一样、谁上都行，门控只好随机撒」——
这两种情况在 loss 与 route_entropy 上**完全一样**。要区分它们，
只能直接比「各专家的输出彼此像不像」，即**专家表征的两两余弦相似度**。

用法::

    cd sports-ai && python -m sports_ai.diagnose_moe [--ckpt models/super_moe.pt] [--seeds 3]
"""

from __future__ import annotations

import argparse
import json
import os
from typing import Dict, List

import numpy as np
import torch

from sports_ai.data.super_encode import encode_super_graph
from sports_ai.data.super_scenarios import generate_super_scenario
from sports_ai.models.super_moe import N_TASKS, N_UNIT_TASKS, SuperScheduleMoE

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TIERS = ("HELL", "REGULAR", "BLOCK", "LANE", "TEAM")


def load_model(ckpt: str):
    ck = torch.load(ckpt, map_location="cpu")
    meta = ck.get("meta", {}) if isinstance(ck, dict) and "meta" in ck else {}
    # ⚠️ 结构参数必须从权重 meta 还原：写死默认值会 shape 不匹配
    m = SuperScheduleMoE(
        hidden=int(meta.get("hidden", 192)),
        steps=int(meta.get("steps", 8)),
        expert_depth=int(meta.get("expert_depth", 2)),
        n_global=int(meta.get("n_global", 3)),
    )
    m.load_state_dict(ck["state_dict"] if isinstance(ck, dict) and "state_dict" in ck else ck)
    m.eval()
    return m, meta


def batch_of(tier: str, seed: int):
    scen = generate_super_scenario(tier, seed=seed)
    d = encode_super_graph(scen)
    if d is None:
        return None
    return (torch.from_numpy(d["node_feat"]).float(),
            torch.from_numpy(d["adj_by_type"]).float(),
            torch.from_numpy(d["type_mask"]).float(),
            torch.from_numpy(d["mask"]).float(),
            torch.from_numpy(np.asarray(d["graph_feat"], dtype=np.float32)))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", default=os.path.join(ROOT, "models", "super_moe.pt"))
    ap.add_argument("--seeds", type=int, default=2)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    model, meta = load_model(args.ckpt)
    n_experts = len(model.moe.experts)
    print(f"权重: hidden={meta.get('hidden')} steps={meta.get('steps')} "
          f"expert_depth={meta.get('expert_depth')} n_global={meta.get('n_global')} "
          f"epochs_run={meta.get('epochs_run')} satisfied={meta.get('budget_satisfied')}")
    print(f"专家数 = {n_experts}（任务专家 0..{N_UNIT_TASKS - 1}，能力专家 {N_UNIT_TASKS}..{N_TASKS - 1}）")
    print()

    per_tier: Dict[str, Dict[str, object]] = {}
    acc_mat: List[List[float]] = []
    usage_acc: Dict[str, List[float]] = {}
    for tier in TIERS:
        rows = []
        for sd in range(args.seeds):
            b = batch_of(tier, sd)
            if b is None:
                continue
            rows.append(model.model_diagnostics(*b))
        if not rows:
            continue
        mean_sim = float(np.mean([r["expert_similarity"]["mean"] for r in rows]))
        cross = [r["expert_similarity"].get("cross_mean") for r in rows]
        capw = [r["expert_similarity"].get("cap_within_mean") for r in rows]
        per_tier[tier] = {
            "usage_min": round(min(min(r["expert_usage"].values()) for r in rows), 5),
            "route_entropy": round(float(np.mean([r["route_entropy"] for r in rows])), 5),
            "sim_mean": round(mean_sim, 4),
            "sim_max": round(float(np.mean([r["expert_similarity"]["max"] for r in rows])), 4),
            "cross_mean": round(float(np.mean([c for c in cross if c is not None])), 4)
            if any(c is not None for c in cross) else None,
            "cap_within_mean": round(float(np.mean([c for c in capw if c is not None])), 4)
            if any(c is not None for c in capw) else None,
        }
        acc_mat.append(rows[0]["expert_similarity"]["matrix"])
        for i, v in rows[0]["expert_usage"].items():
            usage_acc.setdefault(i, []).append(float(v))

    print("=== 各档诊断（专家分工的三个正交指标）===")
    print(f"{'档':<9}{'使用率最低':>11}{'路由熵':>9}{'相似度均值':>11}{'相似度最高':>11}"
          f"{'跨组(任务×能力)':>16}{'能力组内':>10}")
    for t, m in per_tier.items():
        print(f"{t:<9}{m['usage_min']:>11.4f}{m['route_entropy']:>9.4f}{m['sim_mean']:>11.4f}"
              f"{m['sim_max']:>11.4f}{str(m['cross_mean']):>16}{str(m['cap_within_mean']):>10}")

    print()
    print("=== 专家使用率（跨档均值）===")
    for i in sorted(usage_acc, key=lambda k: int(k[1:])):
        v = float(np.mean(usage_acc[i]))
        bar = "#" * max(1, int(v * 120))
        print(f"  {i:<4} {v:.4f} {bar}")

    if acc_mat:
        M = np.asarray(acc_mat[0])
        print()
        print("=== 相似度矩阵（首档，行/列 = 专家下标）===")
        hdr = "      " + "".join(f"{j:>7}" for j in range(M.shape[1]))
        print(hdr)
        for i in range(M.shape[0]):
            print(f"  E{i:<3}" + "".join(f"{M[i, j]:>7.3f}" for j in range(M.shape[1])))

    sim_mean = float(np.mean([m["sim_mean"] for m in per_tier.values()])) if per_tier else 0.0
    print()
    print("=== 判读 ===")
    if sim_mean > 0.95:
        print(f"  ⚠️ 相似度均值 {sim_mean:.4f} > 0.95 → 专家**高度同质**："
              "扩专家只是在摊薄容量，没有带来分工。")
        print("     下一步应做「专家归纳偏置分化」（不同 hop 范围 / 不同聚合方式），"
              "而不是继续加专家数。")
    elif sim_mean > 0.85:
        print(f"  ⚠️ 相似度均值 {sim_mean:.4f} 偏高（0.85~0.95）→ 专家**部分分化**，"
              "但仍有明显冗余。")
    else:
        print(f"  ✅ 相似度均值 {sim_mean:.4f} < 0.85 → 专家确实在做不同的事。")
    print("  注：route_entropy 只说明门控分布均匀；能否分工要看相似度这一列。")

    if args.out:
        with open(args.out, "w", encoding="utf-8") as fh:
            json.dump({"per_tier": per_tier, "matrix": acc_mat}, fh,
                      ensure_ascii=False, indent=2)
        print(f"\n明细 -> {args.out}")


if __name__ == "__main__":
    main()
