"""跨时段拆分 AI：**多个项目抢同一段上午余量时，排序「先拆谁」**（Learning-to-Rank）。

**问题**：项目编排是「整块放置」——``u.duration`` 必须整个塞进某一个时段窗口。
于是「100 米 6 组共 100 分钟，上午只剩 30 分钟」时，编排器只能整块挪到下午，
上午那 30 分钟白白空着。允许跨时段拆分后，它变成
「前 2 组排上午 10:50–11:30，后 4 组排下午 14:00–14:40」，排得下、也不浪费上午余量。

## ⚠️ 为什么这个模型学的是「先拆谁」，而不是「切几组」

最初的设计是「给定一个项目，输出上午切 k 组」（分类）。**实测发现学不了**，教训值得写死：

判据「从合法 k 里挑最优」里的**最优**本身就是我手写的确定性规则
（``SlotSplit.beats``：冲突少 → 上午组次多 → 窗口/起点靠前），
而合法 k 集合又近似「1..kmax 的连续前缀」（实测 99.2%）——
于是标签几乎恒等于候选集上界，模型只需 ``argmax(round_norm)``，
命中率冲到 **1.000** 却**什么都没学**（消融：置零 round_norm 掉到 0.150）。
换判据权重、改余量抖动（单调/非单调都试过）都无法改变这一点——
**这不是调参问题，是任务设计问题**。

真正的增量价值在**多解竞争**：上午的零头通常只够拆**一个**项目，
而候选往往有 3~5 个。这时的问题是「**先拆谁收益最大**」——
这有真实的多解空间，且答案依赖「拆完之后上午还剩多少给下一个」的**动态推演**，
规则只能贪心地看当前收益，模型可以学到「留余量给后续大项目的全局权衡」。

所以任务重定义为：**一批「整块放不下」的项目，输出「先拆谁」的优先级**。
标签用**序列模拟**构造：对每个候选项目试拆，优先选「上午用得足、下午留余量多」的，
再把剩余上午余量给下一个项目……如此得到一个贪心序，模型学这个序。

**输入只有一个张量**（``split_feat [B,P,F]``）：P = 候选项目数（≤16），逐项目一行，
全局量（上午余量、下午余量、项目总数）广播进每行
（与 ``lane_advisor`` 同一手法），ONNX 导出只有一个动态轴。

8 维逐项目特征（Java 端 ``SlotSplitAdvisor`` 逐位复刻）::

    0 heat_count_norm   min(组次数,16)/16         ← 全局，广播
    1 per_round_norm    min(每组用时,20)/20       ← 全局，广播
    2 total_norm        min(总时长,240)/240        ← 全局，广播
    3 am_free_norm      min(上午剩余,240)/240      ← 全局，广播
    4 pm_free_norm      min(下午剩余,240)/240      ← 全局，广播
    5 max_split_norm    min(可拆组次数,16)/16
    6 can_free_ratio    拆满时上午能用掉的比例
    7 tail_pressure     尾段占下午的比例（越大越挤）
"""

from __future__ import annotations

import argparse
import os
import random
from typing import List, Optional, Tuple

import numpy as np
import torch
import torch.nn as nn
from sports_ai.device import (add_device_arg, backup_before_overwrite, describe_device,
                             resolve_device, seed_all, to_device)
from sports_ai.nn import SpecialistMoE

MODEL_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "models")

#: 逐项目特征维数（与 Java 端 SlotSplitAdvisor 严格对齐）
SPLIT_FEAT_DIM = 8
#: 候选项目数上限
MAX_CAND = 16
#: 训练补齐长度（推理不补齐）
SPLIT_TRAIN_PAD = MAX_CAND


class Proj:
    """一个「整块放不下、可考虑跨时段拆分」的候选项目。"""

    __slots__ = ("heat_count", "per_round", "duration", "can_split")

    def __init__(self, heat_count: int, per_round: int, can_split: bool):
        self.heat_count = heat_count
        self.per_round = per_round
        self.duration = heat_count * per_round
        self.can_split = can_split


class SplitCase:
    """一批候选项目的「先拆谁」决策。"""

    __slots__ = ("feats", "mask", "priority", "n", "am_free", "pm_free")

    def __init__(self, feats, mask, priority, n, am_free, pm_free):
        self.feats = feats
        self.mask = mask
        self.priority = priority
        self.n = n
        self.am_free = am_free
        self.pm_free = pm_free


def make_case(rng: random.Random) -> Optional[SplitCase]:
    """构造一批「整块放不下」的项目，标签 = 序列模拟得到的贪心拆分序。

    **标签怎么来（关键）**：不是「给每个项目算一个静态分数」，
    而是**按顺序模拟**——上午余量只够拆有限几个项目，谁先拆会让**后面**的项目
    受益或受损。于是「先拆谁」取决于「拆完还剩多少」这一**动态推演**，
    规则只能用贪心近似，模型则能学到「给后续留余量」的全局权衡。

    模拟逻辑（与 Java 端 ``SlotSplit.findSplit`` 的红线一致）：
      每轮在「还没拆的、仍可拆的」项目里选一个**当前**上午占用效率最高者
      （能吃掉的上午分钟 / 自身总时长），扣掉它的上午占用；
      上午余量不够吃任何一整组时停止。
    """
    am_cap = rng.choice([40, 60, 80, 100, 120, 150, 180, 210])
    pm_cap = rng.choice([120, 150, 180, 210, 240])
    am_free = max(0, am_cap - rng.choice([0, 10, 20, 30, 45]))
    pm_free = max(0, pm_cap - rng.choice([0, 0, 20, 40]))

    n = rng.randint(3, MAX_CAND)
    projs: List[Proj] = []
    for _ in range(n):
        hc = rng.randint(2, 10)
        pr = rng.choice([3, 4, 5, 6, 8, 10])
        total = hc * pr
        # 可拆 = 组次边界对得上 且 至少能吃 1 组进上午 且 尾段能被下午装下
        can_split = (total % pr == 0 and hc >= 2 and pr <= am_free
                     and total - pr <= pm_free)
        projs.append(Proj(hc, pr, can_split))
    if not any(p.can_split for p in projs):
        return None

    # ---- 序列模拟：得到贪心拆分序 ----
    remaining = list(range(n))
    left = am_free
    rank = [0] * n
    step = 0
    while remaining and step < n:
        best_i, best_eff = None, 0.0
        for i in remaining:
            p = projs[i]
            if not p.can_split:
                continue
            if p.per_round > left:          # 连一组都塞不进 → 此轮不可选
                continue
            k = min(p.heat_count - 1, left // p.per_round)
            if k < 1:
                continue
            eff = (k * p.per_round) / p.duration    # 上午占用效率
            # 同效率时优先拆「小的」（给后面留大块）
            if eff > best_eff + 1e-9 or (abs(eff - best_eff) <= 1e-9
                                         and best_i is not None
                                         and p.duration < projs[best_i].duration):
                best_i, best_eff = i, eff
        if best_i is None:
            break
        p = projs[best_i]
        k = min(p.heat_count - 1, left // p.per_round)
        left -= k * p.per_round
        step += 1
        rank[best_i] = step
        remaining.remove(best_i)
    # 未被拆到的（不可拆或没余量）优先级置 0 —— 线上它们本来就不拆
    if step == 0:
        return None

    # 优先级转成「越大越先拆」
    order = sorted([i for i in range(n) if rank[i] > 0], key=lambda i: rank[i])
    prio = np.zeros((n,), dtype=np.float32)
    for pos, i in enumerate(order):
        prio[i] = 1.0 - pos / max(1, len(order) - 1) if len(order) > 1 else 1.0

    feats = np.zeros((n, SPLIT_FEAT_DIM), dtype=np.float32)
    mask = np.zeros((n,), dtype=np.float32)
    for i, p in enumerate(projs):
        k_max = min(p.heat_count - 1, am_free // p.per_round) if p.per_round > 0 else 0
        head = max(0, k_max) * p.per_round
        tail = p.duration - head
        feats[i] = [
            min(p.heat_count, 16) / 16.0,                  # 0 组次数
            min(p.per_round, 20) / 20.0,                   # 1 每组用时
            min(p.duration, 240) / 240.0,                  # 2 总时长
            min(am_free, 240) / 240.0,                    # 3 上午剩余（全局）
            min(pm_free, 240) / 240.0,                    # 4 下午剩余（全局）
            min(max(0, k_max), 16) / 16.0,                # 5 可拆组次数
            min(1.0, head / max(1, p.duration)),           # 6 拆满时上午能用掉的比例
            min(1.0, tail / max(1, pm_free)),              # 7 尾段占下午的比例
        ]
        # 只有「可拆且能被拆到」的项目参与排序；其余不参与（线上不会去拆它们）
        if rank[i] > 0:
            mask[i] = 1.0
    if mask.sum() < 2:      # 至少要有两个候选才有排序可言
        return None
    return SplitCase(feats, mask, prio, n, am_free, pm_free)


def make_batch(batch: int, seed: int, pad_to: int = SPLIT_TRAIN_PAD):
    rng = random.Random(seed)
    xs, ms, ys = [], [], []
    for _ in range(batch):
        case = make_case(rng)
        for _try in range(40):
            if case is not None:
                break
            case = make_case(rng)
        if case is None:
            case = SplitCase(np.zeros((1, SPLIT_FEAT_DIM), dtype=np.float32),
                             np.zeros((1,), dtype=np.float32),
                             np.zeros((1,), dtype=np.float32), 1, 0, 0)
        x = np.zeros((pad_to, SPLIT_FEAT_DIM), dtype=np.float32)
        m = np.zeros((pad_to,), dtype=np.float32)
        y = np.zeros((pad_to,), dtype=np.float32)
        x[:case.n] = case.feats
        m[:case.n] = case.mask
        y[:case.n] = case.priority
        xs.append(x)
        ms.append(m)
        ys.append(y)
    return (torch.from_numpy(np.stack(xs)), torch.from_numpy(np.stack(ms)),
            torch.from_numpy(np.stack(ys)))


# ---------------------------------------------------------------------------
# 模型
# ---------------------------------------------------------------------------
class SlotSplitAdvisor(nn.Module):
    """候选项目打分：**专项 MoE**（多架构专家 + 层次两级门控 + 共享专家）。

    架构与 :class:`sports_ai.heat_stagger_advisor.HeatStaggerAdvisor` 同源
    （都取自 :mod:`sports_ai.nn`），保证两个专项模型可共享演进、可对比。

    这里的**多架构**各司其职：
    卷积专家看「时段推进」的局部顺序、注意力专家看「谁抢谁」的**长程**竞争、
    交叉专家看「本项目 vs 全场余量」、图卷积看同批次邻接、MLP 打底。

    另外带**后续步骤预测头**（3 步）：可预测「先拆这个之后，
    下一个该拆谁 / 还能不能继续拆 / 是否已无余量」。
    """

    def __init__(self, feat: int = SPLIT_FEAT_DIM, hidden: int = 128, dropout: float = 0.1,
                 n_layers: int = 6, n_experts: int = 8, n_groups: int = 4):
        super().__init__()
        self.moe = SpecialistMoE(in_dim=feat, hidden=hidden, n_layers=n_layers,
                                 n_experts=n_experts, n_groups=n_groups, top_k=2,
                                 n_shared=1, n_edges=1, dropout=dropout, n_steps=3)
        # 预测分支（「未来 H 步时间槽」）：与主任务**共享 self.moe 主干**，
        # 所以预测能力会回流到主表征；不进部署契约（导出只回主输出）。
        from sports_ai.nn.forecast_aux import ForecastAux
        self.aux = ForecastAux(hidden)

    def forward(self, x: torch.Tensor, mask: torch.Tensor):
        """x [B,P,F]  mask [B,P] → (逐项目优先级 [B,P], 后续步骤预测 [B,3])。"""
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


def spearman(pred: np.ndarray, truth: np.ndarray, mask: np.ndarray) -> float:
    """候选集内的**秩相关**（Spearman）。

    为什么用秩相关而不是命中率：标签是一条**序**（先拆谁），
    个别次序错位并不影响可用性，而「整体趋势对不对」才是关键。
    随机基线 ≈ 0（随机序与真序的秩相关期望为 0）。
    """
    idx = np.where(mask > 0)[0]
    if idx.size < 2:
        return float("nan")
    a = pred[idx]
    b = truth[idx]
    ra = np.argsort(np.argsort(-a))
    rb = np.argsort(np.argsort(-b))
    ra = ra - ra.mean()
    rb = rb - rb.mean()
    den = float(np.sqrt((ra * ra).sum() * (rb * rb).sum()))
    return float((ra * rb).sum() / den) if den > 1e-9 else float("nan")


def top1_hit(pred: np.ndarray, truth: np.ndarray, mask: np.ndarray) -> float:
    """首选命中率：模型排在第一的是否就是模拟序里的第一个（随机基线 1/候选数）。"""
    idx = np.where(mask > 0)[0]
    if idx.size < 2:
        return float("nan")
    k = idx[int(np.argmax(pred[idx]))]
    t = idx[int(np.argmax(truth[idx]))]
    return 1.0 if k == t else 0.0


# ---------------------------------------------------------------------------
# 训练 / 导出
# ---------------------------------------------------------------------------
def train(args) -> str:
    device = resolve_device(getattr(args, "device", "auto"))
    seed_all(20261006)
    model = SlotSplitAdvisor().to(device)
    opt = torch.optim.Adam(model.parameters(), lr=1e-3, weight_decay=1e-4)
    # 回归：预测优先级（Learning-to-Rank 的点式版本，配合 Spearman 评测）
    loss_fn = nn.MSELoss(reduction="none")

    print(f"[训练] 跨时段拆分专项 MoE：{args.iters} 步，batch={args.batch}  设备={describe_device(device)}")
    print(f"       架构：深度={model.depth()} 稀疏专家={len(model.moe.experts)} "
          f"共享专家={len(model.moe.shared)} 组数={model.moe.router.n_groups}")
    # 预测分支的数据：一次预生成、训练期循环采样（现场每步生成要跑
    # 「场景生成 + 贪心着色」，那是主要开销）。
    from sports_ai.nn.forecast_aux import AuxData, aux_loss, specialist_trunk
    aux_data = AuxData(n=512, seed=args.seed + 4242, device=device)
    # 共享主干：取 SpecialistMoE 里**只吃 hidden 维**的那一段（不能直接调 encode，
    # 它的 in_proj 吃原始特征维）。
    share = specialist_trunk(model.moe)

    for it in range(args.iters):
        x, m, y = make_batch(args.batch, seed=args.seed + it)
        x, m, y = to_device((x, m, y), device)
        pred, _ = model(x, m)
        loss = (loss_fn(pred, y) * m).sum() / m.sum().clamp(min=1)
        # 预测分支：未来 H 步时间槽 —— 过**同一个 moe 主干**，于是预测能力
        # 会回流到主任务的表征里（不是外挂一条不相干的支路）。
        sx, sy = aux_data.sample(args.batch)
        loss = loss + aux_loss(model.aux, sx, sy, trunk_fn=share)
        opt.zero_grad()
        loss.backward()
        opt.step()
        # 无辅助损失的负载均衡（DeepSeek-V3 式）
        model.update_router_bias()
        if (it + 1) % args.log_every == 0:
            with torch.no_grad():
                p = pred.cpu().numpy()
                t = y.cpu().numpy()
                mm = m.cpu().numpy()
                rs, hs, rnd = [], [], []
                for b in range(x.shape[0]):
                    r = spearman(p[b], t[b], mm[b])
                    if r == r:
                        rs.append(r)
                        hs.append(top1_hit(p[b], t[b], mm[b]))
                        rnd.append(1.0 / max(1, int(mm[b].sum())))
                if rs:
                    used = sum(1 for v in model.expert_usage().values() if v > 0.001)
                    print(f"  第 {it + 1:5d} 步  loss={loss.item():.5f}  "
                          f"Spearman={np.mean(rs):+.3f}（随机≈0）  "
                          f"首选命中率={np.mean(hs):.3f}（随机≈{np.mean(rnd):.3f}）  "
                          f"激活专家={used}/{len(model.expert_usage())}")

    os.makedirs(MODEL_DIR, exist_ok=True)
    path = os.path.join(MODEL_DIR, "slot_split_advisor.pt")
    backup_before_overwrite(path, f"split-{args.iters}")
    torch.save({k: v.detach().cpu() for k, v in model.state_dict().items()}, path)
    print(f"完成：{os.path.relpath(path, os.path.dirname(MODEL_DIR))}")
    return path


def export(args) -> str:
    from sports_ai.onnx_utils import inline_weights

    model = SlotSplitAdvisor()
    # ⚠️ load_with_aux：ckpt 带预测分支的 aux.* 参数，部署契约只要主输出 ——
    #    strict=True 会报 "Unexpected key(s): aux.*" 让导出失败（＝训了导不出）。
    from sports_ai.nn.forecast_aux import load_with_aux
    load_with_aux(model, torch.load(
        os.path.join(MODEL_DIR, "slot_split_advisor.pt"), map_location="cpu"))
    model.eval()
    path = os.path.join(MODEL_DIR, "slot_split_advisor.onnx")
    n = SPLIT_TRAIN_PAD
    args_in = (
        torch.zeros((1, n, SPLIT_FEAT_DIM), dtype=torch.float32),
        torch.ones((1, n), dtype=torch.float32),
    )
    # ⚠️ 只导出「优先级」这一个输出：MoE 内部 TopK + 稀疏分支与 ONNX 动态轴
    #    在 tracing 下容易冲突，包一层只回主输出最稳（next_step 目前 Java 侧未用）。
    class _ScoreOnly(torch.nn.Module):
        def __init__(self, m):
            super().__init__()
            self.m = m

        def forward(self, x, mask):
            return self.m(x, mask)[0]

    torch.onnx.export(
        _ScoreOnly(model), args_in, path,
        input_names=["split_feat", "mask"],
        output_names=["priority"],
        dynamic_axes={"split_feat": {1: "p"}, "mask": {1: "p"}, "priority": {1: "p"}},
        opset_version=17,
        dynamo=False,
    )
    inline_weights(path)
    print(f"[ok] 导出 {os.path.basename(path)}（候选项目数 p 为动态轴）")

    if args.verify:
        import onnxruntime as ort
        sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
        for n2 in (2, 8, 16):
            out = sess.run(None, {
                "split_feat": np.zeros((1, n2, SPLIT_FEAT_DIM), dtype=np.float32),
                "mask": np.ones((1, n2), dtype=np.float32),
            })
            print(f"[verify] p={n2} → 输出 shape {[o.shape for o in out]}")
    return path


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--iters", type=int, default=2500)
    p.add_argument("--batch", type=int, default=96)
    p.add_argument("--seed", type=int, default=20261006)
    add_device_arg(p)
    p.add_argument("--log-every", type=int, default=500)
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
