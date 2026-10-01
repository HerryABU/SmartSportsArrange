"""多步预测：序列数据构建。

把一次编排实例转成「单元序列」：
- 每步特征 = 单元自身属性（时长 / 径赛田赛 / 参赛人数）；
- 每步目标 = 该单元被分配的**时间槽**（由 oracle 贪心着色给出）——即「预定项目顺序」的结果。

任务：给定前 L 步，预测后 H 步的时间槽序列。用于让回溯**提前发生**
（预测到未来 H 步不可行就提前回溯），即架构文档第 5.2 节。
"""

from __future__ import annotations

import random

import numpy as np

from sports_ai.data.generator import generate_scenario
from sports_ai.data.gnn_io import encode_gnn_inputs
from sports_ai.generative.oracle import greedy_coloring_single

MAX_SEQ = 32      # 序列最大长度（不足补零、超出截断）
IN_DIM = 4        # 每步特征维
L_IN = 12         # 输入步数
H_OUT = 8         # 预测步数


def build_sequence(scenario):
    """返回 (X [MAX_SEQ, IN_DIM], Y [MAX_SEQ], length)。Y 为归一化时间槽。"""
    nf, adj, mask, _ = encode_gnn_inputs(scenario)
    n = int(mask[0].sum())
    if n == 0:
        return np.zeros((MAX_SEQ, IN_DIM), np.float32), np.zeros((MAX_SEQ,), np.float32), 0
    colors = greedy_coloring_single(adj[0], n, 16)
    # 按 (槽, 时长) 排序 = 真实排程顺序
    units = scenario.units[:n]
    order = sorted(range(n), key=lambda i: (int(colors[i]) if colors[i] >= 0 else 99,
                                            -units[i].raw_duration))
    X = np.zeros((MAX_SEQ, IN_DIM), np.float32)
    Y = np.zeros((MAX_SEQ,), np.float32)
    for pos, i in enumerate(order[:MAX_SEQ]):
        u = units[i]
        ath = len(u.athletes)
        X[pos] = [
            1.0 if u.track else 0.0,
            min(u.raw_duration, 300) / 300.0,
            min(ath, 64) / 64.0,
            float(math_log(ath)),
        ]
        c = colors[i]
        Y[pos] = (c / 15.0) if c >= 0 else 0.0
    return X, Y, min(n, MAX_SEQ)


def math_log(x: int) -> float:
    import math
    return math.log1p(x) / math.log(65.0)


def make_dataset(n_samples: int, seed: int):
    """返回 (X_in [N, L, IN_DIM], Y_out [N, H], lengths [N])。

    用多年级场景（3 个年级 × 12 个项目 → 最多 36 个单元）以获得足够长的序列。
    """
    rng = random.Random(seed)
    Xs, Ys, Ls = [], [], []
    for _ in range(n_samples):
        s = generate_scenario(seed=rng.randint(0, 10 ** 9), n_athletes=rng.randint(200, 800),
                              n_days=rng.randint(2, 6), multi_event_prob=rng.uniform(0.4, 0.9),
                              grades=["高一", "高二", "高三"])
        X, Y, ln = build_sequence(s)
        if ln < L_IN + H_OUT:
            continue
        Xs.append(X[:L_IN])
        Ys.append(Y[L_IN:L_IN + H_OUT])
        Ls.append(ln)
    if not Xs:
        raise RuntimeError(f"未产生任何序列样本（需要单元数 ≥ {L_IN + H_OUT}）")
    return (np.stack(Xs).astype(np.float32), np.stack(Ys).astype(np.float32),
            np.asarray(Ls, dtype=np.int64))
