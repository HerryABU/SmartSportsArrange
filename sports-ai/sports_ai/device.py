"""训练侧设备选择（CPU / CUDA / Apple MPS）。

为什么需要这个模块：训练脚本此前**完全没有 device 判断**——`model = Xxx(...)`
之后就直接 `loss.backward()`，全程只在 CPU 上跑，机器再好的 GPU 也用不上。
本模块给所有训练入口提供**同一个**设备解析口径，避免各处各写一套。

设计原则
--------
1. **无 GPU 时行为与从前完全一致**（一律 CPU），不改变任何既有训练结果；
2. ``--device auto``（默认）自动探测：CUDA → MPS → CPU；
3. 探测结果打印到 stdout，训练日志里能一眼看到到底跑在哪个设备上；
4. 显式 ``--device cuda`` 但机器没 CUDA 时**立刻报错**而不是静默回落——
   训练时静默回落会让人以为跑在 GPU 上，白等几小时。

用法::

    from sports_ai.device import resolve_device, describe_device

    device = resolve_device("auto")          # 训练函数入口调一次
    model = Foo().to(device)
    xb = xb.to(device)
    print(describe_device(device))
"""

from __future__ import annotations

import argparse
from typing import Optional

import torch

DEVICE_CHOICES = ("auto", "cpu", "cuda", "mps")


def resolve_device(requested: str = "auto") -> torch.device:
    """把 ``--device`` 参数解析成 :class:`torch.device`。

    参数
    ----
    requested:
        ``auto``（默认）/ ``cpu`` / ``cuda`` / ``mps``。

    行为
    ----
    - ``auto``：CUDA 可用则用 CUDA，否则 MPS 可用则用 MPS，否则 CPU；
    - 显式 ``cuda``/``mps`` 但不可用：**抛错**（不静默回落，避免"以为在 GPU 上"）；
    - 显式 ``cpu``：永远可用。
    """
    r = (requested or "auto").strip().lower()
    if r not in DEVICE_CHOICES:
        raise ValueError(f"未知的 --device: {requested!r}，可选：{', '.join(DEVICE_CHOICES)}")

    if r == "auto":
        if torch.cuda.is_available():
            return torch.device("cuda")
        mps = getattr(torch.backends, "mps", None)
        if mps is not None and mps.is_available():
            return torch.device("mps")
        return torch.device("cpu")

    if r == "cuda":
        if not torch.cuda.is_available():
            raise RuntimeError(
                "--device cuda 但当前环境没有可用 CUDA。"
                "请确认已安装与 PyTorch 匹配的 CUDA 版 PyTorch；"
                "若只想安静地在 CPU 上训练，请显式传 --device cpu。"
            )
        return torch.device("cuda")

    if r == "mps":
        mps = getattr(torch.backends, "mps", None)
        if mps is None or not mps.is_available():
            raise RuntimeError(
                "--device mps 但当前环境没有可用的 Apple Metal(MPS)。"
                "该选项仅适用于 macOS 12.3+ 且为 Apple Silicon 芯片。"
            )
        return torch.device("mps")

    return torch.device("cpu")


def describe_device(device: torch.device) -> str:
    """返回一行人类可读的设备描述（训练日志里直接打印）。"""
    if device.type == "cuda":
        idx = device.index or 0
        try:
            name = torch.cuda.get_device_name(idx)
        except Exception:                       # 驱动异常不应让训练崩掉
            name = "unknown"
        return f"cuda:{idx} ({name})"
    if device.type == "mps":
        return "mps (Apple Metal)"
    return "cpu"


def cuda_is_usable() -> bool:
    """CUDA 是否真的能跑推理/训练（含显存自检）。"""
    if not torch.cuda.is_available():
        return False
    try:
        torch.zeros(1, device="cuda")
    except Exception:
        return False
    return True


def add_device_arg(parser: argparse.ArgumentParser) -> argparse.ArgumentParser:
    """给训练脚本的 ArgumentParser 挂上统一的 ``--device`` 参数。

    刻意做成 ``default="auto"`` 而非 ``"cpu"``：默认就该用上机器上最快的设备，
    找不到再自然回落 CPU。
    """
    parser.add_argument(
        "--device",
        default="auto",
        choices=list(DEVICE_CHOICES),
        help="训练设备：auto(默认，CUDA→MPS→CPU) / cpu / cuda / mps",
    )
    parser.add_argument(
        "--no-amp",
        action="store_true",
        help="关闭混合精度（AMP）。默认在 CUDA 上开启 FP16 加速，CPU/MPS 上自动忽略。",
    )
    return parser


def amp_enabled(device: torch.device, no_amp: bool = False) -> bool:
    """是否启用自动混合精度。仅 CUDA 受益；CPU/MPS 恒 False。"""
    return device.type == "cuda" and not no_amp


def batch_to(batch, device: torch.device):
    """把一个 batch（tuple/list of tensor）整体搬到目标设备。"""
    if isinstance(batch, (tuple, list)):
        return type(batch)(b.to(device) if torch.is_tensor(b) else b for b in batch)
    return batch.to(device)


def to_device(data, device: torch.device):
    """递归地把嵌套结构里的 tensor 搬到目标设备（非 tensor 原样返回）。"""
    if torch.is_tensor(data):
        return data.to(device)
    if isinstance(data, (tuple, list)):
        return type(data)(to_device(x, device) for x in data)
    if isinstance(data, dict):
        return {k: to_device(v, device) for k, v in data.items()}
    return data


def seed_all(seed: int = 0) -> None:
    """统一随机种子，并让 CUDA 走确定性路径（复现训练结果）。"""
    import random

    import numpy as np

    torch.manual_seed(seed)
    np.random.seed(seed)
    random.seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)
        # cudnn 确定性：同一份数据两次训练结果一致，便于对比 GPU/CPU 差异
        try:
            torch.backends.cudnn.deterministic = True
            torch.backends.cudnn.benchmark = False
        except Exception:
            pass


def backup_before_overwrite(path, tag: str = "") -> str:
    """覆盖模型文件前先备份，返回备份路径（文件不存在时返回 None）。

    **为什么需要**：训练脚本一律把结果写回 ``models/xxx.pt``。一次为了验证流程而跑的
    小样本训练（``--samples 200 --epochs 3``）就会把**正式训练出来的权重当场覆盖**，
    而且 ``.pt`` 通常不在 git 跟踪范围内，丢了就找不回来——更糟的是
    ``selector_stats.json``（导出 ONNX 时固化归一化的 mean/std）会被换成玩具模型的统计量，
    下次导出 ONNX 就会得到一个与权重不匹配的模型。

    代价是几 MB 磁盘，换来「任何一次误跑都能回滚」。

    参数
    ----
    path:
        即将被写入的文件路径（``os.PathLike`` 或 ``str``）。
    tag:
        备份文件名里附的标记，通常传样本量/轮数以便区分，如 ``"smoke-200x3"``。
    """
    import os
    import shutil

    p = str(path)
    if not os.path.isfile(p):
        return None
    suffix = f".{tag}" if tag else ""
    bak = f"{p}.bak{suffix}"
    n = 1
    while os.path.exists(bak):
        bak = f"{p}.bak{suffix}.{n}"
        n += 1
    shutil.copy2(p, bak)
    print(f"[guard] 已备份既有模型 → {bak}")
    return bak
