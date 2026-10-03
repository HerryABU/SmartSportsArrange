"""编排求解层：拆批装箱 + 可解性分析 + 不可解冲突导出。

与 ``generative/``（神经生成）互补——这里是**经典求解**，负责在真实规模与真实时间资源下
把赛程**尽量排下**，并如实产出「排不下什么、为什么、怎么办」的结构化报告。

- :mod:`heats`      单元 → 组次任务展开（拆批；含预赛→决赛偏序）
- :mod:`feasibility` 可解性下界分析（容量缺口 / 最少天数 / 团下界 / 超大单元）
- :mod:`scheduler`  分批装箱 + 局部搜索（容量/池/兼项/偏序四约束）
- :mod:`report`     不可解冲突的结构化输出（给程序消费的稳定 schema）
- :mod:`repair`     神经网络输出 → 规则兜底（容量/兼项硬约束必须 100% 成立）
"""

from .heats import HeatTask, expand_heats
from .feasibility import analyze_bounds
from .scheduler import ScheduleResult, schedule
from .report import build_report, dump_report
from .repair import fragmentation, need_of, repair_assignment

__all__ = [
    "HeatTask",
    "expand_heats",
    "analyze_bounds",
    "ScheduleResult",
    "schedule",
    "build_report",
    "dump_report",
    "fragmentation",
    "need_of",
    "repair_assignment",
]
