"""GPU 可用性检测与安装指引。

## 本机实测结论（2026-10-03）

```
GPU:  NVIDIA Quadro P620 (4 GB)
torch: 2.14.0+cpu          ← CPU 发行版
torch/lib 下的 CUDA .so: 无
```

**硬件在，但 PyTorch 是 CPU 版**。`torch.cuda.is_available()` 返回 False
不是因为没显卡，而是 `+cpu` 发行版**不包含 CUDA 运行时**（没有
`libtorch_cuda.dll` / `cudnn*.dll`）。此时无论怎么设 `--device cuda`
都不可能用上 GPU。

## 为什么不做「自动静默装」

GPU wheel 约 2.5 GB，静默下载会：
- 在离线环境里卡住几分钟才报错；
- 覆盖当前可用的 CPU 版，装失败反而把能用的环境弄坏；
- 换版本后 onnxruntime / numpy 的兼容性需要重新验证。

所以这里只**检测 + 给命令**，装不装由你决定。

## 用法::

    python -m sports_ai.gpu_check              # 检测并打印建议
    python -m sports_ai.gpu_check --install-hint  # 附带可复制的安装命令
"""

from __future__ import annotations

import argparse
import os
import platform
import shutil
import subprocess
import sys
from typing import List, Optional


def _torch_info():
    try:
        import torch
    except ImportError:
        return None
    return torch


def _is_cpu_build(torch) -> bool:
    """判断是不是 CPU-only 发行版：看 torch/lib 下有没有 CUDA DLL/.so。"""
    lib = os.path.join(os.path.dirname(torch.__file__), "lib")
    if not os.path.isdir(lib):
        return True
    for f in os.listdir(lib):
        fl = f.lower()
        if "cud" in fl or "nvidia" in fl:
            return False
    return True


def _nvidia_gpus() -> List[str]:
    exe = shutil.which("nvidia-smi")
    if not exe:
        return []
    try:
        out = subprocess.run(
            [exe, "--query-gpu=name,memory.total,driver_version",
             "--format=csv,noheader"],
            capture_output=True, text=True, timeout=10,
        )
        return [ln.strip() for ln in out.stdout.strip().splitlines() if ln.strip()]
    except Exception:
        return []


def report() -> int:
    """打印检测结果。返回 0=GPU 可用，1=不可用，2=装了 CUDA 版但无卡。"""
    print("=" * 66)
    print("GPU 可用性检测")
    print("=" * 66)
    print(f"Python      : {platform.python_version()}  ({platform.machine()})")
    print(f"平台        : {platform.system()} {platform.release()}")

    gpus = _nvidia_gpus()
    if gpus:
        print(f"显卡        : {len(gpus)} 张")
        for g in gpus:
            print(f"              {g}")
    else:
        print("显卡        : 未检测到 NVIDIA 驱动（nvidia-smi 不可用）")

    torch = _torch_info()
    if torch is None:
        print("PyTorch     : 未安装")
        return 1
    print(f"PyTorch     : {torch.__version__}")
    print(f"  cuda.is_available() = {torch.cuda.is_available()}")
    if hasattr(torch.backends, "mps"):
        print(f"  mps.is_available()  = {torch.backends.mps.is_available()}")

    cpu_only = _is_cpu_build(torch)
    if cpu_only:
        print("发行版类型  : **CPU-only**（torch/lib 下无 CUDA 运行时）")
    else:
        print("发行版类型  : 含 CUDA 运行时")

    print("-" * 66)
    if torch.cuda.is_available():
        n = torch.cuda.device_count()
        for i in range(n):
            p = torch.cuda.get_device_properties(i)
            print(f"✅ 可用 GPU[{i}]: {p.name}  显存 {p.total_memory / 1024 ** 3:.1f} GB")
        print("\n训练时直接传：--device cuda（或 --device auto，会自动选 GPU）")
        return 0

    if cpu_only and gpus:
        print("❌ **有显卡但装的是 CPU 版 PyTorch** —— 这是最常见的「买了 GPU 却用不上」")
        print("   原因：+cpu 发行版不含 CUDA 运行时，`torch.cuda.is_available()` 恒为 False。")
        print("\n解决办法（选一个 tag 与你的驱动匹配）：")
        for tag in ("cu128", "cu126", "cu124"):
            print(f"   {install_hint(tag)}")
        print("\n装完验证：python -m sports_ai.gpu_check")
        return 1

    print("⚠️  未检测到可用 GPU。训练会走 CPU（功能完整，只是慢）。")
    return 1


def install_hint(tag: str = "cu126") -> str:
    """给出一条可复制的安装命令。"""
    py = sys.executable
    return (f'"{py}" -m pip install torch --index-url '
            f'https://download.pytorch.org/whl/{tag}')


def main() -> None:
    ap = argparse.ArgumentParser(description="GPU 可用性检测")
    ap.add_argument("--install-hint", action="store_true", help="只打印安装命令")
    args = ap.parse_args()
    if args.install_hint:
        for tag in ("cu128", "cu126", "cu124"):
            print(install_hint(tag))
        return
    code = report()
    sys.exit(code)


if __name__ == "__main__":
    main()
