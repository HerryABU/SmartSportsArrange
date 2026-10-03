"""把训练好的 ConstraintGnn 导出为 ONNX。

用法：
    python -m sports_ai.export_constraint_onnx

产出：
    models/constraint_gnn.onnx     随 jar 交付的推理模型

ONNX 输入签名（**与 Java 端 ConstraintAwareGraphEncoder 逐位对齐**）::

    node_feat    [B, N, 16]   float32
    adj_by_type  [B, T, N, N] float32   T = 6
    type_mask    [B, T]       float32
    mask         [B, N]       float32

B 与 N 都是**动态轴**——同一个模型既能跑 20 个单元的小赛会，
也能（配合分层聚簇后）跑上千节点，不需要为规模重训。
"""

from __future__ import annotations

import os

import torch

from sports_ai.data.constraint_gnn_io import N_TYPES
from sports_ai.models.constraint_gnn import ConstraintGnn

MODEL_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "models")
PT = os.path.join(MODEL_DIR, "constraint_gnn.pt")
ONNX = os.path.join(MODEL_DIR, "constraint_gnn.onnx")
STATS = os.path.join(MODEL_DIR, "constraint_gnn_stats.json")

# 导出时的示例规模（动态轴，这里只是给 trace 形状用）
N_EXAMPLE = 64


def export() -> str:
    if not os.path.isfile(PT):
        raise FileNotFoundError(f"未找到训练权重：{PT}（先跑 train_constraint_gnn）")

    cfg = {"hidden": 96, "layers": 4}
    if os.path.isfile(STATS):
        import json
        with open(STATS, encoding="utf-8") as fh:
            s = json.load(fh)
        cfg = {"hidden": int(s.get("hidden", 96)), "layers": int(s.get("layers", 4))}

    model = ConstraintGnn(node_feat=16, hidden=cfg["hidden"], layers=cfg["layers"],
                          n_types=N_TYPES, dropout=0.0)
    state = torch.load(PT, map_location="cpu")
    model.load_state_dict(state)
    model.eval()

    B, N, T = 1, N_EXAMPLE, N_TYPES
    node_feat = torch.rand(B, N, 16)
    adj_by_type = torch.rand(B, T, N, N)
    adj_by_type = (adj_by_type * (torch.rand(B, T, N, N) > 0.8)).float()
    type_mask = torch.ones(B, T)
    mask = torch.ones(B, N)

    # dynamic_axes：让 Java 侧任意规模都能直接跑
    dynamic_axes = {
        "node_feat": {0: "B", 1: "N"},
        "adj_by_type": {0: "B", 1: "T", 2: "N", 3: "N"},
        "type_mask": {0: "B", 1: "T"},
        "mask": {0: "B", 1: "N"},
        "priority": {0: "B", 1: "N"},
    }
    with torch.no_grad():
        torch.onnx.export(
            model,
            (node_feat, adj_by_type, type_mask, mask),
            ONNX,
            input_names=["node_feat", "adj_by_type", "type_mask", "mask"],
            output_names=["priority"],
            dynamic_axes=dynamic_axes,
            opset_version=17,
            do_constant_folding=True,
            # ⚠️ PyTorch ≥2.6 默认走 dynamo 导出器，它与 dynamic_axes 冲突
            #    （报 "Found the following conflicts between user-specified ranges and
            #      inferred ranges"）。强制走传统 TorchScript 导出器，行为与旧版一致。
            dynamo=False,
        )
    size = os.path.getsize(ONNX)
    print(f"已导出 {ONNX}  ({size/1024:.0f} KB)  hidden={cfg['hidden']} layers={cfg['layers']} T={T}")
    print(f"动态轴: B / N / T  —— 同一模型可跑任意规模")
    return ONNX


if __name__ == "__main__":
    export()
