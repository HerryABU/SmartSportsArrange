# SmartSportsArrange · AI 编排模型详解

> 本文覆盖 **15 个 ONNX 模型**：每个模型的**原理**、**如何训练**、**输入输出张量**、
> **数据是如何一路转化成张量的**、**Java 侧谁消费**、**失败时如何降级**。
>
> 配套文档：
> - `dataflow.md` —— 端到端数据转化链路（数据库/Excel → 编码 → 张量 → ONNX → 业务对象）
> - `benchmarks.md` —— 实测对比（纯算法 vs AI）、前沿方法检索与落地
> - `audit.md` —— 需求逐条核查表与缺口清单
>
> 版本：2026-10-04（17 专家超级模型合并完成）

---

## 0. 全景：四档编排模式与模型的关系

系统对外暴露的是**四档编排模式**，模型只是其中最高一档的实现手段：

| 档 | 名称 | 实现 | 典型耗时 | 要不要模型 |
|---|---|---|---|---|
| L1 | `rule` 规则 | 确定性 first-fit / 蛇形分组 | 毫秒 | ❌ 纯算法 |
| L2 | 启发式 | 贪心 + 冲突感知放置 + 匈牙利精确分道 | 秒 | ❌ 纯算法 |
| L3 | `optimize` 优化 | Timefold 约束求解 + GA / LNS / MNSA / ALNS / Fix-and-Optimize | 秒 | ❌ 纯算法 |
| L4 | `ai` | ONNX 推理 + 混合链路（模型序当种子 → 搜索精修） | 分钟 | ✅ 全部模型在此 |

**关键设计**：L4 **不替代** L1-L3，而是**站在它们之上**。这是被实测逼出来的 ——
纯 ONNX 链路在 `HELL` 魔鬼档的「未排数」上曾**比 `random` 还差**（16.33 vs 15.33），
根因是训练标签来自贪心解（天花板 = 贪心，而贪心输给 GA）。所以现在 L4 =
「模型序当 GA 初始种子 → GA → LNS 破坏-重建 → 多起点重启」，
最终解在「搜索最优 ∪ 全部种子」中取，**只可能变好、不可能变差**。

---

## 1. 模型总清单（15 个）

| # | ONNX | 大小 | 类别 | 核心作用 | 后端消费方 | 状态 |
|---|---|---|---|---|---|---|
| 1 | `super_moe.onnx` | 42.0 MB | 超级 MoE（GNN+CNN+Diffusion，**异构门控**） | **十七类编排能力统一决策** | `SuperMoeService` | ✅ 主链路 |
| 2 | `constraint_gnn.onnx` | 10.4 MB | 图神经网络 | 约束满足度评估与节点打分 | `ConstraintAwareGraphEncoder` | 🟡 编码器就绪、主流程未接线 |
| 3 | `tournament_gnn.onnx` | 5.3 MB | 图神经网络 | 球类**赛制选择 + 种子排序 + 公平性** | `TournamentAiService` | ✅ |
| 4 | `conflict_gnn.onnx` | 1.4 MB | 图神经网络（160×6） | 冲突簇着色优先级 | `OnnxInferenceService` | ✅ |
| 5 | `algorithm_selector.onnx` | 2.9 MB | MLP（192×5） | 判「硬解 / 取消」策略 | `OnnxInferenceService` | ✅ |
| 6 | `lane_advisor.onnx` | 158 KB | MLP（160） | 道次派遣优先级 | `LaneAdvisorService` | ✅ |
| 7 | `scheme_generator.onnx` | 945 KB | 生成式 GAN-G（160） | 生成候选编排方案 | `AdversarialSchemeService` | ✅ |
| 8 | `scheme_discriminator.onnx` | 839 KB | 生成式 GAN-D（160） | 评判方案优劣 | `AdversarialSchemeService` | ✅ |
| 9 | `scheme_refiner.onnx` | 859 KB | 生成式 Refiner（160） | 方案局部精修 | `AdversarialSchemeService` | ✅ |
| 10 | `scheme_diffusion.onnx` | 1.3 MB | Diffusion（192×6） | 扩散去噪生成方案 | —— | 🟡 Java 未接入 |
| 11 | `forecast_direct.onnx` | 57 KB | 预测（160） | 直接多步预测 | —— | 🟡 Java 未接入 |
| 12 | `forecast_mimo.onnx` | 58 KB | 预测（160） | 多输入多输出预测 | —— | 🟡 Java 未接入 |
| 13 | `referee_gnn.onnx` | 3.0 MB | 图神经网络（160×5） | 裁判派遣优先级 | `RefereeAiService` | ✅ |
| 14 | `teacher_gnn.onnx` | 2.9 MB | 图神经网络（160×5） | 教师（行政）规避优先级 | `TeacherAiService` | ✅ |
| 15 | `forecast_recursive.onnx` | —— | 预测 | 递归多步预测（第 3 种策略） | —— | 🟡 训练产出，未导出线上件 |

> **模型不进 git**：`.gitignore` 已忽略 `*.onnx`（26 个文件约 90 MB，且每次重训都变）。
> 分发方式见 `docs/MODELS.md`（清单 + sha256 校验 + 同步脚本）。
> 缺失时各模型按登记的降级行为运行，**不会崩**（所有 AI 失败都回退规则）。

---

## 2. `super_moe.onnx` —— 统一编排超级模型（核心）

### 2.1 合并演进：把 15 个模型收敛到 1 个

用户诉求是「不要一堆各自为政的小模型，要一个**合并的超级大模型**」。

| 轮次 | 任务数 | 专家是否异构 | 结果 |
|---|---|---|---|
| 初版 | 9 | ❌ 全同构 | 九类编排任务 |
| 上一轮 | 9 → 11 | ❌ 全同构 | 合并裁判编排 / 教师规避。**但只是摊薄容量**：`load_balancing_loss` 把使用率强行拉平均，每个专家学到的几乎是同一件事 |
| **本轮** | 11 → **17** | ✅ **分两层** | 合并 7 个独立模型（GAN 生成/判别、refiner、diffusion、lane_advisor、forecast×2），并引入**异构门控** |

#### 任务专家（0..10）—— 走**节点级**路由

| # | 常量 | 任务 | 对应现实问题 |
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
| 9 | `TASK_REFEREE` | 裁判编排 | 裁判派遣优先级（原独立模型 `referee_gnn`） |
| 10 | `TASK_TEACHER` | 教师规避 | 教师/行政被占用时段（原为纯规则） |

#### 能力专家（11..16）—— 走**图级**路由

| 常量 | 语义 | 替代的原独立模型 | 对应输出 |
|---|---|---|---|
| `TASK_GENERATE`=11 | 方案生成 | `scheme_generator.onnx`（GAN-G） | 复用 `slot_logits` |
| `TASK_REFINE`=12 | 方案精修 | `scheme_refiner.onnx` | 复用 `slot_logits` |
| `TASK_DIFFUSION`=13 | 扩散去噪 | `scheme_diffusion.onnx` | 复用 `slot_logits`（内部 `DiffusionDecoder`） |
| `TASK_DISPATCH`=14 | 道次派遣 | `lane_advisor.onnx` | **`lane_logits` [N,K]** |
| `TASK_FORECAST`=15 | 工期预测 | `forecast_direct` + `forecast_mimo` | **`days_estimate` [1]** |
| `TASK_QUALITY`=16 | 方案判别 | `scheme_discriminator.onnx`（GAN-D） | **`quality_score` [1]** |

### 2.2 为什么必须「异构门控」

任务专家答「**这个单元**怎么排」（看节点表征），
能力专家答「**这个赛会**需要多少生成/精修/派遣/预测」（只看图级上下文）。

实现（`MultiGateMoE.forward`）：

```python
q_base = h @ expert_embed.t() + ctx @ expert_embed.t()          # [B,N,E]
gate_term = stack([g(h) for g in gates]).mean(0)                # [B,N,U]  U = 11
# ⚠️ 拼接而不是相加：gate_term 是 [B,N,U]、q_base 是 [B,N,E]（E>U），
#    直接相加会按尾维广播 → 静默算错（batch=1 时还不报错）
q = cat([q_base[:, :, :U] + gate_term, q_base[:, :, U:]], dim=-1)
# 能力专家：门控**只由图级上下文决定**（去掉节点表征项与门控项）
cap = ctx[:, :1, :] @ expert_embed[U:, :].t()                    # [B,1,C]
q = cat([q[:, :, :U], cap.expand(B, N, -1)], dim=-1)
```

于是同一实例内所有节点对能力专家的权重相同 —— 它们成了**实例级专家**。
这与 Graph-MoE 综述的「路由粒度」一轴对应：光有负载均衡不够，
**输入依赖不同**才能保证专家真正分化。

### 2.3 架构（GNN + CNN + Diffusion + MoE 四路混合）

```
输入 → 投影 → ┌─ MultiGateMoE（17 专家 × expert_depth 层消息传递）
              │      任务专家门控 = 节点表征 + 结构上下文 + 图级上下文
              │      能力专家门控 = 仅图级上下文（实例级）
              │      专家内含 LocalConvBlock（Conv1d, kernel=5）抽项目块局部结构
              ├─ n_global × GlobalBlock（Pre-LN 残差 FFN 主干）
              └─ SharedKnowledge（3 视角共享表征，专家不直接通信）
                        ↓ 局部 + 全局池化 + 结构上下文 三路融合
   ┌────────┬──────────┬──────────┬────────┬──────────┬──────────┐
priority[N] slot_logits format[4] days[1] lane_logits quality[1]
            （DiffusionDecoder，steps=8 步余弦噪声调度）
```

**这一个模型内部同时含 GNN（图注意力 + 类型化邻接）、CNN（`LocalConvBlock`）、
Diffusion（`DiffusionDecoder`）、MoE（多门异构专家）** ——
「GNN + Diffusion + CNN 混合」是在同一网络里达成的，不是三个模型的拼装。

| 部件 | 说明 |
|---|---|
| 输入投影 | `Linear(20→hidden)` + LayerNorm + ReLU，按 mask 清零 pad |
| 类型嵌入 | 8 类边各一个 embedding，投影为结构上下文 |
| 专家 | `expert_depth` 层 `_ExpertStep`（边级 + 节点级双注意力 + 残差） |
| 门控 | `n_unit_tasks` 个 `Linear(hidden→11)`，与专家嵌入内积后 softmax |
| CNN 分支 | `Conv1d` kernel=5 ×3。⚠️ 刻意不用 `Conv2d`：它需要 `int(sqrt(n))` 算边，ONNX 导出会被常量折叠成导出那刻的 N |
| 主干 | `n_global` 层 `GlobalBlock`（Pre-LN 残差 FFN） |
| 扩散解码 | `DiffusionDecoder`（手写 `HeadAttention`，避免 `nn.MultiheadAttention` 的 reshape 常量折叠） |
| 输出头 | `priority` / `slot` / `format` / `days` / `lane` / `quality` 六路 |

### 2.4 输入（5 个张量，B 与 N 全部动态轴）

| 名称 | 形状 | 含义 |
|---|---|---|
| `node_feat` | `[B, N, 20]` | 每单元 20 维特征（见 §7.1） |
| `adj_by_type` | `[B, 8, N, N]` | **8 类**带权二元邻接矩阵 |
| `type_mask` | `[B, 8]` | 哪些边类型在实例里真实存在 |
| `mask` | `[B, N]` | 节点有效性（padding 屏蔽） |
| `graph_feat` | `[B, 8]` | **图级（实例级）特征**，喂给路由器做实例级分工 |

**8 类边**（顺序即 ONNX 通道号，双端契约）：

| 通道 | 常量 | 含义 | 通道 | 常量 | 含义 |
|---|---|---|---|---|---|
| 0 | `E_ATHLETE` | 兼项：共享运动员 | 4 | `E_TIME` | 装箱 / 间隔耦合 |
| 1 | `E_BLOCK` | 项目块：同 `group_key`，须整块相邻 | 5 | `E_LANE` | 同道次 / 同批次 |
| 2 | `E_VENUE` | 场地独占 | 6 | `E_BRACKET` | 淘汰赛晋级关系 |
| 3 | `E_POOL` | 同并发池竞争 | 7 | `E_TEAM` | 同队（球类） |

**图级特征 8 维**（Python 与 Java 逐位对齐，由 `graphFeatMatchesPythonContract` 钉死）：

| 维度 | 含义 |
|---|---|
| 0 | 冲突密度（边数 / 可能边数） |
| 1 | 单元规模（N 归一化） |
| 2 | 场地数（**有窗口的场地数** / 12） |
| 3 | 天数 / 10 |
| 4 | 时间目标三态（`x 天→0.5` / `0 不限→0.0` / `-1 尽快→1.0`） |
| 5 | 并行度（时段内**不同场地数** / 12） |
| 6 | 填充率（需求分钟 / 总容量，**必须用 float 除**，整型相除被截断成 0） |
| 7 | 块压力（同 `group_key` 成组比例） |

### 2.5 输出（7 个，顺序即通道号）

| # | 名称 | 形状 | 含义 |
|---|---|---|---|
| 0 | `priority` | `[B, N]` | 单元优先级（解码时降序摆放） |
| 1 | `slot_logits` | `[B, N, 16]` | 每单元进每个时间桶的打分 |
| 2 | `task_probs` | `[B, 17]` | 17 位专家的平均门控权重（**诊断**：看专家是否塌缩） |
| 3 | `format_logits` | `[B, 4]` | 球类赛制（group / round_robin / knockout / hybrid） |
| 4 | `days_estimate` | `[B, 1]` | 工期估计 |
| 5 | `lane_logits` | `[B, N, 16]` | **道次派遣 logits**（本轮新增） |
| 6 | `quality_score` | `[B, 1]` | **方案质量分 ∈ (0,1)**（本轮新增，Sigmoid） |

> ⚠️ **新输出只能往尾部追加**。Java 侧已改为**按输出名读取**
> （`SuperMoeService.named(...)`，旧 onnx 缺名时回退历史索引），
> 但历史版本按索引读 —— 插在中间会让老代码**静默读错张量**。

### 2.6 训练

**数据从哪来**：**没有标注数据**，全部**自监督** —— 五档场景生成器
（`data/super_scenarios.generate_super_scenario`）造出「赛会」，
再用与评测/线上同源的真实落位算法（贪心装箱 MSBF + best-fit）产出标签。
**标签与输入同源**，这是模型学得到拓扑的前提。

**五个档位（对齐用户点名的魔鬼条件）**

| 档 | 规模 | 兼项 | 天数 | 道次 | 备注 |
|---|---|---|---|---|---|
| `HELL` | 500~900 人 / 15 项目 | **100%**（每人 1~3 项） | 2~4 | — | 魔鬼条件① |
| `REGULAR` | 250~350 人 / 10 项目 | 30% | 2~3 | — | 魔鬼条件②（限时） |
| `BLOCK` | 250~400 人 / 12 项目 | 40% | 2~3 | — | **强制项目块** |
| `LANE` | 200~320 人 / 8 项目 | 20% | 2 | ✅ | 多档道次容量 |
| `TEAM` | 160~400 人 | 15% | 2~3 | — | 球类四赛制 + 淘汰赛晋级 + 二次编排 |

**时间目标三态**（用户明确要求）：`days_limit >= 1` 硬约束 / `0` 不限 / `-1` 尽可能压缩；
每档有 15% 概率取 `-1`、15% 取 `0`，其余取硬约束。编码进第 13 维，原值保留在 `SuperScenario.days_limit` 供 Java 解释语义。

**六个训练目标（全自监督）**

| 目标 | 定义 | 覆盖的原模型 |
|---|---|---|
| `priority` | 0.6×装箱紧张度 + 0.4×冲突暴露 | —— |
| `slot` | 贪心装箱的真实 one-hot 落位 | —— |
| `format` | 场景里出现最多的球类赛制（软目标 CE） | —— |
| `lane_mask` × `slot` | 只有**径赛/批次**单元参与道次损失 | `lane_advisor` |
| `days` | 贪心落位**实际跨的天数** | `forecast×2` |
| `quality` | `1/(1+各桶负载变异系数)` —— 负载越均衡方案越优 | `scheme_discriminator` |

⚠️ **`lane_mask` 的判据必须包含 `track`**：实测只有 `LANE` 档会生成
`heat_capacity>0` 的单元；若只认 `heat_capacity/lanes`，**4/5 的样本里道次头拿不到任何梯度**
（mask 求和恒为 0），道次派遣能力等于没训。径赛（`track=True`）天然有分道语义，纳入后覆盖全部档位。

⚠️ **`days_head` 曾经从未被训练过**：`forward` 里算出来了，`training_loss` 却把它接成 `_` 丢掉 ——
「工期预测」一直是个随机初始化的头，而它对应的正好是原 `forecast` 模型要干的活。

```bash
cd sports-ai
# 训练（段式：每段结束原子落盘，中断只丢当前段）
python -m sports_ai.train_super_moe --samples 500 --epochs 9 --batch 12 \
    --hidden 128 --steps 8 --expert-depth 2 --n-global 2 --device cpu --patience 20
#   续训加 --resume（hidden/steps/expert_depth/n_global 必须与权重 meta 一致）
# 导出（从权重 meta 还原结构，写死默认值会 shape 不匹配）
python -m sports_ai.export_super_moe_onnx
# 评测（random / greedy / GA一条龙 / model / ai混合 五种口径）
python -m sports_ai.evaluate_super_moe --seeds 3 --out ../_trash/eval.json
```

### 2.7 具体参数（当前线上权重）

| 项 | 值 | 说明 |
|---|---|---|
| `hidden` | **128** | 隐藏宽度 |
| `steps` | 8 | Diffusion 去噪步数 |
| `expert_depth` | 2 | 每个专家内部堆叠的消息传递层数 |
| `n_global` | 2 | MoE 之后的主干深层推理块数 |
| `n_experts` | **17** | = `N_TASKS`（11 任务专家 + 6 能力专家） |
| `n_unit_tasks` | 11 | 走节点级路由的专家数（其余走图级） |
| `n_edges` | 8 | 边类型数 |
| `n_slots` | 16 | 时间桶上限（`MAX_SLOTS`） |
| `node_feat` | 20 | `NODE_FEAT_DIM` |
| `graph_feat` | 8 | `GRAPH_FEAT_DIM` |
| `dropout` | 0.1 | |
| 参数量 | **10,573,429** | 实测（11 专家时 7,091,334） |
| ONNX 大小 | **42.04 MB** | opset 17，`dynamo=False` |

**训练指标**

| 指标 | 值 |
|---|---|
| `route_entropy` | **~0.98**（越接近 1 越均衡，专家没塌缩） |
| `min_expert_usage` | ~0.031（17 位全部在用） |
| `val_loss` | 1.43（含 lane/days/quality 三项新损失后口径变了，不可与旧值直接比） |

> ⚠️ **训练预算铁律**：深层网络必须同步加训练预算。本模型曾因只训 5 轮而
> `val_loss` 1.136 / `pri_mse` 0.0168，被误读成「深层不如浅层」；补训后追平。
> 现已做成代码机制 —— `sports_ai/budget.py`，见 §6。

### 2.8 数据转化（本模型）

```
DB / Excel（运动员 · 项目 · 报名 · 场地 · 时段 · 保护时段）
   │  ScheduleService / ArrangementService 组装业务对象
   ▼
List<ScheduleUnit> + List<Window> + daysLimit
   │  SuperScheduleEncoder.encode(...)              ← Java 编码器（20 维 + 8 类边 + 8 维图级）
   ▼
Encoded{ nodeFeat[N][20], adjByType[8][N][N], typeMask[8], mask[N], graphFeat[8], n }
   │  SuperMoeService.advise(...) → OnnxTensor（按名喂 5 个输入）
   ▼
ONNX 推理
   │  按输出名读取（named(...)）
   ▼
Advice{ priority[N], slotLogits[N][16], taskProbs[17], formatLogits[4], daysEstimate,
        laneLogits[N][16], qualityScore, n }
   │  ScheduleService 消费：orderByPriority() 当求解器初始序
   ▼
Timefold / GA / LNS 精修 → 落库的赛程
```

**输出怎么用**

| 输出 | 用法 |
|---|---|
| `priority` | `Advice.orderByPriority()` → 求解器初始排序（也是 GA 的种子序） |
| `slot_logits` | `Advice.slots()`（argmax）→ 候选落桶 |
| `task_probs` | **诊断**：`expertUsage` / 路由熵，判断有没有专家塌缩 |
| `format_logits` | `Advice.recommendedFormat()` → 球类赛制推荐 |
| `days_estimate` | 工期参考（覆盖原 forecast 能力） |
| `lane_logits` | `Advice.laneSlots()` → 道次派遣建议（覆盖原 lane_advisor 能力） |
| `quality_score` | `Advice.qualityLevel()` → good/fair/poor。⚠️ **仅是参考信号，不是裁决**：方案能不能用始终由 `ScheduleFeasibilityService` 与硬约束校验决定 |

### 2.9 逐部件展开（9 个子模块 + 6 个输出头）

`SuperScheduleMoE` 不是「一个大黑盒」，而是 9 个可单独解释的部件串起来。
下面逐个说明**它解决什么问题、参数是什么、输入输出是什么**。

#### ① `HeadAttention` —— 手写多头自注意力（ONNX 友好）

| 项 | 内容 |
|---|---|
| **解决什么** | 让节点能看见「同类节点」的全局关系 |
| **参数** | `Linear(hidden, 3*hidden)`（qkv 合并）+ `Linear(hidden, hidden)`；`heads=4`，`head_dim = hidden/4` |
| **输入/输出** | `x[B,N,H]` → `[B,N,H]`；可选 `key_padding_mask` |
| **⚠️ 为什么不直接用 `nn.MultiheadAttention`** | 导出 ONNX 时它内部的 reshape 会被**常量折叠**成导出那一刻的 B/N。导出脚本用 `B=2, N=24` 造假数据，服务端喂 `N=3` 就报 `Input shape {3,1,128}, requested shape {24,4,32}`。**最阴的是模型能加载、推理必失败，而异常被 `catch(Throwable)` 吞掉 → 「AI 没生效但也不报错」**。这里的每个 reshape 都从 `x.shape` 现算，导出后是动态算子 |

#### ② `_ExpertStep` —— 专家的**一层**（边级 + 节点级双注意力）

| 项 | 内容 |
|---|---|
| **解决什么** | 把「8 类约束」分别按自己的语义传递消息，而不是混成一锅 |
| **结构** | ① 每类边一个专属 `Linear(H,H)`；② 边级注意力 `edge_att: Linear(2H→H/2→1)` 算每对节点的标量权重；③ 按度归一化后聚合；④ 节点级 `HeadAttention`；⑤ FFN（`H→2H→H`）；两处 LayerNorm 残差 |
| **输入/输出** | `h[B,N,H]` + `adj[B,8,N,N]` + `type_mask[B,8]` + `mask[B,N]` → `[B,N,H]` |
| **⚠️ 三个必须保留的写法** | ① `mask` **不提前 unsqueeze**（邻接是三维 `[B,N,N]`，提前升维会广播成 `[B,B,N,N]`）；② 边型缺席用 **`type_mask` 张量门控**，不能写 `if float(...max())==0: continue` —— 那是 Python float，ONNX 会**常量固化**，某类边恰好缺席就永久写死；③ `key_padding_mask` **始终传**，导出时 `if` 分支会被固化，恒真/恒假都会写死一条路径 |

#### ③ `Expert` —— 一位完整专家

| 项 | 内容 |
|---|---|
| **结构** | `expert_depth` 层 `_ExpertStep` + 输出 LayerNorm |
| **为什么必须深** | 编排冲突常 3~4 跳可达：短跑 → 接力（同人）→ 跳远（同批人）→ …。**单层专家只看得到直接邻居**，等于把间接兼项当无约束，排出来的赛程在**三步之外**炸开。`depth=2` 起能看到 2~3 跳 |
| **参数** | `depth = expert_depth = 2` |

#### ④ `SharedKnowledge` —— 多视角知识共享层

| 项 | 内容 |
|---|---|
| **解决什么** | 让专家**间接互补**，又不让他们互相注意力（后者不稳） |
| **结构** | 3 个视角：各一个 `view_proj(H→H)` + 一个 `view_gate(H→E)`；每视角按 gate 加权汇聚所有专家输出，再 `merge` 回 H，残差加回 `h` |
| **⚠️ 关键** | 汇聚用 `einsum("bnk,bnkh->bnh")`，**专家维必须命名为 `k_x` 之类**：之前写成 `be,bnhe->bnh`，当 `hidden==n_experts` 时与 hidden 维隐式广播撞上，报 `subscript e has size 128 ... does not broadcast with previously seen size 9` |

#### ⑤ `LocalConvBlock` —— CNN 分支

| 项 | 内容 |
|---|---|
| **解决什么** | GNN 管「谁和谁冲突」、Diffusion 管「逐槽生成」，但**「一个项目必须在时间上成块连续」是排班网格上的空间局部性** —— 这一先验用卷积表达最直接 |
| **参数** | `Conv1d(H,H,kernel=5)` × 3（padding=2，bias=False）+ `proj(H→H)`；输出 `tanh` |
| **输入/输出** | `[B,N,H]` → 池化成 `[B,H]` 局部块结构向量 |
| **⚠️ 为什么是 `Conv1d` 不是 `Conv2d`** | `Conv2d` 要把 `[B,N,H]` 摊成方阵，需要 `int(sqrt(n))` 这类 **Python 整数运算**算边 —— 导出时被常量折叠成导出那刻的 N（与 ① 同型错误）。`Conv1d` 直接在 `[B,H,N]` 上卷积，**N 是动态维、无需任何 reshape** |

#### ⑥ `MultiGateMoE` —— 异构多门专家（**本轮核心**）

| 项 | 内容 |
|---|---|
| **结构** | `n_experts` 位 `Expert` + `n_unit_tasks` 个门 `Linear(H→11)` + `expert_embed[E,H]`（MPI：让路由器「读懂」每位专家）+ 图级投影 `graph_proj(8→H)` + CNN 分支 + `SharedKnowledge` |
| **路由公式** | 任务专家：`q = h·Eᵀ + ctx·Eᵀ + mean_门(h)`；能力专家：**只留 `ctx·Eᵀ`**（去掉节点表征与门） |
| **返回** | `(融合表征[B,N,H], 平均门控[B,E], 结构上下文[B,H])` |
| **负载均衡** | `n_experts × Σ(frac²) + 使用熵` —— **没有这一项 MoE 会退化成「只有 1~2 个专家在干活」，而表面上 loss 照样下降**（MoE 最常见的隐性失败） |
| **观测** | `expert_usage()` 给每位专家的使用率（0 说明建了从不被选中）；`route_entropy()` 给归一化路由熵 |

#### ⑦ `DiffusionDecoder` —— 扩散解码器

| 项 | 内容 |
|---|---|
| **解决什么** | 「生成候选方案」这件事本身：从噪声出发，逐步去噪出分配矩阵 |
| **结构** | `steps=8` 步余弦噪声调度（`_cosine`，`s=0.008`，`abar_min=0.15`）+ `predict_noise` 小网络（`Linear(H→H)`） |
| **输入/输出** | `cond = fused[B,N,H]` + `mask[B,N]` → `slot_logits[B,N,16]` |
| **合并意义** | 这一个部件同时承担原 `scheme_generator`（生成）、`scheme_refiner`（迭代精修）、`scheme_diffusion`（去噪）三个模型的语义 —— 它们本质都是「从噪声/初值迭代出分配」 |

#### ⑧ `GlobalBlock` —— 主干深层推理块

| 项 | 内容 |
|---|---|
| **结构** | Pre-LN 残差 FFN：`Linear(H→expand·H)` → GELU → `Linear(expand·H→H)` |
| **参数** | `expand=2`，堆 `n_global = 2` 层 |
| **作用** | MoE 之后的全局精炼，让所有专家的融合结果再过两轮非线性变换 |

#### ⑨ 六个输出头

| 头 | 结构 | 输入 | 输出 | 覆盖的原模型 |
|---|---|---|---|---|
| `priority_head` | `3H→H→H/2→1`（含 LayerNorm/Dropout） | `zg`（局部+全局池化+结构上下文） | `[B,N]` × mask | —— |
| `lane_head` | 同构，末层 `H/2→n_slots` | `zg` | `[B,N,16]` | `lane_advisor` |
| `format_head` | `2H→H→4` | `[pooled, struct_ctx]` | `[B,4]` | —— |
| `days_head` | `2H→H→1` | 同上 | `[B,1]` | `forecast_direct/mimo` |
| `quality_head` | `2H→H→1 + Sigmoid` | 同上 | `[B,1] ∈ (0,1)` | `scheme_discriminator` |
| （解码器） | `DiffusionDecoder` | `fused` | `[B,N,16]` | `generator / refiner / diffusion` |

> **为什么生成/精修/扩散复用 `slot_logits` 而不是各开一个头**：它们的输出本质都是
> 「单元 → 资源」的分配矩阵。开三个同形状的头只会三倍参数、三倍输出体积，
> 学到的还是同一件事。真正需要**独立输出**的是派遣（有道次语义）、
> 预测（标量工期）与判别（质量分）—— 这三项已各自建头。

### 2.10 消费方与降级

| 项 | 内容 |
|---|---|
| 消费方 | `SuperMoeService.advise(Encoded)` → `Advice` |
| 降级条件 | 模型缺失 / `n < 3` / 编码降级 / 推理异常 |
| 降级行为 | 返回 `Optional.empty()` → 上层继续走 L1-L3 **规则与算法**，**绝不阻塞编排** |
| 旧模型兼容 | 输入按 `modelHasInput(...)` 判断后才喂（旧 onnx 无 `graph_feat` 时退化成全零上下文） |

---

## 3. GNN 系列

四个 GNN 共享同一套骨架思路：**类型化邻接的消息传递**（不同边类型走不同消息网络）
+ Pre-LN 残差 FFN + JK 跳跃连接，区别只在特征、边语义与输出头。

### 3.1 `constraint_gnn.onnx` —— 约束图神经网络

| 项 | 内容 |
|---|---|
| **原理** | `RelationalBlock` × `layers`：每类边一个 `rel_mlp` + 类型偏置，逐节点汇聚后接残差 FFN；`jk` 层把各层输出拼接聚合 |
| **训练** | `python -m sports_ai.train_constraint_gnn --samples 800 --epochs 8 --batch 16 --hidden 160 --layers 6`。⚠️ **该脚本不支持 `--resume`**，改结构后必须从头训（本模型曾因传了 `--resume` 而 3 段全部瞬间失败，`tail` 又把 usage 刷屏掩盖了失败） |
| **输入** | `node_feat[B,N,16]` / `adj_by_type[B,6,N,N]` / `type_mask[B,6]` / `mask[B,N]` |
| **输出** | `priority[B,N]`（约束满足度打分） |
| **数据转化** | 约束集合（兼项 / 场地 / 顺序 / 容量 / 时间 / 晋级）→ 6 类邻接 + 16 维节点 → 逐节点打分 |
| **消费方** | `ConstraintAwareGraphEncoder`（编码器就绪、单测通过） |
| **状态** | 🟡 **主流程未接线** —— 该职责现由 `super_moe` 承担 |

### 3.2 `tournament_gnn.onnx` —— 球类赛制 GNN

| 项 | 内容 |
|---|---|
| **原理** | 球队为节点，4 类约束边做消息传递；三个头分别出赛制 / 种子序 / 公平性代价。`format_head` 的输入是**全局池化后的 `pooled[B,H]`**（队伍级决策），种子与公平性用节点级 `zg` |
| **训练** | `python -m sports_ai.train_tournament_gnn --samples 800 --epochs 8 --batch 16 --hidden 160 --layers 5`；存档**带 meta** |
| **输入** | `node_feat[B,N,14]` / `adj_by_type[B,4,N,N]` / `type_mask[B,4]` / `mask[B,N]` |
| **输出** | `format_logits[B,3]` / `seed_scores[B,N]` / `fairness_cost[B,N]` |
| **指标** | `val_fmt_ce=0.529` / `val_fmt_acc=0.981` / `val_seed_spearman=0.997` / `val_fair_mse=0.0059` |
| **消费方** | `TournamentAiService` + `TournamentGnnEncoder` |
| **参数** | `hidden=160` / `layers=5` / `n_types=4` |

⚠️ **导出时反推结构的坑**：`blocks.0.gate.weight` 的形状是 `(n_types, hidden)`，
`shape[0]` 是 4 而不是 hidden —— 用它反推会得到 `hidden=4`（第一次就这么错了）。
必须用 `jk.weight` / `proj.weight` / LayerNorm 一维权重。

### 3.3 `conflict_gnn.onnx` —— 冲突簇着色 GNN

| 项 | 内容 |
|---|---|
| **原理** | 冲突图着色：输出每单元的着色优先级，决定兼项避让的先后顺序。**度越高的节点（冲突面越广）应越早着色** |
| **训练** | `python -m sports_ai.train_gnn --samples 2000 --epochs 175`。⚠️ 脚本默认 `--samples 1500 --epochs 20` 是 **64/4 浅层时代**的值；160/6 按预算需 ~175 轮 |
| **输入** | `node_feat[1,N,17]` / `adj[1,N,N]` / `mask[1,N]`（**单一邻接**，不分边类型）。⚠️ 17 = 16 通用维 + **第 17 维「归一化度数」**：本模型预测的就是度数中心度，而对称归一化的消息传递会把度数幅度抹平，所以必须显式给（详见 `audit.md` 附七） |
| **输出** | `priority[1,N]` |
| **数据转化** | 硬约束（「同一单元链」）→ 冲突图 → 逐节点优先级。训练时 `encode_gnn_inputs` 补齐到 `TRAIN_PAD_TO`，但 ONNX 里 `n` 是**动态轴** |
| **超图支持** | 节点数 > `MAX_NODES=1024` 时由 `HierarchicalGnnEncoder` 并查集聚簇分层推理 |
| **消费方** | `OnnxInferenceService` → `AiAdvisory.strategy / cancelProbability / nodePriority` |
| **参数** | `node_feat=17` / `hidden=160` / `layers=6` |

> ⚠️ **真实教训（本轮）**：默认维度从 64/4 提到 160/6 后**没同步加训练预算**，
> 导致「中心节点应得最高着色优先级」这条泛化断言直接失败 ——
> 表现像「深层架构更差」，真因是欠训。现已把 `budget.report_budget` 接进
> `train_gnn` / `train_gan` / `train_refiner`，并让预算按**模型类默认参数**换算
> （`_model_defaults`），避免「改了默认维度、预算没跟着变」再次发生。

### 3.4 `referee_gnn.onnx` —— 裁判编排独立模型

| 项 | 内容 |
|---|---|
| **设计动机** | 裁判派遣原来是纯规则（专长优先 → 负载均衡 → 并行组次不重用）。规则能保证「不出错」，但表达不了「专长匹配 / 负载 / 保护时段 / 同单位回避 / 经验」之间该怎样**加权取舍** —— 那正是模型的强项 |
| **原理** | 与 `constraint_gnn` 同构骨架（`node_feat` 参数化），逐裁判输出派遣优先级 |
| **重要边界** | **模型只排序、不做裁决**：「并行组次不得重用同一裁判」等硬规则仍由 Java 把关，模型输出再离谱也不会产生不合规派遣 |
| **输入** | `node_feat[B,N,12]` / `adj_by_type[B,4,N,N]` / `type_mask[B,4]` / `mask[B,N]` |
| **输出** | `priority[B,N]`（越高越先派） |
| **12 维特征** | 专长匹配度 / 负载比例 / 可用时段比例 / 受保护 / 经验等级 / 同单位 / 并行冲突风险 / 历史派遣归一 / 连续工作长度 / 搭档协同 / 时段偏好匹配 / 资历归一 |
| **4 类边** | 0 同专长竞争 / 1 同单位回避 / 2 同受保护 / 3 负载耦合 |
| **训练** | `python -m sports_ai.referee_advisor --mode both --samples 800 --epochs 40 --hidden 160 --layers 5`；自监督标签 = 专长 0.40 + 负载 0.20 + 保护 0.15 + 同单位 0.15 + 经验 0.10 |
| **指标** | `val_mse=0.00202`（best at epoch 37）；ONNX 3.0 MB |
| **消费方** | `RefereeAiService` + `RefereeGnnEncoder` |

### 3.5 `teacher_gnn.onnx` —— 教师（行政）规避独立模型

| 项 | 内容 |
|---|---|
| **设计动机** | 教师规避同样是纯规则。可现实是多因素叠加：某教师既是高三班主任、又连着两天监考、所带学生还要参加 3 个项目 —— **三个约束撞在一起时先保护谁？** 规则只能给固定优先级，模型能学取舍 |
| **原理** | **复用裁判模型的 GNN 骨架**（两者都是「人 × 时段 × 约束」的分配问题，骨架同构，差别只在特征与边的语义）—— 这是「独立模型 + 统一架构」的合理形态，而不是复制粘贴 |
| **输入** | `node_feat[B,N,10]` / `adj_by_type[B,4,N,N]` / `type_mask[B,4]` / `mask[B,N]` |
| **输出** | `aversion[B,N]`（避让优先级，越大越该避开） |
| **10 维特征** | 任教班级数 / 项目关联度 / 是否班主任 / 已占课时 / 保护时段数 / 行政权重 / 历史冲突 / 本班有比赛 / 教师冗余度 / 时段紧度 |
| **4 类边** | 0 同班级 / 1 同教研组 / 2 同保护时段 / 3 同行政层级 |
| **训练** | `python -m sports_ai.teacher_advisor --mode both --samples 500 --epochs 30 --hidden 160 --layers 5`；标签 = 关联度 0.40 + 已占课时 0.20 + 班主任 0.20 + 行政权重 0.15 + 历史冲突 0.05 |
| **消费方** | `TeacherAiService` + `TeacherAiService.encode` |

> ⚠️ **修掉的设计缺陷**：原用「冗余度相近」近似「同教研组」，
> 结果所有教师该值相同时**整条通道全连** —— 边失去区分力还不报错。
> 已改为显式 `group` 字段，并加断言钉死（"组信息必须显式传入，不能靠冗余度近似"）。

---

## 4. 生成式模型（自对抗方案生成）

`AdversarialSchemeService` 在**推理时**跑「生成 → 精修 → 评判 → 择优」的多轮对抗：

| 模型 | 输入 | 输出 | 作用 | 参数 |
|---|---|---|---|---|
| `scheme_generator.onnx` | `node_feat[N,16]` / `adj` / `mask` / `z[N,8]`（噪声）/ `forbid` | `logits[N,16]` / `scheme` | 从噪声生成候选方案 | `hidden=160` / `noise=8` |
| `scheme_refiner.onnx` | 同上 + 候选 `init_logits` | `logits[N,16]` | 局部精修 | `hidden=160` / `delta_scale=0.6` |
| `scheme_discriminator.onnx` | `node_feat` / `adj` / `mask` / `scheme` | `logits` | 评判方案优劣 | `hidden=160` |
| `scheme_diffusion.onnx` | `node_feat[B,N,16]` / `adj[B,N,N]` / `mask[B,N]` / `z[B,N,16]` | `logits[M,N,16]`（M = 去噪轨迹） | 扩散式去噪生成 | `hidden=192` / `layers=6` / `steps=8` |

**训练**

```bash
cd sports-ai
python -m sports_ai.generative.train_gan --iters 2400          # 生成器 + 判别器（对抗）
python -m sports_ai.generative.train_refiner --iters 3000      # 精修器（对抗 + 组合约束 + 守卫）
python -m sports_ai.generative.train_diffusion                 # 扩散
python -m sports_ai.generative.export_gan                      # 一次导出三个 onnx
python -m sports_ai.export_diffusion_onnx
```

**数据转化**

```
赛会约束 → ConflictGraphEncoder.Encoded（node_feat / adj / mask）
   ＋ 噪声 z（生成）/ 上一轮方案（精修）＋ forbid 禁排表
   ▼
Generator  → logits[N,16] → argmax → slots[N]        ← 候选方案
   ▼
Refiner    → 修正 logits                              ← 局部精修
   ▼
Discriminator → dScore                                ← 打分
   ▼
AdversarialSchemeService 择优（**残余冲突优先**，冲突相同再看 dScore）
```

> **基线始终作为候选参与择优** —— 生成式模块只可能让结果变好，不可能变差。
>
> ⚠️ 这里有个**语义陷阱（本轮修复）**：`SchemeResult.rounds` 记的是
> 「**被采纳**的候选来自第几轮」，当基线即最优时它合法地等于 0。
> 原测试断言 `rounds() >= 1`，等于「模型变好反而测试失败」—— 本末倒置。
> 已拆出 `roundsRun`（**执行**轮数），与 `LnsImprover.Report` 的
> `rounds / acceptedRounds` 同口径：只有分开报，才能区分
> 「功能没生效」与「模型已经很好」。

---

## 5. 辅助模型

### 5.1 `algorithm_selector.onnx`

| 项 | 内容 |
|---|---|
| **原理** | 残差 MLP（`_ResidualBlock`：`Linear→2H→H` + LayerNorm）判「硬解 / 取消」两条路径 |
| **输入** | `features[1,16]`（`InstanceFeatures.extract`，**归一化常量固化在第一层** `Normalize`，因此 Java 喂**原始特征**） |
| **输出** | `strategy[1,2]` |
| **训练** | `python -m sports_ai.train_selector_v2` → `selector.pt` + `selector_stats.json`；导出 `python -m sports_ai.export_onnx` |
| **数据转化** | 赛会统计量（人数 / 项目数 / 兼项率 / 场地 / 天数 / …）→ 16 维实例特征 → 路径概率 |
| **消费方** | `OnnxInferenceService` → `AiAdvisory.strategy()` |
| **参数** | `n_features=16` / `hidden=192` / `blocks=5` |

> ⚠️ **归一化必须在 ONNX 内固化**：`export_onnx` 把 `Normalize(mean, std)` 作为第一层
> 包进 wrapper 再导出。Java 侧因此可以喂原始特征，不必重复实现归一化（否则双端漂移）。

### 5.2 `lane_advisor.onnx`

| 项 | 内容 |
|---|---|
| **原理** | 运动员级 MLP，输出派遣优先级（用于 `ArrangeStyle.AI` 款型） |
| **输入** | `athlete_feat[B,N,8]` / `mask[B,N]` |
| **输出** | `priority[B,N]` |
| **特征** | `LANE_FEAT_DIM = 8`（道次 / 组次 / 成绩 / 兼项负担 / 体力 / 单位 / 年级 / 历史占用） |
| **训练** | `python -m sports_ai.lane_advisor --iters 1500 --batch 32 --device cpu`（训练 + 导出一体）；只导出用 `--export-only` |
| **消费方** | `LaneAdvisorService` → `ArrangementService.buildAiSeedRank` |
| **参数** | `hidden=160` / `LANE_FEAT_DIM=8` |

### 5.3 `forecast_direct.onnx` / `forecast_mimo.onnx` / `forecast_recursive.onnx`

| 项 | 内容 |
|---|---|
| **原理** | 三种赛程/成绩趋势预测策略：直接多步、MIMO 多输出、递归单步滚动 |
| **输入** | `x[1,12,4]`（12 个历史步 × 4 个通道） |
| **输出** | `y[1,8]`（8 步预测） |
| **训练** | `python -m sports_ai.forecast.train_forecast`（产出三种 `.pt` + `forecast_metrics.json`） |
| **导出** | `python -m sports_ai.forecast.export_forecast`（导出 direct 与 mimo，并内联外置权重 `.onnx.data`） |
| **参数** | `hidden=160` / `h=8` |
| **状态** | 🟡 **Java 未接入** —— 该能力现由 `super_moe` 的能力专家 `TASK_FORECAST` + `days_estimate` 输出承担 |

---

## 6. 训练预算机制（`sports_ai/budget.py`）

**为什么需要**：2026-10-03 实测教训 —— 把 `hidden` 96→128 并加深后仍按 5-6 轮训，
`val_loss` 从 0.951 涨到 1.136、`pri_mse` 从 0.0035 涨到 0.0168，**看起来像「深层架构更差」**，
差点去改架构。补训后 `val_loss` 回到 1.022 —— **纯粹是预算没跟上**。
本轮又在 `conflict_gnn` 上原样复现了一次（160/6 只训 20 轮 → 泛化断言失败）。

**机制**

- 参数量近似 ∝ `hidden² × 深度`：**宽度按平方放大、深度按线性放大**
- 深度口径各模型自定：`super_moe` 用 `expert_depth + n_global + 1`，GNN 用 `layers + 1`
- 实测：`96/1/2 → 24 轮`、`128/2/2 → 30 轮`、`192/3/3 → 94 轮`、`256/4/4 → 216 轮`
- 传的轮数 < 建议值 60% 时打 **醒目 WARN**
- checkpoint `meta` 记录 `epochs_run` / `budget_epochs` / `budget_satisfied`
- **已接入**：`train_super_moe` / `train_constraint_gnn` / `train_tournament_gnn` /
  `train_gnn` / `train_gan` / `train_refiner`
- ⚠️ 维度**不抄常量**，用 `_model_defaults(cls)` 从模型类的构造默认值读 ——
  否则「改了默认维度、预算没跟着变」会再次发生

---

## 7. 双端契约（Python ↔ Java 逐位对齐）

改任何一维都必须**双端同步改 + 重训全部受影响模型**，否则 shape 不匹配会**静默回退规则**（不报错，最难查）。

### 7.1 `super_moe` 的 20 维节点特征

| 维 | 含义 | 维 | 含义 |
|---|---|---|---|
| 0 | 单元数占比 | 10 | 块内单元数占比 |
| 1 | 人数 | 11 | 是否道次（`heat_capacity>0`） |
| 2 | 相对最挤单元的人数 | 12 | heat 容量占比 |
| 3 | 时长占比 | 13 | **时间目标三态**（0 不限 / 0.5 硬约束 / 1 最小化） |
| 4 | 场地数 | 14 | 天数占比 |
| 5 | 装箱紧张度 `(dur+interval)/cap` | 15 | stage（main / prelim / final / resecond） |
| 6 | 容量余量 | 16 | 赛制 group |
| 7 | 冲突暴露 | 17 | 赛制 round_robin |
| 8 | 兼项占比 | 18 | 赛制 knockout |
| 9 | 是否项目块 | 19 | 赛制 **hybrid** |

> ⚠️ **本轮修掉的契约错位**：Python 侧曾有一行
> `feat[i,19] = 1.0 if task in (KNOCKOUT, RESECOND)`，把第 19 维
> （本该是「混合赛制」one-hot 第 4 位）**覆盖**成「淘汰赛标记」，
> 而 Java 侧从来没有对应实现 —— **训练与推理对同一个维度的语义不同**，
> 且不报错。同时文件头还存在**两张互相矛盾的特征表**。三者已统一为上表。
> 淘汰赛的跨轮次信息由 `E_BRACKET` 边承载、二次编排由 `stage`（第 15 维）承载，
> 不需要再占一个标量维。

### 7.2 契约钉死的测试

| 契约 | 测试 |
|---|---|
| 任务数 = 17、`N_UNIT_TASKS` = 11、六个能力专家名逐字对齐 | `SuperScheduleEncoderTest` |
| 图级八维逐位（含填充率 225/720 非整值） | `SuperScheduleEncoderTest.graphFeatMatchesPythonContract` |
| 七路输出（含 `lane_logits` / `quality_score` 取值范围） | `SuperScheduleEncoderTest` 输出契约用例 |
| 并行度按「时段内不同场地」去重 | `SuperScheduleEncoderTest.parallelCountsDistinctVenuesPerSlot` |
| 裁判 12 维 / 4 通道对称性 | `RefereeGnnEncoderTest` |
| 教师 10 维 / 教研组必须显式传入 | `TeacherAiServiceTest` |
| 球类 14 维 / 4 通道 | `TournamentGnnEncoderTest` |
| 冲突 16 维 | `ConflictGraphEncoderTest` |
| 项目块完整性 `breaksBlock` 语义 | `ProjectBlockContiguityTest` |
| 球类窗口与行政规避求交 | `BallTournamentServiceTest` |
| 自对抗不劣于基线 + 执行轮数 | `AdversarialSchemeServiceTest` |

---

## 8. 导出物与校验

**导出脚本与产物**

| 脚本 | 产物 |
|---|---|
| `export_super_moe_onnx.py` | `super_moe.onnx`（从权重 meta 还原结构） |
| `export_constraint_onnx.py` | `constraint_gnn.onnx` |
| `export_tournament_onnx.py` | `tournament_gnn.onnx`（meta 优先 → 否则反推） |
| `export_onnx.py` | `conflict_gnn.onnx` + `algorithm_selector.onnx` |
| `lane_advisor.py --export-only` | `lane_advisor.onnx` |
| `generative/export_gan.py` | `scheme_generator / discriminator / refiner.onnx` |
| `export_diffusion_onnx.py` | `scheme_diffusion.onnx` |
| `forecast/export_forecast.py` | `forecast_direct.onnx` + `forecast_mimo.onnx` |
| `referee_advisor.py` / `teacher_advisor.py` | `referee_gnn.onnx` / `teacher_gnn.onnx` |

**⚠️ 所有导出必须显式 `dynamo=False`**

PyTorch ≥2.6 默认走 dynamo 导出器，它有两个致命问题：

1. **依赖 `onnxscript`**（本项目未安装）→ 直接 `ModuleNotFoundError` 崩；
2. 与 `dynamic_axes` 冲突 → `n` 动态轴失效，导出的图会把 N **常量折叠**成 dummy 尺寸。

一旦 N 被写死，服务端喂别的节点数就会在 reshape 处崩，而异常又会被
`catch (Throwable)` 吞掉 → **表现为「AI 没生效但也不报错」**，比直接崩溃难查十倍。

**校验清单**

1. **动态 N 闸门**：`_trash/check_onnx_dyn.py` —— 用 N=1/3/4/12/24/47 各跑一次
2. **输出清单**：`super_moe.onnx` 必须 **5 入 7 出**，`task_probs` 宽度 = **17**
3. **权重形状**：`conflict_gnn` 首个一维 bias = 160、`algorithm_selector` = 192、
   GAN 三件套首个二维权重第二维 = 160
4. **后端同步**：`sports-ai/models/*.onnx` 与 `sports-backend/src/main/resources/models/*.onnx` 字节数一致
5. **全量回归**：`mvn -o -f sports-backend/pom.xml test`

```bash
cd sports-ai
python ../_trash/check_onnx_dyn.py          # 动态 N 闸门
python ../scripts/gen_models_manifest.py    # 生成/校验模型清单（sha256）
```
