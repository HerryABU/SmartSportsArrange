"""把**已训练的专项 MoE 小模型**接进主 MoE 当专家（真正的「混合体」）。

## 为什么能直接接

小模型内部都是「编码投影 → 多架构专家池 + 层次门控 → 深度主干 → 输出头」，
而 **专家池 / 门控 / 主干只跟 `hidden` 有关，与输入维无关**。所以要接进主 MoE，
不需要动主体：只加两片投影

    主 MoE 的 192 维 --bridge--> 小模型的输入空间 --[ 继承的主体 ]--> out --> 192 维

主体权重（参数的 95% 以上）**原样继承**，真正新训的只有 bridge/out。
这就是「主 MoE 是其他 MoE 的混合体」的可落地形态 —— 而不是把一堆
互不兼容的输入契约硬拼在一起。

## 三类小模型，三种接法（判据来自权重本身，不靠人记）

======================================  ===================================
``UpgradedMoE``                          有 ``predictor.*``（后续步骤头）
  主体 = experts + router + trunk        bridge 投到该模型的 ``hidden``
  原 ``legacy`` 吃**它自己的原始特征**，    → 只跑「专家池 + 主干」这一支
  主 MoE 给不出来 → 不接
  （lane_advisor / conflict_gnn / referee_gnn / teacher_gnn /
    constraint_gnn / algorithm_selector / forecast_* /
    heat_stagger / slot_split 属此类）

``MoEEncoder``                           无 ``predictor.*``，但**有 ``legacy.*``**
  主体 = legacy + experts + router + trunk  bridge 投到该模型的 **in_dim**
  ``forward(node_feat, adj, mask)`` 是       → 整条编码器（含 legacy）都能跑，
  完整可复用的编码器                          继承得更满
  （scheme_generator / discriminator / refiner / diffusion 属此类）

``MoERepr``                              **两者都无**（只有 experts/router/trunk）
  主体 = experts + router + trunk + norm    bridge 投到 ``hidden``
  ``forward(h, adj, mask)`` **直接吃        → 与 ``UpgradedMoE`` 同目标，
  hidden 维**，本来就不含 legacy            但主体按 MoERepr 自己的结构建
  （tournament_gnn 属此类）
======================================  ===================================

⚠️ 判据**必须三分类**。曾按 ``predictor.*`` 二分类，于是 ``MoERepr`` 落到
``MoEEncoder`` 分支 → 去要 ``in_proj.weight``/``legacy.*`` → 该模型**静默缺席**专家池
（``build_ensemble`` 只打印一行 ``[skip]``，不看日志就发现不了）。

## 结构不靠猜，靠推导

旧 ckpt 里只有 `state_dict`，所以要重建结构必须能**唯一推导**：

- ``hidden``：``router.coarse.weight`` 的第 1 维（**不是**从 ``in_proj`` 取 ——
  ``MoERepr`` 根本没有 ``in_proj``）
- ``in_dim``：``in_proj.weight`` 的第 1 维；MoERepr 没有 → 记 ``-1``（对它无意义）
- ``n_experts`` / ``n_groups``：``router.fine`` / ``router.coarse`` 的第 0 维
- ``n_layers``：``trunk.<i>.`` 的最大下标 + 1
- 每个专家的架构：``EXPERT_CYCLE[i % len(EXPERT_CYCLE)]``（轮转规则）
- key 的**前缀**（``moe.`` / ``enc.`` / ``model.enc.`` / 空）：靠 ``endswith("router.fine.weight")``
  反查，所以不关心外面还包了几层

任何一条对不上都会在 `load_state_dict` 报出来（形状不匹配 = 显式失败，
不会静默错位）；`--verify` 会把继承率打出来，一眼看出「接上了没、接得满不满」。

## 用法

    python -m sports_ai.nn.ensemble --verify          # 逐个试接 + 真跑前向
"""

from __future__ import annotations

import argparse
import os
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple

import torch
import torch.nn as nn

from sports_ai.nn.blocks import (EXPERT_CYCLE, HierarchicalRouter,
                                 ResidualMLPBlock, build_expert)

MODEL_DIR = os.path.join(os.path.dirname(os.path.dirname(
    os.path.dirname(os.path.abspath(__file__)))), "models")


@dataclass
class ModelSpec:
    """一个候选专家：一个小模型 + 它在编排里的语义角色。"""

    name: str
    role: str


#: 全部专项 MoE 小模型 —— 它们将成为主 MoE 的专家池成员。
#: ⚠️ 顺序即专家在池中的顺序，**不要随意重排**（会改变已训 ckpt 的权重对应）。
ENSEMBLE_SPECS: Tuple[ModelSpec, ...] = (
    ModelSpec("lane_advisor", "道次派遣优先级"),
    ModelSpec("conflict_gnn", "冲突簇着色优先级"),
    ModelSpec("constraint_gnn", "约束紧度评估"),
    ModelSpec("referee_gnn", "裁判派遣"),
    ModelSpec("teacher_gnn", "班主任派遣"),
    ModelSpec("tournament_gnn", "球类赛制 / 种子 / 公平性"),
    ModelSpec("algorithm_selector", "硬解 vs 取消路径"),
    ModelSpec("heat_stagger_advisor", "组次顺序错开"),
    ModelSpec("slot_split_advisor", "跨时段拆分先拆谁"),
    ModelSpec("forecast_direct", "未来 H 步时间槽（直接多步）"),
    ModelSpec("forecast_mimo", "未来 H 步时间槽（多输入多输出）"),
    ModelSpec("scheme_generator", "方案生成"),
    ModelSpec("scheme_discriminator", "方案评判"),
    ModelSpec("scheme_refiner", "方案精修"),
    ModelSpec("scheme_diffusion", "扩散式方案生成"),
)


def ckpt_path(name: str) -> str:
    """权重路径：升级后的权重优先叫 ``<name>.moe.pt``，其次才是 ``<name>.pt``。

    ⚠️ 回退到 `.pt` 是必须的：生成式四模型与两个微调模型是**就地换编码器**
    （权重仍写原名），而 `lane_advisor.pt` 那种旧文件也存在 —— 两者靠
    `infer_layout` 区分：旧版没有 `router.*`，会**显式报错**而不是静默接错。
    """
    for cand in (f"{name}.moe.pt", f"{name}.pt"):
        p = os.path.join(MODEL_DIR, cand)
        if os.path.exists(p):
            return p
    raise FileNotFoundError(f"{name}: 缺少权重（{name}.moe.pt / {name}.pt 都不在）")


def strip_prefix(sd: Dict[str, torch.Tensor]) -> Tuple[Dict[str, torch.Tensor], str]:
    """剥掉权重最外层的前缀（``moe.`` / ``enc.`` / ``model.enc.`` / 空）。

    用 ``endswith("router.fine.weight")`` **反查**前缀，所以不管外面包了几层、
    叫什么都行 —— 这比维护一张「哪个模型用什么前缀」的对照表可靠得多
    （对照表会随代码改名而失效，而且失效时是静默的）。
    """
    prefix = None
    for k in sd:
        if k.endswith("router.fine.weight"):
            prefix = k[: -len("router.fine.weight")]
            break
    if prefix is None:
        raise ValueError("权重里找不到 router.fine.weight —— 不是本项目升级后的 MoE 权重")
    if not prefix:
        return dict(sd), ""
    return ({k[len(prefix):]: v for k, v in sd.items() if k.startswith(prefix)}, prefix)


def infer_layout(sd: Dict[str, torch.Tensor]) -> Tuple[int, int, int, int, int]:
    """从（已剥前缀的）state_dict 推导 (in_dim, hidden, n_experts, n_groups, n_layers)。

    ⚠️ 一律从**形状**推导：形状是权重自带的客观事实，而任何「约定值」都会随
    代码改动漂移 —— 那正是「结构对不上却没人发现」的来源。
    """
    if "router.fine.weight" not in sd or "router.coarse.weight" not in sd:
        raise ValueError("缺少 router.fine / router.coarse")
    # hidden 一律取 router 的第 1 维 —— 它比 in_proj 更基本：``MoERepr``（就地增强表征）
    # **根本没有 in_proj**（它直接吃 hidden 维输入）。把 in_proj 当必要条件会把它
    # 判成「损坏权重」而**静默跳过**（`build_ensemble` 只打印 [skip]）。
    hidden = int(sd["router.coarse.weight"].shape[1])
    # in_proj 只有「吃原始特征」的那两类才有（UpgradedMoE / MoEEncoder）；
    # MoERepr 没有 → in_dim 对它无意义，置 -1 以便调用方据此分流。
    in_dim = int(sd["in_proj.weight"].shape[1]) if "in_proj.weight" in sd else -1
    n_experts = int(sd["router.fine.weight"].shape[0])
    n_groups = int(sd["router.coarse.weight"].shape[0])
    idx = [int(k.split(".")[1]) for k in sd if k.startswith("trunk.")]
    n_layers = (max(idx) + 1) if idx else 0
    return in_dim, hidden, n_experts, n_groups, n_layers


def load_sd(name: str) -> Tuple[Dict[str, torch.Tensor], str]:
    path = ckpt_path(name)
    sd = torch.load(path, map_location="cpu", weights_only=False)
    if isinstance(sd, dict) and "state_dict" in sd:
        sd = sd["state_dict"]
    return strip_prefix(sd)


class TrainedMoEExpert(nn.Module):
    """把一个已训练的小模型当作主 MoE 的一个专家。

    :param dst_hidden: 主 MoE 的 hidden 维（桥接的输入侧）。
    :param freeze:     True → 只训 bridge/out，主体冻结。
                       在「能力已经是好的、只差接进来」时更稳：主 MoE 的辅助任务
                       会把主体往它自己的目标上拉，可能把专项能力拉坏。
    """

    def __init__(self, spec: ModelSpec, dst_hidden: int = 192,
                 freeze: bool = True, dropout: float = 0.0, device: str = "cpu"):
        super().__init__()
        sd, prefix = load_sd(spec.name)
        in_dim, hidden, n_experts, n_groups, n_layers = infer_layout(sd)
        # 判据取自权重本身 —— **三类**必须分清，二分类会把第三类悄悄归错：
        #   ① 有 `predictor.*`          → UpgradedMoE 适配器（body = 专家池+主干）
        #   ② 无 predictor、有 `legacy.*` → MoEEncoder（整条编码器，含 legacy 共享专家）
        #   ③ 两者都无                    → **MoERepr**（就地增强表征，如 tournament_gnn）
        # ⚠️ 曾只按 `predictor.*` 二分类，于是 MoERepr 被当成 MoEEncoder →
        # 去要 `in_proj.weight`/`legacy.*` → 判失败 → 该模型**静默缺席**专家池。
        self.kind = ("upgraded" if any(k.startswith("predictor.") for k in sd)
                     else "encoder" if any(k.startswith("legacy.") for k in sd)
                     else "repr")
        #: kind 为 encoder / repr 时主体是**整块子模块**，直接用它的 forward。
        self._body_delegates = self.kind in ("encoder", "repr")

        self.name = spec.name
        self.role = spec.role
        self.src_hidden = hidden
        self.src_in_dim = in_dim
        self.n_experts = n_experts
        self.prefix = prefix

        if self.kind == "repr":
            # MoERepr.forward(h, adj, mask) 直接吃 hidden 维 → bridge 投到 hidden，
            # 与 "upgraded" 目标一致，但主体要按 MoERepr 自己的结构建
            # （注意它的 LayerNorm 叫 `norm`，不是 `in_norm`/`out_norm`）。
            from sports_ai.nn.moe_encoder import MoERepr
            self.bridge = nn.Linear(dst_hidden, hidden)
            self.body = MoERepr(hidden, n_layers=n_layers, n_experts=n_experts,
                                n_groups=n_groups, dropout=dropout)
            miss, unexp = self.body.load_state_dict(sd, strict=False)
            body_missing = [k for k in miss if not k.startswith("predictor.")]
            if body_missing:
                raise RuntimeError(f"{spec.name}: MoERepr 权重缺失 {body_missing[:5]}")
            if unexp:
                raise RuntimeError(f"{spec.name}: 出现权重里没有的层 {unexp[:5]}")
        elif self.kind == "encoder":
            # 整条编码器（含 legacy 共享专家）都能吃 hidden 维特征 → 继承得最满
            from sports_ai.nn.moe_encoder import MoEEncoder
            self.bridge = nn.Linear(dst_hidden, in_dim)
            self.body = MoEEncoder(in_dim, hidden, n_layers=n_layers,
                                   n_experts=n_experts, n_groups=n_groups,
                                   dropout=dropout)
            miss, unexp = self.body.load_state_dict(sd, strict=False)
            body_missing = [k for k in miss if not k.startswith("predictor.")]
            if body_missing:
                raise RuntimeError(f"{spec.name}: 编码器权重缺失 {body_missing[:5]}")
        else:
            self.bridge = nn.Linear(dst_hidden, hidden)
            self.experts = nn.ModuleList([
                build_expert(EXPERT_CYCLE[i % len(EXPERT_CYCLE)], hidden, 1, dropout)
                for i in range(n_experts)])
            self.router = HierarchicalRouter(n_experts, hidden, n_groups=n_groups,
                                             top_k=2, n_shared=0, warmup_steps=0)
            self.trunk = nn.ModuleList([ResidualMLPBlock(hidden, dropout=dropout)
                                        for _ in range(max(1, n_layers))])
            self.in_norm = nn.LayerNorm(hidden)
            self.out_norm = nn.LayerNorm(hidden)
            body = {k: v for k, v in sd.items()
                    if k.startswith(("experts.", "router.", "trunk.",
                                     "in_norm.", "out_norm."))}
            miss, unexp = self.load_state_dict(body, strict=False)
            body_missing = [k for k in miss if k.startswith(("experts.", "router.", "trunk."))]
            if body_missing:
                raise RuntimeError(f"{spec.name}: 主体权重缺失 {body_missing[:5]} —— 结构推导有误")
            if unexp:
                raise RuntimeError(f"{spec.name}: 出现权重里没有的层 {unexp[:5]}")
        self.out = nn.Linear(hidden, dst_hidden)

        self.inherited = int(sum(v.numel() for v in sd.values()))
        self.total = int(sum(p.numel() for p in self.parameters()))

        if freeze:
            for mod in ([self.body] if self._body_delegates
                        else [self.experts, self.router, self.trunk,
                              self.in_norm, self.out_norm]):
                for p in mod.parameters():
                    p.requires_grad_(False)
        self.to(device)

    def forward(self, h: torch.Tensor, adj: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
        """``h[B,N,dst_hidden]`` + ``adj[B,N,N]`` + ``mask[B,N]`` → ``[B,N,dst_hidden]``。"""
        feat = self.bridge(h)
        if self._body_delegates:
            # MoEEncoder.forward(node_feat, adj, mask) / MoERepr.forward(h, adj, mask)
            # → 两者都返回 [B,N,hidden]
            x = self.body(feat, adj, mask)
        else:
            # 对照 UpgradedMoE.forward 的中间段：没有 legacy（它吃的是该小模型
            # 自己的原始特征，主 MoE 给不出来），只继承「专家池 + 主干」这一支。
            x = self.in_norm(feat)
            w, _, _ = self.router(x)
            acc = torch.zeros_like(x)
            for i, ex in enumerate(self.experts):
                acc = acc + w[..., i].unsqueeze(-1) * ex(x, adj, mask)
            x = self.in_norm(x + acc)
            for blk in self.trunk:
                x = blk(x)
            x = self.out_norm(x)
        return self.out(x)

    @property
    def depth(self) -> int:
        return (getattr(self.body, "depth", lambda: 0)() + 1 if self._body_delegates
                else len(self.trunk) + 2)

    def load_balance_loss(self) -> torch.Tensor:
        target = self.body if self._body_delegates else self
        return target.router.load_balance_loss()

    def update_router_bias(self, rate: float = 0.02):
        target = self.body if self._body_delegates else self
        target.router.update_bias(rate)

    def expert_usage(self) -> dict:
        target = self.body if self._body_delegates else self
        return target.router.expert_usage()

    def describe(self) -> str:
        trainable = sum(p.numel() for p in self.parameters() if p.requires_grad)
        pref = f"前缀'{self.prefix}'" if self.prefix else "无前缀"
        return (f"{self.name:22s} [{self.kind:8s} {pref:12s}] {self.role:18s} "
                f"in={self.src_in_dim} H={self.src_hidden} E={self.n_experts} "
                f"深度={self.depth} | 继承 {self.inherited/1e6:.2f}M / 总 {self.total/1e6:.2f}M "
                f"（可训 {trainable/1e6:.2f}M）")


def build_ensemble(names: Optional[List[str]] = None, dst_hidden: int = 192,
                   freeze: bool = True, dropout: float = 0.0,
                   device: str = "cpu") -> List[TrainedMoEExpert]:
    """按名字构造专家列表；``names=None`` 表示全部。缺权重/结构不符会明确报告并跳过。"""
    specs = [s for s in ENSEMBLE_SPECS if (names is None or s.name in names)]
    out: List[TrainedMoEExpert] = []
    for spec in specs:
        try:
            out.append(TrainedMoEExpert(spec, dst_hidden=dst_hidden, freeze=freeze,
                                        dropout=dropout, device=device))
        except (FileNotFoundError, ValueError, RuntimeError) as exc:
            print(f"[skip] {exc}")
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--verify", action="store_true", help="逐个试接并真跑一次前向")
    ap.add_argument("--dst-hidden", type=int, default=192, help="主 MoE 的 hidden 维")
    ap.add_argument("--no-freeze", action="store_true", help="主体也参与训练")
    args = ap.parse_args()

    experts = build_ensemble(dst_hidden=args.dst_hidden, freeze=not args.no_freeze)
    print(f"共接入 {len(experts)} / {len(ENSEMBLE_SPECS)} 个专项模型：")
    for e in experts:
        print("  " + e.describe())
    if len(experts) < len(ENSEMBLE_SPECS):
        print("  （其余缺权重或结构不符，见上面的 [skip] 行）")

    if args.verify:
        b, n, d = 2, 7, args.dst_hidden
        h = torch.randn(b, n, d)
        adj = (torch.rand(b, n, n) > 0.7).float()
        mask = torch.ones(b, n)
        for e in experts:
            y = e(h, adj, mask)
            assert y.shape == (b, n, d), f"{e.name}: 输出 {tuple(y.shape)} ≠ {(b, n, d)}"
            assert torch.isfinite(y).all(), f"{e.name}: 输出含 NaN/Inf"
        print(f"[verify] {len(experts)} 个专家前向通过（{tuple(h.shape)} → 同形）")


if __name__ == "__main__":
    main()
