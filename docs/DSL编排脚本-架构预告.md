# DSL 编排脚本 · 架构预告（伪代码 / 自定义编排脚本）

> 结论先行：本预告把系统从「配置驱动的编排工具」推向「**可编程的编排平台**」——
> 不是新增一个功能，而是给系统增加一层**领域特定语言（DSL）**，让用户用自定义脚本描述「怎么排」，
> 而不是只能通过表单与配置去「点」。仿效 Minecraft 的命令方块 / 数据包 / 脚本（KubeJS、CraftTweaker）思路，
> 得益于 Java 成熟的 DSL 生态（JSR-223 脚本引擎、注解处理、ANTLR/JavaCC 解析库、Spring Integration DSL 等）。
>
> 状态：**下一阶段功能（预告）**，本文档为架构规划与落地约束，尚未实现。

---

## 一、定位：从「配置」到「脚本」

| 维度 | 当前（配置驱动） | 下一阶段（DSL 驱动） |
|---|---|---|
| 用户表达方式 | 表单、配置页、Excel 导入 | 编写编排脚本 |
| 能力边界 | 系统预设的编排规则 | 用户可定义任意编排逻辑 |
| 灵活性 | 受限于配置项 | 受限于 DSL 表达力 |
| 类比 | 「使用软件」 | 「为软件编写插件」 |

## 二、归属层级：DSL / 伪代码属于 L1（自定义规则层）

- L1 名义上就是「**自定义规则**」层：用户可选择用**哪一款**规则来分组分道。
- 现有款型：`CLASS`（班级均衡，默认）、`SNAKE`（蛇形排布）、`SNAKE_SEEDED`（种子蛇形）。
- **DSL / 伪代码将作为 L1 的「新款型」接入**（如 `PSEUDO` / `SCRIPT`），与既有款型并列、可选。
- 落地约束：
  1. **多文件**：DSL 相关实现拆分为多个文件（词法/语法/AST/执行/沙箱各司其职），不塞进单个大文件；
  2. **两条路径都要支持**（见第三节），分层共存；
  3. L1 款型目录已抽为独立类型（`com.sports.schedule.rule.l1.L1Rule`），新增款型 = 加枚举 + 在 `allocate` 分流；
     前端经 `GET /api/arrange/l1-rules` 动态列出，**无需改前端**。

## 三、两条主路（分层共存，非二选一）

| 维度 | 外部 DSL（自定义语法） | 脚本注入（JSR-223） |
|---|---|---|
| 用户写什么 | 你定义的领域语法 | JavaScript / Groovy / Python |
| 解析方式 | ANTLR / JavaCC 解析 | 脚本引擎直接执行 |
| 学习成本 | 需学你的语法 | 需会一门脚本语言 |
| 灵活性 | 受限于你定义的语法 | 接近图灵完备 |
| 安全性 | 可控（语法受限） | 需沙箱 |
| 类比 | Minecraft 命令方块 | Minecraft 数据包 / 脚本模组 |

```
┌─────────────────────────────────────────────────────┐
│ 第 1 层：外部 DSL（声明式）                          │
│   用户写：arrange M100 { group by grade ... }        │
│   解析：ANTLR → AST                                  │
├─────────────────────────────────────────────────────┤
│ 第 2 层：脚本注入（命令式）                          │
│   用户写：Groovy / JavaScript                        │
│   执行：JSR-223 + 沙箱                               │
├─────────────────────────────────────────────────────┤
│ 第 3 层：编排引擎（Java 原生）                       │
│   AlgorithmPortfolio / GA / LNS / 匈牙利 / 蛇形      │
│   接收 DSL/脚本指令，执行实际编排                    │
└─────────────────────────────────────────────────────┘
```

外部 DSL 负责「说得清楚」，脚本注入负责「做得灵活」，编排引擎负责「真正执行」。

## 四、可选形态：代码 + 积木双模式（共享同一棵 AST）

本质：**积木与代码是同一个 DSL 的两种前端表达** —— 一个可序列化的**编排 AST**，两种编辑器去生成它（类比 Scratch）。

```
积木模式（拖拽）  ⇄  编排 AST（中间表示）  ⇄  代码模式（文本）
```

- 积木模式：Blockly（自定义积木：事件 / 分组 / 约束 / 策略 / 参数 / 条件 / 循环）
- 代码模式：Monaco（VS Code 同款）或 CodeMirror + ANTLR 解析
- 双模式互转：`blocksToAst` / `astToCode` / `parseCode` / `astToBlocks`
- 共享 AST → 积木拖出的结构就是代码写出的结构，可互相转换

## 五、分阶段路线（从轻到重）

1. **形态一 · 规则注入（最轻）**：用户写规则片段，系统用 JSR-223 解析后注入求解器；成本最低、见效最快。
2. **形态二 · DSL 规则**：ANTLR 自定义语法，接近自然语言，可读性好、更可控。
3. **形态三 · 脚本文件**：Groovy/Kotlin 完整脚本（`configure()` / `constraints()` / `output()`），适合二次开发。
4. **形态四 · 可视化 DSL**：前端积木/规则编辑器生成 DSL，零代码门槛。

## 六、与现有架构的衔接点

| 已有能力 | DSL / 脚本接入点 |
|---|---|
| `AlgorithmPortfolio` | DSL 指定 `strategy`（用哪个算法） |
| `ScheduleVerifier` | DSL 声明 `constraint`（自定义约束） |
| `SnakeGrouping` / L1 款型 | DSL 写 `strategy snake-lane` 或自定义分组 |
| 裁判编排 | DSL 写 `referee auto` |
| 时间窗配置 | DSL 写 `window 08:00-12:00` |
| `Event` 七字段模型 | DSL `event(...)` 声明 |
| 输出（秩序册） | DSL `output "order-book" { ... }` |

DSL 不是替代现有算法，而是在算法之上加一层「可编程的编排描述」。

## 七、关键工程要点

- **安全**：用户脚本不得执行任意代码 → 沙箱（如 Groovy `SecureASTCustomizer`）限制可访问类/方法；
  超时控制（防死循环）；只暴露受控 API，不暴露底层连接；脚本来源需审核。
- **可观测**：脚本执行失败可定位；规则冲突可报告；执行结果可追溯（复用现有 `AuditService`）。
- **与求解器集成**：DSL 解析为 `ConstraintProvider` / HardMediumSoftScore 计算逻辑，注入 Timefold。
- **与 AGPLv3 兼容**：DSL 解析器属项目一部分，受 AGPLv3 覆盖；用户编写的脚本归用户所有，不强制 AGPLv3。
  （与既有「开源版基础功能免费 / 商业版高级功能与技术支持」双轨天然适配。）

## 八、一句话

下一阶段：**伪代码支持，仿效 MC 的自定义编排脚本，得益于 Java 加设计 DSL**。
从「填参数」升级到「写规则」，从「配置驱动」升级到「脚本驱动」，从「产品」升级到「平台」。

---

## 九、第一阶段（形态一 · 规则注入）落地进度

> 状态：**执行内核 + 持久化 + REST 已完成并测试通过；编排结果可见；求解器深度注入待续。**

### 已实现（多文件，遵循「DSL 拆多文件」约束）

| 文件 | 职责 |
|---|---|
| `inject/RuleScript` | 脚本模型：`id / name / engine(builtin\|js\|groovy) / enabled / source` |
| `inject/RuleContext` | 只读「点路径」上下文（`event.category` / `athlete.className` / `lane`…），沙箱边界 |
| `inject/RuleOutcome` | 结果：hard/medium/soft 增量 + veto + 触发说明 + error |
| `inject/RuleScriptEngine` | 引擎抽象——「两条路径」共用 |
| `inject/builtin/Expr` | 内置伪代码的表达式：词法 + 递归下降解析 + 求值（`|| && !`、比较、括号、字面量、点路径） |
| `inject/builtin/BuiltinRuleScriptEngine` | 内置伪代码语句：`when <cond> then <actions>` / `if (<cond>) { <actions> }`；动作 `hard/medium/soft += N`、`veto` |
| `inject/Jsr223RuleScriptEngine` | JSR-223 脚本注入路径（引擎缺失 → 明确错误，不抛异常） |
| `inject/RuleScriptEvaluator` | 按引擎分派 + **超时保护** + 失败降级 |
| `inject/RuleScriptStore` | 持久化到 `system_config.rule_scripts`（JSON，脏配置容错） |
| `inject/RuleInjectionService` | 聚合评估 / 试运行 / 引擎可用性 |
| `ArrangementService` | 编排后对每条落位评估规则 → 结果新增 `ruleInjection`（hard/medium/soft/veto/hitCount/fired），命中硬否决/硬分时并入 `warnings` |

### 语法示例

```
# 头注释（# 或 //）
when event.category == "径赛" && lane <= 2 then soft += 30
if (teamMembers > 1 && heat > 6) { hard += 100; veto }
```

### REST

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/arrange/rule-scripts` | 脚本列表 + 各引擎可用性 |
| PUT | `/api/arrange/rule-scripts` | 覆盖保存（带审计） |
| POST | `/api/arrange/rule-scripts/test` | 试运行（不落库，实时看命中/增量/错误） |

### 关键工程决策

- **两条路径并存**：`builtin`（零依赖、按构造即沙箱，**今日即可用**）+ `js/groovy`（JSR-223）。
  因 **Java 21 已移除内置 Nashorn**、离线仓库亦无 Groovy/GraalJS，故 JSR-223 引擎缺失时如实降级
  （`available()=false` + 明确错误），引入依赖即自动生效——系统在无引擎环境下照常运行。
- **失败绝不拖垮编排**：脚本异常/超时统一降级为带 error 的结果并并入 `warnings`。
- 测试：`BuiltinRuleScriptEngineTest 10`、`RuleInjectionServiceTest 4`、`RuleScriptStoreTest 2`、`ArrangementServiceTest 15`，全绿。

### 待续（第二阶段前的收尾）

1. 把注入结果**真正作用于落位选择**（当前为「如实上报 + 硬否决告警」，尚未改写 allocation）；
2. 与 Timefold 求解器打通（动态 `ConstraintProvider`），真正「注入求解器」；
3. 前端脚本编辑器 + 试运行面板（代码模式；积木模式属形态四）；
4. 沙箱加固（JSR-223 引擎级类/方法白名单）。
