# 从模型输出还原成表格

> 本文件是**独立专题**：讲清「模型的张量输出」如何一步步变回**人能看的表格**（Excel / 前端表格）。
> 这是 `dataflow.md` 的**逆过程** —— 那份讲「表格 → 张量」，这份讲「张量 → 表格」。
>
> 版本：2026-10-04

---

## 0. 一句话总览

```
ONNX 张量输出 ──(①解释)──▶ Advice / 候选方案        ← 建议，不是结果
                              │
                              │ ②精修 + 硬约束校验（L1-L3 算法链路）
                              ▼
                        Arrangement 实体（落库）      ← 真正的结果
                              │ ③组织 + 排序 + 标签化
                              ▼
                        rows[表头][数据行]            ← 纯粹的二维字符串数组
                              │ ④EasyExcel 写出
                              ▼
                        xxx_编排表_阶段_v版本_时间戳.xlsx
```

**核心原则（务必先记住）**：

> **模型输出永远只是「建议」，落库的 `Arrangement` 才是「结果」。**
> 模型输出**不直接**变成表格 —— 它先被当作求解器的初始序/种子，
> 经 Timefold / GA / LNS 精修并通过**硬约束校验**（容量、兼项、保护时段）后才落库。
> 这样即使模型输出完全离谱，也排不出违规赛程；最坏情况只是「排得不够好」。

---

## 1. 第①步：张量 → 可解释建议

以 `super_moe` 为例，7 个输出各自被解释成什么（`SuperMoeService.Advice`）：

| 张量输出 | 形状 | 解释方法 | 变成什么业务语义 |
|---|---|---|---|
| `priority` | `[B,N]` | `orderByPriority()` 按值降序 | **单元排列顺序**（越前越先排） |
| `slot_logits` | `[B,N,16]` | `slots()` 逐行 argmax | 每单元的**候选时间桶** |
| `lane_logits` | `[B,N,16]` | `laneSlots()` 逐行 argmax | 每单元的**候选道次/批次** |
| `format_logits` | `[B,4]` | `recommendedFormat()` argmax + 名称表 | 球类**赛制推荐**（group/round_robin/knockout/hybrid） |
| `days_estimate` | `[B,1]` | 直接取值 | **工期参考**（天） |
| `quality_score` | `[B,1]` | `qualityLevel()` 分三档 | 方案**质量提示**（good/fair/poor） |
| `task_probs` | `[B,17]` | `expertUsage()` / 路由熵 | **诊断**：专家有没有塌缩 |

> ⚠️ `quality_score` **只是参考信号，不是裁决**。表格里若展示它，必须标注为「模型自评」，
> 因为「方案能不能用」由 `ScheduleFeasibilityService` 与硬约束校验决定 ——
> 模型自评再高也不能覆盖「不可行」。

其他模型的解释方式：

| 模型 | 输出 | 解释成 |
|---|---|---|
| `conflict_gnn` | `priority[1,N]` | 着色优先级 + `AiAdvisory.strategy`（硬解/取消）+ `cancelProbability` |
| `tournament_gnn` | `format_logits[3]` / `seed_scores[N]` / `fairness_cost[N]` | 赛制 / 种子序 / 公平性代价 |
| `referee_gnn` | `priority[N]` | 裁判派遣序（**只排序，派遣合法性由规则把关**） |
| `teacher_gnn` | `aversion[N]` | 教师避让序 |
| `lane_advisor` | `priority[N]` | 运动员派遣序 |
| GAN 三件套 | `logits[N,16]` | argmax → `slots[N]` 候选方案 |
| `algorithm_selector` | `strategy[2]` | 硬解 / 取消路径 |
| `forecast_*` | `y[8]` | 未来 8 步趋势点 |

---

## 2. 第②步：建议 → 落库实体 `Arrangement`

**入口**：`POST /api/arrange/events/{eventId}`（可带 `style` / `days` / 各 `rule*` 参数）

```
Advice.priority ─┐
                 ├─▶ L1/L2/L3 求解（Timefold + GA + LNS + …）─▶ 校验硬约束 ─▶ Arrangement 逐行落库
Advice.slots    ─┘        （模型序只是**初始序**）
```

### 2.1 `Arrangement` 的关键字段（决定表格每一列）

| 字段 | 来源 | 对应表格列 |
|---|---|---|
| `round` | 赛次（`prelim` / `final` / 自定义） | **赛次**（由 `roundLabel()` 转中文） |
| `grade` | 运动员年级（过 `Grades` 归一） | **年级** |
| `gender` | 运动员性别 | （用于裁判查找键，不单独成列） |
| `heat` | 组号 | **组号**（径赛） |
| `lane` | 道次 | **道次**（径赛） |
| `position` | 出场顺序 | **出场顺序**（田赛，`h+1`） |
| `athlete` | 参赛学生 | **运动员 / 号码簿 / 班级** |
| `prelimTime` | 预赛成绩 | **预赛成绩** |
| `qualified` | 是否晋级 | **晋级**（✓） |
| `EventReferee.refereeIds` | 裁判分配 | **裁判**（姓名串，`、` 连接） |

### 2.2 「同组不同班」硬约束在落库前生效

`ArrangementService` 的核心分配保证**同一组不能同班**：

```
组数 = max( ceil((自动池 + 锁定项) / 道数),
            最大单班人数(含锁定项),
            锁定项最大组号 )
贪心：按「当前组人数最少」选组；命中「同班已占用」则跳过。
人工锁定项先按 (heat, lane) 预置进矩阵并占位，自动编排不会把组号回退到锁定项之前。
```

> 这一段是**表格能不能用的关键**：它决定了「同一个班的 3 个人会不会被塞进同一组」。
> 它由**规则**保证，不由模型保证 —— 模型只影响顺序。

---

## 3. 第③步：实体 → 二维字符串数组

**入口**：`GET /api/arrange/events/{eventId}/export`（单项目）
**入口**：`GET /api/arrange/export-all`（全部项目，返回 JSON 结构供前端渲染）

### 3.1 表头（**径赛与田赛不同**）

| 场景 | 表头（10 列 / 9 列） |
|---|---|
| **径赛** `track = true` | 赛次 · 年级 · **组号** · **道次** · 运动员 · 号码簿 · 班级 · 预赛成绩 · 晋级 · 裁判 |
| **田赛** `track = false` | 赛次 · 年级 · **出场顺序** · 运动员 · 号码簿 · 班级 · 预赛成绩 · 晋级 · 裁判 |

> ⚠️ **田赛没有组号/道次**，改用连续的「出场顺序」。
> 这是有意的：田赛是依次出场试跳/试投，用「组号+道次」表示会误导现场。

### 3.2 排序规则（**这一条修过两个真 bug**）

```
赛次（预赛在前、决赛在后）
  → 年级
    → 组次
      → 道次
```

> ⚠️ **为什么必须带「年级」**：各年级的组号是**独立编号**的（高三 1 组 与 高一 1 组 不是同一组）。
> 早期不加年级直接按 `组号→道次` 排，会把不同年级的组混在一起（Bug2/Bug3）。

### 3.3 逐列取值规则（含历史数据兼容）

| 列 | 取值 |
|---|---|
| 赛次 | `roundLabel(round)`；`round` 为空/空串时按 `final` 处理 |
| 年级 | `grade`，null → `""` |
| 组号 | `heat` 的字符串形式 |
| 道次 | `lane` 的字符串形式 |
| 出场顺序 | **优先**用落库 `position`；历史数据 `position == null` 时回退为**连续序号** `++fieldSeq` |
| 运动员 | `athlete.name` |
| 号码簿 | `athlete.number`，null → `""` |
| 班级 | `athlete.classInfo.name`，null → `""` |
| 预赛成绩 | `prelimTime`，null → `""` |
| 晋级 | `qualified == true` → `"✓"`，否则 `""` |
| 裁判 | 按 `年级\|性别\|赛次\|组次` 查 `EventReferee` → `refereeIds` → 姓名用 `、` 连接；查不到 → `""` |

> **裁判列是「按组次挂载」的**：同一 (年级, 性别, 赛次, 组次) 的所有道次共用同一串裁判姓名。
> 这与裁判编排模型（`referee_gnn`）的粒度一致 —— 模型给的是**派遣优先级**，
> 最终「哪个组次用哪几位裁判」由 `ArrangementService.assignReferees` 按
> 「专长优先 → 负载均衡 → 并行组次不重用」裁决。

---

## 4. 第④步：写出 Excel

```java
List<List<String>> headCols = rows.get(0).stream().map(List::of).collect(toList());
EasyExcel.write(out).head(headCols).sheet(eventName).doWrite(rows.subList(1, rows.size()));
```

**文件名规范**（`ExportNaming`，用于区分多版本产物，避免拿错）：

```
{项目名}_编排表_{阶段}_v{版本}_{yyyyMMdd-HHmmss}.xlsx
例：男子100米_编排表_二次编排后_v2.8.5_20261004-091500.xlsx
```

| 组成 | 取值 |
|---|---|
| 阶段 | 存在 `round == final` 的行 → **二次编排后**；否则 → 原始编排（`ExportNaming.stage(...)`） |
| 版本 | jar 清单 `Implementation-Version` → 否则 Maven 生成的 `pom.properties` 的 `version`（与 `pom.xml` 始终同步）→ 最后才是兜底常量 |
| 时间戳 | `yyyyMMdd-HHmmss` |

**响应头**：`Content-Type` = xlsx；`Content-Disposition` 同时给 ASCII 与 `filename*=UTF-8''` 两种形式
（中文文件名在部分浏览器/代理下会乱码，必须双写）。

---

## 5. 其他导出物与其表结构

| 端点 | 产物 | 结构 |
|---|---|---|
| `GET /api/arrange/events/{id}/export` | 单项目**编排表**（xlsx） | §3.1 的 10/9 列 |
| `GET /api/arrange/export-all` | 全部项目**编排总览**（JSON） | `{generatedAt, eventCount, finalRoundCount, events:[{eventId, eventName, track, rounds, statistics}]}` |
| `GET /api/arrange/events/{id}/referees` | **裁判安排表** | 按 (年级, 性别, 赛次, 组次) 聚合的裁判名单 |
| `GET /api/arrange/events/{id}/qualifiers` | **晋级名单** | 从预赛成绩按录取规则产生 |
| `GET /api/arrange/events/{id}/reservations` | **场地预约表** | 场地 × 时段的占用 |
| `GET /api/arrange/events/{id}/verify` | **校验报告** | 硬约束违例清单（不是表格，是诊断） |
| `GET /api/excel/template?type=xxx` | **导入模板** | 与 `dataflow.md §1.3` 的 13 种表一一对应 |
| `GET /api/rankings/export` | **成绩/名次表** | 名次 · 号码 · 姓名 · 班级 · 成绩 · 风速 · 得分 · 备注 |

> `/export-all` 返回的是 **JSON 而不是 xlsx**：它给前端「编排总览页」渲染用，
> 需要 `rounds`（多轮）与 `statistics`（统计）这类**嵌套结构**，二维表格表达不了。

---

## 6. 回退与重排（**表格层面的「撤销/重做」**）

用户诉求里明确提到「**如果发现不行，可以回退**」。系统提供的回退路径：

| 端点 | 作用 | 回退粒度 |
|---|---|---|
| `POST /api/arrange/events/{id}/rollback` | 回退该项目的编排 | **项目级** |
| `POST /api/arrange/events/{id}/rearrange` | 再次排道（body `{grade, round}`，`auto→null`） | 年级 + 赛次级 |
| `POST /api/arrange/events/{id}/preliminary` | 生成预赛编排 | 赛次级 |
| `POST /api/arrange/events/{id}/prelim-results` | 录入预赛成绩 | 成绩级 |
| `POST /api/arrange/events/{id}/qualify` | 按录取规则产出晋级名单 | 晋级级 |
| `POST /api/arrange/finals/rebuild-all` | **重建全部决赛编排** | 全局（决赛） |

**典型回退闭环**（现场真实流程）：

```
生成编排 → 导出 xlsx → 打印/现场核对 → 发现某组同班/撞兼项
   → /rollback（或直接 /rearrange 指定年级+赛次）
   → 重新导出（文件名自动带新时间戳与「阶段」标记，不会与旧文件混淆）
```

> ⚠️ **文件名带「阶段 + 版本 + 时间戳」不只是好看**：
> 导出表被下载到本地后，`原始编排` 与 `二次编排后` 两份文件如果同名，
> 现场极易拿错 —— 这正是引入 `ExportNaming` 的原因（U11/B13）。

---

## 7. 导入 ↔ 导出 的对称性

| 数据 | 能导入 | 能导出 | 备注 |
|---|---|---|---|
| 项目（`event` / `eventsimple`） | ✅ | ❌ | 导出的是编排结果，不是项目定义 |
| 运动员（`athlete` / `roster`） | ✅ | ✅（成绩/名次表含姓名号码班级） | |
| 报名（`signup` / `athlete_signup`） | ✅ | ❌ | |
| 成绩（`score`） | ✅ | ✅（`/api/rankings/export`） | |
| **编排结果** | ❌ | ✅（**编排表**） | 编排结果不可反向导入 —— 它是**计算结果**，不是配置 |
| 场地（`venue`） | ✅ | ❌ | |
| 裁判安排 | ❌ | ✅（裁判表） | |

> ⚠️ **编排结果刻意不支持导入**：它依赖当前运动员/项目/场地的完整状态。
> 允许导入等于允许「用旧状态的结果覆盖新状态的配置」，会产生难以追查的不一致。
> 要复用编排，应该复用的是**参数**（款型、天数、规则脚本），而不是结果。

---

## 8. 这一层最常见的坑

| 坑 | 症状 | 修法 |
|---|---|---|
| **跨年级组号混排** | 表格里「高三 1 组」与「高一 1 组」被排在一起 | 排序键加「年级」（Bug2/Bug3 已修） |
| **田赛出现组号+道次** | 田赛表格里空组号、空道次，现场看不懂 | 田赛改用连续「出场顺序」；`position` 落库 |
| **历史数据 `position` 为空** | 老数据导出出场顺序全是空 | 回退为连续序号 `++fieldSeq` |
| **裁判列整列为空** | 查找键 `年级\|性别\|赛次\|组次` 拼错（如性别为 null） | 键里的 null 统一成 `""`；查不到给 `""` 而不是抛异常 |
| **中文文件名乱码** | 浏览器下载后文件名是乱码 | `Content-Disposition` 同时给 ASCII 与 `filename*=UTF-8''` |
| **同阶段两份文件同名** | 现场拿错原始/二次编排的表 | 文件名带阶段 + 版本 + 时间戳 |
| **模型输出被当结果** | 表格里的分配违反容量/兼项 | 模型输出只做种子；**硬约束校验在落库前**，失败则回退规则路径 |
| **`quality_score` 被当验收依据** | 表格显示「good」但方案其实不可行 | 标注为「模型自评」；可行性只看 `ScheduleFeasibilityService` |

---

## 9. 反向还原的「诚实边界」

以下三件事**无法**从表格还原回模型输入，必须知道：

1. **表格是投影后的视图**：`Arrangement` 落库时已把「时间桶」展开成「赛次/年级/组号/道次」这类现场语言。
   时间桶（`slot`）与道次（`lane`）不是一一对应 —— 一个时间桶可容纳多个组次的并行场地。
2. **模型的中间量不落库**：`priority` / `slot_logits` / `task_probs` 都不入库，
   只在响应里返回。想复盘「模型当时建议了什么」，只能看日志与响应体。
3. **裁判列是挂载值**：它是 (年级, 性别, 赛次, 组次) → 姓名串 的查找结果，
   不是「某个裁判被模型排到了这里」。模型的贡献在**派遣优先级**，不在最终名单。
