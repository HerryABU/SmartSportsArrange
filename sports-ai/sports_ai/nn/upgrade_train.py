"""把既有小模型批量升级为专项 MoE 并重训（统一训练器）。

## 为什么不逐个改训练脚本

11 个小模型各有各的 ``train()``、各自的 loss 形式（回归 / 二分类 /
逐行打分）、各自的导出壳。逐个改意味着：

* 11 处重复的「建模型 → 训 → 导出 → 搬 weights」样板；
* 每处都可能漏掉 MoE 特有的两件事：**负载均衡正则**与**路由偏置更新**；
* 改错一个不会报错、只是那个模型静默退化成单体网络。

所以这里改成**声明式**：每个模型只登记 5 项（数据源、模型类、契约维度、
legacy 调用模式、损失形式），训练循环只写一份。

用法::

    python -m sports_ai.nn.upgrade_train --model lane_advisor --iters 2000
    python -m sports_ai.nn.upgrade_train --model all --iters 2000
"""

from __future__ import annotations

import argparse
import inspect
import os
import random
from dataclasses import dataclass, field
from typing import Callable, Optional

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

from sports_ai.device import (add_device_arg, backup_before_overwrite, describe_device,
                              resolve_device, seed_all, to_device)
from sports_ai.lane_advisor import LANE_FEAT_DIM
from sports_ai.referee_advisor import N_REF_FEAT, N_TYPES as N_REF_TYPES
from sports_ai.teacher_advisor import N_TCH_FEAT, N_TYPES as N_TCH_TYPES
from sports_ai.models.tournament_gnn import N_TYPES as N_TMT_TYPES
from sports_ai.data.constraint_gnn_io import N_TYPES as N_CON_TYPES
from sports_ai.data import scenario_hook
from sports_ai.forecast.dataset import (H_OUT as FC_H_OUT, IN_DIM as FC_IN_DIM,
                                        L_IN as FC_L_IN, build_sequence)
from sports_ai.data.features import N_FEATURES
from sports_ai.nn.upgrade import UpgradedMoE

#: ``ConstraintGnn`` 的节点特征维（模型模块 docstring 写明 ``node_feat[B,N,16]``，
#: 与 Java 侧 ``ConstraintGnnService`` 的 feed 维度对应）。
N_CON_FEAT = 16

MODEL_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))), "models")


@dataclass
class Spec:
    """一个小模型的升级登记项。"""
    name: str                      # 模型名（也是 onnx 文件名主干）
    module: str                    # 存放模型类的模块（延迟 import，避免循环依赖）
    cls: str                       # 模型类名
    feat_dim: int                  # 输入特征维数（**契约，必须与旧模型一致**）
    legacy_mode: str = "pair"      # "pair"（x, mask） / "gnn"（x, adj, tmask, mask）
    out_dim: int = 1               # 1 → [B,N]；>1 → [B,N,out_dim]
    pool: bool = False             # True → 整个实例出一个向量
    loss: str = "mse"              # "mse" | "bce" | "listwise"
    make_batch: Optional[str] = None   # 数据源函数名
    #: 数据源所在模块。**必须能独立于模型模块指定**：
    #: ``TournamentGnn`` 在 ``models/tournament_gnn.py``（只放模型定义），
    #: 而它的 ``make_dataset`` 在 ``train_tournament_gnn.py``（放训练逻辑）——
    #: 早前只从模型模块里找，报「未登记 make_batch」而模型定义本身完全正常。
    data_module: Optional[str] = None
    pad_to: int = 16
    takes_pad_to: bool = False         # 数据源是否接受 pad_to 参数
    batch_fn: Optional[Callable] = None  # 把 List[Dict] 转成张量批的函数
    #: 原生 make_batch 的元组布局。**必须显式登记，不能猜**：
    #: 早前靠「谁是二维掩码」判别，而 lane_advisor 返回 (x, mask, label) ——
    #: 掩码与标签**都是二维**，判别式抓到第一个（mask）当掩码、
    #: 于是把真正的标签排除掉，loss 拿 label 当 mask 算，尺寸对不上直接崩。
    #: 靠猜的代价是「换个模型就静默取到错的那一项」。
    layout: str = "xmL"                # x=特征 m=掩码 a=邻接 t=类型掩码 L=标签
    n_types: int = 1                   # 邻接的边型数（**取模型自己的常量**）
    #: 构造原模型时额外要传的关键字参数。**必须显式登记**：
    #: ``TeacherGnn`` 其实是 ``RefereeGnn`` 的复用（骨架同构、只换特征维），
    #: 不传 ``node_feat=10`` 就会用默认的 12 ——
    #: 报 "mat1 and mat2 shapes cannot be multiplied (4x10 and 12x160)"，
    #: 而这是**构造参数错**、不是数据错，靠改数据永远修不好。
    ctor: dict = field(default_factory=dict)
    #: 原模型吃 **2 维** ``[B,F]``（实例级，无节点维）—— 见 UpgradedMoE._pooled_legacy。
    #: 这类模型（算法选择器）本就把整个实例压成一行向量，
    #: 用 `pool=True` 表达「输出是 [B,out_dim]」，用本字段表达「输入也是 [B,F]」。
    flat_input: bool = False
    #: ONNX 的**输入名与顺序**（必须与 Java 侧的 feed 完全一致）。
    #: ⚠️ 这不是可有可无的装饰：名字不符时 onnxruntime 直接报
    #: "Invalid input name: X"，而调用方通常 try/catch 后**静默回退规则** ——
    #: 于是「模型换了但一点用没有」。逐模型核实结果：
    #:   lane_advisor                 → athlete_feat, mask        （**不是 node_feat**）
    #:   conflict_gnn                 → node_feat, adj, mask      （邻接是 3 维、无 type_mask）
    #:   referee/teacher/tournament   → node_feat, adj_by_type, type_mask, mask
    #:   algorithm_selector / ai      → features                  （单个 [1,F]，无 N 轴）
    onnx_inputs: tuple = ()
    #: 主输出的 ONNX 名字。默认 ``out``；``forecast_*`` 的历史契约叫 ``y``，
    #: 保留原名才能「零改动替换」——将来真接线到 Java 时，
    #: 按旧名字读输出的代码一行都不用改。
    onnx_output: str = "out"
    #: >0 时给该模型加「未来 H 步时间槽序列」预测头（H 即此值）。监督信号来自
    #: **同一批场景**的真实序列标签（见 `data/scenario_hook.py`）。
    #: 0 = 不加（保持旧 ckpt 原样可载）。这是「给需要预测的小模型嵌入预测」的落点。
    forecast_steps: int = 0
    extra: dict = field(default_factory=dict)


# ---------------------------------------------------------------------------
# 批次转换：把各模型 ``make_dataset`` 的 List[Dict] 拉平成张量批
# ---------------------------------------------------------------------------
def make_selector_batch(items, spec: "Spec"):
    """算法选择器的数据源返回 ``(x[B,F], y[B])`` 两元组（不是 List[Dict]）。"""
    x, y = items[0], items[1]
    T = lambda a: torch.from_numpy(np.asarray(a, dtype=np.float32))
    xt = T(x)
    return xt, torch.ones(xt.shape[0], 1), None, None, T(y)


def make_torch_batch(items: list, spec: "Spec"):
    """把各模型 ``make_dataset`` 的样本列表拉平成张量批。

    返回 ``(x, mask, adj, type_mask, label)``，与 :func:`train_one` 的解包一致。

    ## 为什么按模型分派而不是写一个通用转换

    实测三种结构互不相同（硬凑通用函数只会埋坑）：

    ==============  ==================================  ====================
    模型            样本结构                            标签
    ==============  ==================================  ====================
    referee_gnn     ``{enc:{node_feat[1,N,F], adj, type_mask, mask}, n, y[N]}``  逐节点
    teacher_gnn     同上但 F=10                        逐节点
    tournament_gnn  ``{node_feat[1,N,F], adj[1,E,N,N], format[3], fair[N]}``  实例级
    ==============  ==================================  ====================

    变长样本 pad 到批内最大 N，**padding 区 mask 必须置 0** ——
    否则假节点会进池化分母、把梯度稀释（这是 padding 区必须掩码的第二个理由，
    第一个是它会经残差块被 LayerNorm「复活」）。

    ⚠️ 三个 ``make_dataset`` 返回的是**已经带 batch 维的数组**（``[1,N,F]``），
    直接 ``np.pad((0,pad),(0,0))`` 会 pad 错位置 —— 必须先去 batch 维。
    """
    width = max(int(d["n"]) for d in items)
    xs, ms, as_, tms, ys = [], [], [], [], []
    for d in items:
        n, pad = int(d["n"]), width - int(d["n"])
        if "enc" in d:                                   # referee / teacher
            e = d["enc"]
            x = np.asarray(e["node_feat"], dtype=np.float32)[0]      # [N,F]
            # ⚠️ mask 同样是 [1,N]，**必须去 batch 维** ——
            #    忘记去的话 np.pad(m,(0,pad)) 补在第 0 轴（长度 1）上，
            #    补出来的 mask 仍是 [1, N+pad] 而非 [N+pad]，
            #    于是 stack 时报 "inhomogeneous shape"。
            m = np.asarray(e["mask"], dtype=np.float32).reshape(-1)
            adj = np.asarray(e["adj_by_type"], dtype=np.float32)[0]  # [E,N,N]
            tm = np.asarray(e["type_mask"], dtype=np.float32).reshape(-1)
            # ⚠️ 标签长度是**该样本自己的 n**，不是批内 width ——
            #    直接 stack 会报 "inhomogeneous shape"。必须补到 width。
            y = np.asarray(d["y"], dtype=np.float32).reshape(-1)
            if y.shape[0] < width:
                y = np.pad(y, (0, width - y.shape[0]))
            elif y.shape[0] > width:
                y = y[:width]
            xs.append(np.pad(x, ((0, pad), (0, 0))))
            ms.append(np.pad(m, (0, pad)))
            as_.append(np.pad(adj, ((0, 0), (0, pad), (0, pad))))
            tms.append(tm)
            ys.append(y)
        else:                                            # tournament
            x = np.asarray(d["node_feat"], dtype=np.float32)[0]
            m = np.asarray(d["mask"], dtype=np.float32).reshape(-1)
            adj = np.asarray(d["adj_by_type"], dtype=np.float32)[0]
            tm = np.asarray(d["type_mask"], dtype=np.float32).reshape(-1)
            xs.append(np.pad(x, ((0, pad), (0, 0))))
            ms.append(np.pad(m, (0, pad)))
            as_.append(np.pad(adj, ((0, 0), (0, pad), (0, pad))))
            tms.append(tm)
            # 标签是**赛制分布 [3]**（pool 目标），fair 是逐队伍公平度（辅助）
            ys.append(np.asarray(d["format"], dtype=np.float32))
    T = lambda a: torch.from_numpy(np.asarray(a, dtype=np.float32))
    return T(xs), T(ms), T(as_), T(tms), T(ys)


def make_constraint_batch(items: list, spec: "Spec"):
    """``ConstraintGnn`` 的 ``make_dataset`` 返回**扁平**样本（与 referee/teacher 的 ``enc`` 包装不同）。

    样本形如::

        {node_feat[1,N,16], adj_by_type[1,T,N,N], type_mask[1,T], mask[1,N], rank[N]}

    ⚠️ 两处必须显式处理，否则形状对、结果错：

    1. 样本自带 batch 维（``[1,N,F]``）→ 先去掉再 pad，否则补在第 0 轴（长度 1）上；
    2. 标签字段叫 ``rank``（不是 ``y``），且长度恰为 N —— pad 到批宽后
       padding 区标签必须是 0（掩码已置 0，加权后不参与 loss）。
    """
    width = max(int(d["n"]) for d in items)
    xs, ms, as_, tms, ys = [], [], [], [], []
    for d in items:
        n, pad = int(d["n"]), width - int(d["n"])
        x = np.asarray(d["node_feat"], dtype=np.float32)[0]            # [N,F]
        m = np.asarray(d["mask"], dtype=np.float32).reshape(-1)        # [N]
        a = np.asarray(d["adj_by_type"], dtype=np.float32)[0]          # [T,N,N]
        t = np.asarray(d["type_mask"], dtype=np.float32).reshape(-1)   # [T]
        y = np.asarray(d["rank"], dtype=np.float32).reshape(-1)[:n]
        xs.append(np.pad(x, ((0, pad), (0, 0))))
        ms.append(np.pad(m, (0, pad)))
        as_.append(np.pad(a, ((0, 0), (0, pad), (0, pad))))
        tms.append(t)
        ys.append(np.pad(y, (0, pad)))
    T = lambda a: torch.from_numpy(np.asarray(a, dtype=np.float32))
    return T(xs), T(ms), T(as_), T(tms), T(ys)


def make_forecast_batch(items: tuple, spec: "Spec"):
    """``forecast_*`` 的数据源返回 ``(X[B,L,F], Y[B,H], lengths)``。

    与其它模型的两点不同：

    1. **没有 padding** —— ``make_dataset`` 已按 ``L_IN`` / ``H_OUT`` 切好定长窗口，
       每个时间步都是真实样本，所以掩码直接给全 1；
    2. 标签是 ``[B,H]``（未来 H 步的时间槽序列），配 ``pool=True`` 的输出 ``[B,H]``。

    返回三元组 ``(x, mask, y)``，与 ``layout="xmL"`` **逐位对应**
    （``train_one`` 是按 layout 的字符顺序去 batch 里取元素的，
    多塞一个 ``None`` 占位会把 mask 与标签错位）。
    """
    x = torch.from_numpy(np.asarray(items[0], dtype=np.float32))    # [B,L,F]
    y = torch.from_numpy(np.asarray(items[1], dtype=np.float32))    # [B,H]
    mask = torch.ones(x.shape[0], x.shape[1], dtype=torch.float32)
    return x, mask, y


#: 辅助预测损失的权重。取小值：它是**表示学习**的辅助信号（逼 hidden 编码
#: 「未来会排到哪」），主任务仍是各自的原目标 —— 权重过大会把主能力带偏。
AUX_FORECAST_W = 0.1

#: 预测分支预生成的序列样本数。一次生成、训练期循环采样：
#: 既保证 X/Y 同源，又不必每步重跑场景生成 + 贪心着色（那是主要开销）。
AUX_SAMPLES = 512


def forecast_targets(scenarios: list, n_samples: int):
    """把「本步刚生成的场景」转成 ``[n_samples, H]`` 的未来时间槽真值。

    ⚠️ **必须先校验 ``len(scenarios) == n_samples``**。数据源里普遍存在
    「不合格样本 continue 掉」的过滤，那时场景数 > 样本数；按下标配
    等于给样本配了**别人的标签** —— 形状全对、监督全错，是最难查的一类 bug。
    不等则返回 None，调用方跳过本步辅助损失（宁可少训，不可训错）。

    另一个前提是场景得够长：`build_sequence` 需要单元数 ≥ ``L_IN + H_OUT``，
    短场景（单年级、项目少）构不成「给前 12 步预测后 8 步」这个任务，
    同样返回 None。所以**不是所有小模型都能吃上这个辅助任务** —— 这是
    数据本身的限制，不要在调用侧硬凑。
    """
    if not scenarios or len(scenarios) != n_samples:
        return None
    rows = []
    for sc in scenarios:
        try:
            _x, y, ln = build_sequence(sc)
        except Exception:                                          # noqa: BLE001
            return None
        if ln < FC_L_IN + FC_H_OUT:
            return None
        rows.append(y[FC_L_IN:FC_L_IN + FC_H_OUT])
    return torch.from_numpy(np.asarray(rows, dtype=np.float32))


# ---------------------------------------------------------------------------
# 登记：确有 Python 训练代码的小模型
# ---------------------------------------------------------------------------
def registry() -> dict:
    """在役小模型的升级登记（用**外部适配器**路线的那些）。

    ⚠️ **只登记确实存在 Python 训练代码的模型**。清点时发现：
    ``scheme_generator / scheme_refiner / scheme_discriminator / scheme_diffusion``
    这 4 个只有 Java 侧加载（``AdversarialSchemeService``），
    **sports-ai 侧没有对应训练脚本** —— 它们的历史权重是别处训好拷进来的。
    登记它们会在训练时直接 ``AttributeError``，属于「接线正确但从未通电」。

    维数一律取自**各模型自己的常量**（而不是抄一遍），改维度时不会漂移。
    """
    reg = {
        # ── 高频：每次编排都会走 ──
        "lane_advisor": Spec("lane_advisor", "sports_ai.lane_advisor", "LaneAdvisor",
                             LANE_FEAT_DIM, loss="mse", make_batch="make_batch",
                             pad_to=64, takes_pad_to=True, layout="xmL",
                             # ⚠️ 这里**刻意不挂** forecast_steps：实测该数据源是
                             # 纯合成特征（make_sample），根本不生成场景对象 →
                             # 拿不到「未来时间槽」真值，挂上只会每步白算一遍并报未对齐。
                             # 要用这个能力得先改数据源走 generate_scenario（会改变
                             # 训练数据分布，属于另一件事）。
                             onnx_inputs=("athlete_feat", "mask")),
        "conflict_gnn": Spec("conflict_gnn", "sports_ai.train_gnn", "ConflictGnn",
                             17, loss="mse", make_batch="make_batch",
                             legacy_mode="gnn3", layout="xamL", n_types=1,
                             onnx_inputs=("node_feat", "adj", "mask")),
        "referee_gnn": Spec("referee_gnn", "sports_ai.referee_advisor", "RefereeGnn",
                            N_REF_FEAT, loss="listwise", make_batch="make_dataset",
                            legacy_mode="gnn", batch_fn=make_torch_batch,
                            layout="xmatL", n_types=N_REF_TYPES,
                            onnx_inputs=("node_feat", "adj_by_type", "type_mask", "mask")),
        "teacher_gnn": Spec("teacher_gnn", "sports_ai.teacher_advisor", "TeacherGnn",
                            N_TCH_FEAT, loss="listwise", make_batch="make_dataset",
                            legacy_mode="gnn", batch_fn=make_torch_batch,
                            layout="xmatL", n_types=N_TCH_TYPES,
                             ctor={"node_feat": N_TCH_FEAT},
                             onnx_inputs=("node_feat", "adj_by_type", "type_mask", "mask")),
        "constraint_gnn": Spec("constraint_gnn", "sports_ai.models.constraint_gnn",
                               "ConstraintGnn", N_CON_FEAT, loss="listwise",
                               make_batch="make_dataset",
                               data_module="sports_ai.train_constraint_gnn",
                               batch_fn=make_constraint_batch,
                               legacy_mode="gnn", layout="xmatL", n_types=N_CON_TYPES,
                               onnx_inputs=("node_feat", "adj_by_type", "type_mask", "mask")),
        # ── 算法选择器（实例级，输入是 [B,F] 无节点维）──
        # ⚠️ 两个选择器都是**二分类**：原模型 head 的最后一层是 `Linear(..., 2)`
        #    （`AlgorithmSelectorV2.n_classes=2` / `AlgorithmSelector` 硬写 2），
        #    Java 侧 `OnnxInferenceService.runSelector` 读的是 `r.get(0)` 的**全部**
        #    浮点值（硬解 / 取消两条路径的 logits）。
        #    所以 `out_dim=2` 是契约、不是可调项：写成默认的 1 会导出 [1,1]，
        #    形状对得上「一个输出张量」、语义却少一类 → Java 读到单值后
        #    取第二条路径越界 → 异常被 catch → **静默回退规则**。
        #    标签 y ∈ {0,1}（见 train_selector.make_dataset）→ 用 CE。
        "algorithm_selector": Spec("algorithm_selector", "sports_ai.models.selector_v2",
                                   "AlgorithmSelectorV2", N_FEATURES, pool=True,
                                   flat_input=True, loss="ce", out_dim=2,
                                   make_batch="make_dataset",
                                   data_module="sports_ai.train_selector",
                                   layout="xL", batch_fn=make_selector_batch,
                                   # 实测该数据源基于 generate_scenario 且逐场景 1 样本、
                                   # 场景长度足够 → 三条对齐条件全过，可吃上预测嵌入。
                                   forecast_steps=FC_H_OUT,
                                   onnx_inputs=("features",)),
        "ai": Spec("ai", "sports_ai.models.selector", "AlgorithmSelector", N_FEATURES,
                   pool=True, flat_input=True, loss="ce", out_dim=2,
                   make_batch="make_dataset", data_module="sports_ai.train_selector",
                   layout="xL", batch_fn=make_selector_batch,
                   onnx_inputs=("features",)),
        # ── 序列型：多步预测（把「时间步」当节点序列，长度固定 L_IN）──
        # ⚠️ 三处必须照抄旧契约，漏一处就是「模型换了但读不出来」：
        #    ① 输入名 `x`、输出名 `y`（不是其它模型的 node_feat / out）；
        #    ② 原模型只有一个 `forward(x)` 参数、**没有 mask 输入**；
        #    ③ `hidden=64` —— 训练脚本用的是 64，而模型类默认 160，
        #       不显式传会 load_state_dict 形状不匹配。
        "forecast_direct": Spec("forecast_direct", "sports_ai.forecast.model",
                                "DirectForecaster", FC_IN_DIM, out_dim=FC_H_OUT,
                                pool=True, loss="mse",
                                make_batch="make_dataset",
                                data_module="sports_ai.forecast.dataset",
                                batch_fn=make_forecast_batch, layout="xmL",
                                pad_to=FC_L_IN,
                                ctor={"in_dim": FC_IN_DIM, "hidden": 64, "h": FC_H_OUT},
                                onnx_inputs=("x",), onnx_output="y"),
        "forecast_mimo": Spec("forecast_mimo", "sports_ai.forecast.model",
                              "MimoForecaster", FC_IN_DIM, out_dim=FC_H_OUT,
                              pool=True, loss="mse",
                              make_batch="make_dataset",
                              data_module="sports_ai.forecast.dataset",
                              batch_fn=make_forecast_batch, layout="xmL",
                              pad_to=FC_L_IN,
                              ctor={"in_dim": FC_IN_DIM, "hidden": 64, "h": FC_H_OUT},
                              onnx_inputs=("x",), onnx_output="y"),
        # ⚠️ tournament_gnn **不在这里登记**：它有三个输出头
        # （赛制/种子分/公平性），而 Java 的 TournamentAiService 读 r.get(0..2)
        # 三个输出 —— 外部适配器只回主输出，少两个会被 catch 后静默回退规则
        # （实测：onnx 单独加载推理完全正常，测试却报「模型应可用 was false」，
        #  因为问题在输出**个数**而不是能否加载）。
        # 它已改为在 `models/tournament_gnn.py` 里就地插 `MoERepr` 增强表征，
        # 训练/导出仍走它自己的 `train_tournament_gnn.py` / `export_tournament_onnx.py`。
    }
    # 统一给**全部**专项模型挂上「未来 H 步时间槽」预测分支：用户明确要求
    # 「能力最好的」，不是覆盖最少的。这条分支自带序列输入（不依赖各模型自己的
    # 数据源是否生成场景），所以能 **100% 覆盖**；先前靠场景钩子的做法只对 4/9 生效，
    # 而且「需要预测」的那几个恰好都用不上。
    for _spec in reg.values():
        _spec.forecast_steps = FC_H_OUT
    return reg


# ---------------------------------------------------------------------------
# 训练
# ---------------------------------------------------------------------------
def new_legacy(spec: Spec):
    """按登记构造原模型（带上它自己需要的构造参数）。"""
    mod = __import__(spec.module, fromlist=[spec.cls])
    return getattr(mod, spec.cls)(**spec.ctor)


def build(spec: Spec, args) -> UpgradedMoE:
    legacy = new_legacy(spec)
    return UpgradedMoE(legacy, in_dim=spec.feat_dim, out_dim=spec.out_dim,
                       hidden=args.hidden, n_layers=args.n_layers,
                       n_experts=args.n_experts, n_groups=args.n_groups,
                       pool=spec.pool, dropout=0.1,
                       warmup_steps=args.warmup, legacy_mode=spec.legacy_mode,
                       n_types=spec.n_types, flat_input=spec.flat_input,
                       forecast_steps=spec.forecast_steps)


def data_source(spec: Spec):
    """取回该模型的数据生成函数（若登记了）。

    优先从 ``data_module`` 取；没登记时才回退到模型模块
    （多数模型把数据生成与模型定义放在同一个文件里）。
    """
    if not spec.make_batch:
        return None
    mod = __import__(spec.data_module or spec.module, fromlist=[spec.make_batch])
    fn = getattr(mod, spec.make_batch, None)
    if fn is None and spec.data_module is None:
        # 回退：模型模块里没有就去对应的 train_ 脚本找（命名约定）
        base = spec.module.rsplit(".", 1)[0]
        alt = f"{base}.train_{spec.module.rsplit('.', 1)[-1]}"
        try:
            mod2 = __import__(alt, fromlist=[spec.make_batch])
            fn = getattr(mod2, spec.make_batch, None)
        except Exception:
            fn = None
    return fn


def normalize_out(out):
    """把 (out, next) 或裸 out 统一成 (主输出, next)。"""
    if isinstance(out, (tuple, list)) and len(out) >= 2:
        return out[0], out[1]
    return out, None


def find_target(spec: Spec, batch, x, mask, adj, tmask):
    """从批次里定位**标签张量**。

    ⚠️ 不能用 ``batch[-1]``：原生 ``make_batch`` 的元组顺序各模型不同
    （``lane_advisor`` 是 (x, adj, mask, label)、``train_gnn`` 是 (x, adj, mask, label)、
    经 ``batch_fn`` 的则是 (x, mask, adj, type_mask, label)），
    写死位置会在某个模型上静默取到错的那一项。
    判据：**排除掉已知的输入张量，剩下的那个就是标签**。
    """
    used = {id(x), id(mask)}
    for t in (adj, tmask):
        if t is not None:
            used.add(id(t))
    for b in batch:
        if id(b) in used:
            continue
        if hasattr(b, "dim") and b.dim() in (1, 2):
            return b
    return batch[-1]


def compute_loss(spec: Spec, pred, batch, mask, target=None):
    """按登记的损失形式算 loss。返回 (loss, 标量指标)。"""
    if target is None:
        target = batch[-1]
    if spec.loss == "mse":
        if spec.pool:
            # 实例级：pred [B,out_dim] vs target [B,out_dim]（或 [B]）
            t = target.float().reshape(pred.shape) if target.shape != pred.shape \
                else target.float()
            return F.mse_loss(pred, t), float(pred.mean())
        # 逐行：pred [B,N] 或 [B,N,K]，mask [B,N]（或 [B,N,1]）
        m = mask.reshape(mask.shape[0], -1).float()
        if m.shape[1] != pred.shape[1]:
            raise SystemExit(f"掩码宽度 {m.shape[1]} 与预测宽度 {pred.shape[1]} 不一致")
        t = target.float().reshape(pred.shape) if target.shape != pred.shape \
            else target.float()
        # ⚠️ **不要**在这里写 pred.reshape(target.shape)：lane_advisor 的标签是
        #    [B]（每批一个分组）而 pred 是 [B,N]，reshape 会把 pred 压成 [B]，
        #    后面与 mask 相乘就报 "size of tensor a (B) must match (N)"。
        # 掩码按 pred 的**尾部维数**决定要不要补轴：
        #   pred [B,N]    → mm [B,N]
        #   pred [B,N,K]  → mm [B,N,1]
        # ⚠️ 判据必须用 pred.dim()，**不能用 target**：
        #    pred/target/mask 三者都是 [4,64] 时，
        #    写成「target 的最后一维是否为 1」会误判成需要补轴，
        #    得到 mm [4,64,1] 与 [4,64] 广播成 [4,64,64] —— 不报错、结果全错。
        mm = m if pred.dim() == 2 else m.unsqueeze(-1)
        l = ((pred - t) ** 2 * mm).sum() / mm.sum().clamp(min=1.0)
        return l, float(l.detach())
    if spec.loss == "ce":
        # 多类 logits（选择器两个版本的原模型都输出 [B,2]：0=硬解 / 1=取消路径）。
        # ⚠️ 这里**必须**用 CE 而不是 BCE：BCE 会把 [B,2] 直接拉平成 [2B] 再与
        #    [B] 的标签比 —— 要么形状不匹配直接报错，要么（更坏）被当成
        #    「两个独立的二分类样本」，loss 能降、语义全错。
        t = target.long().reshape(-1)
        l = F.cross_entropy(pred, t)
        return l, float((pred.argmax(dim=-1) == t).float().mean())
    if spec.loss == "bce":
        if spec.pool and pred.dim() == 2 and pred.shape[-1] > 1:
            raise SystemExit(
                f"{spec.name}: bce 只适用于**单** logits 输出，当前输出 "
                f"{tuple(pred.shape)}；多类 logits 请登记 loss='ce'")
        p = pred.reshape(-1) if spec.pool else pred.reshape(pred.shape[0], -1)[:, 0]
        t = target.float().reshape(-1)
        l = F.binary_cross_entropy_with_logits(p, t)
        return l, float((p > 0).float().eq(t).float().mean())
    # listwise：掩码内做 pairwise ranking（Spearman 的可微近似）
    p = pred.reshape(pred.shape[0], -1)
    t = target.float().reshape(pred.shape[0], -1)
    m = mask.float()
    pm = p.masked_fill(m <= 0, -1e9)
    tm = t.masked_fill(m <= 0, -1e9)
    # 软排序损失：让高分项排在低分项之前（成对 margin）
    dp = pm.unsqueeze(2) - pm.unsqueeze(1)
    dt = (tm.unsqueeze(2) - tm.unsqueeze(1))
    valid = (dt > 0) & (m.unsqueeze(2) > 0) & (m.unsqueeze(1) > 0)
    if valid.sum() == 0:
        return torch.zeros((), device=p.device), 0.0
    margin = F.relu(0.1 - dp) * valid.float()
    l = margin.sum() / valid.float().sum().clamp(min=1.0)
    return l, float(l.detach())


def train_one(spec: Spec, args, device) -> str:
    model = build(spec, args).to(device)
    src = data_source(spec)
    if src is None:
        raise SystemExit(f"{spec.name}: 未登记 make_batch，无法自动重训")
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-5)
    print(f"[{spec.name}] 升级为专项 MoE：深度={model.depth()} 专家={len(model.experts)} "
          f"（{','.join(sorted(set(type(e.inner).__name__ for e in model.experts)))}）"
          f"  参数={sum(p.numel() for p in model.parameters()):,}")

    # 辅助预测任务（「给需要预测的小模型嵌入预测」）：走**独立的序列数据源**。
    # 一次预生成整批再循环采样，有两个好处：
    #   ① 标签与输入**严格同源**（同一份 make_dataset 的 X/Y，不存在错配）；
    #   ② 不必每步都跑一遍场景生成 + 贪心着色（那会吃掉大半个训练时间）。
    # 这条分支自带序列输入，所以**不依赖各模型自己的数据源** —— 覆盖 100%，
    # 而先前靠场景钩子的做法只对 4/9 生效。
    aux_on = spec.forecast_steps > 0
    aux_X = aux_Y = None
    if aux_on:
        from sports_ai.forecast.dataset import make_dataset as _fc_dataset
        _ax, _ay, _ = _fc_dataset(AUX_SAMPLES, seed=args.seed + 4242)
        aux_X = torch.from_numpy(np.asarray(_ax, dtype=np.float32))
        aux_Y = torch.from_numpy(np.asarray(_ay, dtype=np.float32))
        print(f"[{spec.name}] 预测分支已挂：{aux_X.shape[0]} 条序列样本 "
              f"{tuple(aux_X.shape[1:])} → {tuple(aux_Y.shape[1:])}")
    for it in range(args.iters):
        if spec.takes_pad_to:
            batch = src(args.batch, seed=args.seed + it, pad_to=spec.pad_to)
        else:
            batch = src(args.batch, seed=args.seed + it)
        if spec.batch_fn is not None:
            batch = spec.batch_fn(batch, spec)
        batch = tuple(b.to(device) if hasattr(b, "to") else b for b in batch)
        x, mask = batch[0], batch[2]
        # 严格按登记的 layout 解包（不猜）
        x = batch[0]
        pos = 1
        adj = tmask = None
        mask = None
        target = None
        for ch in spec.layout[1:]:
            if pos >= len(batch):
                break
            t = batch[pos]
            pos += 1
            if ch == "m":
                mask = t
            elif ch == "a":
                adj = t
            elif ch == "t":
                tmask = t
            elif ch == "L":
                target = t
        if mask is None:
            # flat_input（实例级）的模型本来没有节点维，掩码是长度 1 的占位
            mask = torch.ones(x.shape[0], 1, dtype=x.dtype)
        if target is None:
            target = batch[-1]
        # 邻接缺省时给「自聚合」单位阵（GNN 类原模型内部会直接解引用）
        if spec.legacy_mode == "gnn" and adj is not None and adj.dim() == 3:
            # referee/teacher/tournament 要 [B,E,N,N]（少一个边型维会在 bmm 处报
            # "batch1 must be a 3D tensor"）；而 conflict_gnn（gnn3）本身就要 3 维，不要动。
            adj = adj.unsqueeze(1)
        if spec.legacy_mode in ("gnn", "gnn3") and adj is None:
            b2, n2 = x.shape[0], x.shape[1]
            adj = torch.eye(n2, dtype=x.dtype).expand(b2, 1, n2, n2)
            if tmask is None:
                tmask = torch.ones(b2, 1, dtype=x.dtype)
        elif spec.legacy_mode != "gnn":
            adj, tmask = None, None
        pred, nxt = model(x, mask, adj, tmask)
        loss, metric = compute_loss(spec, pred, batch, mask, target)
        if aux_on:
            # 从预生成池里随机采一批序列，走共享专家池 + 主干 → 未来 H 步
            k = min(args.batch, aux_X.shape[0])
            idx = torch.randint(0, aux_X.shape[0], (k,))
            fc = model.forward_seq(aux_X[idx].to(device))
            if fc is not None:
                loss = loss + AUX_FORECAST_W * F.mse_loss(fc, aux_Y[idx].to(device))
        # MoE 特有的两件事：**必须**都做，否则等于白装 MoE
        loss = loss + args.w_lb * model.load_balance_loss()
        opt.zero_grad()
        loss.backward()
        opt.step()
        model.update_router_bias()
        if (it + 1) % args.log_every == 0:
            u = model.expert_usage()
            used = sum(1 for v in u.values() if v > 0.001)
            print(f"  第 {it+1:5d} 步  loss={float(loss):.5f}  指标={metric:.4f}  "
                  f"激活专家={used}/{len(u)}")

    os.makedirs(MODEL_DIR, exist_ok=True)
    path = os.path.join(MODEL_DIR, f"{spec.name}.moe.pt")
    backup_before_overwrite(path, f"moe-{args.iters}")
    torch.save({k: v.detach().cpu() for k, v in model.state_dict().items()}, path)
    return path

def export_one(spec: Spec, args) -> str:
    """导出 ONNX，**输入名/顺序严格按 Spec.onnx_inputs**。

    ## 两条硬约束

    1. **输入名必须与 Java 侧 feed 的一致**。名字不符时 onnxruntime 报
       "Invalid input name: X"，而调用方 try/catch 后静默回退规则 ——
       「模型换了却一点用没有」。各模型的名字已逐个人工核实（见 Spec.onnx_inputs）。

    2. **文件名用原名**（如 ``lane_advisor.onnx``，不是 ``lane_advisor.moe.onnx``）。
       这是适配器路线的意义所在：契约（输入名/形状/输出形状）与旧模型逐位一致，
       所以新模型可以**直接覆盖投放**，Java 侧一行都不用改。
       权重文件仍叫 ``<name>.moe.pt`` 以区分新旧。
    """
    from sports_ai.onnx_utils import inline_weights

    legacy = new_legacy(spec)
    model = UpgradedMoE(legacy, in_dim=spec.feat_dim, out_dim=spec.out_dim,
                        hidden=args.hidden, n_layers=args.n_layers,
                        n_experts=args.n_experts, n_groups=args.n_groups,
                        pool=spec.pool, dropout=0.0, warmup_steps=0,
                        legacy_mode=spec.legacy_mode, n_types=spec.n_types,
                        # ⚠️ 必须与训练时**逐字一致**：漏了它，加载 ckpt 会报
                        # "Missing key(s): seq_proj.weight, forecast_head.weight" ——
                        # 也就是「训了导不出」。预测分支不进部署契约（导出只回主输出），
                        # 但它的参数在 ckpt 里，结构就必须对得上。
                        flat_input=spec.flat_input,
                        forecast_steps=spec.forecast_steps)
    ck = os.path.join(MODEL_DIR, f"{spec.name}.moe.pt")
    if not os.path.exists(ck):
        raise SystemExit(f"缺少 {ck}，请先训练（去掉 --export-only）")
    # ⚠️ 用 load_with_aux 而不是 load_state_dict(strict=True)：
    #    ckpt 可能还没带上预测分支（`forecast_steps` 是后加的）——
    #    strict=True 会报 "Missing key(s): seq_proj.*, forecast_head.*"，
    #    于是「旧的、但完全可用的权重」也导不出来。
    #    这里只放行 `aux.` / `seq_proj.` / `forecast_head.` 三类，其余仍然严格。
    from sports_ai.nn.forecast_aux import load_with_aux
    load_with_aux(model, torch.load(ck, map_location="cpu"))
    model.eval()

    names = list(spec.onnx_inputs)
    if not names:
        raise SystemExit(f"{spec.name}: 未登记 onnx_inputs（名字对不上会静默失效）")

    n = spec.pad_to
    # 按名字构造输入张量。flat_input（选择器）是 [B,F]，无节点轴。
    if spec.flat_input:
        main = torch.zeros(1, spec.feat_dim, dtype=torch.float32)
    else:
        main = torch.zeros(1, n, spec.feat_dim, dtype=torch.float32)
    mask = torch.ones(1, n, dtype=torch.float32)
    adj3 = torch.zeros(1, n, n, dtype=torch.float32)
    adj4 = torch.zeros(1, spec.n_types, n, n, dtype=torch.float32)
    tmask = torch.ones(1, spec.n_types, dtype=torch.float32)

    def tensor_of(nm: str):
        if nm in ("node_feat", "athlete_feat", "features", "x"):
            return main
        if nm == "mask":
            return mask
        if nm == "adj":
            return adj3
        if nm == "adj_by_type":
            return adj4
        if nm == "type_mask":
            return tmask
        raise SystemExit(f"{spec.name}: 未知的 ONNX 输入名 {nm}")

    inputs = tuple(tensor_of(nm) for nm in names)
    device = next(model.parameters()).device

    class _Main(torch.nn.Module):
        """只回**主输出**：next_step 是训练期辅助信号，不进部署契约。"""

        def __init__(self, m, nms, flat):
            super().__init__()
            self.m = m
            self.nms = nms
            self.flat = flat

        def forward(self, *a):
            d = {nm: t for nm, t in zip(self.nms, a)}
            x = d.get("node_feat", d.get("athlete_feat", d.get("features", d.get("x"))))
            if self.flat:
                # 选择器：Java 喂 [1,F]，模型内部要 [1,1,F] + mask=[1,1]
                x = x.unsqueeze(1)
                mk = torch.ones(x.shape[0], 1, device=x.device, dtype=x.dtype)
            else:
                # 序列型模型（forecast_*）的输入就是 [B,L,F]，**没有 mask 输入**
                # → 传 None，由 UpgradedMoE.forward 补一张全 1 掩码。
                mk = d.get("mask")
            # ⚠️ UpgradedMoE.forward 只吃 4 个参数 (x, mask, adj, type_mask)：
            #    「3 维压平邻接」与「4 维多边型邻接」是**同一个位置**的两种形态，
            #    同时传会报 "takes 5 positional arguments but 6 were given"。
            adj = d.get("adj_by_type", d.get("adj"))
            return self.m(x, mk, adj, d.get("type_mask"))[0]

    w = _Main(model, names, spec.flat_input).to(device).eval()
    axes = {}
    for nm in names:
        if nm in ("node_feat", "athlete_feat", "x"):
            # 序列模型的轴 1 是序列长度，同样是动态轴（GRU 对长度无要求）；
            # forecast 的历史 onnx 是写死的 L_IN，放开后照样吃原长度输入。
            axes[nm] = {1: "N"}
        elif nm == "adj":
            axes[nm] = {1: "N", 2: "N"}
        elif nm == "adj_by_type":
            axes[nm] = {2: "N", 3: "N"}
        elif nm == "mask":
            axes[nm] = {1: "N"}
    # 输出形状与旧模型一致：pool → [B,out_dim]；逐行 → [B,N] 或 [B,N,out_dim]
    if spec.pool:
        axes["out"] = {}
    elif spec.out_dim == 1:
        axes["out"] = {1: "N"}
    else:
        axes["out"] = {1: "N"}

    path = os.path.join(MODEL_DIR, f"{spec.name}.onnx")
    # 注意：每个模型的输入顺序可能不同（关键看 Spec.onnx_inputs）
    torch.onnx.export(w, inputs, path, input_names=names, output_names=[spec.onnx_output],
                      dynamic_axes=axes, opset_version=17, dynamo=False)
    inline_weights(path)
    est = (1, spec.out_dim) if spec.pool else (
        (1, n) if spec.out_dim == 1 else (1, n, spec.out_dim))
    print(f"[{spec.name}] 导出 {os.path.basename(path)}  输入 {names}  输出 {est}")

    if args.verify:
        import onnxruntime as ort
        sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
        for n2 in (2, n):
            feed = {}
            for nm in names:
                if nm in ("node_feat", "athlete_feat", "x"):
                    feed[nm] = np.zeros((1, n2, spec.feat_dim), dtype=np.float32)
                elif nm == "features":
                    feed[nm] = np.zeros((1, spec.feat_dim), dtype=np.float32)
                elif nm == "mask":
                    feed[nm] = np.ones((1, n2), dtype=np.float32)
                elif nm == "adj":
                    feed[nm] = np.zeros((1, n2, n2), dtype=np.float32)
                elif nm == "adj_by_type":
                    feed[nm] = np.zeros((1, spec.n_types, n2, n2), dtype=np.float32)
                elif nm == "type_mask":
                    feed[nm] = np.ones((1, spec.n_types), dtype=np.float32)
            out = sess.run(None, feed)[0]
            print(f"  [verify] {'features' if spec.flat_input else 'N=' + str(n2)}"
                  f" → {out.shape}")
    return path


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--model", default="lane_advisor",
                   help="模型名，或 all / high（含高频）")
    p.add_argument("--iters", type=int, default=2000)
    p.add_argument("--batch", type=int, default=32)
    p.add_argument("--seed", type=int, default=20261007)
    p.add_argument("--lr", type=float, default=1e-3)
    p.add_argument("--w-lb", type=float, default=0.01, help="负载均衡正则权重")
    p.add_argument("--hidden", type=int, default=128)
    p.add_argument("--n-layers", type=int, default=6)
    p.add_argument("--n-experts", type=int, default=9)
    p.add_argument("--n-groups", type=int, default=3)
    p.add_argument("--warmup", type=int, default=200)
    p.add_argument("--log-every", type=int, default=500)
    add_device_arg(p)
    p.add_argument("--export-only", action="store_true")
    p.add_argument("--verify", action="store_true")
    args = p.parse_args()

    reg = registry()
    # tournament_gnn 不在此列：它已改为就地插 MoERepr（见 registry 里的说明）
    high = ["lane_advisor", "conflict_gnn", "referee_gnn", "teacher_gnn",
            "algorithm_selector", "ai"]
    if args.model == "all":
        names = list(reg)
    elif args.model == "high":
        names = high
    else:
        names = [args.model]
    device = resolve_device(getattr(args, "device", "auto"))
    seed_all(args.seed)

    for nm in names:
        if nm not in reg:
            raise SystemExit(f"未知模型 {nm}，可选：{list(reg)}")
        spec = reg[nm]
        if not args.export_only:
            train_one(spec, args, device)
        if args.verify or args.export_only:
            export_one(spec, args)


if __name__ == "__main__":
    main()
