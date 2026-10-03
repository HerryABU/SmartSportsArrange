# SmartSportsArrange · AI 编排模型详解

本目录是编排系统里**全部模型**的说明书：原理、输入、输出、具体参数、训练与导出方式、双端契约。

> 版本：2.8.5 ｜ 分支：`2.8.5` ｜ 最后核对：2026-10-03
>
> 一句话定位：**12 个 ONNX 模型全部本地推理，生产环境不依赖 Python**；
> 其中 `super_moe.onnx` 是「一个模型覆盖九类编排任务」的合并超级模型。

---

## 0. 全景：四档编排模式与模型的对应关系

| 档位 | 名称 | 用什么 | 典型耗时 | 依赖模型 |
|---|---|---|---|---|
| L1 | 规则模式 | 蛇形分组 + 固定分道 + 确定性 first-fit | 毫秒 | 无 |
| L2 | 启发式 | 贪心 + 冲突感知放置 + 匈牙利精确分道 | 秒 | 无 |
| L3 | 优化模式 | Timefold 约束求解 + GA + LNS + MNSA + ALNS + Fix-and-Optimize + 算子 bandit | 秒 | 无（纯算法链） |
| L4 | AI 模式 | **L3 全链路之上叠加 ONNX 推理**：算法选择、冲突簇着色、模型序当搜索种子、自对抗方案生成、AI 派遣款型、可解性诊断 | **分钟** | 12 个 onnx 全部 |

**关键设计**：L4 = L3 + AI，而不是替换。任何模型缺失或推理异常都**静默回退到 L3**，绝不编排失败。

---

## 1. 模型总清单（12 个）

| # | ONNX | 大小 | 类别 | 核心作用 | 后端消费方 |
|---|---|---|---|---|---|
| 1 | `super_moe.onnx` | 24.8 MB | 超级 MoE（GNN+CNN+Diffusion） | **九类编排任务统一决策** | `SuperMoeService` |
| 2 | `constraint_gnn.onnx` | 10.9 MB | 图神经网络 | 约束满足度评估与节点打分 | `ConstraintAwareGraphEncoder`（编码器就绪，主流程待接入） |
| 3 | `tournament_gnn.onnx` | 5.5 MB | 图神经网络 | 球类**赛制选择 + 种子排序 + 公平性** | `TournamentAiService` |
| 4 | `conflict_gnn.onnx` | 173 KB | 图神经网络 | 冲突簇着色优先级 | `OnnxInferenceService` |
| 5 | `algorithm_selector.onnx` | 478 KB | MLP | 判「硬解 / 取消」策略 | `OnnxInferenceService` |
| 6 | `lane_advisor.onnx` | 29 KB | MLP | 道次派遣优先级 | `LaneAdvisorService` |
| 7 | `scheme_generator.onnx` | 172 KB | 生成式 GAN-G | 生成候选编排方案 | `SchemeGeneratorService` / `AdversarialSchemeService` |
| 8 | `scheme_discriminator.onnx` | 152 KB | 生成式 GAN-D | 评判方案优劣 | `AdversarialSchemeService` |
| 9 | `scheme_refiner.onnx` | 160 KB | 生成式 Refiner | 方案精修 | `AdversarialSchemeService` |
| 10 | `scheme_diffusion.onnx` | 1.3 MB | Diffusion | 扩散去噪生成方案 | Python 侧（Java 未接入） |
| 11 | `forecast_direct.onnx` | 58 KB | 预测 | 直接预测 | Python 侧（Java 未接入） |
| 12 | `forecast_mimo.onnx` | 60 KB | 预测 | 多输入多输出预测 | Python 侧（Java 未接入） |

---

## 2. `super_moe.onnx` —— 统一编排超级模型（核心）

### 2.1 设计目标

用户诉求是「不要一堆各自为政的小模型，要一个**合并的超级大模型**」。本模型的九个专家头刚好覆盖九类编排任务：

| 头 | 常量 | 任务 | 对应现实问题 |
|---|---|---|---|
| 0 | `TASK_PROJECT` | 项目编排 | 项目排到哪天哪一时段 |
| 1 | `TASK_LANE` | 道次编排 | 径赛分道（含接力整队） |
| 2 | `TASK_BALL` | 球类编排 | 小组赛/循环赛排期 |
| 3 | `TASK_KNOCKOUT` | 淘汰赛 | 对阵与晋级 |
| 4 | `TASK_BLOCK` | 项目块完整性 | **防止见缝插针乱排，项目必须成块** |
| 5 | `TASK_CONFLICT` | 兼项避让 | 同一运动员不撞车 |
| 6 | `TASK_CAPACITY` | 装箱容量 | 场地容量与并行度 |
| 7 | `TASK_MAKESPAN` | 工期压缩 | 限定 x 天 / -1 尽快 |
| 8 | `TASK_RESECOND` | 二次编排 | **淘汰赛之后的第二遍编排（含道次）** |

### 2.2 原理（四路混合架构）

```
输入 → 投影 → ┌─ MultiGateMoE（9 专家 × expert_depth 层消息传递）
              │      路由器 = 实例特征 + **图级特征**（8 维）
              │      专家内含 LocalConvBlock（Conv1d, kernel=5）抽项目块局部结构
              ├─ n_global × GlobalBlock（Pre-LN 残差 FFN 主干）
              └─ SharedKnowledge（3 视角共享表征）
                        ↓ 局部 + 全局池化 + 结构上下文 三路融合
   ┌────────────┬────────────┬───────────┬───────────┐
 priority[N]  slot_logits   format[4]   days[1]     （gate_probs[9] 供诊断）
              （DiffusionDecoder，steps=8 步去噪）
```

**值得注意的是**：这一个模型内部同时含 **GNN（图注意力 + 类型化邻接）、CNN（LocalConvBlock）、Diffusion（DiffusionDecoder）、MoE（多门专家）**，即「GNN + Diffusion + CNN 混合」是在同一网络里达成的，不是三个模型的拼装。

### 2.3 输入（5 个张量，全部动态轴）

| 名称 | 形状 | 含义 |
|---|---|---|
| `node_feat` | `[B, N, 20]` | 每单元 20 维特征（时长、间隔、场地、运动员数、任务类型 one-hot…） |
| `adj_by_type` | `[B, 8, N, N]` | **8 类**带权二元邻接矩阵 |
| `type_mask` | `[B, 8]` | 哪些边类型在图里真实存在 |
| `mask` | `[B, N]` | 节点有效性（padding 屏蔽） |
| `graph_feat` | `[B, 8]` | **图级（实例级）特征**，喂给路由器做实例级分工 |

**8 类边**（顺序即 ONNX 通道号，双端契约）：

| 通道 | 常量 | 含义 |
|---|---|---|
| 0 | `E_ATHLETE` | 兼项：共享运动员 |
| 1 | `E_BLOCK` | 项目块：同 `group_key`，必须整块相邻 |
| 2 | `E_VENUE` | 场地独占 |
| 3 | `E_POOL` | 同并发池竞争 |
| 4 | `E_TIME` | 装箱 / 间隔耦合 |
| 5 | `E_LANE` | 同道次 / 同批次 |
| 6 | `E_BRACKET` | 淘汰赛晋级关系 |
| 7 | `E_TEAM` | 同队（球类） |

**图级特征 8 维**（Python 与 Java 逐位对齐，已由 `SuperScheduleEncoderTest.graphFeatMatchesPythonContract` 钉死）：

| 维度 | 含义 |
|---|---|
| 0 | 冲突密度（边数 / 可能边数） |
| 1 | 单元规模（N 归一化） |
| 2 | 场地数（**有窗口的场地数** / 12） |
| 3 | 天数 / 10 |
| 4 | 时间目标三态（`x 天→0.5` / `0 不限→0.0` / `-1 尽快→1.0`） |
| 5 | 并行度（时段内**不同场地数** / 12） |
| 6 | 填充率（需求分钟 / 总容量，**必须用 float 除**，整型相除会被截断成 0） |
| 7 | 块压力（同 `group_key` 成组比例） |

### 2.4 输出（5 个）

| 名称 | 形状 | 含义 |
|---|---|---|
| `priority` | `[B, N]` | 单元优先级（解码时降序摆放） |
| `slot_logits` | `[B, N, MAX_SLOTS=16]` | 每单元进每个时间桶的打分 |
| `gate_probs` | `[B, 9]` | 专家路由权重（**诊断用**：看是否专家塌缩） |
| `format_logits` | `[B, 4]` | 球类赛制（group / round_robin / knockout / hybrid） |
| `days_estimate` | `[B, 1]` | 工期估计 |

### 2.5 具体参数（当前线上权重）

| 项 | 值 | 说明 |
|---|---|---|
| `hidden` | **128** | 隐藏宽度 |
| `steps` | 8 | Diffusion 去噪步数 |
| `expert_depth` | 2 | 每个专家内部堆叠的消息传递层数 |
| `n_global` | 2 | MoE 之后的主干深层推理块数 |
| `n_experts` | 9 | 等于任务数 |
| `n_edges` | 8 | 边类型数 |
| `n_slots` | 16 | 时间桶上限（`MAX_SLOTS`） |
| `node_feat` | 20 | `NODE_FEAT_DIM` |
| `dropout` | 0.1 | |
| 参数量 | **5,956,180** | 实测 |
| ONNX 大小 | 24,821,617 B | opset 17，`dynamo=False` |

**训练指标（2026-10-03 补训收敛后）**

| 指标 | 值 |
|---|---|
| `val_loss` | **1.022** |
| `val_priority_mse` | **0.00357**（追平浅层版 0.0035） |
| `val_slot_mse` | 0.7847 |
| `min_expert_usage` | 0.050 |
| `route_entropy` | **0.967**（越接近 1 越均衡，专家没塌缩） |

> ⚠️ **训练预算铁律**：深层网络必须同步加训练预算。本模型曾因只训 5 轮而 `val_loss` 1.136 / `pri_mse` 0.0168，被误读成「深层不如浅层」；
> 补训到第 3 段即追平。现已做成代码机制 —— `sports_ai/budget.py`，训练启动时按 `hidden² × 深度` 推导建议轮数，低于建议值 60% 会打醒目 WARN。

### 2.6 训练与导出

```bash
cd sports-ai
# 训练（epochs=0 表示按深度自动推导预算）
python -m sports_ai.train_super_moe --samples 500 --epochs 6 --hidden 128 --steps 8 \
    --expert-depth 2 --n-global 2 --seed 2101 --device cpu --resume --patience 10

# 导出（必须从权重 meta 还原结构，写死默认值会 shape 不匹配）
python -m sports_ai.export_super_moe_onnx

# 评测（含 random / greedy / GA一条龙 / model / ai 混合 五种口径）
python -m sports_ai.evaluate_super_moe --seeds 3 --out ../_trash/eval.json
```

---

## 3. GNN 系列

### 3.1 `constraint_gnn.onnx` —— 约束图神经网络

| 项 | 内容 |
|---|---|
| 原理 | 类型化邻接的消息传递 + 残差 FFN，逐节点打分 |
| 输入 | `node_feat[B,N,16]` / `adj_by_type[B,6,N,N]` / `type_mask[B,6]` / `mask[B,N]` |
| 输出 | 节点打分（约束满足度） |
| 参数 | `hidden=160` / `layers=6` / `n_types=6` / dropout 0.1 |

⚠️ 现状：Java 侧 `ConstraintAwareGraphEncoder` 已实现且单测通过，但**主流程尚未接入**（`super_moe` 承担了它的职责）。属「能力就绪、未接线」。

### 3.2 `tournament_gnn.onnx` —— 球类赛制 GNN

| 项 | 内容 |
|---|---|
| 原理 | 球队图为节点，4 类约束边（同单位回避、实力、场地、晋级）做消息传递；三个头分别出赛制/种子/公平性 |
| 输入 | `node_feat[B,N,14]` / `adj_by_type[B,4,N,N]` / `type_mask[B,4]` / `mask[B,N]` |
| 输出 | `format_logits[B,3]`（赛制）/ `seed_scores[B,N]`（种子序）/ `fairness_cost[B,N]`（公平性代价） |
| 参数 | `hidden=160` / `layers=5` / `n_types=4` |

**训练指标**：`val_fmt_ce=0.529` / `val_fmt_acc=0.981` / `val_seed_spearman=0.997` / `val_fair_mse=0.0059`

### 3.3 `conflict_gnn.onnx` —— 冲突簇着色 GNN

| 项 | 内容 |
|---|---|
| 原理 | 冲突图着色，输出每单元的着色优先级（决定兼项避让顺序） |
| 输入 | `node_feat[1,N,16]` / `adj[1,N,N]` / `mask[1,N]` |
| 输出 | `priority[1,N]` |
| 超图支持 | 节点数 > `MAX_NODES=1024` 时由 `HierarchicalGnnEncoder` 并查集聚簇分层推理 |

---

## 4. 生成式模型（自对抗方案生成）

`AdversarialSchemeService` 在推理时跑「生成 → 精修 → 评判 → 择优」的多轮对抗：

| 模型 | 输入 | 输出 | 作用 |
|---|---|---|---|
| `scheme_generator.onnx` | `node_feat / adj / mask / z / forbid` | `logits` / `scheme` | 从噪声生成候选方案 |
| `scheme_refiner.onnx` | 同上 + 候选 | 修正后方案 | 局部精修 |
| `scheme_discriminator.onnx` | 同上 | 优劣打分 | 评判并择优 |
| `scheme_diffusion.onnx` | `node_feat[B,N,16]` / `adj[B,N,N]` / `mask[B,N]` / `z[B,N,16]` | `logits` | 扩散式去噪生成 |

**基线始终作为候选参与择优** —— 生成式模块只可能让结果变好，不可能变差。

---

## 5. 辅助模型

### 5.1 `algorithm_selector.onnx`

| 项 | 内容 |
|---|---|
| 原理 | 16 维实例特征 → 判「硬解 / 取消」策略 |
| 输入 | `features[1,16]`（`InstanceFeatures.extract`） |
| 输出 | `strategy` |
| 消费方 | `OnnxInferenceService`，在求解前给策略建议 |

### 5.2 `lane_advisor.onnx`

| 项 | 内容 |
|---|---|
| 原理 | 运动员特征 → 派遣优先级（用于 `ArrangeStyle.AI` 款型） |
| 输入 | `athlete_feat` / `mask` |
| 输出 | `priority` |
| 消费方 | `LaneAdvisorService`，接入 `ArrangementService.buildAiSeedRank` |

### 5.3 `forecast_direct.onnx` / `forecast_mimo.onnx`

| 项 | 内容 |
|---|---|
| 原理 | 赛程/成绩趋势预测（单入单出 / 多入多出两种） |
| 输入 | `x` |
| 输出 | `y` |
| 状态 | **Java 未接入**，Python 侧能力保留 |

---

### 5.4 `referee_gnn.onnx` —— 裁判编排独立模型

| 项 | 内容 |
|---|---|
| **设计动机** | 裁判派遣原来是纯规则（专长优先 → 负载均衡 → 并行组次不重用）。规则能保证「不出错」，但表达不了「专长匹配 / 负载 / 保护时段 / 同单位回避 / 经验」这些因素之间该怎样**加权取舍**——那正是模型的强项 |
| 原理 | 类型化邻接消息传递 + Pre-LN 残差 FFN，**逐裁判输出派遣优先级**。模型只负责**排序**，「并行组次不得重用同一裁判」等硬规则仍由 Java 把关，因此模型输出再离谱也不会产生不合规派遣 |
| 输入 | `node_feat[B,N,12]` / `adj_by_type[B,4,N,N]` / `type_mask[B,4]` / `mask[B,N]` |
| 输出 | `priority[B,N]`（越高越先派） |
| **12 维特征** | 专长匹配度 / 负载比例 / 可用时段比例 / 受保护 / 经验等级 / 同单位 / 并行冲突风险 / 历史派遣归一 / 连续工作长度 / 搭档协同 / 时段偏好匹配 / 资历归一 |
| **4 类边** | 0 同专长竞争 / 1 同单位回避 / 2 同受保护 / 3 负载耦合 |
| 具体参数 | `hidden=160` / `layers=5` / `n_types=4` / `node_feat=12` / dropout 0.1 |
| 训练指标 | `val_mse=**0.00202**`（best at epoch 37） |
| ONNX 大小 | 3,088,772 B（opset 17，`dynamo=False`，N 动态轴） |
| 消费方 | `RefereeAiService` + `RefereeGnnEncoder`（独立模型层） |

```bash
cd sports-ai
python -m sports_ai.referee_advisor --mode both --samples 800 --epochs 40 \
    --hidden 160 --layers 5 --device cpu     # 训练 + 导出
```

> ⚠️ **「合并进主模型」是下一步**：那需要把 `super_moe` 的任务数 9→10 并改输出契约，
> 必须连同全量重训一起做，不能顺手改（改了不重训 → shape 不匹配 → 静默回退规则）。

---

## 6. 训练预算机制（`sports_ai/budget.py`）

**为什么需要**：2026-10-03 实测教训 —— 把 `hidden` 96→128 并加深后仍按 5-6 轮训，
`val_loss` 从 0.951 涨到 1.136、`pri_mse` 从 0.0035 涨到 0.0168，**看起来像「深层架构更差」**，
差点去改架构。补训到第 3 段后 `val_loss` 回到 1.022、`pri_mse` 0.00357 —— **纯粹是预算没跟上**。

**机制**（三个训练脚本共用，唯一真相源）：

- 参数量近似 ∝ `hidden² × 深度`，因此**宽度按平方放大、深度按线性放大**
- 按各模型自己的深度口径换算：`super_moe` 用 `expert_depth + n_global + 1`，GNN 用 `layers + 1`
- 实测推导：`96/1/2 → 24 轮`、`128/2/2 → 30 轮`、`192/3/3 → 94 轮`、`256/4/4 → 216 轮`
- 显式传的轮数 < 建议值 60% 时打 **醒目 WARN**，并在 checkpoint `meta` 里记录
  `epochs_run` / `budget_epochs` / `budget_satisfied`，让「这份权重训够没有」可诊断

---

## 7. 双端契约（Python ↔ Java 逐位对齐）

改任何一维都必须**双端同步改 + 重训全部受影响模型**，否则 shape 不匹配会**静默回退规则**（不报错，最难查）。

| 契约 | 维度 | 钉死的测试 |
|---|---|---|
| 节点特征 | 20 | `SuperScheduleEncoderTest` |
| 边类型 | 8 | 同上 |
| 图级特征 | 8 | `graphFeatMatchesPythonContract`（逐位断言，含填充率 225/720 非整值） |
| 并行度口径 | 时段内**不同场地数**（去重） | `parallelCountsDistinctVenuesPerSlot` |
| 时间桶 | `(day, window_idx)`，`MAX_SLOTS=16` | — |
| 节点上限 | `MAX_NODES=1024`（超出走分层聚簇） | `HierarchicalGnnEncoder` 测试 |

**已知踩坑**（都已在测试里钉住）：

1. 场地数：Java 必须取「有窗口的场地数」（`nActiveVenues`），不能取场地表总数
2. 并行度：Java 按窗口条数算会偏大，必须按**时段内不同场地 Set 去重**
3. 填充率：`demand / totalCapacity` 两个 `int` 相除会被截断成 0，必须 `(float)` 强转
4. 动态轴：`torch.onnx.export` 必须 `dynamo=False` + opset 17（PyTorch ≥2.6 默认 dynamo 导出器与 `dynamic_axes` 冲突）
5. `nn.MultiheadAttention` 导出会被常量折叠 → 手写 `HeadAttention`（reshape 全取 `x.shape`）

---

## 8. 导出物与校验

**动态 N 闸门**（`_trash/check_onnx_dyn.py`）：同一份 ONNX 必须能跑任意规模。
实测通过：`N = 1 / 3 / 4 / 12 / 24 / 47`，输入 `['node_feat','adj_by_type','type_mask','mask','graph_feat']`，
输出形状随 N 正确伸缩 `[(2,N), (2,N,16), (2,9), (2,4), (2,1)]`。

**回归基线**：全量测试 **543 passed / 0 failed**。
