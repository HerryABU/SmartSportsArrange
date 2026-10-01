"""推理时的自对抗精修（test-time adversarial refinement）。

与「训练时对抗」不同：这里**冻结生成器 G 与判别器 D 的参数**，在推理时对潜在噪声 ``z``
做梯度上升（``z`` 是需要优化的变量），让生成的方案同时满足两件事：

1. **骗过判别器**——最大化 ``D(方案)``，即让方案「看起来像真实可行解」；
2. **满足组合约束**——最小化冲突期望 / 禁止列表 / 槽容量。

也就是说：**G 与 D 在生成推导过程中继续博弈**，而不是训练一次就固定下来。
训练时的对抗决定「模型长什么样」，推理时的自对抗决定「这一次生成的方案怎么逼出来」——
这正是用户要求的「生成推导中也要自对抗」。

用法（Python，用于验证 / 蒸馏）：
    refiner = AdversarialRefiner(G, D)
    slots, before, after = refiner.refine(nf, adj, mask, forbid)
"""

from __future__ import annotations

import torch

from .scheme import MAX_SLOTS, combination_loss


def hard_conflict(logits: torch.Tensor, adj: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
    """硬化后的真实残余冲突（按边数归一化）。返回 [B]。"""
    hard = logits.argmax(dim=-1)
    same = (hard.unsqueeze(2) == hard.unsqueeze(1)).float()
    am = adj * mask.unsqueeze(-1) * mask.unsqueeze(1)
    return (same * am).sum(dim=(1, 2)) / am.sum(dim=(1, 2)).clamp(min=1.0)


def _onehot(logits: torch.Tensor) -> torch.Tensor:
    idx = logits.argmax(dim=-1, keepdim=True)
    return torch.zeros_like(logits).scatter_(-1, idx, 1.0)


class AdversarialRefiner:
    """推理时对抗精修器：冻结 G/D，在潜在空间做「骗判别器 + 降约束」的梯度上升。"""

    def __init__(self, generator, discriminator, device: str = "cpu", noise_dim: int = 8):
        self.G = generator.to(device).eval()
        self.D = discriminator.to(device).eval()
        self.device = device
        self.noise_dim = noise_dim
        for p in self.G.parameters():
            p.requires_grad_(False)
        for p in self.D.parameters():
            p.requires_grad_(False)

    @torch.no_grad()
    def _evaluate(self, nf, at, mt, z, fb):
        logits = self.G.logits_of(nf, at, mt, z, fb)
        conflict = hard_conflict(logits, at, mt)
        d_score = self.D(nf, at, mt, _onehot(logits)).squeeze(-1)
        return logits, conflict, d_score

    def refine(
        self,
        node_feat: torch.Tensor,
        adj: torch.Tensor,
        mask: torch.Tensor,
        forbid: torch.Tensor | None = None,
        capacity: float | None = None,
        steps: int = 80,
        lr: float = 0.08,
        lambda_conf: float = 15.0,
        restarts: int = 3,
        w_forbid: float = 1.0,
        w_capacity: float = 0.1,
    ):
        """返回 (best_logits, info)：
        - best_logits [B,N,K]：精修后的方案 logits（取 argmax 得时间槽）；
        - info：``{"conflict_before", "conflict_after", "d_before", "d_after"}``（各 [B]）。
        """
        nf = node_feat.to(self.device)
        at = adj.to(self.device)
        mt = mask.to(self.device)
        fb = forbid.to(self.device) if forbid is not None else None
        b, n = nf.shape[0], nf.shape[1]
        cap = capacity if capacity is not None else max(1.0, float(mt.sum(dim=1).mean()) / MAX_SLOTS * 1.5)

        # 单次生成（基线）
        with torch.no_grad():
            z0 = torch.zeros(b, n, self.noise_dim, device=self.device)
            base_logits, base_conf, base_d = self._evaluate(nf, at, mt, z0, fb)

        best_logits = base_logits.clone()
        best_conf = base_conf.clone()

        for _ in range(restarts):
            # 每轮从随机噪声出发，在潜在空间做对抗梯度上升
            z = torch.randn(b, n, self.noise_dim, device=self.device, requires_grad=True)
            opt = torch.optim.Adam([z], lr=lr)
            for _ in range(steps):
                # hard=False 才能对 z 求导（软方案）
                _, scheme = self.G(nf, at, mt, z, fb, hard=False)
                d = self.D(nf, at, mt, scheme).squeeze(-1)
                loss_adv = -d.mean()                                   # 骗过判别器（最大化 D）
                loss_conf, _ = combination_loss(
                    scheme, at, mt, fb, capacity=cap,
                    w_conflict=1.0, w_forbid=w_forbid, w_capacity=w_capacity)
                loss = loss_adv + lambda_conf * loss_conf              # 对抗 + 组合约束
                opt.zero_grad()
                loss.backward()
                opt.step()

            with torch.no_grad():
                logits, conf, _ = self._evaluate(nf, at, mt, z, fb)
                # 只接受「冲突不升」的更优候选（避免对抗项把冲突带高）
                improved = conf < best_conf
                best_logits[improved] = logits[improved]
                best_conf[improved] = conf[improved]

        with torch.no_grad():
            conf_after = hard_conflict(best_logits, at, mt)
            d_after = self.D(nf, at, mt, _onehot(best_logits)).squeeze(-1)
        info = {
            "conflict_before": base_conf.detach().cpu(),
            "conflict_after": conf_after.detach().cpu(),
            "d_before": base_d.detach().cpu(),
            "d_after": d_after.detach().cpu(),
        }
        return best_logits.detach(), info
