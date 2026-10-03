"""导出球类赛制模型 TournamentGnn → ONNX（tournament_gnn.onnx）。

输入/输出契约（与 Java ``TournamentGnnEncoder`` 逐位对齐）::

    输入  node_feat [B,N,14] / adj_by_type [B,4,N,N] / type_mask [B,4] / mask [B,N]
    输出  format_logits [B,3] / seed_scores [B,N] / fairness_cost [B,N]

B / N 均为动态轴——球类规模从 4 队到 64 队都要能跑。
"""

from __future__ import annotations

import os

import torch

from sports_ai.models.tournament_gnn import N_TYPES, NODE_FEAT_DIM, TournamentGnn

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL_DIR = os.path.join(ROOT, "models")
HIDDEN = 96
LAYERS = 4


def main() -> None:
    ckpt = os.path.join(MODEL_DIR, "tournament_gnn.pt")
    if not os.path.exists(ckpt):
        raise SystemExit(f"缺少权重 {ckpt}，请先跑 train_tournament_gnn")
    model = TournamentGnn(hidden=HIDDEN, layers=LAYERS)
    model.load_state_dict(torch.load(ckpt, map_location="cpu"))
    model.eval()

    B, N = 2, 24
    node_feat = torch.rand(B, N, NODE_FEAT_DIM)
    adj = (torch.rand(B, N_TYPES, N, N) * (torch.rand(B, N_TYPES, N, N) > 0.85)).float()
    type_mask = torch.ones(B, N_TYPES)
    mask = torch.ones(B, N)

    out = os.path.join(MODEL_DIR, "tournament_gnn.onnx")
    dynamic_axes = {
        "node_feat": {0: "B", 1: "N"},
        "adj_by_type": {0: "B", 2: "N", 3: "N"},
        "type_mask": {0: "B"},
        "mask": {0: "B", 1: "N"},
        "format_logits": {0: "B"},
        "seed_scores": {0: "B", 1: "N"},
        "fairness_cost": {0: "B", 1: "N"},
    }
    torch.onnx.export(
        model,
        (node_feat, adj, type_mask, mask),
        out,
        input_names=["node_feat", "adj_by_type", "type_mask", "mask"],
        output_names=["format_logits", "seed_scores", "fairness_cost"],
        dynamic_axes=dynamic_axes,
        opset_version=17,
        do_constant_folding=True,
        # PyTorch ≥2.6 默认走 dynamo 导出器，它与 dynamic_axes 冲突
        # （报 "Found the following conflicts between user-specified ranges and
        #   inferred ranges"）。必须显式回退到传统 TorchScript 导出器。
        dynamo=False,
    )
    print(f"导出完成: {out}  ({os.path.getsize(out) / 1024 / 1024:.2f} MB)")


if __name__ == "__main__":
    main()
