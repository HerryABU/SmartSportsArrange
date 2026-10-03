"""Oracle：真实可行方案（硬着色）生成器——判别器的「真样本」来源。

真 GAN 必须让判别器见到**真实数据分布**，否则只剩「生成器 vs 规则」。这里用经典的
**贪心图着色（DSATUR 顺序 + 最小可用色）**求出冲突图的可行时间槽分配：

- 顶点按「饱和度 / 度数」降序排列（优先给最难着色、冲突最多的项目配色）；
- 每个顶点取「邻居没用过、且不在禁止列表里」的最小时间槽；
- 槽不够时落到最后一个可用槽——这对应「容量客观不足时允许少量残余冲突」的现实。

产出的硬方案（one-hot）即判别器眼中的「真」；生成器的软/硬方案即「假」。
"""

from __future__ import annotations

import numpy as np
import torch


def greedy_coloring_single(adj: np.ndarray, n: int, max_slots: int,
                           forbid: np.ndarray | None = None) -> np.ndarray:
    """单图贪心着色，返回颜色标签 [n]（-1 表示无法着色）。"""
    degree = adj[:n, :n].sum(axis=1)
    # DSATUR 近似：按度数降序（度数相同按索引稳定）
    order = sorted(range(n), key=lambda i: (-degree[i], i))
    colors = -np.ones(n, dtype=np.int64)
    for v in order:
        used = set()
        nbrs = np.nonzero(adj[v, :n])[0]
        for u in nbrs:
            if colors[u] >= 0:
                used.add(int(colors[u]))
        forb = set()
        if forbid is not None:
            forb = {int(c) for c in forbid[v] if c >= 0}
        chosen = -1
        for c in range(max_slots):
            if c not in used and c not in forb:
                chosen = c
                break
        if chosen < 0:
            # 所有槽都冲突/禁止 → 退到「冲突最少」的槽（现实里就是允许少量残余冲突）
            best_c, best_bad = 0, None
            for c in range(max_slots):
                if c in forb:
                    continue
                bad = sum(1 for u in nbrs if int(colors[u]) == c)
                if best_bad is None or bad < best_bad:
                    best_bad, best_c = bad, c
            chosen = best_c
        colors[v] = chosen
    return colors


def oracle_batch(adj: torch.Tensor, mask: torch.Tensor,
                 forbid: torch.Tensor | None, max_slots: int) -> torch.Tensor:
    """批量真样本：返回 one-hot 硬方案 [B, N, max_slots]。"""
    # ⚠️ adj 是 **[B, N, N] 三维**（不是四维）。曾写成 b, n, _ = adj.shape
    #    期望四维，一旦真传三维张量就报 "too many values to unpack (expected 3)"。
    b, n = adj.shape[0], adj.shape[1]
    adj_np = adj.detach().cpu().numpy()
    mask_np = mask.detach().cpu().numpy()
    forbid_np = forbid.detach().cpu().numpy() if forbid is not None else None
    out = np.zeros((b, n, max_slots), dtype=np.float32)
    for i in range(b):
        # ⚠️ ni 必须**夹到 n 以内**：批量里若混入 mask 未清零但节点数超出的样本，
        #    degree 数组只有 n 项，sorted 里按 -degree[i] 索引会直接 IndexError。
        ni = min(n, int(mask_np[i].sum()))
        if ni == 0:
            continue
        fb = forbid_np[i] if forbid_np is not None else None
        colors = greedy_coloring_single(adj_np[i], ni, max_slots, fb)
        for v in range(ni):
            if colors[v] >= 0:
                out[i, v, colors[v]] = 1.0
    return torch.from_numpy(out).to(adj.device)
