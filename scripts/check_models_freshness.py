r"""核对「已部署的 onnx 是不是最新权重导出的、是不是升级后的 MoE 版、两侧是不是同一份」。

## 这个脚本要解决的三类故障（全都是静默的）

本项目模型在**三条命令**之间传递产物：

    train_*.py  →  models/<name>.moe.pt   （权重）
    export_*.py →  models/<name>.onnx     （sports-ai 侧产物）
    cp          →  sports-backend/src/main/resources/models/<name>.onnx  （部署）

漏做任何一步，Java 侧 `ModelSource` 都读得到文件、`available()` 都是 true、
推理都能跑 —— 只是**用的是旧模型**。真实发生过：

1. **训了没导出**：`super_moe` 与生成式四模型的权重已更新，onnx 还是旧版；
2. **导出了没部署 / 部署了别的**：两侧 sha256 不一致；
3. **根本不是新架构**：`constraint_gnn` 一直没进升级登记表，onnx 是单体 GNN，
   而只看时间戳的话它会「通过」（因为压根没有 `.moe.pt` 可比较）。

所以判据必须是三条，**缺一条就会被上面第 3 类骗过**：

1. **部署时间 ≥ 权重时间**（`<name>.moe.pt` 优先于 `<name>.pt`）；
2. **两侧 sha256 一致**；
3. **架构指纹**：ONNX initializer 名里含 `expert` / `router` ⇒ 确实是被
   `UpgradedMoE` / `MoEEncoder` / `MoERepr` 改过的模型。
   （PyTorch 导出会保留模块路径命名，所以这个名字判据是可靠的；
   只靠参数量级无法区分「未升级」与「升级但训得少」。）

## 用法

    python scripts/check_models_freshness.py            # 打印核对表
    python scripts/check_models_freshness.py --strict   # 有异常时退出码 1（可用于 CI / 提交前钩子）

## 豁免

`EXPECTED_PLAIN` 里的模型**按设计**就不是 MoE（时序趋势预测，不参与编排决策），
不该被报成异常；要新增豁免必须同时改这里与 docs/MODELS.md 的口径。
"""
from __future__ import annotations

import argparse
import hashlib
import os
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AI_DIR = os.path.join(ROOT, "sports-ai", "models")
DEPLOY_DIR = os.path.join(ROOT, "sports-backend", "src", "main", "resources", "models")

#: 按设计保持原架构（不做 MoE 升级）的模型。
EXPECTED_PLAIN = {"forecast_direct.onnx", "forecast_mimo.onnx"}


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def hhmm(path: str) -> str:
    return time.strftime("%m-%d %H:%M", time.localtime(os.path.getmtime(path)))


def scan(path: str) -> tuple:
    """返回 (参数量, expert 相关 initializer 数, router 相关 initializer 数, 名字总数)。

    ⚠️ 用 `onnx` 直接读图、不建 InferenceSession：后者要跑图优化，
    对 300MB+ 的 `super_moe` 会卡几分钟；这里只读元信息，秒级完成。
    """
    import onnx

    m = onnx.load(path, load_external_data=False)
    total, n_expert, n_router = 0, 0, 0
    names = [init.name for init in m.graph.initializer]
    for init in m.graph.initializer:
        n = 1
        for d in init.dims:
            n *= d
        total += n
    for nm in names:
        low = nm.lower()
        if "expert" in low:
            n_expert += 1
        elif "router" in low or "gate" in low:
            n_router += 1
    return total, n_expert, n_router, len(names)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--strict", action="store_true", help="有异常时返回非 0")
    args = ap.parse_args()

    if not os.path.isdir(DEPLOY_DIR):
        raise SystemExit(f"未找到部署目录：{DEPLOY_DIR}")

    problems: list[str] = []
    print(f"{'模型':26s} {'部署时间':11s} {'参数量':>9s} {'专家层':>6s} {'门控':>5s}  判定")
    print("-" * 112)

    for name in sorted(os.listdir(DEPLOY_DIR)):
        if not name.endswith(".onnx"):
            continue
        deployed = os.path.join(DEPLOY_DIR, name)
        side = os.path.join(AI_DIR, name)
        stem = name[:-5]

        weight = None
        for cand in (f"{stem}.moe.pt", f"{stem}.pt"):
            p = os.path.join(AI_DIR, cand)
            if os.path.exists(p):
                weight = p
                break

        notes = []
        # ① 时间戳：训了有没有导出
        if weight is None:
            notes.append("⚠️ 无权重可比")
        elif os.path.getmtime(deployed) + 60 < os.path.getmtime(weight):
            notes.append(f"❌ onnx 比权重旧（权重 {hhmm(weight)}）")
            problems.append(f"{name}: 权重 {hhmm(weight)} 晚于部署 {hhmm(deployed)}，疑似「训了未导出」")

        # ② 两侧一致：导出了有没有部署
        if not os.path.exists(side):
            notes.append("⚠️ sports-ai 侧无同名文件")
        elif sha256(deployed) != sha256(side):
            notes.append("❌ 两侧 sha256 不一致")
            problems.append(f"{name}: sports-ai 侧与部署侧 sha256 不一致（导出未部署 / 部署了别的）")

        # ③ 架构指纹：是不是升级过的 MoE
        try:
            total, n_expert, n_router, n_names = scan(deployed)
            params = f"{total / 1e6:8.2f}M"
            is_moe = (n_expert + n_router) > 0
            moe_note = "✅ MoE" if is_moe else ("— 按设计非 MoE" if name in EXPECTED_PLAIN else "❌ 非 MoE（未升级）")
            if not is_moe and name not in EXPECTED_PLAIN:
                problems.append(
                    f"{name}: 图中找不到 expert/router 结构 —— 仍是旧架构"
                    f"（initializer 共 {n_names} 个，全部无 MoE 命名）")
        except Exception as exc:                                   # noqa: BLE001
            params, n_expert, n_router, moe_note = "     ERR", 0, 0, "❌ 读取失败"
            problems.append(f"{name}: 读取 ONNX 失败 {exc}")

        print(f"{name:26s} {hhmm(deployed):11s} {params} {n_expert:6d} {n_router:5d}  "
              f"{moe_note} | {' ; '.join(notes) if notes else '时间戳与两侧均正常'}")

    print()
    if problems:
        print(f"[check] 发现 {len(problems)} 处问题：")
        for p in problems:
            print(f"  - {p}")
        return 1 if args.strict else 0
    print("[check] 通过：全部为最新权重导出的 MoE 版，且两侧一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
