"""教师（行政）规避独立模型。

## 为什么教师侧也要独立模型

原来「教师规避」是**纯规则**：查 `AdminTimeProtection` 表 → 教师→班级→运动员→项目→保护区间。
规则能保证「不出错」，但表达不了**多因素该怎么权衡**：

    某教师既是高三班主任、又连着两天有监考、所带学生还要参加 3 个项目 ——
    这三个约束撞在一起时，先保护哪个？规则只能给出固定优先级，模型可以学出取舍。

而且它与裁判编排是同一类问题（**人 × 时段 × 约束的分配**），所以复用同一套 GNN 骨架，
只是特征与边的语义不同 —— 这也是「独立模型 + 统一架构」的合理形态。

## 10 维特征（顺序即契约）

| 维度 | 含义 |
|---|---|
| 0 | 任教班级数（归一） |
| 1 | 与本批项目的关联度（所带学生参加本批项目的比例） |
| 2 | 是否班主任 |
| 3 | 已被占课时比例 |
| 4 | 已有保护时段数（归一） |
| 5 | 行政职务权重 |
| 6 | 历史冲突次数（归一） |
| 7 | 本时段本班是否有比赛 |
| 8 | 教师冗余度（同科目可替班教师数归一） |
| 9 | 时段紧度 |

## 4 类边

0 同班级 / 1 同教研组 / 2 同保护时段 / 3 同行政层级

## 自监督标签

`关联度 + 已占课时 + 班主任 + 行政权重` 的加权（越大越该避让），
与裁判模型同一套思路：**让模型学「多因素加权」，而不是照抄一个固定优先级**。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import dataclass, field
from typing import Dict, List, Tuple

import numpy as np
import torch
import torch.nn as nn

if __package__ in (None, ""):
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from sports_ai.budget import report_budget
from sports_ai.device import add_device_arg, backup_before_overwrite, describe_device, resolve_device
# 复用裁判模型的 GNN 骨架：两者都是「人 × 时段 × 约束」的分配问题，
# 骨架完全同构，差别只在特征与边的语义。RefereeGnn 的 node_feat 已参数化，
# 传 10 即可（裁判用 12）。这也是「独立模型 + 统一架构」的合理形态。
from sports_ai.referee_advisor import RefereeGnn as TeacherGnn

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL_DIR = os.path.join(ROOT, "models")

N_TCH_FEAT = 10
N_TYPES = 4
MAX_NODES = 256

BASE_HIDDEN = 160
BASE_DEPTH_UNITS = 6
BASE_EPOCHS = 40

FEAT_NAMES = ["任教班级数", "项目关联度", "是否班主任", "已占课时", "保护时段数",
              "行政权重", "历史冲突", "本班有比赛", "教师冗余度", "时段紧度"]


@dataclass
class TeacherScenario:
    feat: np.ndarray
    edges: List[Tuple[int, int, int]] = field(default_factory=list)
    n_classes: int = 0


def generate_teacher_scenario(seed: int, n_tch: int = 0) -> TeacherScenario:
    rng = np.random.default_rng(seed)
    n = n_tch or int(rng.integers(12, 40))
    n_classes = int(rng.integers(6, 20))

    teach_class = rng.integers(0, n_classes, size=(n, 3))       # 每人最多带 3 个班
    group = rng.integers(0, 6, size=n)                          # 教研组
    is_master = (rng.random(n) < 0.35).astype(np.float32)       # 班主任
    admin = (rng.random(n) < 0.15).astype(np.float32) * rng.random(n)   # 行政职务权重
    busy = rng.random(n).astype(np.float32)                      # 已占课时
    protected = rng.integers(0, 4, size=n).astype(np.float32)
    hist = rng.integers(0, 5, size=n).astype(np.float32)

    batch_classes = set(int(c) for c in rng.choice(n_classes, size=max(2, n_classes // 2), replace=False))
    link = np.array([len(set(teach_class[i]) & batch_classes) / max(1, len(batch_classes))
                     for i in range(n)], dtype=np.float32)

    feat = np.zeros((n, N_TCH_FEAT), dtype=np.float32)
    feat[:, 0] = np.clip(teach_class.shape[1] / 3.0, 0, 1)
    feat[:, 1] = link
    feat[:, 2] = is_master
    feat[:, 3] = busy
    feat[:, 4] = np.clip(protected / 3.0, 0, 1)
    feat[:, 5] = np.clip(admin, 0, 1)
    feat[:, 6] = np.clip(hist / 4.0, 0, 1)
    feat[:, 7] = (link > 0).astype(np.float32)
    feat[:, 8] = rng.random(n).astype(np.float32)
    feat[:, 9] = (0.5 + 0.5 * rng.random(n)).astype(np.float32)

    edges: List[Tuple[int, int, int]] = []
    for i in range(n):
        for j in range(i + 1, n):
            if set(teach_class[i]) & set(teach_class[j]):
                edges.append((i, j, 0))
            if group[i] == group[j]:
                edges.append((i, j, 1))
            if protected[i] > 0 and protected[j] > 0:
                edges.append((i, j, 2))
            if abs(float(admin[i]) - float(admin[j])) < 0.1:
                edges.append((i, j, 3))
    return TeacherScenario(feat=feat, edges=edges, n_classes=n_classes)


def teacher_targets(scen: TeacherScenario) -> np.ndarray:
    """自监督标签：越大越该被避让。"""
    f = scen.feat
    score = (0.40 * f[:, 1]            # 项目关联度最高：所带学生要比赛，必须先保护他
             + 0.20 * f[:, 3]          # 已占课时重
             + 0.20 * f[:, 2]          # 班主任
             + 0.15 * f[:, 5]          # 行政权重
             + 0.05 * f[:, 6])         # 历史冲突
    lo, hi = float(score.min()), float(score.max())
    if hi - lo < 1e-6:
        return np.full_like(score, 0.5, dtype=np.float32)
    return ((score - lo) / (hi - lo)).astype(np.float32)


def encode(scen: TeacherScenario) -> Dict[str, np.ndarray]:
    n = scen.feat.shape[0]
    adj = np.zeros((1, N_TYPES, n, n), dtype=np.float32)
    for i, j, t in scen.edges:
        adj[0, t, i, j] = 1.0
        adj[0, t, j, i] = 1.0
    return {"node_feat": scen.feat[None, ...], "adj_by_type": adj,
            "type_mask": np.ones((1, N_TYPES), dtype=np.float32),
            "mask": np.ones((1, n), dtype=np.float32), "n": np.int64(n)}


def make_dataset(samples: int, seed: int) -> List[Dict]:
    rng = np.random.default_rng(seed)
    out = []
    for i in range(samples):
        scen = generate_teacher_scenario(int(rng.integers(0, 10 ** 7)))
        out.append({"enc": encode(scen), "y": teacher_targets(scen), "n": int(scen.feat.shape[0])})
    return out


def train(args) -> None:
    device = resolve_device(args.device)
    print(f"[device] 训练设备: {describe_device(device)}")
    data = make_dataset(args.samples, args.seed)
    if len(data) < 40:
        print("[data] 有效样本不足，终止")
        return
    rng = np.random.default_rng(args.seed)
    order = rng.permutation(len(data))
    n_tr = max(1, int(len(data) * 0.8))
    tr = [data[int(i)] for i in order[:n_tr]]
    va = [data[int(i)] for i in order[n_tr:]] or tr[:8]

    model = TeacherGnn(node_feat=N_TCH_FEAT, hidden=args.hidden, layers=args.layers).to(device)
    print(f"[model] 参数量 {sum(q.numel() for q in model.parameters()):,}")
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-5)

    def batch(items):
        n = max(int(it["n"]) for it in items)
        nf = np.zeros((len(items), n, N_TCH_FEAT), dtype=np.float32)
        adj = np.zeros((len(items), N_TYPES, n, n), dtype=np.float32)
        mk = np.zeros((len(items), n), dtype=np.float32)
        y = np.zeros((len(items), n), dtype=np.float32)
        for b, it in enumerate(items):
            k = int(it["n"])
            nf[b, :k] = it["enc"]["node_feat"][0]
            adj[b, :, :k, :k] = it["enc"]["adj_by_type"][0, :, :k, :k]
            mk[b, :k] = 1.0
            y[b, :k] = it["y"]
        t = lambda a: torch.from_numpy(a).to(device)      # noqa: E731
        return t(nf), t(adj), t(np.ones((len(items), N_TYPES), dtype=np.float32)), t(mk), t(y)

    best, best_state, best_stats = float("inf"), None, {}
    for epoch in range(args.epochs):
        model.train()
        tot, nb = 0.0, 0
        idx = np.arange(len(tr))
        rng.shuffle(idx)
        for k in range(0, len(idx) - args.batch + 1, args.batch):
            sel = [int(i) for i in idx[k:k + args.batch]]
            nf, adj, tm, mk, y = batch([tr[i] for i in sel])
            pred = model(nf, adj, tm, mk)
            loss = (((pred - y) ** 2) * mk).sum() / mk.sum().clamp(min=1.0)
            opt.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            tot += float(loss.item())
            nb += 1
        model.eval()
        with torch.no_grad():
            vs = []
            for k in range(0, len(va), args.batch):
                nf, adj, tm, mk, y = batch(va[k:k + args.batch])
                pred = model(nf, adj, tm, mk)
                vs.append(float((((pred - y) ** 2) * mk).sum() / mk.sum().clamp(min=1.0)))
        vloss = float(np.mean(vs)) if vs else float("inf")
        print(f"epoch {epoch:3d}  train={tot / max(1, nb):.5f}  val_mse={vloss:.5f}")
        if vloss < best:
            best = vloss
            best_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}
            best_stats = {"val_mse": vloss, "epoch": epoch}
            os.makedirs(MODEL_DIR, exist_ok=True)
            path = os.path.join(MODEL_DIR, "teacher_gnn.pt")
            backup_before_overwrite(path, f"teacher-{args.samples}x{args.epochs}")
            _prune(path, "teacher_gnn.pt.bak")
            tmp = path + ".tmp"
            torch.save({"state_dict": best_state,
                        "meta": {"hidden": args.hidden, "layers": args.layers,
                                 "node_feat": N_TCH_FEAT, "n_types": N_TYPES,
                                 "samples": args.samples, "epochs": args.epochs}}, tmp)
            os.replace(tmp, path)
    if best_state is not None:
        model.load_state_dict(best_state)
    with open(os.path.join(MODEL_DIR, "teacher_gnn_stats.json"), "w", encoding="utf-8") as fh:
        json.dump({**best_stats, "hidden": args.hidden, "layers": args.layers},
                  fh, ensure_ascii=False, indent=2)
    print(f"best {best_stats}")


def _prune(path: str, marker: str, keep: int = 3) -> None:
    """只留最近 keep 份备份（每轮刷新最优都会备份，不清理会堆出几十个 3MB 文件）。"""
    try:
        d = os.path.dirname(path)
        cands = sorted((f for f in os.listdir(d) if marker in f),
                       key=lambda f: os.path.getmtime(os.path.join(d, f)), reverse=True)
        for f in cands[keep:]:
            os.remove(os.path.join(d, f))
    except OSError as e:
        print(f"[guard] 清理旧备份失败（不影响训练）: {e}")


def export() -> str:
    pt = os.path.join(MODEL_DIR, "teacher_gnn.pt")
    if not os.path.isfile(pt):
        raise SystemExit(f"未找到权重 {pt}，先跑 --mode train")
    raw = torch.load(pt, map_location="cpu")
    meta = raw.get("meta", {}) if isinstance(raw, dict) else {}
    state = raw["state_dict"] if isinstance(raw, dict) and "state_dict" in raw else raw
    model = TeacherGnn(node_feat=N_TCH_FEAT, hidden=int(meta.get("hidden", 160)),
                       layers=int(meta.get("layers", 5)))
    model.load_state_dict(state)
    model.eval()
    n = 24
    out = os.path.join(MODEL_DIR, "teacher_gnn.onnx")
    torch.onnx.export(
        model,
        (torch.rand(1, n, N_TCH_FEAT),
         (torch.rand(1, N_TYPES, n, n) * (torch.rand(1, N_TYPES, n, n) > 0.9)).float(),
         torch.ones(1, N_TYPES), torch.ones(1, n)),
        out,
        input_names=["node_feat", "adj_by_type", "type_mask", "mask"],
        output_names=["aversion"],
        dynamic_axes={"node_feat": {0: "B", 1: "N"}, "adj_by_type": {0: "B", 2: "N", 3: "N"},
                      "type_mask": {0: "B"}, "mask": {0: "B", 1: "N"}, "aversion": {0: "B", 1: "N"}},
        opset_version=17, do_constant_folding=True, dynamo=False)
    print(f"导出完成: {out}  ({os.path.getsize(out) / 1024:.0f} KB)  "
          f"hidden={meta.get('hidden')} layers={meta.get('layers')}")
    return out


def main() -> None:
    ap = argparse.ArgumentParser(description="教师（行政）规避独立模型")
    ap.add_argument("--mode", choices=["train", "export", "both"], default="train")
    ap.add_argument("--samples", type=int, default=800)
    ap.add_argument("--epochs", type=int, default=BASE_EPOCHS)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--hidden", type=int, default=BASE_HIDDEN)
    ap.add_argument("--layers", type=int, default=5)
    ap.add_argument("--lr", type=float, default=2e-3)
    ap.add_argument("--seed", type=int, default=20261006)
    add_device_arg(ap)
    args = ap.parse_args()
    if args.mode in ("train", "both"):
        report_budget("teacher_gnn", hidden=args.hidden, base_hidden=BASE_HIDDEN,
                      depth_units=args.layers + 1, base_depth_units=BASE_DEPTH_UNITS,
                      base_epochs=BASE_EPOCHS, base_patience=10,
                      epochs=args.epochs, patience=10)
        train(args)
    if args.mode in ("export", "both"):
        export()


if __name__ == "__main__":
    main()
