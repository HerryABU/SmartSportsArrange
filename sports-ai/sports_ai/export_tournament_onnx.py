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
DEFAULT_HIDDEN = 96
DEFAULT_LAYERS = 4



def _unpack(save):
    """兼容两种存档：裸 state_dict 与 {"state_dict":..., "meta":...}。"""
    if isinstance(save, dict) and "state_dict" in save:
        return save["state_dict"], save.get("meta", {})
    return save, {}


def _infer_cfg(state) -> tuple:
    """从权重反推 hidden / layers —— 老存档没带 meta，只能这么干。

    hidden 取 blocks.0.gate.weight 的输出维；layers 取 blocks 最大下标 + 1。
    """
    hidden, layers = DEFAULT_HIDDEN, DEFAULT_LAYERS
    for key in state:
        if key.startswith("blocks."):
            layers = max(layers, int(key.split(".")[1]) + 1)
    # ⚠️ blocks.0.gate.weight 是 (n_types, hidden)，它的 shape[0] 是 4 而非 hidden，
    # 必须排除；用 jk / proj（Linear 输出/输入维）与 LayerNorm 一维权重才对。
    for key in ("jk.weight", "jk.bias", "proj.weight", "blocks.0.norm.weight"):
        tensor = state.get(key)
        if tensor is not None:
            hidden = int(tensor.shape[0])
            break
    return hidden, layers


def load_cfg() -> tuple:
    """优先 checkpoint meta，其次从裸权重推断。"""
    state, meta = _unpack(torch.load(
        os.path.join(MODEL_DIR, "tournament_gnn.pt"), map_location="cpu"))
    if meta:
        return (int(meta.get("hidden", DEFAULT_HIDDEN)),
                int(meta.get("layers", DEFAULT_LAYERS)))
    return _infer_cfg(state)


def main() -> None:
    ckpt = os.path.join(MODEL_DIR, "tournament_gnn.pt")
    if not os.path.exists(ckpt):
        raise SystemExit(f"缺少权重 {ckpt}，请先跑 train_tournament_gnn")
    hidden, layers = load_cfg()
    state, _meta = _unpack(torch.load(ckpt, map_location="cpu"))
    model = TournamentGnn(hidden=hidden, layers=layers)
    # ⚠️ 用 load_with_aux 而不是 load_state_dict：ckpt 里带了预测分支的 aux.* 参数，
    #    而部署契约只要主输出（三个头）—— strict=True 会报
    #    "Unexpected key(s): aux.*" 直接让导出失败（＝训了导不出）。
    from sports_ai.nn.forecast_aux import load_with_aux
    load_with_aux(model, state)
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
