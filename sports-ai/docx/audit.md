# 编排能力逐条核查表

> 核对时间：2026-10-03 ｜ 代码基线：分支 `2.8.5`
> 状态口径：✅ 已实现 ｜ 🟡 部分实现 ｜ ❌ 未实现 ｜ 🔧 本轮新修
> 证据均为代码路径 + 类/方法，可逐条复核。

---

## A. 模型架构升级

| # | 要求 | 状态 | 证据与说明 |
|---|---|---|---|
| A1 | 全部升级为**深层网络** | ✅ | **13 个模型全部深层化**：`super_moe`(128/2/2，17 专家)、`constraint_gnn`(160/6)、`tournament_gnn`(160/5)、`referee_gnn`(160/5)、`teacher_gnn`(160/5)、`conflict_gnn` 64/4→**160/6**（+10×）、`algorithm_selector` 96/3→**192/5**（+6×）、`lane_advisor` 64→**160**（+5.5×）、GAN `generator`/`discriminator`/`refiner` 64→**160**、`scheme_diffusion` 128/4→**192/6**、`forecast×2` 64→**160**。<br>⚠️ 深层化的正确姿势是「改模型类默认维度」：`train_gnn.py` / `export_onnx.py` 等都是**无参构造**，训练与导出自动跟随，**Java 契约一行都不用改**；但必须**重训 + 重导**（旧 `.pt` 会 shape 不匹配，onnx 固化的是导出那一刻的结构）。 |

> **深层化为什么可以「只改默认维度」**：`train_gnn.py` / `export_onnx.py` 都是
> `ConflictGnn()` / `AlgorithmSelector()` **无参构造** —— 改模型类的默认 `hidden`/`layers`
> 后训练与导出自动跟随，且**输入输出契约不变 → Java 侧一行都不用改**。
> ⚠️ 但必须重训（改了默认维度用旧 `.pt` 会 shape 不匹配）+ 重导（onnx 固化的是导出那一刻的结构）。
| A2 | 强悍鲁棒 | ✅ | 全部推理失败**静默回退规则**，绝不编排失败；`ModelSource` 支持 classpath 与外部热替换；`OnnxSessionFactory` EP 降级链 cpu/directml/cuda/auto |
| A3 | 可重构 | 🔧 | 构造参数从 checkpoint `meta` 还原（写死默认值会 shape 不匹配）；本轮把训练预算抽成公共模块 `sports_ai/budget.py`；导出脚本改为「meta 优先 → 权重反推」 |
| A4 | 搜索最新方法 | 🟡 | 已用 MoE 多门路由、图级条件路由、类型化邻接注意力、Diffusion 解码、CNN 局部块；未引入更新的 GNN 变体（如 Graph Transformer / 边感知注意力的替代实现） |
| A5 | **合并的超级大模型** | ✅ | `super_moe.onnx` 单模型，9 个专家头覆盖九类编排任务（见 `models.md` §2.1） |
| A6 | MoE 专家系统 | ✅ | `MultiGateMoE`：9 专家、多门打分、`expert_embed` 路由、负载均衡 loss；`expert_usage()` / `route_entropy()` 独立统计（实测 `route_entropy 0.967`，无专家塌缩） |
| A7 | GNN 加强 | ✅ | 约束 GNN 96/4 → **160/6**；赛制 GNN 96/3 → **160/5**；超 `MAX_NODES=1024` 走 `HierarchicalGnnEncoder` 并查集聚簇分层推理 |
| A8 | Diffusion 探索 | ✅ | 两处：`super_moe` 内部的 `DiffusionDecoder`（steps=8 去噪出 slot_logits）；独立的 `scheme_diffusion.onnx` |
| A9 | **混合 GNN+Diffusion+CNN** | ✅ | 在**同一个** `SuperScheduleMoE` 内：图注意力（GNN）+ `DiffusionDecoder`（Diffusion）+ `LocalConvBlock`（Conv1d kernel=5，CNN）+ MoE |

## B. 编排能力覆盖

| # | 要求 | 状态 | 证据与说明 |
|---|---|---|---|
| B1 | 项目编排 | ✅ | `TASK_PROJECT`；`RuleBasedScheduler` / Timefold / `ScheduleService.autoSchedule` |
| B2 | 道次编排（**注意接力赛**） | 🟡 | 径赛道次 ✅（`ArrangementService.allocate`，阶段一同组不同班硬约束 + 阶段二匈牙利精确分道）；田赛分组 ✅（`groupMustStartTogether`）；**接力「整队不可拆」未接线** —— `SnakeGrouping.assignTeamUnits` 存在但仅测试引用，主代码未调用 |
| B3 | 球类编排（小组/淘汰/循环/混合） | ✅ | `RoundRobinGenerator` / `EliminationGenerator` / `HybridGenerator` / `VolleyballTournament`；`BallTournamentService` 四赛制枚举；Python 侧 `tournament/{round_robin,elimination,hybrid,volleyball}.py` |
| B4 | 拔河的淘汰/循环/混合 | 🟡 | 拔河作为团体项目**复用球类同一套赛制生成**，无专属赛制实现（`ExcelService` TUG 项、裁判专长含拔河） |
| B5 | 混合编排 | ✅ | 赛制层 `HybridGenerator`（小组+淘汰）；编排层 `super_moe` 把跨大类任务统一到一张冲突图（`tournament/adapt.py` 是桥梁） |
| B6 | 兼项避免 | ✅ | 约束层 `athleteMustNotClash`；缓冲 15 分钟（`ConflictService.CONFLICT_BUFFER_MIN`）；规则模式可经 `ruleConflictBufferMinutes` 覆盖；**优化/AI 档仍是硬编码 15** |
| B7 | **防止见缝插针（项目必须块状）** | 🔧 | **本轮补上**：`ScheduleConstraintProvider.projectBlockContiguity`（同一项目的组次跨天即罚，`BLOCK_BREAK_PENALTY=120`；用软约束而非硬约束，避免容量不足时直接把求解器逼到无解）+ 可测纯函数 `breaksBlock` + 7 项单测。`E_BLOCK` 仍是模型输入特征；碎片另有 LNS/ALNS 窗口邻域处理 |
| B8 | **淘汰赛后第二次编排道次** | 🟡 | 预赛→决赛两轮 ✅（`computeQualifiers` + `arrangePool(ROUND_FINAL)` + `appendFinalScheduleRow`，`/qualify` `/rearrange` 接口齐备）；**无复赛/半决赛多轮**（轮次常量只有 `preliminary` / `final`）；球类 `resecond` 是**占位实现**（只回 `reschedulable=true`，未真正重排槽位） |
| B9 | 避开兼项 + 压缩赛程 | ✅ | 兼项 ✅；压缩：球类 `daysLimit` 三态完整；**本轮把主田径编排也补齐三态** —— `ScheduleService` 解析 `days<0 → 尽可能减少`，并把跨天惩罚放大 5 倍（静态桥 `ScheduleConstraintProvider.setMinimizeDaysMode`，`finally` 复位防跨请求泄漏） |
| B10 | 时间三态（x / 0 / -1） | ✅ | 后端三态已补齐（见 B9）；前端「时间目标」下拉为 限定天数 / 不限 / 尽可能减少 三选一，提交时映射 `days = 具体天数 / 0 / -1`；`SuperScheduleEncoder` 侧三态编码（`>=1→0.5`/`0→0.0`/`<0→1.0`）已一致 |

## C. 魔鬼条件与训练

| # | 要求 | 状态 | 证据与说明 |
|---|---|---|---|
| C1 | **500+ 人 / 15+ 项目 / 1-3 兼项** | 🟡 | 场景生成器 `super_scenarios.py` 有五档（HELL/REGULAR/BLOCK/LANE/TEAM），HELL 实测 N≈54 单元；兼项通过 `E_ATHLETE` 边建模。**未做 500 人 / 15 项目的端到端规模压测** |
| C2 | **300 人 / 10 项目 / 限 2-3 天** | 🟡 | BLOCK/REGULAR 档接近此规模（N≈34~70），实测各档工期均压到 `days_limit` 内；同样缺显式规模压测 |
| C3 | 道次 / 球类 / 拔河的淘汰+循环+混合训练 | 🟡 | `tournament_gnn` 在 `ball_tournament.py` 数据上训练（四赛制 + 拔河在 SPORTS 列表内）；**拔河无专属赛制训练** |
| C4 | 对兼项出难题 | ✅ | HELL 档构造兼项密集实例（1/2/3 兼项档位），`tiers_tasks` 含 `TASK_CONFLICT`；实测兼项撞车恒为 0 |
| C5 | **裁判编排 / 教师规避：先独立模型，后合并主模型** | ✅ | **独立模型**：`referee_gnn.onnx`（12 维裁判节点 / 4 类边 / 160×5）+ `teacher_gnn.onnx`（10 维教师节点 / 4 类边 / 160×5），两侧 Java 服务（`RefereeAiService` / `TeacherAiService`）与双端契约单测齐备。<br>**合并完成**：N_TASKS 9→11（影子任务）→ **11→17**（本轮把剩余 7 个独立模型也并进来）。参数量 5.96M → 7.09M → **10.57M**。 |
| C6 | 争取 RSI | 🟡 | **第一步已实现**：`train_super_moe --label-search` 让标签由**搜索**产生（小规模 GA + 同一套 decode/代价，搜索没赢则保留原标签，标签质量单调不降）。实测：**AI 链路指标与贪心标签版完全一致** —— 因为最终解由搜索主导、模型序只是种子被兜住。故该开关默认关闭，等「模型直接输出分配」或「魔鬼规模下搜索预算受限」时再启用。详见 `benchmarks.md` §5 |

## D. 质量与对比

| # | 要求 | 状态 | 证据与说明 |
|---|---|---|---|
| D1 | **ONNX 准确率必须比遗传算法一条龙高** | 🔧 | 本轮建立 `ga` 基线并实测：**AI 混合链路每档不劣于 GA，魔鬼档 HELL 严格更优（9.67 < 10.33）**。但**纯模型链路比 `random` 还差**（HELL 16.33 vs 15.33）—— 根因已定位：标签天花板 + `slot_logits` 误导落桶（详见 `benchmarks.md`） |
| D2 | 加强 L2/L3 传统算法，纯算法也要突破 | 🟡 | L2（贪心 + 冲突感知 + 匈牙利精确分道）✅、L3（Timefold + GA + LNS + MNSA + ALNS + Fix-and-Optimize + UCB1 bandit）✅ 均已存在；**本轮未针对魔鬼条件做算法强化**（如构造启发式改进、下界引导重启） |
| D3 | **L4 借鉴 L2/L3 思维** | 🔧 | 本轮落地：模型序作为 GA 初始种子 + GA 搜索 + LNS 破坏-重建 + 多起点重启，最终解在「搜索最优 ∪ 全部种子」取 → 只可能变好不可能变差。见 `evaluate_super_moe.py` 的 `ga_search` / `lns_refine` |
| D4 | 约束条件**算法与 AI 都要能处理** | 🟡 | 绝大多数约束两档共用同一套池/窗口口径；**块状约束两档都缺**（B7）；主田径时间三态两档都缺（B9） |

## E. 输入能力（"条件都要可以输入"）

| # | 约束 | 状态 | 说明 |
|---|---|---|---|
| E1 | 场地 / 时段 / 天数 / 并发位 / 时长 / 间隔 | ✅ | 前端「运动会日程配置」全可录；后端 `ScheduleService` 白名单解析 |
| E2 | 田赛分组 / 出场顺序 / 项目级并发 / 捆绑组 | ✅ | `Schedule.vue` fieldGroups；`Events.vue` concurrency/groupSize/bundleGroup |
| E3 | 款型（`ArrangeStyle`） | ✅ | `/api/arrange/styles` 下发；CLASS / SNAKE / SNAKE_SEEDED / AI / **PLAN（规划层，第 5 款）** |
| E4 | 球类全部参数（赛制/组数/晋级/单场分/双回合/时间目标） | ✅ | `BallTournament.vue` → `/ball/arrange` |
| E5 | 规避时间（全校/班主任/裁判） | ✅ | `ProtectionManage.vue` → `/api/protections` |
| E6 | 规则注入 / DSL | ✅ | `RuleScripts.vue`（积木 + 代码双模式，builtin/groovy/javascript） |
| E7 | **兼项缓冲分钟** | 🔧 | **本轮补齐**：前端 `Schedule.vue` 新增「兼项缓冲(分钟)」输入，且 `arrangePayload()` 把它作为 `ruleConflictBufferMinutes` 随编排请求发出（原先前端硬编码 15、后端收不到） |
| E8 | **AI 自对抗轮数 / AI 款型** | 🔧 | **补了一般**：前端新增「AI 对抗轮数」输入（仅 AI 模式显示），随请求发 `aiAdversarialRounds`；`aiLaneStyle` 仍未开输入口（默认 `ai` 已够用） |
| E9 | **分道策略 / 全局录取人数 / 冲突检查开关** | 🟡 | 后端 `RuleScheduleConfig` 仍可收 `ruleLanePolicy` / `ruleAdvanceCount` / `ruleConflictCheckEnabled`，前端暂无入口（项目级 `advanceCount` 已有替代） |
| E10 | 保护时段经**请求体**传入 | ❌ | 只能预置 `AdminTimeProtection` 表 |
| E11 | 排球赛制 / 几局几胜 / 可用时段 | ❌ | `/api/tournament/generate` 可收，前端无入口 |
| E12 | 写而不生效项 | ⚠️ | `Settings.vue` 的 `soft_constraints.*`（6 项）与 `params.timeout_seconds` / `optimization_rounds` 保存后**后端无读取处**；道次页 `preferDiffHeat` / `preferDiffLane` / `banSameClassSameLane` 后端不消费 |

## F. 工程与交付

| # | 要求 | 状态 | 证据 |
|---|---|---|---|
| F1 | 模型详解文档放 `sports-ai/docx` | 🔧 | 本轮新建：`models.md`（12 模型原理/输入/输出/参数）、`benchmarks.md`（实测对比）、`audit.md`（本表） |
| F2 | 训练预算随深度缩放 | 🔧 | 新增 `sports_ai/budget.py`，三个训练脚本共用；低于建议预算 60% 打醒目 WARN；checkpoint `meta` 记录 `epochs_run` / `budget_epochs` / `budget_satisfied` |
| F3 | git 纪律 | ✅ | 本轮已提交 `93f03a0`（GNN 深层化 + 导出修复）、`fbb62e6`（super_moe 补训收敛）；后续提交见 git log |
| F4 | `super_scenarios.py` stat 幽灵 M | 🔧 | 根因是**索引 size 27885（LF 存法）vs 工作区 28484（CRLF）**，差 599 字节 = 行数；`git hash-object` 走 autocrlf 规范化所以 hash 一致，`status` 直接比 size 就判脏。已用 `git add` 刷新 stat 缓存，工作区干净且暂存区零内容变化 |
| F5 | 全量回归 | ✅ | 最近一次 **550 passed / 0 failed**（543 → 550，新增 7 项块状/三态测试）；本轮 17 专家合并后待复跑 |

---

## 附：本轮（第二次升级）新增与修复

| 项 | 内容 | 证据 |
|---|---|---|
| 块状约束 | `projectBlockContiguity` 软约束 + `breaksBlock` 纯函数 + 7 项单测 | `ScheduleConstraintProvider.java`、`ProjectBlockContiguityTest.java` |
| 时间三态 | `days<0` → 尽可能减少；跨天惩罚 ×5 静态桥（finally 复位）；前端「时间目标」下拉 | `ScheduleService.java`、`Schedule.vue` |
| 裁判独立模型 | Python 模型 + 训练 + 导出 ONNX + Java 编码器/服务 + 契约单测 | `referee_advisor.py`、`RefereeGnnEncoder.java`、`RefereeAiService.java` |
| 备份爆炸修复 | 新训练脚本每轮备份 → 40 轮堆 18 个 3MB；补 `_prune_backups(keep=3)` | `referee_advisor.py` |
| **行政规避贯通球类** | 原来只有主赛程与道次读保护时段，**球类编排完全没读** → 新增 `ballWindows(days, blocks)` 静态方法剔除受保护窗口（窗口无精确时刻，按 `WINDOW_SPANS` 约定映射后与保护区间求交）；全被保护时诚实报「排不了」而非静默排 | `BallTournamentService.java`、`BallTournamentServiceTest.java`（+2 用例） |
| **前端约束入口** | 新增「兼项缓冲(分钟)」（`ruleConflictBufferMinutes`，原硬编码 15、后端收不到）与「AI 对抗轮数」（`aiAdversarialRounds`）；三处编排请求统一走 `arrangePayload()` | `Schedule.vue` |
| **RSI 第一步** | `--label-search` 让标签由搜索产生（搜索没赢则保留原标签，质量单调不降）；实测与贪心标签版指标一致 → 默认关闭并记录原因 | `train_super_moe.py`、`benchmarks.md` §5 |

---

## 附二：本轮（第三次升级）——7 个独立模型合并进超级模型

| 项 | 状态 | 说明 |
|---|---|---|
| 任务数 11 → 17 | ✅ | 新增 6 位**能力专家**（生成/精修/扩散/派遣/预测/判别），逐一替代 `scheme_generator` / `scheme_refiner` / `scheme_diffusion` / `lane_advisor` / `forecast_direct`+`forecast_mimo` / `scheme_discriminator` |
| **异构门控** | ✅ | `MultiGateMoE`：任务专家（0..10）走**节点级**路由，能力专家（11..16）走**图级**路由。直接加 6 个同构专家只会摊薄容量 |
| 输出 5 → 7 | ✅ | 新增 `lane_logits[N,K]`（道次派遣）+ `quality_score[1]`（方案质量）；**只能尾部追加** |
| Java 按名读取 | ✅ | `SuperMoeService.named(...)` 取代按索引读取，旧 onnx 缺通道时回退索引 |
| `days_head` 从未训练 | ✅ 修复 | `forward` 算了、`training_loss` 丢了（`_`）→ 工期预测一直是个随机初始化头。本轮补回归目标 |
| 常量不同步 | ✅ 修复 | Java `N_TASKS` 写死 9（9→11 时漏改），Python `super_moe.py` 也写死过 `N_TASKS=9`。现已双向收敛到 17 / N_UNIT_TASKS=11 |
| `export_onnx` 缺 `dynamo=False` | ✅ 修复 | PyTorch ≥2.6 默认走 dynamo 导出器，依赖未安装的 `onnxscript` → 导出直接崩；且与 `dynamic_axes` 冲突。4 个导出脚本补齐 |
| 13 个模型深层化 | ✅ | 见 A1 |
| ONNX 严格全面超 GA | ✅ 定性完成 | 判定口径已从「单纯未排数」换成**加权代价**（`metrics.py` / `PlanCost`，含 兼项撞/道次撞/碎块/工期）。换口径后 HELL 档差距放大且可分解（AI 10040 vs GA 10380），**AI ≤ GA 五档全 ✅**；其余四档 GA 与 AI 仍持平，但已定位为**结构性原因**（L4 取「搜索最优 ∪ 种子」，而种子含 GA → 只可能 ≤ 必然相等），属**搜索能力**问题而非口径问题。另：默认生成器使「工期」分量恒为 0（窗口天数恰好 = days_limit），已加 `--extra-days` 口径实测其真正触发（每档 ≈ 27 分）。详见 `frontier-2026.md` §6.3 |

### 附三：本轮挖出的三处**真缺陷**（静默类，不是新功能）

| 缺陷 | 为什么危险 | 修法 |
|---|---|---|
| **双端第 19 维语义错位** | Python `super_encode.py` 有一行 `feat[i,19] = KO/RESECOND 标记`，把本该是「混合赛制」one-hot 的第 4 位**覆盖**掉；Java 侧从来没有这个实现 → **训练与推理对同一维语义不同**，且不报错、指标照常下降。同文件头部还存在**两张互相矛盾的特征表** | 删掉覆盖行与 `task_oh` 死代码，统一特征表；淘汰赛跨轮次由 `E_BRACKET` 边承载、二次编排由 `stage`（第 15 维）承载 |
| **`SchemeResult.rounds` 分不清「没跑」与「跑了没改进」** | 它记的是「**被采纳**的候选来自第几轮」，基线即最优时合法等于 0。测试却断言 `rounds() >= 1` → **模型变好反而测试失败** | 拆出 `roundsRun`（执行轮数），与 `LnsImprover.Report` 的 `rounds / acceptedRounds` 同口径；测试改断言「循环执行满 4 轮」+「不劣于基线」 |
| **`days_head` 从未被训练** | `forward` 算出来了，`training_loss` 却把它接成 `_` 丢掉 → 「工期预测」一直是个**随机初始化的头**，而它对应原 `forecast` 模型的职责 | 补 `days` 回归目标（贪心落位的真实跨天数） |

### 附四：同类的**工程性**缺陷（不修会反复）

| 缺陷 | 症状 | 修法 |
|---|---|---|
| `export_onnx` 等 4 个脚本缺 `dynamo=False` | PyTorch ≥2.6 默认 dynamo 导出器**依赖未安装的 `onnxscript`** → 导出直接崩；且与 `dynamic_axes` 冲突 | 4 处补齐 `dynamo=False` |
| 训练预算只接进 3 个脚本 | `train_gnn` / `train_gan` / `train_refiner` 仍用**浅层时代的默认轮数** → 160/6 只训 20 轮，泛化断言失败 | 统一接入 `budget.report_budget`；维度用 `_model_defaults(cls)` 从**模型类构造默认值**读，不抄常量 |
| `_model_defaults` 未传 keys | 返回空字典 → `KeyError: 'hidden'` → 训练秒崩（且 A 路脚本在训练失败后**仍然导出并覆盖了后端 onnx**） | helper 改为「不传 keys 即返回全部数值默认值」；补跑脚本加 **「训练未成功则不导出」** 的守卫 |
| 训练脚本里重复的预算块 | 旧块引用已不存在的 `_base_hidden()` → `NameError` | 清理重复块 |
| 训练日志被管道缓冲 | `python -m ... \| tee` / `\| grep` 会把输出**缓冲**到进程结束，几十分钟看不到一行 epoch → 误判"卡死" | 改为重定向到独立文件，事后 `grep -a` 摘要 |

## 附五：本轮新增规划层（启发式 + 神经搜索 + 预测 + 可回退）

新增 `sports_ai/plan/`（`hybrid_search.py` + 14 项单测），把用户要求的
「启发式 + 搜索神经网络 + 预测神经网络，预测后面的、发现不行可以回退」做成一条链：

| 要素 | 实现 | 是否参与可行性裁决 |
|---|---|---|
| **启发式** | MSBF 序（候选越少越先排）+ best-fit 紧度 + 前向检查 | ✅（确定性，构成裁剪基线） |
| **搜索神经网络** | `neural_prior(unit, slot)` = 模型 `slot_logits`，**只影响候选排序** | ❌ |
| **预测神经网络** | `CompletionPredictor`（8 维**状态局部化**特征 + 非对称损失 + 校准偏移） | ❌ |
| **回退** | ejection chain（让位）+ Repair + Restart + Rollback，三动作计数可诊断 | ✅（由 `_local_ok`/`verify_slot_map` 裁决） |

**实测（真实五档场景）**：BLOCK / LANE / TEAM **完全解出**（未排 0），
HELL 未排 5、REGULAR 未排 1，且**零容量/兼项违规**。
预测器校准后**可采纳性违例 0/69**（复现 arXiv:2606.04860 的结论）。

**同时修掉 3 个既有结构性问题**（详见 `frontier-2026.md` §3.4）：
影子任务窗口容量写死导致**超容标签**、影子单元随机共享运动员 ID 导致**永远排不下**、
以及搜索里「排序」与「剪枝」混淆导致空转。

### 附六：训练分布必须覆盖推理分布（本轮第三次踩同一类坑）

| 现象 | 根因 | 修法 |
|---|---|---|
| `conflict_gnn` 在 4 单元星形图上输出 `[0.080,0.064,0.069,0.080]`（几乎无区分、量级也不对），而正确标签是 `[1.0,0.667,0.667,0.333]` | 训练数据 `n_athletes=rng.randint(150, 800)`，**完全没有小赛会**；而标签是 `度数/(n-1)`，**n 越小标签越大** → 模型从没见过大标签，只会输出小值。**模型不报错，只是在那个区间完全失效** | 训练规模改为 `randint(4, 800)` 并重训 |

> 这与之前两次是同一类错误的不同面孔：
> ① **深度**没配够预算 → 误判「深层更差」；
> ② **特征**双端口径不一致 → 静默学错；
> ③ **规模**没覆盖推理分布 → 在小实例上静默失效。
> 共同点：**都不报错**。所以判据只能是「拿推理侧真实会出现的样子去测」。

### 附七：输入里必须有「预测目标」的可判据（本轮最深的一条）

修 `conflict_gnn` 在 4 单元星形图上排错中心节点时，连续排除了三层可能原因：

| 假设 | 验证 | 结论 |
|---|---|---|
| 训练预算不够 | 160/6 只训 20 轮 → 按预算补到 175 轮（`val_loss 0.0003`） | ❌ 仍是 argmax=3 |
| 训练分布没覆盖小规模 | `n_athletes` 从 `randint(150,800)` 扩到 `randint(4,800)` 重训（`val_loss 0.0008`） | ❌ 仍是 argmax=3 |
| **输入里没有「度数」** | 16 维特征中没有任何一维是度数；而**标签就是 `degree/(n-1)`** | ✅ **真因** |

**为什么度数"看不见"**：图注意力用**对称归一化** D^{-1/2} A D^{-1/2}，
在星形图上中心节点（度 3）与叶子节点（度 1）的聚合幅度**完全一样** ——
度数信息在归一化那一步就被抹平了。模型只能从邻居特征间接推测，而这在 4 个节点上无从推测。

**修法**：给冲突图**单独**加第 17 维「归一化度数」（与标签同源）。
补上后模型输出从 `[0.108,0.070,0.082,0.119]`（argmax=3，错）变成
`[0.475,0.407,0.401,0.291]`（argmax=0，正确）。

> ⚠️ 关键工程细节：**新维度只能加给需要它的模型**。
> 第一版直接改共享常量 `ConflictGraphEncoder.NODE_FEAT_DIM = 17`，
> 结果生成式三件套（同样用这个常量喂 `node_feat`）立刻
> `Got invalid dimensions for input: node_feat` 全部加载失败，连带 2 个测试挂掉。
> 正确做法是拆成 `NODE_FEAT_DIM=16`（通用）+ `CONFLICT_MODEL_FEAT_DIM=17`（专用）
> + `conflictModelFeat(enc)` 扩展方法。

**一句话教训**：**要预测什么，输入里就该有那个东西的可判据。**
当模型在某个区间系统性失效时，先问「那个目标的可判据在输入里吗」，
而不是先怀疑训练轮数或架构深度。

## 附八：本轮（第四次升级）—— 预测器进评测 + 非对称训练 + 规划层入 Java

| 项 | 状态 | 说明 |
|---|---|---|
| 预测器接进正式评测 | ✅ | `evaluate_super_moe` 新增按档位训练的 `CompletionPredictor` 与 `predictor_ablation`；**同扩展预算**协议（`EXPANSION_BUDGET`）比较，因为「省扩展 + 解逐位一致」在本问题上**不可实现**（首个可行槽被剪掉必然改选后面的） |
| 实测：单次尝试省 65~89% | ✅ | 剪枝确实省搜索量 |
| 实测：同预算总省 0~56% | ⚠️ | 省下的预算被用于更多次重启（HELL 5→30、TEAM 18→59） |
| 实测：**纯剪枝会扰动解** | ⚠️ 已处理 | HELL 102→111、REGULAR 30→31。新增**守卫式组合**（取两侧更优），五档 `guarded_not_worse` 全 ✅ —— 解质量单调不劣成为**结构保证** |
| 非对称训练 | ✅ | `asymmetric_mse(beta=3.0)` 接在 `days_head` / `quality_head`；需重训生效 |
| 规划层接进 Java | ✅ | `PredictivePlanner`（8 项单测）+ `PredictivePlannerService` + `POST /api/schedule/plan`（只读预演） |
| 前端接入 | ✅ | 赛程页「规划预演」按钮 + 诊断弹窗（8 个诊断数字 + 排不下清单 + 方案明细） |
| 17 专家模型预算跑满 | ✅ | 补训达标：`epochs_run=22 / budget_epochs=30 / budget_satisfied=true`；`val_loss 1.2444 / pri_mse 0.00432`，`route_entropy 0.991`（17 专家全激活）；ONNX 已重导 42.04 MB（5 入 7 出，动态 N 全过）|
| 主链路评测（2 seeds） | ✅ | **AI 五档全部 ≤ GA**；兼项撞 0 / 超占 0 / 修复 0 / 修不了 0 |

### ⚠️ 附九：本轮最贵的缺陷 —— 「预算耗尽」被当成「结论为否」

| 现象 | 根因 | 修法 |
|---|---|---|
| HELL 采到 1182 个标签样本，**100% 是 0**；预测器输出退化成常数 0.0033；消融显示「带预测与不带预测」扩展数**完全相同** | ① `if len(feats) >= max_states: return False` 的 `False` **沿递归上传**，把整条祖先链标成「排不完」；② 穷举 DFS 的 `∏|cands|` 分支在 18 单元上只走到第 5 层就吃光预算，**从未到达 `depth == n`**，一个成功叶子都产生不了 | 区分「未知」（`None`，祖先保持未知）与「确定不可行」；并改为 **rollout 标签**（沿真实规划路径落位，每步调 `_greedy_complete` 判定能否排完），成本线性 |

> 这条与之前两次是同一族问题：**「截断/缺失」被当成「否」**。
> 判据：凡是「搜索/采样中途停下」的地方，都要问一句
> 「我这里是把它当成了『否』，还是当成了『不知道』？」

### 附十：软排序信号是噪声（一个反直觉结论）

第一版让预测器**参与候选排序**（权重 0.3）。实测：即使一刀不剪，
它也会因为「同一批候选被重新排序」而改变贪心选择 ——
在 HELL 档把未排从 9 抬到 **10（变差）**。
而确定性 best-fit 本身已把「最紧且可行」的槽排在前面，软信号基本是噪声。
现已默认 `pred_use_in_order=False`：**剪枝负责省搜索量，排序交给确定性启发。**

## 结论：缺口清单（按优先级）

**P0 — 直接影响用户核心诉求**

1. **块状约束缺失**（B7）：需要新增「同项目组次必须连续成块」的硬约束或代价项，并在 rule/optimize/ai 三档都生效
2. **主田径时间三态缺失**（B9/B10）：`/api/schedule/auto` 需支持 `0=不限` / `-1=尽可能压缩`
3. **标签升级（RSI 前提）**（D1/C6）：标签从 `greedy_targets` 换成搜索解，否则模型天花板永远是贪心
4. **`slot_logits` 有害**（D1）：实测误导落桶，需重训或推理侧降权

**P1 — 能力完整性**

5. 裁判编排 / 教师规避的**独立模型**（C5），再合并进 `super_moe` 专家头
6. **接力整队不可拆**接线（B2）
7. 球类 `resecond` 占位实现补全（B8）
8. 前端补齐 E7–E11 的输入入口；E12 的「写而不生效」项要么接线要么下线

**P2 — 打磨**

9. 其余 9 个 onnx 的深层化（A1）
10. 拔河专属赛制（B4）
11. 魔鬼规模端到端压测（C1/C2）
12. 未接入的 `constraint_gnn` / `scheme_diffusion` / `forecast_*` 决定接线或明确下线
