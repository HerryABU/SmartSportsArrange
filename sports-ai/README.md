# sports-ai — 运动会编排系统的 AI 训练侧

本目录是编排算法 AI 核心的**训练侧**（Python 3.12）。生产部署**不依赖**本目录：
训练完成后导出 `.onnx`，运行时由 Java 端 `com.sports.schedule.ai` 通过 onnxruntime 加载推理，
生产环境无需 Python 解释器。

## 目录结构

```
sports-ai/
├── requirements.txt          # 训练依赖（torch CPU / onnx / onnxruntime / numpy / networkx）
├── sports_ai/
│   ├── data/
│   │   ├── generator.py      # 合成报名数据生成器（镜像真实「冲突簇」结构）
│   │   ├── features.py       # 16 维实例特征契约（兼项共现统计层）
│   │   └── gnn_io.py         # 冲突图 → GNN 输入编码（固定 shape 契约）
│   ├── models/
│   │   ├── selector.py       # 算法选择器 MLP（硬解 vs 取消路径）
│   │   └── gnn.py            # 冲突簇 GNN（节点着色优先级）
│   ├── train_selector.py     # 训练入口
│   ├── train_gnn.py
│   └── export_onnx.py        # 统一导出 ONNX + onnxruntime 自检
├── models/                   # 产出：selector.pt / gnn.pt / *.onnx
├── scripts/                  # setup_venv / train / export
└── tests/                    # 特征契约自检（不依赖 torch）
```

## 快速开始

```powershell
# 1. 建虚拟环境（Python 3.12）+ 装依赖
.\scripts\setup_venv.ps1

# 2. 训练 + 导出 ONNX + 自检
.\scripts\train.ps1
```

产出 `models/algorithm_selector.onnx` 与 `models/conflict_gnn.onnx`。

## ONNX 契约（与 Java 端严格对齐）

### algorithm_selector.onnx — 算法选择

| 方向 | 名称 | 类型 | shape |
|:--|:--|:--|:--|
| 输入 | `features` | float32 | `[1, 16]` |
| 输出 | `strategy` | float32 | `[1, 2]` |

- 输入是**原始** 16 维实例特征（未归一化），归一化作为模型第一层固化。
- 输出 logits：`softmax` 后 index 0 = 硬解，index 1 = 取消路径。

16 维特征顺序（`sports_ai/data/features.py` 的 `FEATURE_NAMES`，Java 端 `InstanceFeatures` 逐位对齐）：

```
0 unit_count  1 demand_minutes  2 supply_minutes  3 tension_ratio
4 multi_event_athlete_ratio  5 athlete_count  6 conflict_edges
7 conflict_density  8 conflict_components  9 max_degree  10 avg_degree
11 pool_count  12 day_count  13 avg_duration  14 duration_cv  15 group_count
```

### conflict_gnn.onnx — 冲突簇着色优先级

| 方向 | 名称 | 类型 | shape |
|:--|:--|:--|:--|
| 输入 | `node_feat` | float32 | `[1, 256, 8]` |
| 输入 | `adj` | float32 | `[1, 256, 256]`（二值，无自环） |
| 输入 | `mask` | float32 | `[1, 256]`（1=真实节点） |
| 输出 | `priority` | float32 | `[1, 256]` |

8 维节点特征（`gnn_io.py`，Java 端 `ConflictGraphEncoder` 对齐）：
`track / athlete_count_norm / duration_norm / has_group / group_size_norm / pool_idx_norm / event_idx_norm / log_athlete_norm`。

## 设计要点

- **稀疏冲突图**：合成数据还原「短跑簇 / 跳跃簇 / 投掷簇」三大兼项共现结构，网络密度
  落在真实排课冲突图的 0.02–0.29 区间。
- **固定 shape 契约**：GNN 用稠密邻接矩阵乘法实现消息传递，`MAX_NODES=256` 补齐/截断，
  保证 `torch.onnx.export` 稳定导出、Java 端无需复刻归一化常数。
- **归一化固化**：选择器把 `mean/std` 作为模型第一层导出，Java 端只喂原始特征。
