"""导出 GAN（生成器 + 判别器 + 精修器）为 ONNX。

用法：
    python -m sports_ai.generative.export_gan --verify

产出（models/ 下）：
    scheme_generator.onnx      生成器：冲突图 + 噪声 → 时间槽着色方案（推理用）
    scheme_discriminator.onnx  判别器：方案 → 真/假（推理时自对抗的「批评者」）
    scheme_refiner.onnx        精修器：初始方案 → 精修方案（迭代自对抗的一次前向蒸馏）

契约（与 Java 端 com.sports.schedule.ai 对齐）：**节点数 n 为动态轴**，
`n = 单元数`；槽数 MAX_SLOTS=16 固定。

    scheme_generator.onnx
        input  node_feat [1,n,16] / adj [1,n,n] / mask [1,n]
        input  z [1,n,8] / forbid [1,n,16]
        output logits [1,n,16] / scheme [1,n,16]（one-hot）
    scheme_discriminator.onnx
        input  node_feat [1,n,16] / adj [1,n,n] / mask [1,n] / scheme [1,n,16]
        output logits [1,1]

为什么 n 用动态轴：GNN 是归纳式的，参数与节点数无关。固定成 1024 会让训练把绝大部分
算力花在 padding 上，并且给「大型赛会」留下硬上限；动态轴让同一个模型从 5 个单元到
2000 个单元都能处理。
"""

from __future__ import annotations

import argparse
import os

import numpy as np
import torch
import torch.nn as nn

from sports_ai.data.features import NODE_FEAT_DIM
from sports_ai.data.gnn_io import TRAIN_PAD_TO
from sports_ai.onnx_utils import inline_weights
from sports_ai.generative.discriminator import SchemeDiscriminator
from sports_ai.generative.generator import SchemeGenerator
from sports_ai.generative.refiner import SchemeRefiner
from sports_ai.generative.scheme import MAX_SLOTS

MODEL_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "models")

#: 导出 dummy 的节点数（轴声明为动态后可接受任意 n）
N_DUMMY = TRAIN_PAD_TO

#: 各输入的动态轴声明：第 1 维（节点数）可变，邻接再带第 2 维
DYN = {
    "node_feat": {1: "n"},
    "adj": {1: "n", 2: "n"},
    "mask": {1: "n"},
    "z": {1: "n"},
    "forbid": {1: "n"},
    "scheme": {1: "n"},
    "init_logits": {1: "n"},
    "logits": {1: "n"},
    "scheme_out": {1: "n"},
}


class GenExport(nn.Module):
    def __init__(self, g: SchemeGenerator):
        super().__init__()
        self.g = g

    def forward(self, node_feat, adj, mask, z, forbid):
        logits, scheme = self.g.generate(node_feat, adj, mask, z, forbid)
        return logits, scheme


def _zeros(*shape):
    return torch.zeros(shape, dtype=torch.float32)


def export_generator(path: str) -> None:
    g = SchemeGenerator()
    g.load_state_dict(torch.load(os.path.join(MODEL_DIR, "scheme_generator.pt"), map_location="cpu"))
    g.eval()
    m = GenExport(g).eval()
    args = (
        _zeros(1, N_DUMMY, NODE_FEAT_DIM),
        _zeros(1, N_DUMMY, N_DUMMY),
        _zeros(1, N_DUMMY),
        _zeros(1, N_DUMMY, 8),
        _zeros(1, N_DUMMY, MAX_SLOTS),
    )
    torch.onnx.export(
        m, args, path,
        input_names=["node_feat", "adj", "mask", "z", "forbid"],
        output_names=["logits", "scheme"],
        dynamic_axes={k: DYN[k] for k in ("node_feat", "adj", "mask", "z", "forbid", "logits")}
        | {"scheme": DYN["scheme_out"]},
        opset_version=17,
    )
    print(f"[ok] 导出 {os.path.basename(path)}（n 动态）")


def export_discriminator(path: str) -> None:
    d = SchemeDiscriminator()
    d.load_state_dict(torch.load(os.path.join(MODEL_DIR, "scheme_discriminator.pt"), map_location="cpu"))
    d.eval()
    args = (
        _zeros(1, N_DUMMY, NODE_FEAT_DIM),
        _zeros(1, N_DUMMY, N_DUMMY),
        _zeros(1, N_DUMMY),
        _zeros(1, N_DUMMY, MAX_SLOTS),
    )
    torch.onnx.export(
        d, args, path,
        input_names=["node_feat", "adj", "mask", "scheme"],
        output_names=["logits"],
        dynamic_axes={k: DYN[k] for k in ("node_feat", "adj", "mask", "scheme")},
        opset_version=17,
    )
    print(f"[ok] 导出 {os.path.basename(path)}（n 动态）")


def export_refiner(path: str) -> None:
    r = SchemeRefiner()
    r.load_state_dict(torch.load(os.path.join(MODEL_DIR, "scheme_refiner.pt"), map_location="cpu"))
    r.eval()
    args = (
        _zeros(1, N_DUMMY, NODE_FEAT_DIM),
        _zeros(1, N_DUMMY, N_DUMMY),
        _zeros(1, N_DUMMY),
        _zeros(1, N_DUMMY, MAX_SLOTS),
        _zeros(1, N_DUMMY, MAX_SLOTS),
    )
    torch.onnx.export(
        r, args, path,
        input_names=["node_feat", "adj", "mask", "init_logits", "forbid"],
        output_names=["logits"],
        dynamic_axes={k: DYN[k] for k in ("node_feat", "adj", "mask", "init_logits", "forbid", "logits")},
        opset_version=17,
    )
    print(f"[ok] 导出 {os.path.basename(path)}（n 动态）")


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
        # 用与导出 dummy 不同的节点数（且非 2 的幂）自检，证明动态轴真的生效
        for n in (5, 129):
            verify(gen_path, {
                "node_feat": np.zeros((1, n, NODE_FEAT_DIM), dtype=np.float32),
                "adj": np.zeros((1, n, n), dtype=np.float32),
                "mask": np.ones((1, n), dtype=np.float32),
                "z": np.zeros((1, n, 8), dtype=np.float32),
                "forbid": np.zeros((1, n, MAX_SLOTS), dtype=np.float32),
            })
            verify(dis_path, {
                "node_feat": np.zeros((1, n, NODE_FEAT_DIM), dtype=np.float32),
                "adj": np.zeros((1, n, n), dtype=np.float32),
                "mask": np.ones((1, n), dtype=np.float32),
                "scheme": np.zeros((1, n, MAX_SLOTS), dtype=np.float32),
            })
            if has_refiner:
                verify(ref_path, {
                    "node_feat": np.zeros((1, n, NODE_FEAT_DIM), dtype=np.float32),
                    "adj": np.zeros((1, n, n), dtype=np.float32),
                    "mask": np.ones((1, n), dtype=np.float32),
                    "init_logits": np.zeros((1, n, MAX_SLOTS), dtype=np.float32),
                    "forbid": np.zeros((1, n, MAX_SLOTS), dtype=np.float32),
                })
    print("完成：models/ 下已生成 GAN 的 .onnx。")


if __name__ == "__main__":
    main()
