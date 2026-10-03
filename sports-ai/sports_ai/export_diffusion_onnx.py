"""导出 SchemeDiffusion → ONNX（scheme_diffusion.onnx）。

输入/输出契约（与 Java 编排域对齐）::

    输入  node_feat [B,N,16] / adj [B,N,N] / mask [B,N] / z [B,N,16]
    输出  logits [B,N,16]        ← 各时间槽的 logits（argmax 即槽位）

N 走动态轴（推理时单元数随规模变化），B 固定 1。
去噪步数在导出时**烧成常量循环**（时间步是嵌入不是结构），
用 ``--steps`` 控制；步数越多越慢越准，与训练步数解耦。
"""

from __future__ import annotations

import argparse
import os

import torch

from sports_ai.data.features import NODE_FEAT_DIM
from sports_ai.generative.diffusion import SchemeDiffusion
from sports_ai.generative.scheme import MAX_SLOTS

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL_DIR = os.path.join(ROOT, "models")
HIDDEN = 128
LAYERS = 4
TRAIN_STEPS = 8


class ExportWrapper(torch.nn.Module):
    """把去噪循环包成可导出的模块（ONNX 导出需要单一 forward）。"""

    def __init__(self, model: SchemeDiffusion, steps: int):
        super().__init__()
        self.model = model
        self.steps = steps

    def forward(self, node_feat, adj, mask, z):
        return self.model.logits_of(node_feat, adj, mask, z, None, self.steps)


def main() -> None:
    ap = argparse.ArgumentParser(description="导出 SchemeDiffusion → ONNX")
    ap.add_argument("--steps", type=int, default=8, help="去噪步数（烧进图）")
    args = ap.parse_args()

    ckpt = os.path.join(MODEL_DIR, "scheme_diffusion.pt")
    if not os.path.exists(ckpt):
        raise SystemExit(f"缺少权重 {ckpt}，请先跑 train_diffusion")
    model = SchemeDiffusion(hidden=HIDDEN, steps=TRAIN_STEPS, layers=LAYERS)
    model.load_state_dict(torch.load(ckpt, map_location="cpu"))
    model.eval()

    wrapper = ExportWrapper(model, args.steps).eval()
    n = 24
    node_feat = torch.rand(1, n, NODE_FEAT_DIM)
    adj = (torch.rand(1, n, n) * (torch.rand(1, n, n) > 0.85)).float()
    mask = torch.ones(1, n)
    z = torch.zeros(1, n, MAX_SLOTS)

    out = os.path.join(MODEL_DIR, "scheme_diffusion.onnx")
    torch.onnx.export(
        wrapper,
        (node_feat, adj, mask, z),
        out,
        input_names=["node_feat", "adj", "mask", "z"],
        output_names=["logits"],
        dynamic_axes={"node_feat": {1: "N"}, "adj": {1: "N", 2: "N"},
                      "mask": {1: "N"}, "z": {1: "N"}, "logits": {1: "N"}},
        opset_version=17,
        do_constant_folding=True,
        # PyTorch ≥2.6 默认的 dynamo 导出器与 dynamic_axes 冲突
        dynamo=False,
    )
    print(f"导出完成: {out}  ({os.path.getsize(out) / 1024 / 1024:.2f} MB, steps={args.steps})")


if __name__ == "__main__":
    main()
