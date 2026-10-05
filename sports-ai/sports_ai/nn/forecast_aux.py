"""给**任意**专项模型挂上「未来 H 步时间槽」预测能力（可插拔，统一实现）。

## 为什么要抽出来

`UpgradedMoE`（统一训练器那 9 个模型）已经吃到这条分支；但
`tournament_gnn` / `heat_stagger_advisor` / `slot_split_advisor`
**各有自己的模型类与训练脚本**，不在登记表里。三处各写一遍必然走样，
所以抽成一个可插拔模块：模型里只多一个 `self.aux = ForecastAux(hidden)`，
训练循环里只多一行 `aux_loss(...)`。

## 为什么预测要靠「真序列输入」

主任务的特征里**没有时间步语义**（节点/候选特征描述的是单元属性，不是「第几步」），
拿它预测未来排程等于让模型猜 —— 信息不足只会训出常数输出。
所以这里给一条真正的序列输入（与 `forecast_*` 同一任务定义、同一份数据源），
再让它过**共享主干**，于是预测能力会回流到主任务的表征里。

## 用法

    # 模型 __init__
    self.aux = ForecastAux(hidden)

    # 训练循环（一次建好、循环采样；别每步重跑场景生成 + 贪心着色）
    data = AuxData(n=512, seed=0, device=device)
    sx, sy = data.sample(args.batch)
    loss = loss + aux_loss(model.aux, sx, sy, trunk_fn=lambda h: model.moe(h, adj=None))

⚠️ 导出时必须允许 `aux.*` 缺失（`strict=False` + 白名单校验）：预测分支**不进部署
契约**（导出只回主输出），但它的参数在 ckpt 里 —— 直接 strict=True 会报
"Unexpected key(s): aux.*" 而让导出失败（＝训了导不出）。
"""

from __future__ import annotations

from typing import Callable, Optional

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

from sports_ai.forecast.dataset import H_OUT, IN_DIM, make_dataset

#: 辅助损失权重。取小值：它是**表示学习**的辅助信号（逼 hidden 编码「未来会排到哪」），
#: 主任务仍是各自的原目标 —— 权重过大会把主能力带偏。
AUX_W = 0.1

#: 预生成的序列样本数（一次生成、循环采样）。
AUX_SAMPLES = 512


class ForecastAux(nn.Module):
    """预测分支：`[B,L,4]` 序列 → 未来 H 步时间槽 `[B,H]`。

    :param trunk_fn: 把 hidden 表征过一遍**共享主干**（如 ``model.moe``）。
                     传 None 就退化成一条独立的小分支 —— 那样预测能力**不会**
                     回流到主任务的表征，等于白加，所以实际都该传。
    """

    def __init__(self, hidden: int, seq_in: int = IN_DIM, h: int = H_OUT):
        super().__init__()
        self.seq_proj = nn.Linear(seq_in, hidden)
        self.head = nn.Linear(hidden, h)
        self.hidden = hidden

    def forward(self, x_seq: torch.Tensor,
                trunk_fn: Optional[Callable[[torch.Tensor], torch.Tensor]] = None,
                mask: Optional[torch.Tensor] = None) -> torch.Tensor:
        h = self.seq_proj(x_seq)                                  # [B,L,H]
        if trunk_fn is not None:
            h = trunk_fn(h)
        # 池化后出预测：未来 H 步排程是**实例级**判断，不是逐时间步判断
        if mask is not None:
            m = mask.unsqueeze(-1)
            pooled = (h * m).sum(dim=1) / m.sum(dim=1).clamp(min=1.0)
        else:
            pooled = h.mean(dim=1)
        return self.head(pooled)


class AuxData:
    """预生成一批序列样本，训练期循环采样。

    为什么不在每步现场生成：`make_dataset` 每条样本都要「生成场景 + 贪心着色」，
    那是主要开销；一次生成 512 条再采样，既保证 X/Y **严格同源**（不错配），
    又把开销摊到整个训练过程之外。
    """

    def __init__(self, n: int = AUX_SAMPLES, seed: int = 0, device: str = "cpu"):
        x, y, _ = make_dataset(n, seed)
        self.x = torch.from_numpy(np.asarray(x, dtype=np.float32)).to(device)
        self.y = torch.from_numpy(np.asarray(y, dtype=np.float32)).to(device)

    def sample(self, batch: int):
        k = min(batch, self.x.shape[0])
        idx = torch.randint(0, self.x.shape[0], (k,), device=self.x.device)
        return self.x[idx], self.y[idx]


def specialist_trunk(moe) -> Callable[[torch.Tensor], torch.Tensor]:
    """从 ``SpecialistMoE`` 里取出**只吃 hidden 维**的那一段，作为可共享主干。

    ⚠️ 为什么不能直接调 `moe.encode(h, mask)`：``encode`` 第一行是
    ``in_norm(in_proj(x))``，而 ``in_proj`` 吃的是**原始特征维**（如 6/8 维），
    把 hidden（128）喂进去会直接报
    ``mat1 and mat2 shapes cannot be multiplied (96x128 and 6x128)``。
    这里复用它的 ``router`` / ``experts`` / ``shared`` / ``trunk`` / ``out_norm``
    —— 这几段才是与输入维无关、值得共享的部分。

    邻接传 None：专家池内的图卷积对 None 有自环兜底（``encode`` 里的
    ``adj`` 也允许为 None）。
    """
    def fn(h: torch.Tensor) -> torch.Tensor:
        mask = torch.ones(h.shape[0], h.shape[1], device=h.device, dtype=h.dtype)
        weights, top_i, _ = moe.router(h)
        acc = torch.zeros_like(h)
        for e, ex in enumerate(moe.experts):
            acc = acc + weights[..., e].unsqueeze(-1) * ex(h, None, mask)
        out = acc
        for sh in moe.shared:
            out = out + sh(h)
        for blk in moe.trunk:
            out = blk(out)
        return out
    return fn


def encoder_trunk(enc) -> Callable[[torch.Tensor], torch.Tensor]:
    """从 ``MoEEncoder`` 里取出**只吃 hidden 维**的那段（router + experts + out_norm + trunk）。

    ⚠️ 不能直接调 ``enc(h, adj, mask)``：它的 ``in_proj`` 与 ``legacy`` 都吃
    **原始特征维**（生成式模型是 16 / 32），把 hidden 喂进去会直接形状不符。
    与 :func:`specialist_trunk` 的区别：``MoEEncoder`` 的共享专家是 ``legacy``
    （吃原始特征，接不了），结构里也没有 ``shared`` 列表。

    邻接传 None：专家池对 None 有自环兜底。
    """
    def fn(h: torch.Tensor) -> torch.Tensor:
        mask = torch.ones(h.shape[0], h.shape[1], device=h.device, dtype=h.dtype)
        weights, top_i, _ = enc.router(h)
        acc = torch.zeros_like(h)
        for e, ex in enumerate(enc.experts):
            acc = acc + weights[..., e].unsqueeze(-1) * ex(h, None, mask)
        out = enc.out_norm(h + acc)
        for blk in enc.trunk:
            out = blk(out)
        return out
    return fn


def aux_loss(aux: ForecastAux, x_seq: torch.Tensor, y: torch.Tensor,
             trunk_fn: Optional[Callable[[torch.Tensor], torch.Tensor]] = None,
             mask: Optional[torch.Tensor] = None, w: float = AUX_W) -> torch.Tensor:
    """辅助预测损失（已乘权重，直接加进总 loss）。"""
    return w * F.mse_loss(aux(x_seq, trunk_fn=trunk_fn, mask=mask), y)


def load_with_aux(model: nn.Module, sd: dict) -> None:
    """加载**带 aux 分支**的 ckpt：允许 ``aux.*`` 缺失，但其它 key 一个都不能少。

    导出脚本要用这个而不是 `strict=True`：预测分支不进部署契约（导出只回主输出），
    可它的参数在 ckpt 里 —— strict=True 会报 "Unexpected key(s): aux.*" 而导出失败。
    也不能简单用 `strict=False`：那会把真正的结构错误（少一层、维数不对）一起吞掉。
    """
    # 三类前缀都是「预测分支」的，两侧都放行：
    #   `aux.`            —— 可插拔模块挂在模型上的那套
    #   `seq_proj.` / `forecast_head.` —— `UpgradedMoE` 内置的那套
    # 两个方向都要放行：① ckpt 有而模型没建（导出时不挂）→ unexpected；
    #                    ② ckpt 没带（旧权重）而模型挂了 → missing。
    aux_prefixes = ("aux.", "seq_proj.", "forecast_head.")
    missing, unexpected = model.load_state_dict(sd, strict=False)
    real_missing = [k for k in missing if not k.startswith(aux_prefixes)]
    real_unexpected = [k for k in unexpected if not k.startswith(aux_prefixes)]
    if real_missing:
        raise SystemExit(f"权重缺失关键层 {real_missing[:5]} —— 结构不匹配，导出中止")
    if real_unexpected:
        raise SystemExit(f"出现权重里没有的层 {real_unexpected[:5]} —— 结构不匹配，导出中止")
