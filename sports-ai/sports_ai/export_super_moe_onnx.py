"""导出 SuperScheduleMoE → ONNX（super_moe.onnx）。

一个模型覆盖全部九类编排，导出后 Java 侧只需一个 session。

## 契约（与 Java ``SuperScheduleEncoder`` / ``SuperMoEService`` 逐位对齐）

输入::

    node_feat   [B, N, 20]
    adj_by_type [B, E=8, N, N]
    type_mask   [B, E]
    mask        [B, N]

输出::

    priority       [B, N]        单元调度优先级（越高越先排）
    slot_logits    [B, N, K=16]  时间槽分配 logits（argmax 即槽位）
    task_probs     [B, 9]        九类任务的专家组合权重
    format_logits  [B, 4]        球类赛制（group/rr/knockout/hybrid）
    days_estimate  [B, 1]        预计所需天数

B / N 均为动态轴：同一个模型要能从 20 个单元跑到数千个。

⚠️ 导出必须显式 ``dynamo=False``（PyTorch ≥2.6 的 dynamo 导出器与 dynamic_axes 冲突）。
"""

from __future__ import annotations

import os

import torch

from sports_ai.models.super_moe import (
    MAX_SLOTS,
    N_EDGES,
    N_FORMATS,
    N_TASKS,
    NODE_FEAT_DIM,
    SuperScheduleMoE,
)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL_DIR = os.path.join(ROOT, "models")
HIDDEN = 128
LAYERS_HINT = 3
STEPS = 8


def main() -> None:
    ckpt = os.path.join(MODEL_DIR, "super_moe.pt")
    if not os.path.exists(ckpt):
        raise SystemExit(f"缺少权重 {ckpt}，请先跑 train_super_moe")
    model = SuperScheduleMoE(node_feat=NODE_FEAT_DIM, hidden=HIDDEN, steps=STEPS)
    model.load_state_dict(torch.load(ckpt, map_location="cpu"))
    model.eval()

    B, N = 2, 24
    node_feat = torch.rand(B, N, NODE_FEAT_DIM)
    adj = (torch.rand(B, N_EDGES, N, N) * (torch.rand(B, N_EDGES, N, N) > 0.85)).float()
    type_mask = torch.ones(B, N_EDGES)
    mask = torch.ones(B, N)

    out = os.path.join(MODEL_DIR, "super_moe.onnx")
    dynamic_axes = {
        "node_feat": {0: "B", 1: "N"},
        "adj_by_type": {0: "B", 2: "N", 3: "N"},
        "type_mask": {0: "B"},
        "mask": {0: "B", 1: "N"},
        "priority": {0: "B", 1: "N"},
        "slot_logits": {0: "B", 1: "N"},
        "task_probs": {0: "B"},
        "format_logits": {0: "B"},
        "days_estimate": {0: "B"},
    }
    torch.onnx.export(
        model,
        (node_feat, adj, type_mask, mask),
        out,
        input_names=["node_feat", "adj_by_type", "type_mask", "mask"],
        output_names=["priority", "slot_logits", "task_probs", "format_logits", "days_estimate"],
        dynamic_axes=dynamic_axes,
        opset_version=17,
        do_constant_folding=True,
        # PyTorch ≥2.6 默认走 dynamo 导出器，与 dynamic_axes 冲突
        dynamo=False,
    )
    print(f"导出完成: {out}  ({os.path.getsize(out) / 1024 / 1024:.2f} MB)")
    print(f"  输入 {NODE_FEAT_DIM} 维节点特征 / {N_EDGES} 类约束边 / {MAX_SLOTS} 个时间槽")
    print(f"  输出 优先级[N] + 槽位logits[N,{MAX_SLOTS}] + 任务权重[{N_TASKS}] "
          f"+ 赛制[{N_FORMATS}] + 天数[1]")


if __name__ == "__main__":
    main()
