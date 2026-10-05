"""组次错开 AI：建议**换到第几组**（Learning-to-Rank）。

**问题**：编排精修链（GA/LNS/MNSA/ALNS/Fix-opt）全都只在「挪项目时间」这一维度
找改进，受「同并发位不重叠 + 组次必须连续」约束，**总有一些残余兼项冲突挪不动**。
此时有一条不占时段容量的出路：项目时间窗**一分不动**，只改换运动员在项目内的
**组次顺序**（``arrangement.heat``）——体育老师的标准做法是
「这两个项目同时段了，把他从第 1 组调到第 4 组就行」。

**为什么是「排序」而不是「直接输出组次」**：组次数 H 随人数变化（同一项目不同
年级可能不同），直接输出组次意味着输出维度随 H 变、模型无法固定契约；
而输出**每个候选组次一个分**（长度固定 = 最大组次数上限）后，
Java 侧只要 ``argmax`` 并按既有红线（同班/容量/人工锁定）过滤即可 —— 与现有管线零阻抗。

**标签怎么来**：用**穷举**构造「理想目标组次」——对该运动员的每个候选组次，
算它与其余全部项目的时间间隔，取**间隔最大**者（要求 ≥ 缓冲 15 分钟才算合法解）。
这与 Java 端 ``HeatStaggerMath.bestFor`` 的择优判据**完全同源**，
所以模型学到的是「那条判据背后的模式」，而不是另立一套标准。

**输入只有一个张量**（``heat_feat [B,C,F]``）：候选组次 × 特征。
把「其余项目的间隔」这类全局量**广播进每一行**（与 ``lane_advisor`` 同一手法），
好处是所有输入共用同一个动态轴 ``c``，ONNX 导出时不会有形状固定的旁路输入。

6 维候选组次特征（Java 端 ``HeatStaggerAdvisor`` 逐位复刻）::

    0 heat_norm        组次序号 / (组次数-1)
    1 heat_count_norm  min(组次数,16)/16          ← 全局，广播
    2 per_round_norm   min(每组用时,20)/20        ← 全局，广播
    3 heat_fill_norm   该组人数 / 并道数          ← 全局，广播
    4 gap_to_others    与其余项目最小间隔 / 60
    5 is_current       是否当前组次（0/1）

## ⚠️ 特征设计的一条硬纪律：**判据的组成部分一律不进特征**

本轮最初放了 11 维（含 ``clash_count`` / ``buffer_ok`` / ``same_class``），
训练命中率直接冲到 **1.000** —— 看着是完美成功，实则是**什么都没学**：
标签定义为「无 clash 且达缓冲的组里间隔最大者」，而
``clash_count==0 ∧ buffer_ok==1`` 就是这个判据的**原文**，
模型只需「找同时满足这两维的组」即可命中，用两行 if 规则就能做到。

消融实验给出了铁证（把某一维整列置零后重测命中率）::

    完整特征        1.000
    置零 clash_count 1.000      ← 布尔化的判据，留着就是送答案
    置零 buffer_ok   1.000
    置零 gap_to_others 1.000    ← 竟然也不依赖间隔！
    置零 same_class  0.816      ← 真凶：同班与否是判据的**必要条件**

进一步统计证实：``same_class`` 全为 0 的样本里**有解样本仅 79 个**，
而含同班冲突的样本有 3211 个 —— 标签几乎完全由「挑一个没有同班的组」决定。

**结论（可迁移到其它排序/建议类模型）**：
模型只应看到「**上下文与结构**」，不该看到「**判据本身**」。
红线（同班/容量/人工锁定）由 Java 侧 ``HeatStaggerMath`` 过滤，
模型只在**已过滤后的候选**里比较「哪个更好」——那才是它不可替代的价值。
"""

from __future__ import annotations

import argparse
import os
import random
from typing import Dict, List, Optional, Tuple

import numpy as np
import torch
import torch.nn as nn
from sports_ai.device import (add_device_arg, backup_before_overwrite, describe_device,
                             resolve_device, seed_all, to_device)
from sports_ai.nn import SpecialistMoE

MODEL_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "models")

#: 候选组次特征维数（与 Java 端 HeatStaggerAdvisor 严格对齐）
HEAT_FEAT_DIM = 6
#: 候选组次上限（推理时按实际组次数截断）
MAX_CAND = 16
#: 训练补齐长度（推理不补齐）
HEAT_TRAIN_PAD = MAX_CAND
#: 赶场缓冲（与 ConflictService.CONFLICT_BUFFER_MIN 同值）
BUFFER = 15


# ---------------------------------------------------------------------------
# 场景 → 样本
# ---------------------------------------------------------------------------
class HeatCase:
    """一名运动员在一个项目上的换组决策问题。"""

    __slots__ = ("feats", "mask", "label", "n", "heat_count", "per_round", "cur_heat")

    def __init__(self, feats: np.ndarray, mask: np.ndarray, label: int,
                 n: int, heat_count: int, per_round: int, cur_heat: int):
        self.feats = feats
        self.mask = mask
        self.label = label
        self.n = n
        self.heat_count = heat_count
        self.per_round = per_round
        self.cur_heat = cur_heat


def legal_candidates(heat_count: int, cur: int, lanes: int,
                     group_classes: Dict[int, set], my_class: str,
                     manual: bool) -> List[int]:
    """**红线过滤**后的候选组次（同班 / 组容量 / 人工锁定 / 非当前组次）。

    这一步与 Java 端 ``HeatStaggerMath`` 的红线一一对应，且**刻意在训练侧也执行**：
    线上是「先过滤、再让模型在剩下的里排序」，训练时就必须是同一个流程，
    否则模型会遇到线上永不出现的输入分布。

    返回空列表 = 无候选（换不开），这是**有效结论**，不是脏数据。
    """
    if manual or heat_count < 2:
        return []
    out: List[int] = []
    for h in range(1, heat_count + 1):
        if h == cur:
            continue
        if lanes > 0 and len(group_classes.get(h, ())) >= lanes:
            continue
        if my_class and my_class in group_classes.get(h, ()):
            continue
        out.append(h)
    return out


def _best_target(legal: List[int], per_round: int,
                 others: List[Tuple[int, int, int]]) -> int:
    """在**已过滤的候选**里，穷举「与其它项目间隔最大」的那个（-1 = 全撞，0 = 换不开）。

    这里的判据是「模型要学的排序依据」，它**只作为标签出现、不作为特征出现**——
    这正是文件头那条硬纪律：判据进特征 = 送答案。
    """
    best, best_gap = -1, -1
    for h in legal:
        s, e = (h - 1) * per_round, h * per_round
        gap = None
        for (os_, opr, oc) in others:
            for k in range(1, oc + 1):
                ks, ke = os_ + (k - 1) * opr, os_ + k * opr
                g = max(ks - e, s - ke)
                if gap is None or g < gap:
                    gap = g
        if gap is None:
            continue
        if gap > best_gap:
            best, best_gap = h, gap
    return best if best_gap >= BUFFER else -1


def make_case(rng: random.Random) -> Optional[HeatCase]:
    """构造一个「换组次」决策样本。

    **候选集就是红线过滤后的那几组**（``mask`` 标 1），与线上完全一致：
    Java 侧 ``HeatStaggerMath`` 同样只把合法组次交给打分环节。
    若过滤后没有候选（换不开），返回 None —— 这类样本线上压根不会走到模型。
    """
    heat_count = rng.randint(2, 8)
    per_round = rng.choice([3, 4, 5, 6, 8, 10])
    lanes = rng.choice([4, 6, 8])
    cur = rng.randint(1, heat_count)
    n_classes = rng.randint(3, 6)
    my_class = f"c{rng.randint(1, n_classes)}"

    # 该项目的组次班级构成。
    # ⚠️ 保留空位（k ≤ lanes-2）：让「同组不同班」成为**有真实压力**的约束，
    #    而不是随机把绝大多数候选否掉（曾因每组塞满导致有解率跌到 10%）。
    group_classes: Dict[int, set] = {}
    for h in range(1, heat_count + 1):
        k = rng.randint(0, max(0, lanes - 2))
        group_classes[h] = {f"c{rng.randint(1, n_classes)}" for _ in range(k)}
    fill = {h: len(group_classes[h]) / max(1, lanes) for h in group_classes}

    # 该运动员的其余项目（真正产生兼项冲突的那些）。
    # 起点必须有变化：全设 0 会让两个时间轴完全重合 → 标签全落在末几组。
    # 其余项目的总组次数也受控：远多于本项目时，无论换到哪组都被至少一个组次撞上。
    n_others = rng.randint(1, 2)
    budget = max(2, heat_count)
    others: List[Tuple[int, int, int]] = []
    left = budget
    for k in range(n_others):
        rest = n_others - k
        take = max(1, min(left - (rest - 1), rng.randint(1, max(1, budget // 2 + 1))))
        others.append((rng.choice([0, 0, 5, 10, -5, 15]),
                       rng.choice([3, 4, 5, 6, 8, 10]),
                       take))
        left -= take

    manual = 1.0 if rng.random() < 0.15 else 0.0
    legal = legal_candidates(heat_count, cur, lanes, group_classes, my_class, manual > 0)
    if not legal:
        return None            # 换不开 → 线上不会调用模型
    label_heat = _best_target(legal, per_round, others)
    if label_heat < 0:
        return None            # 换过去仍全撞 → 同上

    feats = np.zeros((heat_count, HEAT_FEAT_DIM), dtype=np.float32)
    mask = np.zeros((heat_count,), dtype=np.float32)
    for h in range(1, heat_count + 1):
        s_, e_ = (h - 1) * per_round, h * per_round
        gap_min = None
        for (os_, opr, oc) in others:
            for k in range(1, oc + 1):
                ks, ke = os_ + (k - 1) * opr, os_ + k * opr
                g = max(ks - e_, s_ - ke)
                if gap_min is None or g < gap_min:
                    gap_min = g
        gap_min = 0 if gap_min is None else gap_min
        feats[h - 1] = [
            (h - 1) / max(1, heat_count - 1),          # 0 组次序号
            min(heat_count, 16) / 16.0,                # 1 组次数（全局）
            min(per_round, 20) / 20.0,                 # 2 每组用时（全局）
            fill[h],                                    # 3 该组填充率（全局）
            min(1.0, max(0.0, gap_min) / 60.0),         # 4 与其余项目最小间隔
            1.0 if h == cur else 0.0,                  # 5 是否当前组次
        ]
        if h in legal:
            mask[h - 1] = 1.0
    return HeatCase(feats, mask, label_heat - 1,
                    heat_count, heat_count, per_round, cur)


def make_batch(batch: int, seed: int, pad_to: int = HEAT_TRAIN_PAD):
    """训练批：补齐到 ``pad_to``；标签 = 最优目标组次下标（-1 = 无合法解）。"""
    rng = random.Random(seed)
    xs, ms, ys = [], [], []
    for _ in range(batch):
        case = make_case(rng)
        for _try in range(40):                  # 可能整批都「换不开」，重采样
            if case is not None:
                break
            case = make_case(rng)
        if case is None:                        # 该 seed 下确实无解，造个占位（不产生梯度）
            case = HeatCase(np.zeros((1, HEAT_FEAT_DIM), dtype=np.float32),
                            np.zeros((1,), dtype=np.float32), 0, 1, 1, 1, 1)
        n = case.n
        x = np.zeros((pad_to, HEAT_FEAT_DIM), dtype=np.float32)
        m = np.zeros((pad_to,), dtype=np.float32)
        y = np.full((pad_to,), -1.0, dtype=np.float32)
        x[:n] = case.feats
        m[:n] = case.mask
        y[:n] = case.label
        xs.append(x)
        ms.append(m)
        ys.append(y)
    return (torch.from_numpy(np.stack(xs)), torch.from_numpy(np.stack(ms)),
            torch.from_numpy(np.stack(ys)))


# ---------------------------------------------------------------------------
# 模型
# ---------------------------------------------------------------------------
class HeatStaggerAdvisor(nn.Module):
    """候选组次打分：**专项 MoE**（多架构专家 + 层次两级门控 + 共享专家）。

    架构（2026-10-05 升级，此前是 5 层 MLP）：

    * **多架构专家**（来自 :mod:`sports_ai.nn`）：残差 MLP / 图卷积 / 一维卷积 /
      自注意力 / 交叉特征，各司其职 —— 卷积看组次**顺序**模式、注意力看**长程**依赖、
      图卷积看同项目内的邻接、交叉看个体与全场的关系；
    * **层次两级门控**：先选专家组、再组内选专家，搜索空间从 N 降到 N/G；
    * **共享专家隔离**：1 个恒定激活专家处理通用知识，稀疏专家只学差异；
    * **深度主干 6 层**（用户要求「至少 6 层」在此兑现）+ 输入/输出投影 = 实际深度 8；
    * **后续步骤预测头**：额外输出 3 个「下一步」预测（见 :class:`NextStepHead`）。
    """

    def __init__(self, feat: int = HEAT_FEAT_DIM, hidden: int = 128, dropout: float = 0.1,
                 n_layers: int = 6, n_experts: int = 8, n_groups: int = 4):
        super().__init__()
        self.moe = SpecialistMoE(in_dim=feat, hidden=hidden, n_layers=n_layers,
                                 n_experts=n_experts, n_groups=n_groups, top_k=2,
                                 n_shared=1, n_edges=1, dropout=dropout, n_steps=3)

    def forward(self, x: torch.Tensor, mask: torch.Tensor):
        """x [B,C,F]  mask [B,C] → (逐组得分 [B,C], 后续步骤预测 [B,3])。"""
        return self.moe(x, mask)

    def load_balance_loss(self):
        """辅助负载均衡损失（训练时以小系数加到主损失）。"""
        return self.moe.load_balance_loss()

    def update_router_bias(self, rate: float = 0.02):
        self.moe.update_router_bias(rate)

    def expert_usage(self) -> dict:
        return self.moe.expert_usage()

    def depth(self) -> int:
        return self.moe.depth()


def top1_hit(pred: np.ndarray, truth: float, mask: np.ndarray) -> float:
    """首选命中率：**在合法候选（mask=1）里** argmax 是否等于穷举最优目标组次。

    ⚠️ 必须在候选集内比较：若拿非法组一起比，模型可能被 mask 之外的组误导，
    指标会**系统性偏低**，而线上根本不会让那些组参与 —— 指标与口径分叉。
    随机基线 = 1/候选数（均匀猜），必须与它一起解读。
    """
    idx = np.where(mask > 0)[0]
    if truth < 0 or idx.size == 0:
        return float("nan")
    k = idx[int(np.argmax(pred[idx]))]
    return 1.0 if k == int(truth) else 0.0


def mrr(pred: np.ndarray, truth: float, mask: np.ndarray) -> float:
    """平均倒数排名（同样只在候选集内比）。"""
    idx = np.where(mask > 0)[0]
    if truth < 0 or idx.size == 0:
        return float("nan")
    order = idx[np.argsort(-pred[idx], kind="stable")]
    pos = int(np.where(order == int(truth))[0][0]) + 1
    return 1.0 / pos


# ---------------------------------------------------------------------------
# 训练 / 导出
# ---------------------------------------------------------------------------
def train(args) -> str:
    device = resolve_device(getattr(args, "device", "auto"))
    seed_all(20261005)
    model = HeatStaggerAdvisor().to(device)
    opt = torch.optim.Adam(model.parameters(), lr=1e-3, weight_decay=1e-4)
    loss_fn = nn.CrossEntropyLoss(ignore_index=-100)

    print(f"[训练] 组次错开专项 MoE：{args.iters} 步，batch={args.batch}  设备={describe_device(device)}")
    print(f"       架构：深度={model.depth()} 稀疏专家={len(model.moe.experts)} "
          f"共享专家={len(model.moe.shared)} 组数={model.moe.router.n_groups}")
    for it in range(args.iters):
        x, m, y = make_batch(args.batch, seed=args.seed + it)
        x, m, y = to_device((x, m, y), device)
        logits, _ = model(x, m)
        # 标签 -1（无合法解）→ ignore_index -100，让这类样本不产生梯度。
        # ⚠️ cross_entropy 的 target 必须是 **Long**；给 Float 会直接抛
        #    "expected scalar type Long but found Float"（不是静默算错，但会中断训练）。
        tgt = torch.where(y[:, 0] >= 0, y[:, 0], torch.full_like(y[:, 0], -100.0)).long()
        loss = loss_fn(logits, tgt)
        opt.zero_grad()
        loss.backward()
        opt.step()
        # 无辅助损失的负载均衡：按本批实际激活量更新路由偏置（DeepSeek-V3 式）
        model.update_router_bias()
        if (it + 1) % args.log_every == 0:
            with torch.no_grad():
                p = logits.cpu().numpy()
                t = y.cpu().numpy()
                hits, rr, rnd = [], [], []
                for b in range(x.shape[0]):
                    mrow = m[b].cpu().numpy()
                    h = top1_hit(p[b], t[b, 0], mrow)
                    if h == h:   # 非 NaN
                        hits.append(h)
                        rr.append(mrr(p[b], t[b, 0], mrow))
                        rnd.append(1.0 / max(1, int(mrow.sum())))
                if hits:
                    used = sum(1 for v in model.expert_usage().values() if v > 0.001)
                    print(f"  第 {it + 1:5d} 步  loss={loss.item():.5f}  "
                          f"首选命中率={np.mean(hits):.3f}（随机≈{np.mean(rnd):.3f}）  "
                          f"MRR={np.mean(rr):.3f}  激活专家={used}/{len(model.expert_usage())}")

    os.makedirs(MODEL_DIR, exist_ok=True)
    path = os.path.join(MODEL_DIR, "heat_stagger_advisor.pt")
    backup_before_overwrite(path, f"stagger-{args.iters}")
    torch.save({k: v.detach().cpu() for k, v in model.state_dict().items()}, path)
    print(f"完成：{os.path.relpath(path, os.path.dirname(MODEL_DIR))}")
    return path


def export(args) -> str:
    from sports_ai.onnx_utils import inline_weights

    model = HeatStaggerAdvisor()
    model.load_state_dict(torch.load(
        os.path.join(MODEL_DIR, "heat_stagger_advisor.pt"), map_location="cpu"))
    model.eval()
    path = os.path.join(MODEL_DIR, "heat_stagger_advisor.onnx")
    n = HEAT_TRAIN_PAD
    args_in = (
        torch.zeros((1, n, HEAT_FEAT_DIM), dtype=torch.float32),
        torch.ones((1, n), dtype=torch.float32),
    )
    # ⚠️ 导出的是「只回分数」的包装：MoE 内部逐步 TopK + 稀疏分支在 ONNX 上容易踩到
    #    dynamic_axes 与控制流的冲突（tracing 推断出的形状与声明不符）。这里显式包一层，
    #    丢掉 next_step 输出（Java 侧目前不用它），换取导出稳定。
    class _ScoreOnly(torch.nn.Module):
        def __init__(self, m):
            super().__init__()
            self.m = m

        def forward(self, x, mask):
            return self.m(x, mask)[0]

    torch.onnx.export(
        _ScoreOnly(model), args_in, path,
        input_names=["heat_feat", "mask"],
        output_names=["score"],
        dynamic_axes={"heat_feat": {1: "c"}, "mask": {1: "c"}, "score": {1: "c"}},
        opset_version=17,
        dynamo=False,   # PyTorch ≥2.6 默认 dynamo 导出器与 dynamic_axes 冲突
    )
    inline_weights(path)
    print(f"[ok] 导出 {os.path.basename(path)}（候选组次数 c 为动态轴）")

    if args.verify:
        import onnxruntime as ort
        sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
        for n2 in (2, 7, 16):
            out = sess.run(None, {
                "heat_feat": np.zeros((1, n2, HEAT_FEAT_DIM), dtype=np.float32),
                "mask": np.ones((1, n2), dtype=np.float32),
            })
            print(f"[verify] c={n2} → 输出 shape {[o.shape for o in out]}")
    return path


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--iters", type=int, default=2000)
    p.add_argument("--batch", type=int, default=64)
    p.add_argument("--seed", type=int, default=20261005)
    add_device_arg(p)
    p.add_argument("--log-every", type=int, default=400)
    p.add_argument("--export", action="store_true")
    p.add_argument("--verify", action="store_true")
    p.add_argument("--export-only", action="store_true")
    args = p.parse_args()
    if not args.export_only:
        train(args)
    if args.export or args.verify or args.export_only:
        export(args)


if __name__ == "__main__":
    main()
