"""ONNX 导出公共工具。"""

from __future__ import annotations

import os


def inline_weights(path: str) -> None:
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
