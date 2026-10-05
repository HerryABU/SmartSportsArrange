r"""模型收尾一条龙：**导出 → 部署 → 刷新清单 → 核对**。

## 为什么需要它

模型产物要经过四步才真正生效，而每一步漏掉都是**静默**的：

    train          → models/<name>.moe.pt            权重
    export         → models/<name>.onnx              sports-ai 侧产物
    cp             → resources/models/<name>.onnx    部署（进 jar）
    gen_manifest   → models/MANIFEST.json            分发清单

漏导出的后果是「Java 继续用旧模型」，漏部署是「导了但没生效」，
漏刷新清单是「分发件与训练产物对不上」。手工做 16 个模型 × 4 步必然出错，
所以固化成脚本，末尾**强制跑核对工具**，不通过就非 0 退出。

## 用法

    python scripts/finalize_models.py              # 导出 + 部署 + 刷新清单 + 核对
    python scripts/finalize_models.py --only a,b   # 只处理指定模型
    python scripts/finalize_models.py --check      # 跳过导出/部署，只核对现状
    python scripts/finalize_models.py --no-train-required   # 缺权重的模型跳过而不报错

⚠️ 导出用的是 `--export-only`（只导出、不训练）。它对**结构不匹配的旧权重**
会明确失败 —— 这是有意的：那正说明该权重还没带上新增的分支参数
（例如预测分支 `aux.*`），必须重训。
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AI = os.path.join(ROOT, "sports-ai")
AI_MODELS = os.path.join(AI, "models")
DEPLOY = os.path.join(ROOT, "sports-backend", "src", "main", "resources", "models")
PYTHON = os.environ.get("SPORTS_PY", sys.executable)

#: 走统一训练器的模型（`--model X --export-only`）
REGISTRY_MODELS = ["lane_advisor", "conflict_gnn", "constraint_gnn", "referee_gnn",
                   "teacher_gnn", "algorithm_selector", "ai",
                   "forecast_direct", "forecast_mimo"]

#: 走各自导出脚本的模型 → 导出命令（模块 + 参数）
CUSTOM_EXPORTERS = {
    # ⚠️ 主 MoE 必须在这里登记：它**不在** `upgrade_train` 的登记表里
    #    （不走统一训练器），漏登记就会被默认分支整段跳过 —— 表现是
    #    「一切正常、核对也通过」，而 super_moe.onnx 其实从没被重导出。
    #    它的导出要按权重 meta 里的 `n_extra` 重建那 15 个外部专家。
    "super_moe": ("sports_ai.export_super_moe_onnx", []),
    "tournament_gnn": ("sports_ai.export_tournament_onnx", ["--verify"]),
    "heat_stagger_advisor": ("sports_ai.heat_stagger_advisor", ["--export-only", "--verify"]),
    "slot_split_advisor": ("sports_ai.slot_split_advisor", ["--export-only", "--verify"]),
    # 一个脚本导出三个（gen / dis / ref）
    "scheme_generator": ("sports_ai.generative.export_gan", []),
    "scheme_discriminator": ("sports_ai.generative.export_gan", []),
    "scheme_refiner": ("sports_ai.generative.export_gan", []),
    "scheme_diffusion": ("sports_ai.export_diffusion_onnx", []),
}


def run(args, label: str) -> bool:
    env = dict(os.environ, PYTHONPATH=".", OMP_NUM_THREADS=os.environ.get("OMP_NUM_THREADS", "3"))
    print(f"[run] {label}: {' '.join(args[:3])} …")
    p = subprocess.run(args, cwd=AI, env=env, capture_output=True, text=True, errors="replace")
    if p.returncode != 0:
        tail = (p.stderr or p.stdout or "").strip().splitlines()[-6:]
        print(f"  ❌ 失败（exit {p.returncode}）：")
        for line in tail:
            print(f"     {line}")
        return False
    print("  ✅")
    return True


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="", help="逗号分隔的模型名（默认全部）")
    ap.add_argument("--check", action="store_true", help="只核对现状")
    ap.add_argument("--no-train-required", action="store_true",
                    help="缺权重时跳过而不是失败")
    args = ap.parse_args()

    wanted = [s.strip() for s in args.only.split(",") if s.strip()] or None

    if not args.check:
        # ── ① 导出 ──
        done_exporters = set()
        for name in REGISTRY_MODELS:
            if wanted and name not in wanted:
                continue
            ckpt = os.path.join(AI_MODELS, f"{name}.moe.pt")
            if not os.path.exists(ckpt):
                msg = f"{name}: 无权重 {ckpt}"
                if args.no_train_required:
                    print(f"[skip] {msg}")
                    continue
                print(f"❌ {msg} —— 先重训")
                return 2
            run([PYTHON, "-m", "sports_ai.nn.upgrade_train", "--model", name,
                 "--export-only"], f"导出 {name}")
        for name, (mod, extra) in CUSTOM_EXPORTERS.items():
            if wanted and name not in wanted:
                continue
            if mod in done_exporters:                      # 一个脚本管多个模型，只跑一次
                continue
            src_ckpt = {"heat_stagger_advisor": "heat_stagger_advisor.pt",
                        "slot_split_advisor": "slot_split_advisor.pt",
                        "super_moe": "super_moe.pt",
                        "tournament_gnn": "tournament_gnn.pt"}.get(name)
            if src_ckpt and not os.path.exists(os.path.join(AI_MODELS, src_ckpt)):
                print(f"[skip] {name}: 无权重")
                continue
            if run([PYTHON, "-m", mod, *extra], f"导出 {name}（{mod}）"):
                done_exporters.add(mod)

        # ── ② 部署 ──
        # ⚠️ 只同步 MANIFEST 里登记的那 16 个：`ai.onnx` 是选择器 v1，
        #    Java 侧已不加载、也不在分发清单里，部署它只会让 jar 白胖 9MB。
        import json
        manifest_path = os.path.join(AI_MODELS, "MANIFEST.json")
        listed = set(json.load(open(manifest_path, encoding="utf-8"))["models"]) \
            if os.path.exists(manifest_path) else None
        n_cp = 0
        for f in sorted(os.listdir(AI_MODELS)):
            if not f.endswith(".onnx"):
                continue
            if listed is not None and f not in listed:
                print(f"[skip] 部署 {f}（不在分发清单）")
                continue
            shutil.copy2(os.path.join(AI_MODELS, f), os.path.join(DEPLOY, f))
            n_cp += 1
        print(f"[deploy] 已同步 {n_cp} 个 onnx → resources/models")

        # ── ③ 刷新清单 ──
        run([PYTHON, os.path.join(ROOT, "scripts", "gen_models_manifest.py")], "刷新 MANIFEST")

    # ── ④ 核对（无论哪条路径都跑，且以它为准）──
    print("\n[check] 模型新鲜度核对：")
    return subprocess.run([PYTHON, os.path.join(ROOT, "scripts", "check_models_freshness.py"),
                           "--strict"]).returncode


if __name__ == "__main__":
    sys.exit(main())
