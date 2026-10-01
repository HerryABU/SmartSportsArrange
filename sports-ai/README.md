# sports-ai — 运动会编排系统的 AI 训练侧

本目录是编排算法 AI 核心的**训练侧**（Python 3.12）。生产部署**不依赖**本目录：
训练完成后导出 `.onnx`，运行时由 Java 端 `com.sports.schedule.ai` 通过 onnxruntime 加载推理，
生产环境无需 Python 解释器。

## 目录结构

```
sports-ai/
├── requirements.txt          # 训练依赖（torch CPU / onnx / onnxruntime / onnxscript / networkx）
├── sports_ai/
│   ├── data/                 # 合成数据 + 特征契约
│   │   ├── generator.py      # 合成报名数据生成器（镜像真实「冲突簇」结构）
│   │   ├── features.py       # 16 维实例特征契约（兼项共现统计层）
│   │   └── gnn_io.py         # 冲突图 → GNN 输入编码（固定 shape 契约）
│   ├── models/               # 经典监督模型
│   │   ├── selector.py       # 算法选择器 MLP（硬解 vs 取消路径）
│   │   └── gnn.py            # 冲突簇 GNN（节点着色优先级）
│   ├── generative/           # ★ 货真价实的 GAN（对抗式网络）
│   │   ├── encoder.py        #   共享 GNN 编码器
│   │   ├── scheme.py         #   方案表示 + 组合约束损失（GenCO 式）
│   │   ├── oracle.py         #   真样本：贪心图着色（可行硬解）
│   │   ├── generator.py      #   生成器 G（噪声 + 冲突图 → 时间槽方案）
│   │   ├── discriminator.py  #   判别器 D（神经网络，非规则校验器）
│   │   ├── train_gan.py      #   minimax 对抗训练
│   │   └── export_gan.py     #   导出生成器/判别器 ONNX
│   ├── forecast/             # ★ 多步预测（Direct / Recursive / MIMO）
│   ├── curriculum/           # ★ 自步学习（课程训练 + 自改进）与难度测量器
│   ├── tournament/           # ★ 球赛赛制生成（循环/淘汰/混合/种子，含排球）
│   ├── train_selector.py     # 训练入口
│   ├── train_gnn.py
│   ├── export_onnx.py        # 统一导出 ONNX + onnxruntime 自检
│   └── onnx_utils.py         # 权重内联（单文件 .onnx）
├── models/                   # 产出：*.pt（中间）与 *.onnx（部署）
├── scripts/                  # setup_venv / train / export
└── tests/                    # 特征契约 / GAN / 赛制生成自检
```

## 快速开始

```powershell
# 1. 建虚拟环境（Python 3.12）+ 装依赖
.\scripts\setup_venv.ps1

# 2. 训练全部模型 + 导出 ONNX + 自检
.\scripts\train.ps1
```

产出 `models/` 下的 `.onnx`：`algorithm_selector` / `conflict_gnn` / `scheme_generator` /
`scheme_discriminator` / `forecast_mimo` / `forecast_direct`。

## 五大 AI 能力

| 能力 | 模块 | 说明 |
|:--|:--|:--|
| 算法选择 | `models/selector.py` | 16 维特征 → 硬解 / 取消路径（架构文档 5.1③）|
| GNN 辅助启发式 | `models/gnn.py` | 冲突图 → 节点着色优先级，中心簇先着色（5.1②）|
| **GAN 对抗生成** | `generative/` | **生成器 G + 判别器 D 神经网络 minimax 对抗**，组合约束损失（5.3）|
| 多步预测 | `forecast/` | Direct / Recursive / MIMO 三策略，让回溯提前发生（5.2）|
| 自步学习 | `curriculum/` | 难度测量器 + 从易到难课程 + 成功经验回流（5.4）|
| 球赛赛制生成 | `tournament/` | 圆桌轮转 / 淘汰对阵 / 混合 / 种子 / 排球（球赛编排）|

## 货真价实的 GAN（`generative/`）

这不是「生成器 + 规则校验」的降级版，而是真正的对抗训练：

- **生成器 G**（`generator.py`）：GNN 编码冲突图 + 每节点噪声 → 时间槽 logits，
  经 **straight-through Gumbel-Softmax** 输出硬方案；
- **判别器 D**（`discriminator.py`）：**可学习神经网络**，把方案拼进节点特征、图池化后判真/假；
- **真样本**（`oracle.py`）：贪心图着色求出的**可行硬解**；
- **假样本**：G 的输出（与真样本同为 one-hot 形态，D 只能靠「约束是否真满足」区分）；
- **minimax 训练**（`train_gan.py`）：D 最大化区分真假，G 最大化骗过 D **+** 最小化组合约束损失；
- **组合约束损失**（`scheme.py`，GenCO 式）：兼项冲突期望 / 行政时间保护禁止列表 / 槽容量。

调平手段：判别器学习率 `--d-lr-scale`（默认 0.4）、标签平滑 `--label-smooth`（0.9）、
判别器 dropout（0.2），避免 D 过快碾压 G 导致对抗梯度消失。

训练实况（参考）：判别器准确率在 0.78–1.0 间动态波动，生成方案残余冲突从 ~0.20 降到 ~0.08。

## 推理时的自对抗（生成推导中也对抗）

训练时的对抗只决定「模型长什么样」；**推理时的自对抗**决定「这一次生成的方案怎么逼出来」。
本仓库两层都做了：

1. **测试时对抗精修**（`refine.py` 的 `AdversarialRefiner`）：**冻结 G/D 参数**，在推理时对潜在
   噪声 `z` 做梯度上升——损失 = `-D(方案)`（骗过判别器）+ λ·组合约束（冲突/禁止/容量），
   迭代后硬化输出。实况：**残余冲突 0.199 → 0.129（↓35.3%）**，判别器分同步上升（更像真实解）。
2. **对抗精修器蒸馏**（`refiner.py` / `train_refiner.py`）：把上面这次迭代博弈**蒸馏成一次前向**
   （Java 不能反传），导出 `scheme_refiner.onnx`。实况：**残余冲突 0.087 → 0.021（↓76.3%）**。
3. **Java 端推理时博弈**（`com.sports.schedule.ai.AdversarialSchemeService`）：多轮
   「G 采样 → 精修器精修 → D 评判 → 计算真实冲突 → 择优」，即 G 生成、D 批评，在推理时交替。
   结果报告「单次生成基线冲突 → 自对抗后冲突」，保证自对抗不劣于基线。

```
训练时对抗：  G ←──── D          （决定模型参数）
推理时对抗：  G 生成 → 精修器 → D 批评 → 择优  （决定这一次的方案，多轮博弈）
```

## ONNX 契约（与 Java 端严格对齐）

### algorithm_selector.onnx — 算法选择

| 方向 | 名称 | 类型 | shape |
|:--|:--|:--|:--|
| 输入 | `features` | float32 | `[1, 16]` |
| 输出 | `strategy` | float32 | `[1, 2]` |

输入是**原始** 16 维实例特征（未归一化），归一化作为模型第一层固化。
16 维顺序（`features.py` 的 `FEATURE_NAMES` =  Java `InstanceFeatures.FEATURE_NAMES`）：

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
| 输入 | `adj` | float32 | `[1, 256, 256]`（二值，无自环）|
| 输入 | `mask` | float32 | `[1, 256]`（1=真实节点）|
| 输出 | `priority` | float32 | `[1, 256]` |

8 维节点特征（`gnn_io.py` = Java `ConflictGraphEncoder`）：
`track / athlete_count_norm / duration_norm / has_group / group_size_norm / pool_idx_norm / event_idx_norm / log_athlete_norm`。

### scheme_generator.onnx — GAN 生成器

| 方向 | 名称 | 类型 | shape |
|:--|:--|:--|:--|
| 输入 | `node_feat` / `adj` / `mask` | float32 | `[1,256,8]` / `[1,256,256]` / `[1,256]` |
| 输入 | `z` | float32 | `[1, 256, 8]`（噪声；推理取 0）|
| 输入 | `forbid` | float32 | `[1, 256, 16]`（禁止列表 = 行政时间保护）|
| 输出 | `logits` / `scheme` | float32 | `[1,256,16]` / `[1,256,16]`（one-hot）|

### scheme_discriminator.onnx / scheme_refiner.onnx — 判别器 / 精修器

| 模型 | 输入 | 输出 |
|:--|:--|:--|
| `scheme_discriminator` | `node_feat[1,256,8]` / `adj[1,256,256]` / `mask[1,256]` / `scheme[1,256,16]` | `logits[1,1]`（真/假）|
| `scheme_refiner` | `node_feat` / `adj` / `mask` / `init_logits[1,256,16]` / `forbid[1,256,16]` | `logits[1,256,16]`（精修方案）|

### forecast_mimo.onnx / forecast_direct.onnx — 多步预测

| 方向 | 名称 | 类型 | shape |
|:--|:--|:--|:--|
| 输入 | `x` | float32 | `[1, 12, 4]` |
| 输出 | `y` | float32 | `[1, 8]` |

## 球赛赛制生成（`tournament/`）

- `seeding.py`：标准种子排位（`seed_order(8)=[1,8,4,5,2,7,3,6]`，1/2 号种子分居半区）；
- `round_robin.py`：圆桌轮转法（单/双循环、分组循环、主客场平衡）；
- `elimination.py`：单淘汰对阵图（轮空 + 种子）、双淘汰（胜者组/败者组）；
- `hybrid.py`：蛇形分组 → 小组循环 → 交叉淘汰；
- `volleyball.py`：排球赛完整赛制（分组 + 交叉 + 局制）。

Java 侧对应 `com.sports.schedule.tournament`，REST 入口 `POST /api/tournament/generate`。

## 设计要点

- **稀疏冲突图 + 真实时长**：合成数据还原「短跑簇 / 跳跃簇 / 投掷簇」三大兼项共现结构，
  网络密度落在真实排课冲突图的 0.02–0.29 区间；单元时长取**真实量级 20–120 分钟**
  （与 Java 端 `ScheduleUnit.rawDuration` 同量级），容量紧张度靠「多年级（3 年级 × 12 项目 ≈ 36 单元）」
  与「天数」调节（tension 0.40–1.61）。
- **固定 shape 契约**：GNN/GAN 用稠密邻接矩阵乘法实现消息传递，`MAX_NODES=256`、`MAX_SLOTS=16` 补齐/截断，保证 `torch.onnx.export` 稳定导出。
- **归一化固化**：选择器把 `mean/std` 作为模型第一层导出，Java 端只喂原始特征。
- **失败即降级**：模型缺失/加载失败/推理异常时，Java 端一律回退规则编排，接口始终能出方案。
- ⚠️ **分布一致性**：特征口径一致还不够——**数值分布也要落在真实量级**，否则模型在真实数据上
  会塌缩（曾因用「赛次」把时长放大到数百分钟，导致模型对真实 20–120 分钟时长分布外）。
