# 编排能力逐条核查表

> 核对时间：2026-10-03 ｜ 代码基线：分支 `2.8.5`
> 状态口径：✅ 已实现 ｜ 🟡 部分实现 ｜ ❌ 未实现 ｜ 🔧 本轮新修
> 证据均为代码路径 + 类/方法，可逐条复核。

---

## A. 模型架构升级

| # | 要求 | 状态 | 证据与说明 |
|---|---|---|---|
| A1 | 全部升级为**深层网络** | 🟡 | 核心三模型已深层化：`super_moe`（`GlobalBlock`×n_global + 专家内 `_ExpertStep`×expert_depth）、`constraint_gnn` 160/6、`tournament_gnn` 160/5。**其余 9 个 onnx 仍是旧结构**（`conflict_gnn` / `selector` / `lane_advisor` / GAN×3 / diffusion / forecast×2） |
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
| C5 | **裁判编排 / 教师规避：先独立模型，后合并主模型** | 🟡 | **独立模型本轮完成**：`sports_ai/referee_advisor.py`（12 维裁判节点 / 4 类边 / 160×5，训练 `val_mse=0.00202`）→ `referee_gnn.onnx`(3.09MB) → Java `RefereeGnnEncoder` + `RefereeAiService`（7 项双端契约单测）。**合并进 `super_moe` 尚未做**：那会改变任务数（9→10）与输出契约，必须连同全量重训一起做，不能顺手改。教师规避仍为规则（`AdminTimeProtectionService`） |
| C6 | 争取 RSI | ❌ | 未实现。已给出唯一有效路径：**标签升级**（见 `benchmarks.md` §4.1）——当前标签是 `greedy_targets`，天花板即贪心 |

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
| E3 | 款型（`ArrangeStyle`） | ✅ | `/api/arrange/styles` 下发；CLASS / SNAKE / SNAKE_SEEDED / AI |
| E4 | 球类全部参数（赛制/组数/晋级/单场分/双回合/时间目标） | ✅ | `BallTournament.vue` → `/ball/arrange` |
| E5 | 规避时间（全校/班主任/裁判） | ✅ | `ProtectionManage.vue` → `/api/protections` |
| E6 | 规则注入 / DSL | ✅ | `RuleScripts.vue`（积木 + 代码双模式，builtin/groovy/javascript） |
| E7 | **兼项缓冲分钟** | ❌ | 后端可收 `ruleConflictBufferMinutes`，但**前端无入口**（硬编码 15） |
| E8 | **AI 自对抗轮数 / AI 款型** | ❌ | 后端可收 `aiAdversarialRounds` / `aiLaneStyle`，**前端无入口** |
| E9 | **分道策略 / 全局录取人数 / 冲突检查开关** | ❌ | 后端可收 `ruleLanePolicy` / `ruleAdvanceCount` / `ruleConflictCheckEnabled`，前端无入口 |
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
| F5 | 全量回归 | ✅ | **550 passed / 0 failed**（543 → 550，新增 7 项块状/三态测试） |

---

## 附：本轮（第二次升级）新增与修复

| 项 | 内容 | 证据 |
|---|---|---|
| 块状约束 | `projectBlockContiguity` 软约束 + `breaksBlock` 纯函数 + 7 项单测 | `ScheduleConstraintProvider.java`、`ProjectBlockContiguityTest.java` |
| 时间三态 | `days<0` → 尽可能减少；跨天惩罚 ×5 静态桥（finally 复位）；前端「时间目标」下拉 | `ScheduleService.java`、`Schedule.vue` |
| 裁判独立模型 | Python 模型 + 训练 + 导出 ONNX + Java 编码器/服务 + 契约单测 | `referee_advisor.py`、`RefereeGnnEncoder.java`、`RefereeAiService.java` |
| 备份爆炸修复 | 新训练脚本每轮备份 → 40 轮堆 18 个 3MB；补 `_prune_backups(keep=3)` | `referee_advisor.py` |

---

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
