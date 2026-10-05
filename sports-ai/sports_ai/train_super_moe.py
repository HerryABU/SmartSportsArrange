"""训练 SuperScheduleMoE（统一编排超级模型）。

五档场景混合（HELL/REGULAR/BLOCK/LANE/TEAM），自监督标签来自
{@code super_encode.greedy_targets}（约束驱动，不需标注的最优解）。

## 训练时必看的两个指标

1. **专家使用率**（{@code model.expert_usage()}）——MoE 最常见的隐性失败是
   「专家建了但从不被选中」，而此时 loss 照样下降。必须盯着它。
2. **负载均衡损失**——不接近均匀就说明专家塌缩了。

## 用法::

    python -m sports_ai.train_super_moe --samples 1200 --epochs 40 --device cpu
产出：``models/super_moe.pt`` + ``models/super_moe_stats.json``

## ⚠️ 为什么每轮都要落盘（实战踩坑）

训练跑 40 轮 ≈ 70 分钟，而**中途被杀一次就全白跑**：原实现只在最后写盘，
进程一断（后台任务被回收 / 用户关机 / 端口占用）就只剩一个 epoch 29 的日志，
磁盘上 ``super_moe.pt`` 还是上一次的旧权重，``stats.json`` 更是上上次训练
的残留（val=4.23 / epoch=2），会**把人往完全错误的方向带**。

所以改成：

1. **每刷新一次 val 最优就立即写盘**（先写 ``.tmp`` 再 ``os.replace`` 原子替换，
   中途被杀最多丢当前这一轮，不会写出半个文件）；
2. 支持 ``--resume`` 从 ``models/super_moe.pt`` 续训，分段跑长任务不会互相打断；
3. ``--patience`` 早停（val 连续不降就收工），避免过拟合后白烧时间。

分段跑长训练的推荐姿势::

    python -m sports_ai.train_super_moe --epochs 5 --resume   # 跑 5 轮，落盘
    python -m sports_ai.train_super_moe --epochs 5 --resume   # 再来 5 轮
"""

from __future__ import annotations

import argparse
import json
import os
import random
import sys
from typing import Dict, List

import numpy as np
import torch

if __package__ in (None, ""):
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from sports_ai.data.super_encode import MAX_SLOTS, encode_super_graph, greedy_targets
from sports_ai.data.super_scenarios import (
    add_shadow_tasks,
    N_EDGES,
    generate_super_scenario,
    validate_scenario,
)
from sports_ai.device import (
    add_device_arg,
    backup_before_overwrite,
    describe_device,
    resolve_device,
    seed_all,
)
from sports_ai.models.super_moe import NODE_FEAT_DIM, SuperScheduleMoE

MODEL_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "models")
TIERS = ["HELL", "REGULAR", "BLOCK", "LANE", "TEAM"]


def search_refined_priority(scen, n: int, base: Dict, seed: int) -> Dict:
    """用搜索精修 priority 标签（RSI 的第一步）。

    ⚠️ **安全保证**：先比「搜索解的代价」与「原标签顺序的代价」，
    搜索没赢就原样返回 —— 标签质量只会变好、不会变坏，
    所以打开这个开关不存在「训完更差」的风险。
    """
    # 延迟 import：训练脚本 → 评测脚本是单向依赖（反过来会成环），
    # 且只有开启搜索时才需要付这份加载成本。
    from sports_ai.evaluate_super_moe import cost_of, decode, ga_search

    try:
        order = ga_search(scen, n, None, seed=seed, pop=10, gens=12)
        searched = decode(scen, n, None, None, "custom", order_override=order)
        base_order = sorted(range(n), key=lambda i: -float(base["priority"][i]))
        baseline = decode(scen, n, None, None, "custom", order_override=base_order)
    except Exception as e:                     # noqa: BLE001 搜索失败不能拖垮训练
        print(f"[label] 搜索精修失败，保留原标签: {e}")
        return base
    if cost_of(searched) >= cost_of(baseline):
        return base
    pri = np.zeros(n, dtype=np.float32)
    for rank, idx in enumerate(order):
        pri[int(idx)] = 1.0 - rank / max(1, n - 1)
    if float(pri.std()) < 1e-4:
        return base
    return {**base, "priority": pri}


def make_dataset(n: int, seed: int, label_search: bool = False) -> List[Dict]:
    """五档混合采样。**样本不通过 validate 就丢弃**（不修数据）。"""
    rng = random.Random(seed)
    data: List[Dict] = []
    rejected: Dict[str, int] = {}
    attempts = 0
    while len(data) < n and attempts < n * 5:
        attempts += 1
        tier = TIERS[len(data) % len(TIERS)]
        scen = generate_super_scenario(tier, seed=rng.randint(0, 10 ** 9))
        # 合并裁判/教师任务（N_TASKS 9→11）：影子任务并入同一张图
        add_shadow_tasks(scen, rng)
        ok, why = validate_scenario(scen)
        if not ok:
            rejected[why] = rejected.get(why, 0) + 1
            continue
        enc = encode_super_graph(scen)
        if enc is None:
            rejected["编码失败"] = rejected.get("编码失败", 0) + 1
            continue
        tg = greedy_targets(scen, enc["n"])
        if float(tg["priority"].std()) < 1e-4:
            rejected["目标无方差"] = rejected.get("目标无方差", 0) + 1
            continue
        if label_search:
            # RSI 第一步：让标签由搜索产生，抬高模型的天花板
            tg = search_refined_priority(scen, enc["n"], tg, seed=rng.randint(0, 10 ** 9))
        data.append({**enc, "tier": tier, **tg})
    if rejected:
        print(f"[data] 判废统计: {rejected}")
    return data


def to_tensors(batch: List[Dict], idxs: List[int], device):
    """把变长样本 pad 到批内最大 N（ONNX 侧 N 动态，PyTorch 批处理必须先 pad）。"""
    items = [batch[i] for i in idxs]
    width = max(d["n"] for d in items)
    nf, abt, tm, mk, pri, slot, fmt, gf = [], [], [], [], [], [], [], []
    # 三个新目标（覆盖 lane_advisor / forecast / GAN 判别器）
    lmask, days_t, qual_t = [], [], []
    for d in items:
        n, pad = d["n"], width - d["n"]
        nf.append(np.pad(d["node_feat"][0], ((0, pad), (0, 0))))
        abt.append(np.pad(d["adj_by_type"][0], ((0, 0), (0, pad), (0, pad))))
        tm.append(d["type_mask"][0])
        mk.append(np.pad(d["mask"][0], (0, pad)))
        pri.append(np.pad(d["priority"], (0, pad)))
        sl = np.zeros((width, MAX_SLOTS), dtype=np.float32)
        sl[:n] = d["slot"]
        slot.append(sl)
        fmt.append(d["format"])
        gf.append(d["graph_feat"][0])
        # ⚠️ padding 区必须置 0：lane_mask=0 表示「不参与道次损失」，
        #    用 1 填充会让 pad 出来的假节点把分母撑大、梯度被稀释。
        lmask.append(np.pad(d["lane_mask"], (0, pad)))
        days_t.append(d["days"])
        qual_t.append(d["quality"])
    T = lambda a: torch.from_numpy(np.asarray(a, dtype=np.float32)).to(device)
    return (T(nf), T(abt), T(tm), T(mk), T(pri), T(slot), T(fmt).long(), T(gf),
            T(lmask), T(days_t), T(qual_t))


def save_best(model: torch.nn.Module, stats: Dict[str, object],
              meta: Dict[str, object]) -> None:
    """把当前最优权重**立刻**落盘。

    原子性靠「先写 .tmp 再 os.replace」：被杀时不会留下半截文件，
    下次启动的 ``--resume`` 也永远拿到一份完整可用的权重。

    ⚠️ 权重里必须带上 ``meta``（hidden / steps）：这两个值**决定模型结构**，
    原来它们只硬编码在训练与导出两个脚本里，训练用 ``--steps 4`` 而导出脚本
    写死 ``STEPS = 8`` 时，``load_state_dict`` 会 shape 不匹配直接炸，
    而这类报错在服务端表现为「模型加载失败 → 静默回退规则」，极难定位。
    把结构元信息焊进 checkpoint，导出脚本读权重而不是猜常量。
    """
    os.makedirs(MODEL_DIR, exist_ok=True)
    tmp = os.path.join(MODEL_DIR, "super_moe.pt.tmp")
    path = os.path.join(MODEL_DIR, "super_moe.pt")
    torch.save({"state_dict": {k: v.detach().cpu() for k, v in model.state_dict().items()},
                "meta": meta}, tmp)
    if os.path.exists(path):
        backup_before_overwrite(path, f"super-moe-{os.path.splitext(os.path.basename(path))[0]}")
    os.replace(tmp, path)
    # ⚠️ 每轮都在备份 → 32 轮就是 380MB 垃圾（实测堆到 30 个 bak / 276MB）。
    #    只留最近 3 份：够了（真要回退，前一份 + 当前就足以对照），其余清掉。
    keep = 3
    baks = sorted((f for f in os.listdir(MODEL_DIR) if ".bak.super-moe" in f),
                  key=lambda f: os.path.getmtime(os.path.join(MODEL_DIR, f)),
                  reverse=True)
    for f in baks[keep:]:
        os.remove(os.path.join(MODEL_DIR, f))
    with open(os.path.join(MODEL_DIR, "super_moe_stats.json"), "w", encoding="utf-8") as fh:
        json.dump(stats, fh, ensure_ascii=False, indent=2)


from sports_ai.budget import BASE_HIDDEN, budget_for, report_budget

BASE_EPOCHS = 24       # 基线档（hidden=128 / expert_depth=1 / n_global=2）的经验预算
BASE_DEPTH_UNITS = 4   # expert_depth + n_global + 1 在基线档上的取值
BASE_PATIENCE = 8


def depth_units_of(expert_depth: int, n_global: int) -> int:
    """super_moe 的深度口径：专家内堆叠 + 主干深层块 + 1（输入投影）。"""
    return max(1, expert_depth) + max(1, n_global) + 1


def budget_for_depth(hidden: int, expert_depth: int, n_global: int) -> tuple:
    """按网络深度推导 (建议 epochs, 建议 patience)。

    ⚠️ 这条机制是踩坑换来的：加深网络却不同步加训练预算时，val 指标会**看起来**变差，
    极易被误读成「深层架构不如浅层」，于是白改架构。实际是没训够。
    公式与告警文案见 sports_ai.budget（三个训练脚本共用唯一真相源）。
    """
    return budget_for(hidden, BASE_HIDDEN, depth_units_of(expert_depth, n_global),
                      BASE_DEPTH_UNITS, BASE_EPOCHS, BASE_PATIENCE)


def main() -> None:
    ap = argparse.ArgumentParser(description="训练统一编排超级模型 SuperScheduleMoE")
    ap.add_argument("--samples", type=int, default=1200)
    ap.add_argument("--epochs", type=int, default=0,
                    help="训练轮数；0=按网络深度自动推导（深层需要更多轮，见 budget_for_depth）")
    ap.add_argument("--batch", type=int, default=8)
    # ⚠️ 下面两个**决定模型结构**：hidden/steps/expert_depth/n_global 必须与
    #    导出脚本读到的 meta 完全一致，否则 load_state_dict shape 不匹配 →
    #    服务端「模型加载失败 → 静默回退规则」。所以它们全部焊进 checkpoint。
    ap.add_argument("--hidden", type=int, default=192)
    ap.add_argument("--steps", type=int, default=8)
    ap.add_argument("--n-nested", type=int, default=4,
                    help="嵌套 MoE 专家个数（放在能力专家区；0 = 全同构）")
    ap.add_argument("--nest-layers", type=int, default=6,
                    help="嵌套专家内部 SpecialistMoE 的主干层数")
    ap.add_argument("--nest-experts", type=int, default=4,
                    help="嵌套专家内部 SpecialistMoE 的多架构专家数")
    ap.add_argument("--expert-depth", type=int, default=6,
                    help="每个专家内部堆叠的消息传递层数（1=旧版单层）")
    ap.add_argument("--n-global", type=int, default=3,
                    help="MoE 之后的主干深层推理块数")
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--w-lb", type=float, default=0.01, help="负载均衡损失权重")
    ap.add_argument("--seed", type=int, default=20261007)
    ap.add_argument("--label-search", action="store_true",
                    help="用搜索精修 priority 标签（RSI 第一步）；搜索没赢则保留原标签")
    ap.add_argument("--resume", action="store_true",
                    help="从 models/super_moe.pt 续训（分段跑长任务时用）")
    ap.add_argument("--patience", type=int, default=-1,
                    help="val 连续多少轮不下降就早停；-1=按深度自动；0=不早停")
    add_device_arg(ap)
    args = ap.parse_args()

    want_epochs, want_patience = budget_for_depth(
        args.hidden, args.expert_depth, args.n_global)
    epochs = args.epochs if args.epochs > 0 else want_epochs
    patience = args.patience if args.patience >= 0 else want_patience
    report_budget("super_moe", hidden=args.hidden, base_hidden=BASE_HIDDEN,
                  depth_units=depth_units_of(args.expert_depth, args.n_global),
                  base_depth_units=BASE_DEPTH_UNITS, base_epochs=BASE_EPOCHS,
                  base_patience=BASE_PATIENCE, epochs=epochs, patience=patience)
    device = resolve_device(args.device)
    seed_all(0)
    print(f"[device] 训练设备: {describe_device(device)}")

    print(f"[data] 生成 {args.samples} 个五档混合场景…"
          + ("（标签：搜索精修 / RSI）" if args.label_search else "（标签：贪心启发式）"))
    data = make_dataset(args.samples, args.seed, label_search=args.label_search)
    if len(data) < 40:
        print("[data] 有效样本不足，终止")
        return
    rng = np.random.default_rng(args.seed)
    order = rng.permutation(len(data))
    n_tr = max(1, int(len(data) * 0.8))
    tr = [data[int(i)] for i in order[:n_tr]]
    va = [data[int(i)] for i in order[n_tr:]] or tr[:8]
    tiers = {}
    for d in data:
        tiers[d["tier"]] = tiers.get(d["tier"], 0) + 1
    print(f"[data] 训练 {len(tr)} / 验证 {len(va)}，档位分布 {tiers}")

    model = SuperScheduleMoE(node_feat=NODE_FEAT_DIM, hidden=args.hidden,
                             steps=args.steps, expert_depth=args.expert_depth,
                             n_global=args.n_global, n_nested=args.n_nested,
                             nest_layers=args.nest_layers,
                             nest_experts=args.nest_experts).to(device)
    print(f"[model] 参数量 {sum(q.numel() for q in model.parameters()):,}")
    print(f"[model] 专家池构成 {model.moe.expert_kinds()}")
    if args.resume and os.path.exists(MODEL_DIR + "/super_moe.pt"):
        raw = torch.load(MODEL_DIR + "/super_moe.pt", map_location=device)
        # 兼容两种落盘格式：新格式 {"state_dict":…, "meta":…}，旧格式即裸 state_dict
        model.load_state_dict(raw["state_dict"] if isinstance(raw, dict) and "state_dict" in raw else raw)
        print(f"[model] 已从 super_moe.pt 续训（hidden={args.hidden} steps={args.steps} 需一致）")
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-5)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=epochs)

    best = float("inf")
    best_state = None
    best_stats: Dict[str, object] = {}
    stale = 0
    for epoch in range(epochs):
        model.train()
        tot, nb = 0.0, 0
        idx = np.arange(len(tr))
        rng.shuffle(idx)
        for k in range(0, len(idx) - args.batch + 1, args.batch):
            sel = [int(i) for i in idx[k:k + args.batch]]
            if not sel:
                continue
            (nf, abt, tm, mk, pri, slot, fmt, gf,
             lmask, days_t, qual_t) = to_tensors(tr, sel, device)
            loss, parts = model.training_loss(
                pri, slot, nf, abt, tm, mk, fmt, w_lb=args.w_lb, graph_feat=gf,
                lane_mask=lmask, days_target=days_t, quality_target=qual_t)
            opt.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            tot += float(loss.item())
            nb += 1
        sched.step()

        model.eval()
        with torch.no_grad():
            vs, vp = [], []
            for k in range(0, len(va), max(1, args.batch)):
                sel = list(range(k, min(len(va), k + max(1, args.batch))))
                if not sel:
                    continue
                (nf, abt, tm, mk, pri, slot, fmt, gf,
                 lmask, days_t, qual_t) = to_tensors(va, sel, device)
                loss, parts = model.training_loss(
                    pri, slot, nf, abt, tm, mk, fmt, w_lb=args.w_lb, graph_feat=gf,
                    lane_mask=lmask, days_target=days_t, quality_target=qual_t)
                vs.append(float(loss.item()))
                vp.append(parts)
        vloss = float(np.mean(vs)) if vs else float("inf")
        usage = model.expert_usage()
        usage_min = min(usage.values()) if usage else 0.0
        def _m(key):
            vals = [p[key] for p in vp if key in p]
            return float(np.mean(vals)) if vals else float("nan")
        print(f"epoch {epoch:3d}  train={tot / max(1, nb):.5f}  val={vloss:.5f}  "
              f"pri_mse={_m('priority_mse'):.5f}  "
              f"slot_mse={_m('slot_mse'):.5f}  "
              f"lane_mse={_m('lane_mse'):.5f}  days_mse={_m('days_mse'):.4f}  "
              f"qual_mse={_m('quality_mse'):.5f}  "
              f"专家最低使用率={usage_min:.4f}")
        if vloss < best:
            best = vloss
            best_stats = {
                "val_loss": vloss,
                "val_priority_mse": float(np.mean([p["priority_mse"] for p in vp])),
                "val_slot_mse": float(np.mean([p["slot_mse"] for p in vp])),
                "expert_usage": usage,
                "min_expert_usage": usage_min,
                "route_entropy": round(model.route_entropy(), 5),
                "epoch": epoch,
            }
            best_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}
            # ⚠️ 立刻落盘：训练随时可能被后台任务回收杀掉，只在最后写盘 = 一断全白跑
            save_best(model, best_stats, {
                "hidden": args.hidden, "steps": args.steps, "samples": args.samples,
                "expert_depth": args.expert_depth, "n_global": args.n_global,
                # ⚠️ 嵌套结构参数必须一并写进 meta：导出脚本靠它重建模型，
                #    漏掉就会 load_state_dict 形状不匹配 → 服务端静默回退规则。
                "n_nested": args.n_nested, "nest_layers": args.nest_layers,
                "nest_experts": args.nest_experts, "n_steps": model.n_steps,
                # 预算可诊断性：只看到 val_loss 时无法判断「训够了没有」，
                # 把实际轮数与建议预算一并写进 meta，一眼就能看出是不是没训够。
                "epochs_run": epoch + 1, "budget_epochs": want_epochs,
                "budget_satisfied": bool(epoch + 1 >= want_epochs * 0.6),
            })
            stale = 0
        else:
            stale += 1
            print(f"[save] 未刷新最优（连续 {stale} 轮），暂不写盘")
        if patience and stale >= patience:
            print(f"[stop] val 连续 {patience} 轮未下降，早停（本段已跑 {epoch + 1} 轮）")
            break

    if best_state is not None:
        model.load_state_dict(best_state)
    print(f"best {json.dumps(best_stats, ensure_ascii=False)}")
    print(f"→ 已落盘 models/super_moe.pt（best in epoch {best_stats.get('epoch')}）")


if __name__ == "__main__":
    main()
