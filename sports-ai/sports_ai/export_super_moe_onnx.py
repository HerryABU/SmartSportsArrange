"""导出 SuperScheduleMoE → ONNX（super_moe.onnx）。

一个模型覆盖全部九类编排，导出后 Java 侧只需一个 session。

## 契约（与 Java ``SuperScheduleEncoder`` / ``SuperMoEService`` 逐位对齐）

输入::

    node_feat   [B, N, 20]
    adj_by_type [B, E=8, N, N]
    type_mask   [B, E]
    mask        [B, N]

输出::

    priority       [B, N]        单元调度优先级（越高越先排）
    slot_logits    [B, N, K=16]  时间槽分配 logits（argmax 即槽位）
    task_probs     [B, 9]        九类任务的专家组合权重
    format_logits  [B, 4]        球类赛制（group/rr/knockout/hybrid）
    days_estimate  [B, 1]        预计所需天数

B / N 均为动态轴：同一个模型要能从 20 个单元跑到数千个。

⚠️ 导出必须显式 ``dynamo=False``（PyTorch ≥2.6 的 dynamo 导出器与 dynamic_axes 冲突）。
"""

from __future__ import annotations

import os

import torch

from sports_ai.models.super_moe import (
    GRAPH_FEAT_DIM,
    MAX_SLOTS,
    N_EDGES,
    N_FORMATS,
    N_TASKS,
    NODE_FEAT_DIM,
    SuperScheduleMoE,
)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL_DIR = os.path.join(ROOT, "models")
LAYERS_HINT = 3
# ⚠️ 下面两个值**必须从权重里读**，不能写死：hidden/steps 决定模型结构，
#    训练用 `--steps 4` 而这里写死 8 时，load_state_dict 会 shape 不匹配直接失败，
#    服务端表现为「模型加载失败 → 静默回退规则」。训练脚本已把结构元信息
#    焊进 checkpoint，所以这里读权重、绝不猜常量（仅当权重无 meta 时才回退默认值）。
HIDDEN = 192
STEPS = 8
# 旧权重（单层专家版）没有这两个键，回退到与新模型一致的默认值
EXPERT_DEPTH = 6
N_GLOBAL = 3
# 嵌套 MoE 专家（2026-10-05）：主 MoE 内嵌若干「专项 MoE」作为子专家
N_NESTED = 4
NEST_LAYERS = 6
NEST_EXPERTS = 4
N_STEPS_OUT = 4


def main() -> None:
    ckpt = os.path.join(MODEL_DIR, "super_moe.pt")
    if not os.path.exists(ckpt):
        raise SystemExit(f"缺少权重 {ckpt}，请先跑 train_super_moe")
    ck = torch.load(ckpt, map_location="cpu")
    meta = ck["meta"] if isinstance(ck, dict) and "meta" in ck else {}
    # ⚠️ 用局部小写名接收：直接写 HIDDEN = int(meta.get("hidden", HIDDEN))
    #    会在赋值右侧引用同名局部变量 → UnboundLocalError: referenced before assignment
    hidden = int(meta.get("hidden", HIDDEN))
    steps = int(meta.get("steps", STEPS))
    edepth = int(meta.get("expert_depth", EXPERT_DEPTH))
    nglo = int(meta.get("n_global", N_GLOBAL))
    nnest = int(meta.get("n_nested", N_NESTED))
    nlay = int(meta.get("nest_layers", NEST_LAYERS))
    nex = int(meta.get("nest_experts", NEST_EXPERTS))
    nstp = int(meta.get("n_steps", N_STEPS_OUT))
    print(f"[export] 从权重读取结构 hidden={hidden} steps={steps} "
          f"expert_depth={edepth} n_global={nglo} n_nested={nnest} "
          f"nest_layers={nlay} nest_experts={nex} n_steps={nstp}")
    model = SuperScheduleMoE(node_feat=NODE_FEAT_DIM, hidden=hidden, steps=steps,
                             expert_depth=edepth, n_global=nglo, n_nested=nnest,
                             nest_layers=nlay, nest_experts=nex, n_steps=nstp)
    model.load_state_dict(ck["state_dict"] if isinstance(ck, dict) and "state_dict" in ck else ck)
    model.eval()

    B, N = 2, 24
    node_feat = torch.rand(B, N, NODE_FEAT_DIM)
    adj = (torch.rand(B, N_EDGES, N, N) * (torch.rand(B, N_EDGES, N, N) > 0.85)).float()
    type_mask = torch.ones(B, N_EDGES)
    mask = torch.ones(B, N)
    # 图级（实例级）特征：冲突密度 / 规模 / 场地数 / 天数 / 时间目标 / 并行度 / 填充率 / 块压力
    graph_feat = torch.rand(B, GRAPH_FEAT_DIM)

    out = os.path.join(MODEL_DIR, "super_moe.onnx")
    dynamic_axes = {
        "node_feat": {0: "B", 1: "N"},
        "adj_by_type": {0: "B", 2: "N", 3: "N"},
        "type_mask": {0: "B"},
        "mask": {0: "B", 1: "N"},
        "graph_feat": {0: "B"},
        "priority": {0: "B", 1: "N"},
        "slot_logits": {0: "B", 1: "N"},
        "task_probs": {0: "B"},
        "format_logits": {0: "B"},
        "days_estimate": {0: "B"},
        # 新增两个输出（合并 lane_advisor / GAN 判别器）：N,K 都必须是动态轴，
        # 否则导出时被常量折叠成 dummy 的形状，服务端喂别的 N 直接 Reshape 崩。
        "lane_logits": {0: "B", 1: "N"},
        "quality_score": {0: "B"},
        # 后续步骤预测（2026-10-05）：[B, n_steps]，B 必须是动态轴
        "next_step": {0: "B"},
    }
    torch.onnx.export(
        model,
        (node_feat, adj, type_mask, mask, graph_feat),
        out,
        input_names=["node_feat", "adj_by_type", "type_mask", "mask", "graph_feat"],
        # ⚠️ 输出顺序 = SuperScheduleMoE.forward 的返回顺序，**新输出只能往后追加**。
        output_names=["priority", "slot_logits", "task_probs", "format_logits",
                      "days_estimate", "lane_logits", "quality_score", "next_step"],
        dynamic_axes=dynamic_axes,
        opset_version=17,
        do_constant_folding=True,
        # PyTorch ≥2.6 默认走 dynamo 导出器，与 dynamic_axes 冲突
        dynamo=False,
    )
    print(f"导出完成: {out}  ({os.path.getsize(out) / 1024 / 1024:.2f} MB)")
    print(f"  输入 {NODE_FEAT_DIM} 维节点特征 / {N_EDGES} 类约束边 / {MAX_SLOTS} 个时间槽 "
          f"/ {GRAPH_FEAT_DIM} 维图级特征")
    print(f"  输出 优先级[N] + 槽位logits[N,{MAX_SLOTS}] + 任务权重[{N_TASKS}] + 道次logits + 质量分 "
          f"+ 赛制[{N_FORMATS}] + 天数[1] + 后续步骤[{nstp}]")
    print(f"  专家池 {model.moe.expert_kinds()}")


if __name__ == "__main__":
    main()
