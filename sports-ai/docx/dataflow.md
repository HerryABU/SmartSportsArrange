# 数据是怎么一路变成模型输入的

> 本文回答一个问题：**Excel / 数据库里的原始数据，经过哪些环节，最终变成 ONNX 的输入张量；
> 模型的输出又如何变回业务对象、写回数据库。**
>
> 配套：`models.md`（每个模型的原理 / 训练 / 输入输出）、`benchmarks.md`（实测）、`audit.md`（核查表）。

---

## 0. 三层转化总览

```
┌─────────────┐   ①导入   ┌──────────────┐   ②组装   ┌──────────────┐
│ Excel 文件   │ ────────▶ │   SQLite /   │ ────────▶ │  业务对象     │
│ (.xlsx/.xls) │           │ H2 / MySQL   │           │ ScheduleUnit │
└─────────────┘           └──────────────┘           │ Window / ... │
                                                      └──────┬───────┘
                                                             │ ③编码
                                                             ▼
                                                   ┌──────────────────┐
                                                   │ 张量 Encoded      │
                                                   │ nodeFeat[N][20]  │
                                                   │ adjByType[8][N][N]│
                                                   └──────┬───────────┘
                                                          │ ④推理
                                                          ▼
                                                   ┌──────────────────┐
                                                   │ ONNX → Advice    │
                                                   │ priority / slot  │
                                                   └──────┬───────────┘
                                                          │ ⑤回写
                                                          ▼
                                                  赛程落库（Placement / 编排结果）
```

**一句话概括**：模型从不直接读文件 —— 它只吃**张量**。
文件的职责是喂数据库，数据库的职责是喂业务对象，业务对象的职责是被**编码器**翻译成张量。
**编码器是唯一的翻译层**，也是双端契约（Python 训练 / Java 推理）必须逐位一致的地方。

---

## 1. 第①层：Excel 文件 → 数据库

### 1.1 单表导入（按类型）

入口：`POST /api/excel/import?type=xxx`，实现 `ExcelService`。

```
上传文件 → 读表头 → ExcelColumnMapping 识别列 → 逐行 processXxxRow → Repository.save
```

**列映射是这一步的全部难点**，有三条铁律（都是踩坑换来的）：

| 铁律 | 说明 |
|---|---|
| **改一列模板 = 三处同步** | `ExcelColumnMapping.TYPE_COLUMN_ALIASES`（别名表）+ `TYPE_FIELDS`（字段表）+ `ExcelService.processXxxRow`（落库）。漏任一处 → 「表头认得、数据静默丢弃」 |
| **包含匹配是双向的** | 列名 `场地号` 包含别名 `场地` → 反向也算命中 → `场地号` 被判成 `defaultVenue`，再把真正的 `场地` 列挤进 `unmappedHeaders`。修法：给长列名补**精确别名** |
| **短别名会被长别名抢** | 别名 `项目`（2 字）会抢走 `项目编码` / `项目代码` → 必须为模板每一列补精确别名 |

### 1.2 多表导入（一个 Excel 多个 Sheet）

入口：`POST /api/excel/multi/preview` → 结构预览 → `POST /api/excel/multi/import`。

```
读全部 Sheet → SheetTypeResolver 判每张表的类型 → 按依赖序导入 → ImportBatchGuard 批次内去重
```

**表级判定的顺序**（`SheetTypeResolver.resolve`）：

```
人工指定  →  表头指纹（≥2 列命中）  →  notice  →  Sheet 名关键词
```

⚠️ **说明页不能排在表头指纹前面**：Sheet 名叫「填写说明」但里面是**真项目表**时会被整表丢掉。
⚠️ **notice 不参与导入、默认不勾选**，如实报「整表跳过」。

**依赖序**：年级 → 班级 → 名单 → 项目 → 报名 → 成绩。
**批次内去重键优先级**：学号 > 号码 > 姓名@班级；同键**首次为准**。跨批次靠「跳过已存在」。

### 1.3 逐表展开：13 种表格各自怎么转化

系统识别 **13 种表类型**（`ExcelColumnMapping.TYPE_FIELDS`），每种有自己的字段表与别名表。
下面逐表列出**真实列名**（模板列）→ **落到哪个实体** → **最终喂给哪个模型的哪些特征**。

#### ① 年级表 `grade`（1~2 列）

| 列 | 落库 | 说明 |
|---|---|---|
| `年级` → `name` | 年级配置 | 供「按年级匹配项目 / 分组」使用 |
| `序号` → `sortOrder` | 同上 | 展示排序 |

**转化**：年级名会过 `Grades` 归一（`高一` = `高一年级` = `G10`）。
凡按年级 `equals` 的地方都必须过它，否则「高一」与「高一年级」会被当成两个年级。

#### ② 班级表 `class`（4 列）

| 列 | 落库 | 说明 |
|---|---|---|
| `班级名称` → `name` | `Class` | 与年级一起构成 `classKey` |
| `班级编码` → `code` | `Class` | 唯一键 |
| `年级` → `grade` | `Class` | 过 `Grades` 归一 |
| `班主任` → `teacherName` | `Class` | **教师规避模型的输入来源之一**（关系、班级归属） |

**转化**：`classKey` = `年级#班号`（`高三1班` = `高三（1）班` = `G12#1`）。
中文数字切分必须**穷举班号长度**；解析不出退化为 `RAW:<原串>`，不静默丢弃。

#### ③ 运动员表 `athlete`（12 列，主数据）

| 列 | 落库 | 编辑/展示 |
|---|---|---|
| `姓名` → `name` | `Athlete.name` | ✅ |
| `性别` → `gender` | `Athlete.gender` | 用于「性别限制」校验、`lane_advisor` 特征 |
| `年级` → `grade` | `Athlete.grade` | 过 `Grades` |
| `班级` → `className` | `@Transient` → `resolveOrCreateClass` | 班级不存在时**自动创建** |
| `学号` → `studentId` | 去重键（优先级最高） | |
| `号码布编号` → `number` | 去重键（次优先） | 成绩表靠它关联 |
| `身份证号` / `出生日期` | `Athlete` | 归档 |
| `紧急联系人` / `紧急联系电话` | `Athlete` | 归档 |
| `健康状况` → `healthStatus` | `Athlete` | 参赛健康核查 |
| `备注` → `remark` | `Athlete` | |

**转化到模型**：
- 人数 → `node_feat[1]`（人数）、`node_feat[2]`（相对最挤单元）
- 性别 → `lane_advisor` 的 `athlete_feat[1]`、`Event.genderLimit` 校验
- **运动员 ID 列表** → 8 类约束边里的 `E_ATHLETE`（**兼项边的唯一来源**）
- 班级 → `lane_advisor` 的 `athlete_feat[0]`（班级索引归一）

#### ④ 全名单表 `roster`（5 列）

`年级 / 班级 / 姓名 / 学号 / 性别` —— 运动员主数据的精简版，
**按学号 upsert**，班级缺失自动创建。转化路径与 ③ 完全相同。

#### ⑤ 项目表 `event`（完整版，19+ 列）

| 列 | 落库 | → 模型特征 |
|---|---|---|
| `代码` → `code` | `Event.code` | 跨表关联键（报名/成绩都靠它） |
| `项目` → `name` | `Event.name` | 单元名 |
| `类别` → `category` | 径赛 / 田赛 / 球类 | 决定 `E_LANE` 是否存在 |
| `是否田径` → `track` | `ScheduleUnit.track` | **`lane_mask` 判据之一**（`node_feat[11]`） |
| `道次` → `laneCount` | `Event.laneCount` | `lane_advisor`、`E_LANE` |
| `顺序号` → `sortOrder` | 展示/分组序 | |
| `每组次几人` → `groupSize` | `Event.groupSize` | 单元拆分粒度 |
| `捆绑字母` → `bundleGroup` | **`ScheduleUnit.groupKey`** | **`E_BLOCK` 项目块边的来源**；`node_feat[9]/[10]`（是否块 / 块内规模） |
| `并行数` → `concurrency` | `Event.concurrency` | **`E_POOL` 同并发池边**；`graph_feat[5]`（并行度） |
| `场地编码` / `场地` → `defaultVenueCode` / `defaultVenue` | `ScheduleUnit.venue` | `E_VENUE` 场地独占边；`graph_feat[2]`（场地数） |
| `性别` → `genderLimit` | 报名校验 | |
| `年级组` → `gradeGroup` | 分组 | |
| `是否团体` / `团体人数` → `team` / `teamMembers` | 单元拆分为「每队一个单元」 | `E_TEAM` 同队边（球类） |
| `最大用时(分)` → `maxDurationMinutes` | **`ScheduleUnit.duration`** | `node_feat[3]`（时长占比）、`[5]`（装箱紧张度） |
| `间隔(分)` → `intervalMinutes` | **`ScheduleUnit.interval`** | 同上（`dur+interval` 一起算） |
| `组次裁判数量` → `refereesPerGroup` | 裁判需求 | **`referee_gnn` 的「本批需要多少裁判」来源** |
| `抽签(是/否)` → `drawLots` | 是否分道抽签 | 道次编排模式 |
| `最大报名人数` → `maxParticipants` | 报名上限 | |
| `跑道数` → `defaultLanes` | 道数 | `lane_advisor` 的 `lanes` |
| `计分规则` / `校纪录` | `scoringType` / `record` | 成绩统计用，**不进模型** |

> ⚠️ 这张表的列最多，也是最容易出「表头认得、数据静默丢弃」的地方：
> **改任何一列都必须同步三处** —— `TYPE_COLUMN_ALIASES`（别名）+ `TYPE_FIELDS`（字段）
> + `ExcelService.processEventRow`（落库）。

#### ⑥ 运动项目表（精简版）`eventsimple`（9 列）

| 列 | 落库 | → 模型 |
|---|---|---|
| `项目代码` → `eventCode` | `Event.code` | 关联键 |
| `项目名称` → `eventName` | `Event.name` | |
| `每组人数` → `teamMembers` | 拆分粒度 | |
| `每批组数` → `concurrency` | 并行数 | `E_POOL` 边 + `graph_feat[5]` |
| `项目类型` → `category` | 类别 | |
| `场地号` → `defaultVenueCode` | 场地 | `E_VENUE` 边 + `graph_feat[2]` |
| `每批所需时间(分)` → `perBatchMinutes` | **`duration`** | `node_feat[3]/[5]` |
| `性别` → **`gender`** | 报名校验 | ⚠️ **必须落 `gender` 而不是 `genderLimit`**：处理器读的是 `gender`，落错会「识别成功但性别列被当成未识别列、性别静默丢失」 |
| `最大报名人数` → `maxParticipants` | 上限 | |

#### ⑦ 报名表 `signup`（7 列）

`年级 / 班级 / 姓名 / 学号 / 性别 / 项目 / 组号`

| 列 | 落库 | → 模型 |
|---|---|---|
| 年级+班级+姓名+学号+性别 | 运动员主数据（不存在则创建） | 见 ③ |
| `项目` → `eventCode` | `Signup` | **这条记录本身就是 `E_ATHLETE` 兼项边** |
| `组号` → `teamTag` | 团体/接力的 A/B 组 | `E_TEAM` 边 |

> ⚠️ **个人项目严禁填组号**；组号只用于团体与接力。
> 接力的「整队」依赖这个字段，它也是 `TASK_LANE`（含接力整队）的数据基础。

#### ⑧ 名单+报名合一表 `athlete_signup`（8 列）

= ⑦ 再加 `号码布编号`。**单 Sheet 同时含主数据与报名**：
每行 = 一个学生的一次报名；**未报名的学生也占一行（项目留空）**，只落运动员。
⚠️ 合一表与「名单表 + 报名表」二选一；**同时导入会整表被判重复**。

#### ⑨ 报名表（旧版）`registration`（5 列）

`项目编码 / 运动员号码 / 运动员姓名 / 年级 / 班级 / 备注` —— 历史格式，走兼容路径。
落库后与 ⑦ 等价（同样产生 `E_ATHLETE` 边）。

#### ⑩ 成绩表 `score`（8 列）

| 列 | 落库 | → 模型 |
|---|---|---|
| `项目编码` → `eventCode` | 关联 `Event` | |
| `运动员号码` → `athleteNumber` | 关联 `Athlete.number` | 与 ③ 的号码布编号对应 |
| `运动员姓名` → `athleteName` | 校验用 | |
| `成绩` → `rawTime` | 原始成绩串 | 名次计算 |
| `组别` → `heat` | **组次** | `E_LANE` 同道次边 |
| `道次` → `lane` | **道次** | `lane_advisor` 的历史占用 |
| `风速` → `windSpeed` | 径赛记录校验 | |
| `备注` → `remark` | | |

**成绩不进任何模型的输入**，但它决定「已定落位」——`ConflictGraphEncoder`
与 `InstanceFeatures` 都会读 placements（已排/未排、冲突暴露），
所以成绩表质量会影响 `conflict_gnn` 与 `algorithm_selector` 的输入分布。

#### ⑪ 用户表 `user`（5 列）

`用户名 / 密码 / 姓名 / 角色 / 电话`。用于登录与权限，**不进模型**（但角色影响「谁能触发编排」）。

#### ⑫ 场地表 `venue`（7 列）

| 列 | 落库 | → 模型 |
|---|---|---|
| `场地编码` → `code` | `Venue.code` | 与 `Event.defaultVenueCode` 关联 |
| `场地名称` → `name` | `Venue.name` | `Window.venue` |
| `类型` → `type` | 场地类别 | |
| `可容纳项目数` → `capacity` | **`Window.capacity`** | `node_feat[5]/[6]`（装箱紧张度 / 容量余量）、`graph_feat[6]`（填充率） |
| `最大并行数` → `parallelMax` | **并行上限** | `graph_feat[5]`（并行度） |
| `排序` / `启用` → `sortOrder` / `enabled` | 排序 / 是否参与 | 停用场地不生成 `Window` |

> 场地是编排引擎**「并行上限」的唯一数据来源**。容量填错，`node_feat[5]`
> 的装箱紧张度与 `graph_feat[6]` 的填充率会同时失真，而模型不会报错 —— 只会排得更差。

#### ⑬ 填写说明页 `notice`（2 列）

`字段 / 填写说明` —— **不落任何业务数据，整表跳过**，默认不勾选导入，如实报「整表跳过」。

### 1.4 表格列 → 模型特征的对应总表

| 表格 | 关键列 | 变成张量的哪一部分 |
|---|---|---|
| `event` / `eventsimple` | 最大用时、间隔 | `node_feat[3]` 时长占比、`[5]` 装箱紧张度、`[6]` 容量余量 |
| `event` | 捆绑字母 | `node_feat[9]` 是否项目块、`[10]` 块内规模、**`E_BLOCK` 边** |
| `event` / `eventsimple` | 并行数（每批组数） | **`E_POOL` 边**、`graph_feat[5]` 并行度 |
| `event` / `eventsimple` / `venue` | 场地 | **`E_VENUE` 边**、`graph_feat[2]` 场地数 |
| `event` | 是否田径、道次、跑道数 | `node_feat[11]` 是否道次、`[12]` heat 容量、**`E_LANE` 边**、`lane_mask` |
| `event` | 是否团体、团体人数 / `signup.组号` | **`E_TEAM` 边** |
| `roster` / `athlete` / `signup` | 姓名 + 学号 | **`E_ATHLETE` 兼项边**（同一运动员出现在多个项目） |
| `athlete` / `signup` | 性别 | `lane_advisor` 特征 + `genderLimit` 校验 |
| `athlete` / `signup` | 班级 | `lane_advisor` 班级索引；`class.班主任` → 教师规避模型 |
| `class` | 班主任 | `teacher_gnn` 的「关系 / 行政权重」 |
| `venue` | 可容纳项目数、最大并行数 | `Window.capacity` → `node_feat[5]/[6]`、`graph_feat[6]` 填充率 |
| `score` | 组别、道次 | 已定落位 → `InstanceFeatures`、`conflict_gnn` 的输入分布 |
| —— | 时间三态（请求参数，非表格） | `node_feat[13]`、`graph_feat[4]` |
| —— | 保护时段（界面配置，非表格） | 不进张量，**在 Java 侧作为硬约束过滤 `Window`** |

> ⚠️ **保护时段（行政规避）不进模型张量**，而是在**窗口生成阶段**就把被保护的时间桶剔除。
> 这是有意的：让「不可排」在数据源头就消失，比让模型学会「别排那里」更可靠。
> 现已贯通到**球类编排**（`BallTournamentService.ballWindows`）—— 此前球类完全没读保护时段。

### 1.5 导入后的归一化（**关键：不归一化会让所有按值匹配失效**）

| 归一化 | 实现 | 为什么必须有 |
|---|---|---|
| **年级** | `com.sports.common.util.Grades`（`高一`=`高一年级`=`G10`） | 任何按年级 `equals` 的地方都必须过它；不然「高一」与「高一年级」是两个人 |
| **班级** | `classKey`（`高三1班` = `高三（1）班` = `G12#1`） | 中文数字切分必须**穷举班号长度**；解析不出退化为 `RAW:<原串>` |
| 存量数据 | `GradeNormalizeInitializer`（`@Order(30)`） | 幂等收敛历史脏数据 |

唯一收敛点是 `ExcelService.processRow → normalizeGradeFields`。

---

## 2. 第②层：数据库 → 业务对象

编排不直接读实体，而是先组装成**编排专用对象**：

| 对象 | 来源 | 说明 |
|---|---|---|
| `ScheduleUnit` | `Event` + `EventGroup` + `Venue` + 报名人数 | 一个**待排单元**：`key / name / venue / pool / duration / interval / heatCapacity / athletes / groupKey / bracketRound / resecondOf / stage` |
| `Window` | 场地 × 日期 × 时段配置 | 一个**可用的时空片**：`day / windowIdx / venue / pool / capacity` |
| `daysLimit` | 请求参数 | **时间三态**：`>=1` 硬约束 / `0` 不限 / `<0` 尽可能减少 |
| 保护时段 | `AdminTimeProtection` | GLOBAL / TEACHER / REFEREE 三类规避区间 |

**时间三态在业务层的语义**（`ScheduleService`）：

| 输入 | 语义 | 实现 |
|---|---|---|
| `days >= 1` | 硬约束，排不下要报不可行 | 直接作为天数上限 |
| `days == 0` | 不限时间 | 走 `DaysEstimator` 自动推算 |
| `days < 0` | 尽可能减少工期 | 通过静态桥 `ScheduleConstraintProvider.setMinimizeDaysMode` 把**跨天惩罚放大 5 倍**，`finally` 复位防止跨请求泄漏 |

⚠️ 原实现是 `intVal(days, 0) <= 0` —— 把 `0`（不限）与负数（尽可能减少）混为一谈，
`-1` 在这条主链路上**根本无法表达**。已修正。

---

## 3. 第③层：业务对象 → 张量（编码器）

**这是最关键的一层**：同一份语义必须在 Python（训练）和 Java（推理）两侧产出**逐位相同**的张量。

### 3.1 核心：`super_moe` 的三段编码

Java 侧 `SuperScheduleEncoder.encode(units, windows, daysLimit, athletes)`：

```
① 节点特征 node_feat[N][20]
   for i, u in enumerate(units):
       feat[i][0]  = n / 128                        # 单元数占比
       feat[i][1]  = min(people, 512) / 512         # 人数
       feat[i][2]  = people / maxPeople             # 相对最挤单元
       feat[i][3]  = u.duration / totalDur          # 时长占比
       feat[i][4]  = 1 / nVenues                    # 场地
       feat[i][5]  = min(1, (dur+interval) / cap)   # 装箱紧张度
       feat[i][6]  = max(0, 1 - (dur+interval)/cap) # 容量余量
       feat[i][7]  = min(1, expo / 3)               # 冲突暴露
       feat[i][8]  = 兼项占比
       feat[i][9]  = groupKey != null ? 1 : 0       # 是否项目块
       feat[i][10] = min(1, blockSize / 8)          # 块内规模
       feat[i][11] = heatCapacity > 0 ? 1 : 0       # 是否道次
       feat[i][12] = min(1, heatCapacity / 8)       # heat 容量
       feat[i][13] = timeGoal                       # 0 不限 / 0.5 硬约束 / 1 最小化
       feat[i][14] = min(1, unitDay / maxDay)       # 天数占比
       feat[i][15] = stage 编码                     # main 0 / prelim .33 / final .66 / resecond 1
       feat[i][16..19] = 赛制 one-hot               # group / round_robin / knockout / hybrid

② 8 类约束边 adj_by_type[8][N][N]
   0 兼项（共享运动员）  1 项目块（同 group_key）  2 场地独占  3 同并发池
   4 装箱间隔            5 同道次/批次            6 淘汰赛晋级 7 同队
   ⚠️ 只有「有一个端点被掩码」的边才被保留；type_mask 记录哪些通道非空

③ 图级特征 graph_feat[8]
   冲突密度 / 单元规模 / 有效场地数 / 天数 / 时间目标 / 并行度 / 填充率 / 块压力
```

Python 侧 `data/super_encode.encode_super_graph(scen)` 产出**完全相同的三段**
（`SuperScenario` 是训练用的场景对象，字段与 `ScheduleUnit`/`Window` 一一对应）。

**逐位对齐靠三件东西保证**：

1. 两侧代码旁都写着「改这里必须同步另一侧 + `super_moe.onnx` 输入维度」；
2. Java 侧有 `SuperScheduleEncoderTest`，含**逐位断言**的 `graphFeatMatchesPythonContract`；
3. `models.md §7.1` 是唯一权威的特征表（曾有**两张互相矛盾的表 + 一行覆盖第 19 维**的残留代码，已统一）。

### 3.2 其他模型的编码器

| 模型 | 编码器 | 入参（业务对象） | 产出 |
|---|---|---|---|
| `conflict_gnn` | `ConflictGraphEncoder.encode(List<ScheduleUnit>)` | 待排单元 + 已定落位 | `nodeFeat[N][16]` / `adj[N][N]` / `mask[N]` |
| `constraint_gnn` | `ConstraintAwareGraphEncoder`（复用 16 维节点） | 同上 | `nodeFeat[N][16]` / `adjByType[6][N][N]` / `typeMask[6]` |
| `tournament_gnn` | `TournamentGnnEncoder.encode(teams, venues, minutesPerMatch, ...)` | 球队 + 场地 + 单场时长 | `nodeFeat[N][14]` / `adjByType[4][N][N]` / `typeMask[4]` |
| `referee_gnn` | `RefereeGnnEncoder.encode(refs, batchSports, batchUnits)` | 裁判 + 本批项目 + 本批单元 | `nodeFeat[N][12]` / `adjByType[4][N][N]` / `typeMask[4]` |
| `teacher_gnn` | `TeacherAiService.encode(teachers, classSet)` | 教师 + 班级集合 | `nodeFeat[N][10]` / `adjByType[4][N][N]` / `typeMask[4]` |
| `algorithm_selector` | `InstanceFeatures.extract(units, placements)` | 单元 + 落位统计 | `features[16]` |
| `lane_advisor` | `LaneAdvisorService.buildFeatures(...)` | 班级 / 性别 / 项目数 / 种子分 / 道次 | `athlete_feat[N][8]` + `mask[N]` |
| 生成式三件套 | 复用 `ConflictGraphEncoder.Encoded` | 同上 + 噪声 `z` + `forbid` | `node_feat/adj/mask/z/forbid` |
| `forecast_*` | `forecast/dataset.py` | 历史序列 | `x[1,12,4]` |

### 3.3 编码器的三条共同纪律

| 纪律 | 原因 |
|---|---|
| **归一化常量固化在 ONNX 内** | `algorithm_selector` 的 `Normalize(mean, std)` 作为模型第一层导出，Java 因此喂**原始特征**。若在 Java 侧再实现一次归一化，两边迟早漂移 |
| **超限如实降级、不静默截断** | 节点数 > `MAX_NODES`（超图）时走 `HierarchicalGnnEncoder` 并查集聚簇分层，而不是悄悄丢掉节点 |
| **缺失通道要能被识别** | `type_mask` 显式告诉模型「这个实例没有这类边」，避免把「不存在」与「值为 0」混为一谈 |

---

## 4. 第④层：张量 → ONNX → 业务对象

### 4.1 喂输入（以 `super_moe` 为例）

```java
Map<String, OnnxTensor> feed = new LinkedHashMap<>();
feed.put("node_feat",  ...);   // [1, N, 20]
feed.put("adj_by_type",...);   // [1, 8, N, N]
feed.put("type_mask",  ...);   // [1, 8]
feed.put("mask",       ...);   // [1, N]
// ⚠️ 先问模型「你有没有这个输入」再喂：磁盘上可能是旧版 onnx（无 graph_feat），
//    硬塞未知输入名会让**整个推理失败 → 静默回退规则**，比「喂全零」严重得多。
if (modelHasInput("graph_feat")) feed.put("graph_feat", vecTensor(enc.graphFeat()));
```

### 4.2 读输出

```java
// ⚠️ 按输出**名**读，不按索引：
//    本轮输出从 5 扩到 7。按索引读的话，今后任何一次「在中间插输出」
//    都会让老代码静默读错张量 —— 不报错、数值看着还挺像。
double[]  priority = toVec(named(r, "priority", 0));
double[][] slot     = toMatrix(named(r, "slot_logits", 1));
double[]  taskProb = toVec(named(r, "task_probs", 2));      // [17]
double[]  fmt      = toVec(named(r, "format_logits", 3));
double   days      = toScalar(named(r, "days_estimate", 4));
double[][] lane    = r.get("lane_logits").isPresent() ? toMatrix(...) : new double[0][];
double   quality   = r.get("quality_score").isPresent() ? toScalar(...) : 0.0;
```

### 4.3 输出变回业务对象

| 输出 | → 业务 | 消费点 |
|---|---|---|
| `priority` | 单元排序 | `Advice.orderByPriority()` → Timefold 求解器初始序；也是 GA 的种子序 |
| `slot_logits` | 时间槽 | `Advice.slots()`（argmax）→ 候选落桶 |
| `format_logits` | 球类赛制 | `Advice.recommendedFormat()` → `BallTournamentService` |
| `lane_logits` | 道次派遣 | `Advice.laneSlots()` → 覆盖原 `lane_advisor` 能力 |
| `days_estimate` | 工期 | 参考值（覆盖原 `forecast` 能力） |
| `quality_score` | 方案质量 | `Advice.qualityLevel()` → good/fair/poor。**仅参考，不作裁决** |
| `task_probs` | 诊断 | `expertUsage` / 路由熵，判断有没有专家塌缩 |

### 4.4 回写（L4 只是**建议**，落库的是算法链路的结果）

```
ONNX Advice ──(当初始序/种子)──▶ Timefold / GA / LNS 精修 ──▶ SchedulePlan ──▶ 落库
                                        │
                                        └─ 硬约束校验（容量、兼项、保护时段）
                                           ⚠️ 模型自评再高也不能覆盖「不可行」
```

**为什么必须是这个方向**：模型输出的是「倾向」，不是「合法解」。
把模型输出直接落库会让硬约束失去兜底 —— 而现场的底线是「宁可排得不够好，也不能排出违规赛程」。

---

## 5. 降级：任何一环失败都不阻塞编排

| 失败点 | 表现 | 兜底 |
|---|---|---|
| 模型文件缺失 | `ModelSource.read` 返回空 | 抛内部异常 → `advise` 返回 `Optional.empty()` |
| ONNX 加载失败（缺 EP / 驱动） | `catch (Throwable)`（缺 EP 类抛的是 `Error`，故必须 catch Throwable） | 回退规则编排，日志一行 WARN |
| 推理异常（shape 不匹配等） | `catch (Throwable)` | 同上 |
| 编码降级（超限 / 数据不全） | `Encoded.degraded() == true` | 直接返回空，不喂模型 |
| **旧版 onnx 缺新输入/新输出** | `modelHasInput` / `named(...)` 回退 | 退化成全零上下文或历史索引 |

> ⚠️ **这套「静默回退」是双刃剑**：它保证了线上永不因为 AI 挂掉而编排失败，
> 但也让「模型没生效」变得**不报错**。所以必须有主动可观测手段：
> `/api/ai/status`（模型加载状态 + 来源）、`superMoeLoaded` 响应字段、
> 以及日志里的 `超级编排模型已就绪: super_moe.onnx (jar 内)`。

---

## 6. 每个模型的数据来源一览

| 模型 | 数据从哪来 | 编码后 | 推理后回到哪里 |
|---|---|---|---|
| `super_moe` | 单元 + 窗口 + 时间三态 + 保护时段 | `[20] / [8,N,N] / [8]` | 求解器初始序 + 候选方案（**建议**） |
| `constraint_gnn` | 单元 + 6 类约束 | `[16] / [6,N,N]` | 约束满足度打分（主流程未接线） |
| `tournament_gnn` | 球队 + 场地 + 赛制候选 | `[14] / [4,N,N]` | 赛制推荐 + 种子序 + 公平性代价 |
| `conflict_gnn` | 单元冲突图 | `[16] / [N,N]` | 着色优先级 + 取消概率 |
| `algorithm_selector` | 赛会统计量 | `[16]` | 硬解 / 取消路径选择 |
| `lane_advisor` | 运动员 + 班级 + 道次 | `[8] + mask` | 道次派遣优先序 |
| `scheme_generator` | 约束图 + 噪声 + 禁排表 | `[16]/[N,N]/[8]` | 候选方案 → 精修 → 择优 |
| `scheme_discriminator` | 约束图 + 候选方案 | 同左 + `scheme` | 方案打分（择优用） |
| `scheme_refiner` | 约束图 + 候选 + 禁排表 | 同左 + `init_logits` | 修正后的方案 |
| `scheme_diffusion` | 约束图 + 噪声 | `[16]/[N,N]/[16]` | 去噪轨迹 → 方案（Java 未接入） |
| `forecast_*` | 历史序列 | `[12,4]` | 多步预测（Java 未接入） |
| `referee_gnn` | 裁判 + 本批项目 + 单元 | `[12] / [4,N,N]` | 派遣优先序（**只排序，不裁决**） |
| `teacher_gnn` | 教师 + 班级集合 | `[10] / [4,N,N]` | 避让优先序（**只排序，不裁决**） |

---

## 7. 这一层最常见的坑（都真实发生过）

| 坑 | 症状 | 教训 |
|---|---|---|
| **双端特征表不同步** | 训练与推理对同一维语义不同，**不报错**、指标照常下降 | 特征表只能有一份权威；两侧代码旁必须写「改这里要同步哪几处」 |
| **常量双写** | Java `N_TASKS` 写死 9（应为 11）、Python 写死 9 | 常量必须从**唯一真相源**导入，并在单测里钉住 |
| **Python 覆盖 Java 没有的维度** | `feat[i,19]` 把「混合赛制」冲成「淘汰赛标记」 | 双端逐位比对是唯一可靠手段（写个脚本比一遍） |
| **动态轴被常量折叠** | 服务端喂别的 N 就崩，异常被吞 → 「AI 没生效也不报错」 | 导出必须 `dynamo=False`；导出后用 N=1/3/4/12/24/47 各跑一次 |
| **行尾（CRLF/LF）** | 批量脚本把 CRLF 转成 LF，`git diff` 看不出来（autocrlf 归一化） | 改完用二进制模式还原并复核 `LF == CRLF` |
| **改了模型默认维度却没重训** | 导出时报 `size mismatch`，看起来像导出脚本的错 | 真因是训练没跟着跑；预算要按**模型类默认参数**换算 |
| **标签与输入不同源** | 学不到拓扑（早期 slot 标签用 `hash(venue)`，与真实落位无关） | 标签必须由**与线上同源**的算法产生 |
| **排序模型标签方向错** | 精度低于随机基线且不报错 | 必须对基线做对照（本项目评测含 random / greedy / GA 三种口径） |
