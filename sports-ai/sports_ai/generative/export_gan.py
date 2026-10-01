"""导出 GAN（生成器 + 判别器）为 ONNX。

用法：
    python -m sports_ai.generative.export_gan --verify

产出（models/ 下）：
    scheme_generator.onnx      生成器：冲突图 + 噪声 → 时间槽着色方案（推理用）
    scheme_discriminator.onnx  判别器：方案 → 真/假（可选用作「可行性打分」）

契约（与 Java 端 com.sports.schedule.ai 对齐）：
    scheme_generator.onnx
        input  node_feat [1,256,8] / adj [1,256,256] / mask [1,256]
        input  z [1,256,8] / forbid [1,256,16]
        output logits [1,256,16] / scheme [1,256,16]（one-hot）
    scheme_discriminator.onnx
        input  node_feat [1,256,8] / adj [1,256,256] / mask [1,256] / scheme [1,256,16]
        output logits [1,1]
"""

from __future__ import annotations

import argparse
import os

import numpy as np
import torch
import torch.nn as nn

from sports_ai.data.features import MAX_NODES, NODE_FEAT_DIM
from sports_ai.onnx_utils import inline_weights
from sports_ai.generative.discriminator import SchemeDiscriminator
from sports_ai.generative.generator import SchemeGenerator
from sports_ai.generative.scheme import MAX_SLOTS

MODEL_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "models")


class GenExport(nn.Module):
    def __init__(self, g: SchemeGenerator):
        super().__init__()
        self.g = g

    def forward(self, node_feat, adj, mask, z, forbid):
        logits, scheme = self.g.generate(node_feat, adj, mask, z, forbid)
        return logits, scheme


def export_generator(path: str) -> None:
    g = SchemeGenerator()
    g.load_state_dict(torch.load(os.path.join(MODEL_DIR, "scheme_generator.pt"), map_location="cpu"))
    g.eval()
    m = GenExport(g).eval()
    args = (
        torch.zeros((1, MAX_NODES, NODE_FEAT_DIM), dtype=torch.float32),
        torch.zeros((1, MAX_NODES, MAX_NODES), dtype=torch.float32),
        torch.zeros((1, MAX_NODES), dtype=torch.float32),
        torch.zeros((1, MAX_NODES, 8), dtype=torch.float32),
        torch.zeros((1, MAX_NODES, MAX_SLOTS), dtype=torch.float32),
    )
    torch.onnx.export(
        m, args, path,
        input_names=["node_feat", "adj", "mask", "z", "forbid"],
        output_names=["logits", "scheme"], opset_version=17,
    )
    print(f"[ok] 导出 {os.path.basename(path)}")


def export_discriminator(path: str) -> None:
    d = SchemeDiscriminator()
    d.load_state_dict(torch.load(os.path.join(MODEL_DIR, "scheme_discriminator.pt"), map_location="cpu"))
    d.eval()
    args = (
        torch.zeros((1, MAX_NODES, NODE_FEAT_DIM), dtype=torch.float32),
        torch.zeros((1, MAX_NODES, MAX_NODES), dtype=torch.float32),
        torch.zeros((1, MAX_NODES), dtype=torch.float32),
        torch.zeros((1, MAX_NODES, MAX_SLOTS), dtype=torch.float32),
    )
    torch.onnx.export(
        d, args, path,
        input_names=["node_feat", "adj", "mask", "scheme"],
        output_names=["logits"], opset_version=17,
    )
    print(f"[ok] 导出 {os.path.basename(path)}")


def export_refiner(path: str) -> None:
    from sports_ai.generative.refiner import SchemeRefiner
    r = SchemeRefiner()
    r.load_state_dict(torch.load(os.path.join(MODEL_DIR, "scheme_refiner.pt"), map_location="cpu"))
    r.eval()
    args = (
        torch.zeros((1, MAX_NODES, NODE_FEAT_DIM), dtype=torch.float32),
        torch.zeros((1, MAX_NODES, MAX_NODES), dtype=torch.float32),
        torch.zeros((1, MAX_NODES), dtype=torch.float32),
        torch.zeros((1, MAX_NODES, MAX_SLOTS), dtype=torch.float32),
        torch.zeros((1, MAX_NODES, MAX_SLOTS), dtype=torch.float32),
    )
    torch.onnx.export(
        r, args, path,
        input_names=["node_feat", "adj", "mask", "init_logits", "forbid"],
        output_names=["logits"], opset_version=17,
    )
    print(f"[ok] 导出 {os.path.basename(path)}")


def verify(path: str, feeds: dict) -> None:
    import onnxruntime as ort
    sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    for i in sess.get_inputs():
        assert i.name in feeds, f"缺少输入 {i.name}"
    out = sess.run(None, feeds)
    print(f"[verify] {os.path.basename(path)} → 输出 shape {[o.shape for o in out]}")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--verify", action="store_true")
    args = p.parse_args()

    gen_path = os.path.join(MODEL_DIR, "scheme_generator.onnx")
    dis_path = os.path.join(MODEL_DIR, "scheme_discriminator.onnx")
    ref_path = os.path.join(MODEL_DIR, "scheme_refiner.onnx")
    export_generator(gen_path)
    export_discriminator(dis_path)
    has_refiner = os.path.exists(os.path.join(MODEL_DIR, "scheme_refiner.pt"))
    if has_refiner:
        export_refiner(ref_path)
    inline_weights(gen_path)
    inline_weights(dis_path)
    if has_refiner:
        inline_weights(ref_path)

    if args.verify:
        verify(gen_path, {
            "node_feat": np.zeros((1, MAX_NODES, NODE_FEAT_DIM), dtype=np.float32),
            "adj": np.zeros((1, MAX_NODES, MAX_NODES), dtype=np.float32),
            "mask": np.zeros((1, MAX_NODES), dtype=np.float32),
            "z": np.zeros((1, MAX_NODES, 8), dtype=np.float32),
            "forbid": np.zeros((1, MAX_NODES, MAX_SLOTS), dtype=np.float32),
        })
        verify(dis_path, {
            "node_feat": np.zeros((1, MAX_NODES, NODE_FEAT_DIM), dtype=np.float32),
            "adj": np.zeros((1, MAX_NODES, MAX_NODES), dtype=np.float32),
            "mask": np.zeros((1, MAX_NODES), dtype=np.float32),
            "scheme": np.zeros((1, MAX_NODES, MAX_SLOTS), dtype=np.float32),
        })
        if has_refiner:
            verify(ref_path, {
                "node_feat": np.zeros((1, MAX_NODES, NODE_FEAT_DIM), dtype=np.float32),
                "adj": np.zeros((1, MAX_NODES, MAX_NODES), dtype=np.float32),
                "mask": np.zeros((1, MAX_NODES), dtype=np.float32),
                "init_logits": np.zeros((1, MAX_NODES, MAX_SLOTS), dtype=np.float32),
                "forbid": np.zeros((1, MAX_NODES, MAX_SLOTS), dtype=np.float32),
            })
    print("完成：models/ 下已生成 GAN 的 .onnx。")


if __name__ == "__main__":
    main()
