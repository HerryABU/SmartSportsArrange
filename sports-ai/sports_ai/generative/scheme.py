"""方案表示与**组合约束损失**（GenCO 式）。

方案 = 节点 → 时间槽的着色分配：
- 软方案 ``P``：``[B, N, K]``，每个节点在 K 个时间槽上的概率分布（softmax）；
- 硬方案 ``H``：one-hot，等价于「项目排在哪个时间槽」。

组合约束编码为可微损失（这正是 GenCO 的核心——把组合优化约束写进损失、与 GAN 生成损失
一起训练）：
1. **兼项冲突**：同色即同时进行 → 冲突期望 = Σ_{(i,j)∈E} Σ_k P[i,k]·P[j,k]；
2. **禁止列表**（行政时间保护）：某些节点不能用某些时间槽 → 概率质量落在禁用槽的惩罚；
3. **槽容量**：每个时间槽的期望负载不得超过容量。
"""

from __future__ import annotations

import torch
import torch.nn.functional as F

MAX_SLOTS = 16   # 固定时间槽数（ONNX 固定 shape；实际用前 K_actual 个）


def gumbel_scheme(logits: torch.Tensor, tau: float = 1.0, hard: bool = True) -> torch.Tensor:
    """Gumbel-Softmax：前向近似 one-hot（硬方案），反向保留梯度。

    用 straight-through 版本让「生成器造的假方案」与「Oracle 的真实硬方案」同为 one-hot 形态，
    判别器就只能靠「约束是否真的满足」来区分，而不是靠「离散 vs 连续」——这是真 GAN 的关键。
        输入 logits: [B, N, K]  →  输出 [B, N, K]（行和为 1）
    """
    return F.gumbel_softmax(logits, tau=tau, hard=hard, dim=-1)


def build_forbid_mask(
    forbid_idx: torch.Tensor, n_slots: int, device=None
) -> torch.Tensor:
    """由「节点 → 禁止槽索引」构造禁止掩码 ``[B, N, K]``（1=禁止）。

    ``forbid_idx``: [B, N, f]，每项是禁止的槽索引，-1 表示无。
    对应架构文档的「行政时间保护 = 禁止列表」这一层约束。
    """
    b, n, f = forbid_idx.shape
    mask = torch.zeros((b, n, n_slots), dtype=torch.float32, device=device or forbid_idx.device)
    valid = forbid_idx >= 0
    idx = forbid_idx.clamp(min=0)
    # scatter：把禁止位置标 1
    flat = mask.view(b, n, n_slots)
    for j in range(f):
        pos = idx[:, :, j]
        m = valid[:, :, j]
        # one-hot scatter
        oh = F.one_hot(pos, num_classes=n_slots).float() * m.unsqueeze(-1).float()
        flat = torch.maximum(flat, oh)
    return flat


def scheme_conflicts(P: torch.Tensor, adj: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
    """兼项冲突期望：``Σ_{(i,j)∈E} Σ_k P[i,k]·P[j,k]``，按边数归一化。

    P: [B,N,K]  adj: [B,N,N]  mask: [B,N]  →  [B]（越小越无冲突）
    """
    same_color = torch.matmul(P, P.transpose(1, 2))          # [B,N,N]，S[i,j]=Σ_k P[i,k]P[j,k]
    adj = adj * mask.unsqueeze(-1) * mask.unsqueeze(1)       # 屏蔽填充节点
    conflict = (same_color * adj).sum(dim=(1, 2)) / 2.0      # 每条边算一次
    edge_count = adj.sum(dim=(1, 2)) / 2.0
    return conflict / edge_count.clamp(min=1.0)


def forbid_penalty(P: torch.Tensor, forbid: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
    """禁止列表违反惩罚：概率质量落在禁用槽之和（按节点数归一化）。"""
    pen = (P * forbid).sum(dim=(1, 2))
    n = mask.sum(dim=1).clamp(min=1.0)
    return pen / n


def slot_load_penalty(P: torch.Tensor, mask: torch.Tensor, capacity: float) -> torch.Tensor:
    """槽容量惩罚：每槽期望负载 = Σ_i P[i,k]，超过容量则 softplus 惩罚。"""
    load = P.sum(dim=1)                                       # [B,K]
    over = F.softplus(load - capacity)
    return over.sum(dim=1) / max(1, P.shape[-1])


def combination_loss(
    P: torch.Tensor,
    adj: torch.Tensor,
    mask: torch.Tensor,
    forbid: torch.Tensor | None = None,
    capacity: float = 1e9,
    w_conflict: float = 1.0,
    w_forbid: float = 1.0,
    w_capacity: float = 0.1,
) -> tuple[torch.Tensor, dict]:
    """组合约束总损失（GenCO 式）。返回 (loss, 分解字典)。"""
    l_conf = scheme_conflicts(P, adj, mask)
    loss = w_conflict * l_conf
    parts = {"conflict": float(l_conf.mean().detach())}
    if forbid is not None:
        l_forb = forbid_penalty(P, forbid, mask)
        loss = loss + w_forbid * l_forb
        parts["forbid"] = float(l_forb.mean().detach())
    l_cap = slot_load_penalty(P, mask, capacity)
    loss = loss + w_capacity * l_cap
    parts["capacity"] = float(l_cap.mean().detach())
    return loss.mean(), parts
