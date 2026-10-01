"""统一导出 ONNX（固定输入输出契约），并用 onnxruntime 自检推理。

用法：
    python -m sports_ai.export_onnx

产出（models/ 下）：
    algorithm_selector.onnx   算法选择器：硬解 vs 取消
    conflict_gnn.onnx         冲突簇 GNN：节点着色优先级

契约（与 Java 端 com.sports.schedule.ai 严格对齐）：

    algorithm_selector.onnx
        input  "features"  float32 [1, 16]     ← 16 维原始实例特征（未经归一化）
        output "strategy"  float32 [1, 2]      ← logits（softmax 后 index1=取消概率）

    conflict_gnn.onnx
        input  "node_feat"  float32 [1, 256, 8]
        input  "adj"        float32 [1, 256, 256]
        input  "mask"       float32 [1, 256]
        output "priority"   float32 [1, 256]   ← 每节点着色优先级（填充节点为 0）
"""

from __future__ import annotations

import argparse
import json
import os

import numpy as np
import torch

from sports_ai.data.features import MAX_NODES, N_FEATURES, NODE_FEAT_DIM
from sports_ai.models.gnn import ConflictGnn
from sports_ai.models.selector import AlgorithmSelector, Normalize

MODEL_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "models")


def export_selector(path: str) -> None:
    state = torch.load(os.path.join(MODEL_DIR, "selector.pt"), map_location="cpu")
    with open(os.path.join(MODEL_DIR, "selector_stats.json"), encoding="utf-8") as fh:
        stats = json.load(fh)

    model = AlgorithmSelector()
    model.load_state_dict(state)
    model.eval()

    # 归一化作为第一层固化，Java 端喂原始特征
    wrapper = torch.nn.Sequential(Normalize(stats["mean"], stats["std"]), model)
    wrapper.eval()

    dummy = torch.zeros((1, N_FEATURES), dtype=torch.float32)
    torch.onnx.export(
        wrapper, dummy, path,
        input_names=["features"], output_names=["strategy"],
        opset_version=17,
    )
    print(f"[ok] 导出 {os.path.basename(path)}")


def export_gnn(path: str) -> None:
    model = ConflictGnn()
    model.load_state_dict(torch.load(os.path.join(MODEL_DIR, "gnn.pt"), map_location="cpu"))
    model.eval()

    node_feat = torch.zeros((1, MAX_NODES, NODE_FEAT_DIM), dtype=torch.float32)
    adj = torch.zeros((1, MAX_NODES, MAX_NODES), dtype=torch.float32)
    mask = torch.zeros((1, MAX_NODES), dtype=torch.float32)
    torch.onnx.export(
        model, (node_feat, adj, mask), path,
        input_names=["node_feat", "adj", "mask"], output_names=["priority"],
        opset_version=17,
    )
    print(f"[ok] 导出 {os.path.basename(path)}")


def verify(path: str, feeds: dict) -> None:
    import onnxruntime as ort
    sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    for name in sess.get_inputs():
        assert name.name in feeds, f"缺少输入 {name.name}"
    out = sess.run(None, feeds)
    print(f"[verify] {os.path.basename(path)} → 输出 shape {[o.shape for o in out]}")


def _inline_weights(path: str) -> None:
    """把 torch 2.x dynamo 导出器落盘的外置权重（.onnx.data）内联回单个 .onnx。

    新版导出器默认把大张量写到 ``<name>.onnx.data`` 外置文件，导致一个模型拆成两个文件、
    部署时容易漏拷。模型很小，直接 onnx.load + onnx.save 即可把权重内联、删除外置文件，
    使每个模型成为自包含的单文件（Java 端 onnxruntime 只需一个 .onnx）。
    """
    import onnx
    model = onnx.load(path)          # 会自动读取同名 .onnx.data
    onnx.save(model, path)           # 默认内联权重（模型远小于 2GB 阈值）
    data_file = path + ".data"
    if os.path.exists(data_file):
        os.remove(data_file)
        print(f"[ok] 内联权重并清理 {os.path.basename(data_file)}")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--verify", action="store_true", help="导出后用 onnxruntime 自检")
    args = p.parse_args()

    os.makedirs(MODEL_DIR, exist_ok=True)
    sel_path = os.path.join(MODEL_DIR, "algorithm_selector.onnx")
    gnn_path = os.path.join(MODEL_DIR, "conflict_gnn.onnx")

    export_selector(sel_path)
    export_gnn(gnn_path)
    _inline_weights(sel_path)
    _inline_weights(gnn_path)

    if args.verify:
        verify(sel_path, {"features": np.zeros((1, N_FEATURES), dtype=np.float32)})
        verify(gnn_path, {
            "node_feat": np.zeros((1, MAX_NODES, NODE_FEAT_DIM), dtype=np.float32),
            "adj": np.zeros((1, MAX_NODES, MAX_NODES), dtype=np.float32),
            "mask": np.zeros((1, MAX_NODES), dtype=np.float32),
        })
    print("完成：models/ 下已生成 .onnx，供 Java 端 onnxruntime 加载。")


if __name__ == "__main__":
    main()
