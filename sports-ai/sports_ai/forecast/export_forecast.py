"""导出多步预测模型为 ONNX（MIMO 主预测器 + Direct）。

用法：
    python -m sports_ai.forecast.export_forecast --verify

契约：
    forecast_mimo.onnx    input x [1,16,4] → output y [1,8]
    forecast_direct.onnx  input x [1,16,4] → output y [1,8]
"""

from __future__ import annotations

import argparse
import os

import numpy as np
import torch

from sports_ai.onnx_utils import inline_weights
from .dataset import H_OUT, IN_DIM, L_IN
from .model import DirectForecaster, MimoForecaster

MODEL_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "models")


def _export(factory, ckpt, path):
    m = factory()
    m.load_state_dict(torch.load(os.path.join(MODEL_DIR, ckpt), map_location="cpu"))
    m.eval()
    dummy = torch.zeros((1, L_IN, IN_DIM), dtype=torch.float32)
    torch.onnx.export(m, dummy, path, input_names=["x"], output_names=["y"], opset_version=17)
    print(f"[ok] 导出 {os.path.basename(path)}")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--verify", action="store_true")
    args = p.parse_args()

    mimo = os.path.join(MODEL_DIR, "forecast_mimo.onnx")
    direct = os.path.join(MODEL_DIR, "forecast_direct.onnx")
    _export(lambda: MimoForecaster(IN_DIM, 64, H_OUT), "forecast_mimo.pt", mimo)
    _export(lambda: DirectForecaster(IN_DIM, 64, H_OUT), "forecast_direct.pt", direct)
    inline_weights(mimo)
    inline_weights(direct)

    if args.verify:
        import onnxruntime as ort
        for path in (mimo, direct):
            sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
            out = sess.run(None, {"x": np.zeros((1, L_IN, IN_DIM), dtype=np.float32)})
            print(f"[verify] {os.path.basename(path)} → 输出 shape {[o.shape for o in out]}")
    print("完成：多步预测 .onnx 已生成。")


if __name__ == "__main__":
    main()
