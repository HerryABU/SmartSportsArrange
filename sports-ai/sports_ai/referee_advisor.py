"""裁判编排独立模型（用户要求：裁判编排 / 教师规避「先独立建模，再合并进主模型」）。

## 为什么是「每裁判一个优先级」而不是「直接输出分配表」

裁判编排本质是**分配问题**（把组次分给裁判）。但直接让模型吐分配表有两个问题：
① 输出维度随组次数变化，Java 端难以稳定消费；② 模型无法保证「并行组次不重用裁判」这类硬规则。

所以这里与 `lane_advisor` 对道次的做法保持一致：**模型只负责排序**（每个裁判的派遣优先级），
Java 侧按优先级贪心派遣，专长优先、保护规避、并行不重用等硬规则仍由 Java 把关。
模型输出只可能让派遣更合理，不会绕过任何安全规则。

## 4 类边（顺序即 ONNX 通道号，双端契约）

| 通道 | 含义 |
|---|---|
| 0 | 同专长竞争：两个裁判擅长同一项目，会抢同一批组次 |
| 1 | 同单位回避：裁判与该批次参赛单位相同，应回避 |
| 2 | 保护时段：裁判在该时段受保护，不应派遣 |
| 3 | 负载耦合：已派组次多的裁判之间，存在负载均衡压力 |

## 自监督标签

用 `专长匹配 + 负载均衡 + 保护规避` 的多因素加权规则算出目标优先级，
模型学的是**这套加权如何融合**，而不是某个拍脑袋的常数。

用法::

    python -m sports_ai.referee_advisor --mode train --samples 800 --epochs 40
    python -m sports_ai.referee_advisor --mode export
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

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL_DIR = os.path.join(ROOT, "models")

N_REF_FEAT = 12          # 裁判节点特征维度
N_TYPES = 4              # 边类型数
N_SPORTS = 8             # 项目大类数（专长编码用）
MAX_NODES = 256          # 裁判规模上限

# ---- 特征含义（顺序即契约，改这里必须同步 Java 侧 RefereeGnnEncoder）----
FEAT_NAMES = [
    "专长匹配度", "负载比例", "可用时段比例", "受保护", "经验等级", "同单位",
    "并行冲突风险", "历史派遣归一", "连续工作长度", "搭档协同", "时段偏好匹配", "资历归一",
]

# ---- 训练预算基线（本脚本默认结构：hidden=160 / layers=5）----
BASE_HIDDEN = 160
BASE_DEPTH_UNITS = 6     # layers=5 时的 layers+1
BASE_EPOCHS = 40


@dataclass
class RefereeScenario:
    """一个裁判派遣批次：若干裁判 + 本批要派的项目组次。"""
    ref_feat: np.ndarray          # [N, 12]
    edges: List[Tuple[int, int, int]]   # (i, j, type)
    batch_sports: List[int] = field(default_factory=list)
    batch_units: List[str] = field(default_factory=list)


def generate_referee_scenario(seed: int, n_ref: int = 0) -> RefereeScenario:
    """生成一个裁判批次场景（合成，但保证特征有分布多样性）。"""
    rng = np.random.default_rng(seed)
    n = n_ref or int(rng.integers(12, 40))
    n_sports_batch = int(rng.integers(2, min(6, N_SPORTS)))

    batch_sports = sorted(int(s) for s in rng.choice(N_SPORTS, size=n_sports_batch, replace=False))
    batch_units = [f"单位{int(u)}" for u in rng.integers(1, 9, size=int(rng.integers(3, 9)))]

    # 裁判属性
    spec = rng.integers(0, N_SPORTS, size=(n, 3))          # 每人 3 个专长
    unit = rng.integers(0, 9, size=n)
    protected = (rng.random(n) < 0.12).astype(np.float32)
    exp = rng.random(n).astype(np.float32)                  # 经验等级
    served = rng.integers(0, 7, size=n).astype(np.float32)  # 已派组次
    avail = (0.5 + 0.5 * rng.random(n)).astype(np.float32)  # 可用时段比例

    match = np.array([len(set(spec[i]) & set(batch_sports)) / max(1, len(batch_sports))
                      for i in range(n)], dtype=np.float32)
    total_units = max(1, len(batch_units))
    load = np.clip(served / max(1.0, float(n)), 0, 1).astype(np.float32)
    same_unit = np.array([1.0 if f"单位{unit[i] + 1}" in batch_units else 0.0
                          for i in range(n)], dtype=np.float32)

    feat = np.zeros((n, N_REF_FEAT), dtype=np.float32)
    feat[:, 0] = match
    feat[:, 1] = load
    feat[:, 2] = avail
    feat[:, 3] = protected
    feat[:, 4] = exp
    feat[:, 5] = same_unit
    feat[:, 6] = 1.0 - avail                              # 并行冲突风险
    feat[:, 7] = np.clip(served / 6.0, 0, 1)
    feat[:, 8] = rng.random(n).astype(np.float32)         # 连续工作长度
    feat[:, 9] = rng.random(n).astype(np.float32)         # 搭档协同
    feat[:, 10] = rng.random(n).astype(np.float32)        # 时段偏好匹配
    feat[:, 11] = exp                                      # 资历归一

    # 边
    edges: List[Tuple[int, int, int]] = []
    for i in range(n):
        for j in range(i + 1, n):
            if set(spec[i]) & set(spec[j]):
                edges.append((i, j, 0))                    # 同专长竞争
            if unit[i] == unit[j]:
                edges.append((i, j, 1))                    # 同单位
            if protected[i] > 0 and protected[j] > 0:
                edges.append((i, j, 2))                    # 同受保护
            if abs(float(served[i]) - float(served[j])) < 1.0:
                edges.append((i, j, 3))                    # 负载相近 → 均衡压力
    _ = total_units
    return RefereeScenario(ref_feat=feat, edges=edges,
                           batch_sports=batch_sports, batch_units=batch_units)


def referee_targets(scen: RefereeScenario) -> np.ndarray:
    """自监督标签：多因素加权优先级（越大越该先派）。

    权重表达现场经验：**专长匹配最重要**，其次避免受保护/同单位，再次均衡负载。
    """
    f = scen.ref_feat
    score = (0.55 * f[:, 0]                      # 专长匹配
             + 0.15 * (1.0 - f[:, 1])            # 负载轻者优先
             + 0.15 * (1.0 - f[:, 3])            # 未受保护优先
             + 0.10 * (1.0 - f[:, 5])            # 非本单位优先（回避）
             + 0.05 * f[:, 4])                   # 经验
    lo, hi = float(score.min()), float(score.max())
    if hi - lo < 1e-6:
        return np.full_like(score, 0.5, dtype=np.float32)
    return ((score - lo) / (hi - lo)).astype(np.float32)


def encode_referee_graph(scen: RefereeScenario) -> Dict[str, np.ndarray]:
    """→ node_feat[1,N,12] / adj_by_type[1,4,N,N] / type_mask[1,4] / mask[1,N]"""
    n = scen.ref_feat.shape[0]
    adj = np.zeros((1, N_TYPES, n, n), dtype=np.float32)
    for i, j, t in scen.edges:
        if 0 <= i < n and 0 <= j < n:
            adj[0, t, i, j] = 1.0
            adj[0, t, j, i] = 1.0
    mask = np.ones((1, n), dtype=np.float32)
    type_mask = np.ones((1, N_TYPES), dtype=np.float32)
    return {"node_feat": scen.ref_feat[None, ...], "adj_by_type": adj,
            "type_mask": type_mask, "mask": mask, "n": np.int64(n)}


# ---------------------------------------------------------------- 模型
class RefereeGnn(nn.Module):
    """裁判派遣 GNN：类型化邻接消息传递 + Pre-LN 残差 FFN，逐裁判打分。"""

    def __init__(self, node_feat: int = N_REF_FEAT, hidden: int = 160,
                 layers: int = 5, n_types: int = N_TYPES, dropout: float = 0.1):
        super().__init__()
        self.hidden = hidden
        self.layers = layers
        self.proj = nn.Linear(node_feat, hidden)
        self.in_norm = nn.LayerNorm(hidden)
        self.type_emb = nn.Embedding(n_types, hidden)
        self.rel = nn.ModuleList([nn.Linear(hidden, hidden) for _ in range(n_types)])
        self.blocks = nn.ModuleList()
        for _ in range(layers):
            self.blocks.append(nn.ModuleDict({
                "norm": nn.LayerNorm(hidden),
                "gate": nn.Linear(hidden, hidden),
                "ffn": nn.Sequential(nn.Linear(hidden, hidden * 2), nn.GELU(),
                                     nn.Dropout(dropout), nn.Linear(hidden * 2, hidden)),
                "ffn_norm": nn.LayerNorm(hidden),
            }))
        self.head = nn.Sequential(nn.Linear(hidden, hidden // 2), nn.GELU(),
                                  nn.Dropout(dropout), nn.Linear(hidden // 2, 1))

    def forward(self, node_feat: torch.Tensor, adj_by_type: torch.Tensor,
                type_mask: torch.Tensor, mask: torch.Tensor):
        h = torch.relu(self.in_norm(self.proj(node_feat))) * mask.unsqueeze(-1)
        for blk in self.blocks:
            hn = blk["norm"](h)
            agg = torch.zeros_like(hn)
            for t in range(len(self.rel)):
                a = adj_by_type[:, t]
                deg = a.sum(-1, keepdim=True).clamp(min=1.0)
                msg = torch.bmm(a, self.rel[t](hn)) / deg
                in_use = type_mask[:, t].view(-1, 1, 1)
                agg = agg + msg * in_use
            gate = torch.sigmoid(blk["gate"](hn))
            h = h + gate * agg
            h = h + blk["ffn"](blk["ffn_norm"](h))
            h = h * mask.unsqueeze(-1)
        return self.head(h).squeeze(-1) * mask


# ---------------------------------------------------------------- 训练
def make_dataset(samples: int, seed: int):
    out = []
    rng = np.random.default_rng(seed)
    for i in range(samples):
        scen = generate_referee_scenario(int(rng.integers(0, 10_000_000)), 0)
        enc = encode_referee_graph(scen)
        out.append({"enc": enc, "y": referee_targets(scen), "n": int(enc["n"])})
    return out


def _prune_backups(marker: str, keep: int = 3) -> None:
    """只保留最近 keep 份备份。

    ⚠️ 训练里「每刷新最优就落盘」意味着每轮都可能备份一次，不清理的话
    长训练会把磁盘塞满（实测 40 轮堆出 18 个 3MB 备份）。
    """
    try:
        cands = sorted((f for f in os.listdir(MODEL_DIR) if marker in f),
                       key=lambda f: os.path.getmtime(os.path.join(MODEL_DIR, f)),
                       reverse=True)
        for f in cands[keep:]:
            os.remove(os.path.join(MODEL_DIR, f))
    except OSError as e:
        print(f"[guard] 清理旧备份失败（不影响训练）: {e}")


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

    model = RefereeGnn(node_feat=N_REF_FEAT, hidden=args.hidden, layers=args.layers).to(device)
    print(f"[model] 参数量 {sum(q.numel() for q in model.parameters()):,}")
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-5)

    def batch(items):
        n = max(it["n"] for it in items)
        nf = np.zeros((len(items), n, N_REF_FEAT), dtype=np.float32)
        adj = np.zeros((len(items), N_TYPES, n, n), dtype=np.float32)
        tm = np.ones((len(items), N_TYPES), dtype=np.float32)
        mk = np.zeros((len(items), n), dtype=np.float32)
        y = np.zeros((len(items), n), dtype=np.float32)
        for b, it in enumerate(items):
            k = it["n"]
            nf[b, :k] = it["enc"]["node_feat"][0]
            adj[b, :, :k, :k] = it["enc"]["adj_by_type"][0, :, :k, :k]
            mk[b, :k] = 1.0
            y[b, :k] = it["y"]
        t = lambda a: torch.from_numpy(a).to(device)
        return t(nf), t(adj), t(tm), t(mk), t(y)

    best = float("inf")
    best_state = None
    best_stats: Dict[str, object] = {}
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
            vlosses = []
            for k in range(0, len(va), args.batch):
                nf, adj, tm, mk, y = batch(va[k:k + args.batch])
                pred = model(nf, adj, tm, mk)
                vlosses.append(float((((pred - y) ** 2) * mk).sum() / mk.sum().clamp(min=1.0)))
        vloss = float(np.mean(vlosses)) if vlosses else float("inf")
        print(f"epoch {epoch:3d}  train={tot / max(1, nb):.5f}  val_mse={vloss:.5f}")
        if vloss < best:
            best = vloss
            best_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}
            best_stats = {"val_mse": vloss, "epoch": epoch}
            os.makedirs(MODEL_DIR, exist_ok=True)
            path = os.path.join(MODEL_DIR, "referee_gnn.pt")
            backup_before_overwrite(path, f"referee-{args.samples}x{args.epochs}")
            # ⚠️ 每刷新一次最优就备份一次 → 40 轮就是 40 个 3MB 备份（实测堆到 18 个 / 55MB）。
            #    只留最近 3 份足够对照回退，其余清掉（与 train_super_moe 同一套做法）。
            _prune_backups("referee_gnn.pt.bak")
            tmp = path + ".tmp"
            torch.save({"state_dict": best_state,
                        "meta": {"hidden": args.hidden, "layers": args.layers,
                                 "node_feat": N_REF_FEAT, "n_types": N_TYPES,
                                 "samples": args.samples, "epochs": args.epochs}}, tmp)
            os.replace(tmp, path)
    if best_state is not None:
        model.load_state_dict(best_state)
    with open(os.path.join(MODEL_DIR, "referee_gnn_stats.json"), "w", encoding="utf-8") as fh:
        json.dump({**best_stats, "hidden": args.hidden, "layers": args.layers},
                  fh, ensure_ascii=False, indent=2)
    print(f"best {best_stats}")


def export() -> str:
    pt = os.path.join(MODEL_DIR, "referee_gnn.pt")
    if not os.path.isfile(pt):
        raise SystemExit(f"未找到权重 {pt}，先跑 --mode train")
    raw = torch.load(pt, map_location="cpu")
    meta = raw.get("meta", {}) if isinstance(raw, dict) else {}
    state = raw["state_dict"] if isinstance(raw, dict) and "state_dict" in raw else raw
    model = RefereeGnn(node_feat=N_REF_FEAT, hidden=int(meta.get("hidden", 160)),
                       layers=int(meta.get("layers", 5)))
    model.load_state_dict(state)
    model.eval()

    n = 24
    node_feat = torch.rand(1, n, N_REF_FEAT)
    adj = (torch.rand(1, N_TYPES, n, n) * (torch.rand(1, N_TYPES, n, n) > 0.9)).float()
    type_mask = torch.ones(1, N_TYPES)
    mask = torch.ones(1, n)
    out = os.path.join(MODEL_DIR, "referee_gnn.onnx")
    torch.onnx.export(
        model, (node_feat, adj, type_mask, mask), out,
        input_names=["node_feat", "adj_by_type", "type_mask", "mask"],
        output_names=["priority"],
        dynamic_axes={"node_feat": {0: "B", 1: "N"}, "adj_by_type": {0: "B", 2: "N", 3: "N"},
                      "type_mask": {0: "B"}, "mask": {0: "B", 1: "N"},
                      "priority": {0: "B", 1: "N"}},
        opset_version=17, do_constant_folding=True, dynamo=False)
    print(f"导出完成: {out}  ({os.path.getsize(out) / 1024:.0f} KB)  "
          f"hidden={meta.get('hidden')} layers={meta.get('layers')}")
    return out


def main() -> None:
    ap = argparse.ArgumentParser(description="裁判编排模型（独立模型）")
    ap.add_argument("--mode", choices=["train", "export", "both"], default="train")
    ap.add_argument("--samples", type=int, default=800)
    ap.add_argument("--epochs", type=int, default=BASE_EPOCHS)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--hidden", type=int, default=BASE_HIDDEN)
    ap.add_argument("--layers", type=int, default=5)
    ap.add_argument("--lr", type=float, default=2e-3)
    ap.add_argument("--seed", type=int, default=20261005)
    add_device_arg(ap)
    args = ap.parse_args()

    if args.mode in ("train", "both"):
        # ⚠️ 加深/加宽后若不同步加训练预算，指标会「看起来」更差，别误判成架构问题。
        report_budget("referee_gnn", hidden=args.hidden, base_hidden=BASE_HIDDEN,
                      depth_units=args.layers + 1, base_depth_units=BASE_DEPTH_UNITS,
                      base_epochs=BASE_EPOCHS, base_patience=10,
                      epochs=args.epochs, patience=10)
        train(args)
    if args.mode in ("export", "both"):
        export()


if __name__ == "__main__":
    main()
