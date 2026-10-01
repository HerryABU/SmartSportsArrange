"""难度测量器（自步学习 / 自迭代升级的基础，架构文档 5.4 节）。

用三个可观测指标刻画一个编排实例的「难度」：
1. **紧张度** tension = 需求时长 / 供给时长（容量越紧越难）；
2. **冲突密度** density = 冲突图边密度（兼项越密越难）；
3. **Oracle 残余冲突** —— 用贪心图着色试着求解，若能零冲突完成则「易」，否则「难」。

综合得分 = 加权归一化之和，用于自步学习排序（从易到难喂样本）。
"""

from __future__ import annotations

from dataclasses import dataclass

from sports_ai.data.features import extract_features
from sports_ai.data.gnn_io import encode_gnn_inputs
from sports_ai.generative.oracle import greedy_coloring_single


@dataclass
class Difficulty:
    tension: float
    density: float
    oracle_conflict: float      # 归一化残余冲突（0 表示贪心即可行）
    score: float

    def as_tuple(self):
        return self.tension, self.density, self.oracle_conflict, self.score


def measure(scenario) -> Difficulty:
    f = extract_features(scenario)
    tension = f[3]
    density = f[7]
    # oracle 试解
    nf, adj, mask, _ = encode_gnn_inputs(scenario)
    n = int(mask[0].sum())
    if n < 2:
        residual = 0.0
    else:
        colors = greedy_coloring_single(adj[0], n, 16)
        edges = 0
        bad = 0
        for i in range(n):
            for j in range(i + 1, n):
                if adj[0, i, j] > 0:
                    edges += 1
                    if colors[i] == colors[j]:
                        bad += 1
        residual = bad / edges if edges else 0.0
    # 综合难度：残余冲突权重最高（它最直接反映「能不能排下来」）
    norm_t = min(tension / 3.0, 1.0)
    norm_d = min(density / 0.5, 1.0)
    score = 0.25 * norm_t + 0.25 * norm_d + 0.5 * residual
    return Difficulty(round(tension, 4), round(density, 4), round(residual, 4), round(score, 4))


def has_backtrack(instance_meta) -> int:
    """回溯次数（真实系统里由求解器上报；合成场景下用 oracle 残余冲突近似）。"""
    return int(instance_meta.get("backtracks", 0))


def conflict_fail_rate(results) -> float:
    """冲突消解失败率：硬解失败的实例占比。"""
    if not results:
        return 0.0
    return sum(1 for r in results if not r.get("feasible", True)) / len(results)
