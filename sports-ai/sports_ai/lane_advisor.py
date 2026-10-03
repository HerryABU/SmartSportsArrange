"""道次编排 AI：运动员**派遣优先级**建议（Learning-to-Rank）。

**问题**：径赛一个项目有 N 名运动员、分 H 组、每组 L 条道。分组要满足两条互相拉扯的目标：
- **硬约束**：同一组不能出现同班运动员（同班互掐、成绩不可比）；
- **软目标**：各组实力尽量均衡（避免「死亡之组」），且同班运动员在时间上分散（便于班主任兼顾）。

经典做法（项目现有的 ``class`` 款型）是「按报名顺序蛇形分组」——它满足硬约束，
但顺序本身是任意的，强弱可能扎堆。本模型学的是**该按什么顺序派遣**：
给定运动员特征（班级、性别、报名项目数、有无成绩），输出每人的**优先级**；
按优先级降序派遣，再做蛇形分组，同班自然错开、强弱自然交替。

**为什么是「排序」而不是「直接输出分组」**：组数 H 随报名人数变化（同一项目不同年级可能不同），
直接输出分组意味着输出维度随 H 变，模型无法固定契约；而输出**每人一个标量优先级**与 N、H 都无关，
Java 侧只要 ``argsort`` 再走既有的蛇形分组即可 —— 与现有管线零阻抗。

**标签怎么来**：用经典贪心构造「理想顺序」——按班级分桶后**轮询交错**，
使同班运动员在序列中等距分布；同班内部再按实力排序。
模型学的就是这个构造规则背后的模式，因此它输出的顺序能直接替代贪心构造。
标签是**优先级**（``1 - 位置/(n-1)``，越大越先派），与 Java 侧的降序 argsort 语义一致。

**输入只有一个张量**（``athlete_feat [B,N,8]``）：全局特征（组数、每组人数）已经拼在
每行的第 6、7 维里。早期版本额外开了一路 ``global_feat [B,2]``，结果 ONNX 导出时
动态轴声明与 tracing 推断的静态形状冲突（把固定 2 维的输入也标成动态）——
把全局信息**广播进每行**既省一路输入，也让所有输入的第 1 维都是同一个动态轴 ``n``。

8 维运动员特征（Java 侧逐位复刻）：
```
0 class_idx_norm    班级序号 / (班数-1)
1 gender            女=1 / 男=0
2 event_count_norm  min(报名项目数,4)/4
3 has_seed          有预赛成绩/种子标记 = 1
4 seed_norm         成绩归一化（无成绩为 0）
5 class_share       本班人数 / 总人数
6 n_groups_norm     min(组数,16)/16      ← 全局，广播到每行
7 per_group_norm    min(每组人数,32)/32  ← 全局，广播到每行
```
"""

from __future__ import annotations

import argparse
import os
import random
from typing import Dict, List, Tuple

import numpy as np
import torch
import torch.nn as nn
from sports_ai.device import (add_device_arg, backup_before_overwrite, describe_device,
                             resolve_device, seed_all, to_device)

MODEL_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "models")

#: 运动员特征维数（与 Java 端 LaneAdvisorService 严格对齐）
LANE_FEAT_DIM = 8
#: 训练补齐长度（推理不补齐）
LANE_TRAIN_PAD = 64


# ---------------------------------------------------------------------------
# 数据
# ---------------------------------------------------------------------------
def ideal_order(classes: List[int], strengths: List[float]) -> List[int]:
    """构造「理想派遣顺序」：同班等距交错 + 同班内按实力排序。

    做法：按班级分桶 → 轮询取桶（第 1 轮取各班第 1 人、第 2 轮取各班第 2 人…）。
    这样同班运动员在序列中的间隔恰好约等于班级数，蛇形分组时自然落到不同组。
    """
    buckets: Dict[int, List[int]] = {}
    for i, c in enumerate(classes):
        buckets.setdefault(c, []).append(i)
    for c in buckets:
        buckets[c].sort(key=lambda i: -strengths[i])   # 同班内强的排前
    order: List[int] = []
    depth = 0
    while True:
        added = False
        for c in sorted(buckets.keys()):
            if depth < len(buckets[c]):
                order.append(buckets[c][depth])
                added = True
        if not added:
            break
        depth += 1
    return order


def make_sample(rng: random.Random) -> Tuple[np.ndarray, np.ndarray, int]:
    """生成一个项目的运动员批次 → (运动员特征, mask, n)。"""
    n = rng.randint(6, 48)
    n_classes = rng.randint(2, max(3, n // 3))
    classes = [rng.randrange(n_classes) for _ in range(n)]
    event_counts = [rng.randint(1, 3) for _ in range(n)]
    strengths = [rng.random() for _ in range(n)]
    has_seed = [1.0 if rng.random() < 0.35 else 0.0 for _ in range(n)]
    genders = [1.0 if rng.random() < 0.5 else 0.0 for _ in range(n)]
    lanes = rng.choice([4, 6, 8])
    n_groups = max(1, (n + lanes - 1) // lanes)
    per_group = min(lanes, n)

    distinct = sorted(set(classes))
    class_idx = {c: i for i, c in enumerate(distinct)}
    class_count = max(1, len(distinct))
    share = {c: classes.count(c) / n for c in distinct}

    feats = np.zeros((n, LANE_FEAT_DIM), dtype=np.float32)
    for i in range(n):
        c = classes[i]
        feats[i, 0] = class_idx[c] / max(1, class_count - 1)
        feats[i, 1] = genders[i]
        feats[i, 2] = min(event_counts[i], 4) / 4.0
        feats[i, 3] = has_seed[i]
        feats[i, 4] = strengths[i] if has_seed[i] else 0.0
        feats[i, 5] = share[c]
        feats[i, 6] = min(n_groups, 16) / 16.0
        feats[i, 7] = min(per_group, 32) / 32.0
    return feats, np.ones((n,), dtype=np.float32), n


def make_batch(batch: int, seed: int, pad_to: int = LANE_TRAIN_PAD):
    """训练批：补齐到 pad_to；标签 = 派遣**优先级**（越大越先派）。"""
    rng = random.Random(seed)
    xs, ms, ys = [], [], []
    for _ in range(batch):
        feats, mask, n = make_sample(rng)
        classes = [int(round(float(feats[i, 0]) * 15)) for i in range(n)]
        strengths = [float(feats[i, 4]) for i in range(n)]
        order = ideal_order(classes, strengths)
        priority = np.zeros((n,), dtype=np.float32)
        for pos, idx in enumerate(order):
            priority[idx] = 1.0 - pos / max(1, n - 1)   # 越先派，优先级越高

        x = np.zeros((pad_to, LANE_FEAT_DIM), dtype=np.float32)
        m = np.zeros((pad_to,), dtype=np.float32)
        y = np.zeros((pad_to,), dtype=np.float32)
        x[:n] = feats
        m[:n] = mask
        y[:n] = priority
        xs.append(x)
        ms.append(m)
        ys.append(y)
    return (torch.from_numpy(np.stack(xs)), torch.from_numpy(np.stack(ms)),
            torch.from_numpy(np.stack(ys)))


# ---------------------------------------------------------------------------
# 模型
# ---------------------------------------------------------------------------
class LaneAdvisor(nn.Module):
    """运动员级打分：8 维特征 → 派遣优先级（越大越先派）。"""

    def __init__(self, feat: int = LANE_FEAT_DIM, hidden: int = 160, dropout: float = 0.1):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(feat, hidden), nn.ReLU(),
            nn.Linear(hidden, hidden), nn.ReLU(),
            nn.Dropout(dropout),
            nn.Linear(hidden, hidden // 2), nn.ReLU(),
            nn.Linear(hidden // 2, 1),
        )

    def forward(self, x: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
        # x [B,N,F]  mask [B,N] → [B,N]
        return self.net(x).squeeze(-1) * mask


def ordered_indices(score: np.ndarray, n: int) -> List[int]:
    """把**优先级**得分转成派遣顺序（降序：分高者先派）。"""
    return list(np.argsort(-score[:n], kind="stable"))


def top_half_overlap(pred: np.ndarray, truth: np.ndarray, n: int) -> float:
    """预测顺序与理想顺序的「前半段重合度」——随机基线约 0.5，越接近 1 越好。"""
    if n < 2:
        return 1.0
    k = max(1, n // 2)
    a = set(ordered_indices(pred, n)[:k])
    b = set(ordered_indices(truth, n)[:k])
    return len(a & b) / k


# ---------------------------------------------------------------------------
# 训练 / 导出
# ---------------------------------------------------------------------------
def train(args) -> str:
    device = resolve_device(getattr(args, "device", "auto"))
    seed_all(0)
    model = LaneAdvisor().to(device)
    opt = torch.optim.Adam(model.parameters(), lr=1e-3, weight_decay=1e-4)
    loss_fn = nn.MSELoss(reduction="none")

    print(f"[训练] 道次排序建议模型：{args.iters} 步，batch={args.batch}  设备={describe_device(device)}")
    for it in range(args.iters):
        x, m, y = make_batch(args.batch, seed=args.seed + it)
        x, m, y = to_device((x, m, y), device)
        pred = model(x, m)
        loss = (loss_fn(pred, y) * m).sum() / m.sum().clamp(min=1)
        opt.zero_grad()
        loss.backward()
        opt.step()
        if (it + 1) % args.log_every == 0:
            with torch.no_grad():
                ov = float(np.mean([
                    top_half_overlap(pred[b].cpu().numpy(), y[b].cpu().numpy(), int(m[b].sum()))
                    for b in range(x.shape[0])
                ]))
                print(f"  第 {it + 1:5d} 步  loss={loss.item():.5f}  前半段排序重合度={ov:.3f}（随机≈0.5）")

    os.makedirs(MODEL_DIR, exist_ok=True)
    path = os.path.join(MODEL_DIR, "lane_advisor.pt")
    # 覆盖前备份（冒烟训练别把正式权重冲掉）
    backup_before_overwrite(path, f"smoke-{args.iters}")
    # 权重存 CPU：跨设备/跨版本加载更稳（load_state_dict 默认 map_location='cpu'）
    torch.save({k: v.detach().cpu() for k, v in model.state_dict().items()}, path)
    print(f"完成：{os.path.relpath(path, os.path.dirname(MODEL_DIR))}")
    return path


def export(args) -> str:
    from sports_ai.onnx_utils import inline_weights

    model = LaneAdvisor()
    model.load_state_dict(torch.load(os.path.join(MODEL_DIR, "lane_advisor.pt"), map_location="cpu"))
    model.eval()
    path = os.path.join(MODEL_DIR, "lane_advisor.onnx")
    n = LANE_TRAIN_PAD
    args_in = (
        torch.zeros((1, n, LANE_FEAT_DIM), dtype=torch.float32),
        torch.ones((1, n), dtype=torch.float32),
    )
    torch.onnx.export(
        model, args_in, path,
        input_names=["athlete_feat", "mask"],
        output_names=["priority"],
        dynamic_axes={"athlete_feat": {1: "n"}, "mask": {1: "n"}, "priority": {1: "n"}},
        opset_version=17,
    )
    inline_weights(path)
    print(f"[ok] 导出 {os.path.basename(path)}（运动员数 n 为动态轴）")

    if args.verify:
        import onnxruntime as ort
        sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
        for n2 in (5, 91):
            out = sess.run(None, {
                "athlete_feat": np.zeros((1, n2, LANE_FEAT_DIM), dtype=np.float32),
                "mask": np.ones((1, n2), dtype=np.float32),
            })
            print(f"[verify] n={n2} → 输出 shape {[o.shape for o in out]}")
    return path


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--iters", type=int, default=1500)
    p.add_argument("--batch", type=int, default=32)
    p.add_argument("--seed", type=int, default=20260918)
    add_device_arg(p)
    p.add_argument("--log-every", type=int, default=300)
    p.add_argument("--export", action="store_true")
    p.add_argument("--verify", action="store_true")
    p.add_argument("--export-only", action="store_true",
                   help="只导出（不重新训练）—— 供构建脚本在训练步骤之后单独调用")
    args = p.parse_args()
    if not args.export_only:
        train(args)
    if args.export or args.verify or args.export_only:
        export(args)


if __name__ == "__main__":
    main()
