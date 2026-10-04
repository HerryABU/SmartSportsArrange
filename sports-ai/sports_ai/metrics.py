"""统一的**加权代价口径**（单一权值来源，双端一致）。

## 为什么需要它

早期「四档编排模式（rule / optimize / ai / 混合）」的对比只看**未排数**，
于是在魔鬼档之外的四个场景里全部**持平**。后来才定位清楚：

> 那些实例的未排数已经是 1.0 / 1.33 —— 即**已经贴近下界**，
> 四档持平不是「搜索不足」，而是**指标分辨率不够**。
> 未排数是个位数小整数，四档都落在同一个数上，
> 它根本无法区分「都不丢单元、但谁排得更整齐」。

所以要出差异，必须换**加权代价口径**：把「排得好不好」也折算成分数，
让每个档位的解在同一把尺子上比较。

## 五项分量（越小越好）

============  ==================================================  ========
分量           含义                                               权重
============  ==================================================  ========
未排          任何可行时段都放不下的单元（硬失败）                  1000
兼项撞        同一运动员被排进重叠时段（方案本身是错的）            500
容量超占      同一时段同一场地已排时长 > 容量（方案本身是错的）      500
道次撞        同批（同项目+同年级）单元被排进同一时段（公平性）      50
碎块          同 ``group_key`` 的时段不连续 / 区间内混入别的块       10
工期超限      实际占用天数超过 ``days_limit`` 的天数（三态：x/0/-1）  20
============  ==================================================  ========

## 两条不可动摇的排序原则

1. **「能不能排」远重于「排得好不好」**：未排 / 兼项撞 / 超占 的权重
   比 碎块 高一到两个数量级 —— 前者是**不可交付**，后者只是**观感与公平**。
2. **权重是单一来源**：双端（Python 评测 / Java 规划器）必须引用同一张表，
   否则会出现「两边都在优化，却拿着两把不同的尺子」——
   这正是之前「修了半天看不到代价下降」那类 bug 的成因（见 ``_breaks_of`` 的说明）。

⚠️ 权重只在**比较/上报**口径里使用。规划器**搜索**用的代价函数另有其
（``plan/hybrid_search.py::_cost``、``PredictivePlanner.cost()``），它是为搜索速度与
可采纳性调过的；换比较口径**不应**顺手改搜索目标 —— 那是另一件需要单独实测的事。
"""

from __future__ import annotations

from typing import Dict, Mapping

# ---- 单一权值来源（双端一致，改这里就是改口径）----
WEIGHTS: Dict[str, float] = {
    "unplaced": 1000.0,           # 排不下：不可交付
    "athlete_clash": 500.0,       # 兼项撞：方案错
    "capacity_overflow": 500.0,   # 超占：方案错
    "lane_clash": 50.0,           # 道次撞：公平性
    "frag_blocks": 10.0,          # 碎块：观感
    "days_over": 20.0,            # 工期超限：每超一天
}

# 分量的规范顺序（打印与分解都按它走，避免各处顺序不一致）
COMPONENTS = ("unplaced", "athlete_clash", "capacity_overflow",
              "lane_clash", "frag_blocks", "days_over")


def weighted_breakdown(metrics: Mapping[str, object]) -> Dict[str, float]:
    """把原始计数折算成**每个分量的加权贡献**（未出现的分量按 0 计）。

    ``days_over`` 不是原始计数而是**派生量**（``days_used - days_limit``，
    仅在 ``days_limit > 0`` 时有意义）；若调用方直接给了 ``days_over`` 就用它。
    """
    out: Dict[str, float] = {}
    for k in COMPONENTS:
        raw = metrics.get(k)
        if raw is None:
            out[k] = 0.0
            continue
        out[k] = WEIGHTS[k] * float(raw)  # type: ignore[arg-type]
    return out


def days_over(days_used: object, days_limit: object) -> float:
    """工期超限天数。``days_limit`` 三态：x（限定 x 天）/ 0（不限）/ -1（尽可能减少）。

    * ``0``（不限）→ 恒 0：没有上限就谈不上超限；
    * ``-1``（尽可能减少）→ 恒 0：这是「尽量压缩」的软目标，不是上限，
      把它当上限会让「多占一天」变成违规，与语义相反；
    * ``x > 0`` → ``max(0, days_used - x)``。
    """
    try:
        dl = int(days_limit)  # type: ignore[arg-type]
        du = int(days_used)   # type: ignore[arg-type]
    except (TypeError, ValueError):
        return 0.0
    if dl <= 0:
        return 0.0
    return float(max(0, du - dl))


def weighted_cost(metrics: Mapping[str, object]) -> float:
    """加权代价（越小越好）。**这就是四档对比与验收判定的唯一口径。**

    兼容直接传入原始计数（``unplaced`` / ``athlete_clash`` / ...）或
    已算好的 ``days_over``。缺项按 0 计 —— 与「该分量在这一层不适用」等价。
    """
    m: Dict[str, object] = dict(metrics)
    if "days_over" not in m and ("days_used" in m or "days_limit" in m):
        m["days_over"] = days_over(m.get("days_used", 0), m.get("days_limit", 0))
    return float(sum(weighted_breakdown(m).values()))


def is_better(a: Mapping[str, object], b: Mapping[str, object]) -> bool:
    """严格优于：加权代价更小。同分时用「未排更少」作确定性 tie-break。

    ⚠️ tie-break 只是为了让**结果可复现**（同一批解的顺序稳定），
    不是第二套口径 —— 主判据始终是加权代价。
    """
    ca, cb = weighted_cost(a), weighted_cost(b)
    if abs(ca - cb) > 1e-9:
        return ca < cb
    return float(a.get("unplaced", 0) or 0) < float(b.get("unplaced", 0) or 0)
