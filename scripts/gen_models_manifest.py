r"""生成模型清单（名称 / 大小 / sha256 / 用途），供**独立分发**与校验。

## 为什么模型要独立分发

26 个 onnx 合计约 90MB，且每次重训都会变动。放进 git 的代价：
仓库体积线性膨胀、每次提交都是二进制巨块、clone 变慢，而它对代码审查毫无价值。
所以模型产物走独立分发（Release / 网盘 / 内网文件服务），仓库里只保留**清单**——
清单足够小、可 diff，且能校验分发件是否与训练产物一致。

## 用法

    python scripts/gen_models_manifest.py            # 写入 sports-ai/models/MANIFEST.json
    python scripts/gen_models_manifest.py --check    # 校验现有分发件是否与清单一致
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODELS = os.path.join(ROOT, "sports-ai", "models")
BACKEND = os.path.join(ROOT, "sports-backend", "src", "main", "resources", "models")
MANIFEST = os.path.join(MODELS, "MANIFEST.json")

# 每个模型的用途说明：分发时让使用者知道「少了哪个会退化成什么」
USAGE = {
    "super_moe.onnx": "统一编排超级模型（11 类任务）；缺失 → L4 退化为 L3（优化链）",
    "constraint_gnn.onnx": "约束图神经网络；缺失 → 约束评估走规则",
    "tournament_gnn.onnx": "球类赛制 GNN（赛制/种子/公平性）；缺失 → 球类走规则赛制",
    "conflict_gnn.onnx": "冲突簇着色 GNN；缺失 → 兼项退化为贪心着色",
    "algorithm_selector.onnx": "算法选择器（硬解/取消）；缺失 → 默认策略",
    "lane_advisor.onnx": "道次派遣优先级；缺失 → 规则分道",
    "referee_gnn.onnx": "裁判编排独立模型；缺失 → 规则派遣",
    "scheme_generator.onnx": "自对抗方案生成器；缺失 → 跳过生成式增强",
    "scheme_discriminator.onnx": "方案评判器；缺失 → 跳过择优",
    "scheme_refiner.onnx": "方案精修器；缺失 → 跳过精修",
    "scheme_diffusion.onnx": "扩散式方案生成；缺失 → 不使用",
    "forecast_direct.onnx": "趋势预测（单入单出）；缺失 → 不使用",
    "forecast_mimo.onnx": "趋势预测（多入多出）；缺失 → 不使用",
}


def sha256_of(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def build() -> dict:
    entries = {}
    for name in sorted(os.listdir(MODELS)):
        if not name.endswith(".onnx"):
            continue
        path = os.path.join(MODELS, name)
        entries[name] = {
            "bytes": os.path.getsize(path),
            "sha256": sha256_of(path),
            "usage": USAGE.get(name, "(未登记用途)"),
        }
    return {
        "schema": "sports-ai/models-manifest@1",
        "note": "模型不入 git，按本清单独立分发；重训后请重新生成本清单。",
        "count": len(entries),
        "totalBytes": sum(e["bytes"] for e in entries.values()),
        "models": entries,
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="校验分发件是否与清单一致")
    args = ap.parse_args()

    if args.check:
        if not os.path.isfile(MANIFEST):
            raise SystemExit(f"清单不存在：{MANIFEST}（先不带 --check 跑一次生成）")
        with open(MANIFEST, encoding="utf-8") as fh:
            want = json.load(fh)["models"]
        bad = []
        for name, meta in want.items():
            path = os.path.join(MODELS, name)
            if not os.path.isfile(path):
                bad.append(f"{name}: 缺失")
                continue
            got = sha256_of(path)
            if got != meta["sha256"]:
                bad.append(f"{name}: sha256 不符（期望 {meta['sha256'][:12]}… 实际 {got[:12]}…）")
        if bad:
            print("[check] 分发件与清单不一致：")
            for b in bad:
                print("  -", b)
            sys.exit(1)
        print(f"[check] 通过：{len(want)} 个模型全部与清单一致")
        return

    data = build()
    with open(MANIFEST, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=2)
    print(f"[manifest] 已写入 {MANIFEST}")
    print(f"[manifest] {data['count']} 个模型，合计 {data['totalBytes'] / 1024 / 1024:.1f} MB")
    print(f"[manifest] 后端资源目录：{BACKEND}")
    print("           同步命令（构建前）：cd sports-ai && python -m ... 见 docs/MODELS.md")


if __name__ == "__main__":
    main()
