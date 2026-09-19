# 🏃 运动会智能编排系统

> Sports Meet Intelligent Arrangement System v2.6.5

基于 **Spring Boot 3.4 + Vue 3 + Element Plus** 的全栈运动会管理系统。支持**超级管理员 / 体育老师 / 班主任 / 学生**多角色协作，覆盖**建站向导 → 班级名单导入 → 运动会报名 → 智能分组编排 → 赛程编排 → 成绩录入 → 排名积分 → 报表导出**全流程。

核心亮点：

- ⚙️ **零配置建站**：首次启动进入可视化安装向导（参考 WordPress / Discuz 体验），配置站点、数据库、管理员账号后即装即用
- 🔀 **数据库热迁移**：SQLite ↔ MySQL 在线切换，**全程无需重启服务**
- 🧮 **三级求解梯度**：规则模式（确定性规则引擎，毫秒级） ↔ 优化模式（Timefold 约束求解 + GA + LNS），前端一键切换，**向下完全兼容竞品规则、向上独占求解能力**
- 🧠 **智能编排引擎**：贪心 + 局部优化算法自动分组分道，规则完全可配置
- 🌐 **反向代理 / 内网穿透友好**：前端采用 hash 路由（`/#/...`），服务器永远只收到 `/` 或 `/sportmg/`，**cpolar / ngrok 子域隧道、nginx 子路径帽子均开箱即用**，无需任何重写规则，彻底规避深链刷新白屏
- 📊 **全流程 Excel 化**：名单 / 项目 / 报名 / 成绩 全部支持模板导入导出，秩序册 / 成绩册 / 报表一键生成
- 📄 **真实 Word 秩序册**：原生 OOXML（手写 ZIP 包组装，**零 Apache POI 依赖、离线可构建**）生成含封面 / 目录 / 多章表格的 `.docx`，支持一键下载与按开关自动落盘
- 🔢 **号码簿双模式**：模板 / 正则自定义之外，支持**按名单顺序**「补全生成（不覆盖）/ 覆盖重排」两种操作，撞号自动顺延不中断
- ⏱ **1~n 并发位编排**：径赛 / 田赛各自可设「并数」（**1 = 串行，n = 并行，并数上限取决于场地数量**），项目内可设并发人数（田赛 X 人同时试跳/试掷）；支持**自定义项目顺序**（Excel 导入带「顺序号」列）、**田赛分组同期**，以及**项目级并行捆绑组**（填相同字母 A/B/C 的田赛自动同批并行，优先于配置分组）

---

## 目录

- [快速开始](#-快速开始)
- [默认账号](#-默认账号)
- [功能总览（按角色）](#-功能总览按角色)
- [功能详解](#-功能详解)
- [API 接口完整参考](#-api-接口完整参考)
  - [通用约定](#通用约定)
  - [认证 Auth](#1-认证-auth)
  - [班级 Classes](#2-班级-classes)
  - [运动员 Athletes](#3-运动员-athletes)
  - [项目 Events](#4-项目-events)
  - [报名 Registrations](#5-报名-registrations)
  - [班主任端 ClassTeacher](#6-班主任端-classteacher)
  - [智能编排 Arrange](#7-智能编排-arrange)
  - [赛程编排 Schedule](#8-赛程编排-schedule)
  - [成绩 Results](#9-成绩-results)
  - [排名积分 Ranking](#10-排名积分-ranking)
  - [统计报表 Statistics](#11-统计报表-statistics)
  - [学生端 Student](#12-学生端-student)
  - [系统设置 System](#13-系统设置-system)
  - [用户管理 Users](#14-用户管理-users)
  - [裁判管理 Referees](#141-裁判管理-referees)
  - [Excel 导入导出 Excel](#15-excel-导入导出-excel)
  - [数据库备份 Backup](#16-数据库备份-backup)
  - [数据库迁移 DbMigration](#17-数据库迁移-dbmigration)
  - [建站向导 Setup](#18-建站向导-setup)
  - [入场式评分 ParadeScore](#19-入场式评分-paradescore)
- [数据库设计](#-数据库设计)
- [部署指南](#-部署指南)
- [开发指南](#-开发指南)
- [FAQ](#-faq)
- [开源协议](#-开源协议)

---

## 🚀 快速开始

### 前置要求

| 环境 | 版本 | 说明 |
|------|:----:|------|
| JDK | 21+ | Eclipse Temurin / OpenJDK |
| Node.js（仅开发） | 20+ | 前端构建 |
| Maven（仅开发） | 3.9+ | 或使用 `./mvnw` |

### 一键启动

```bash
# 构建（前端 Vite → 后端 Maven → 生成 JAR）
.\build.ps1

# 启动（默认 8080 端口，可用 -Port 9090 自定义）
.\start.ps1
```

如已生成 JAR，也可直接运行：

```bash
java -jar sports-2.6.5.jar
```

浏览器访问 **http://localhost:8080**

- **首次启动**：自动进入安装向导（`/setup`），按提示配置站点信息、数据库、管理员账号即可
- **已安装**：直接进入登录页（`/login`）

> ⚠️ Windows CMD 用户：执行前先运行 `chcp 65001`，或直接双击 `start.bat`。PowerShell 用户运行 `.\start.ps1`。JAR 已内置终端编码自动检测，非 UTF-8 终端会输出英文提示。

---

## 👥 默认账号

| 角色 | 账号 | 密码 | 权限范围 |
|------|------|------|----------|
| 超级管理员 | `admin` | `admin123` | 全部权限 + 用户管理 + 系统配置 + 数据库迁移/备份 |
| 体育老师 | `teacher` | `teacher123` | 编排 / 成绩 / 报表 / 基础数据 |
| 班主任 | `class_teacher` | `class123` | 本班名单 / 报名 / 赛程成绩查看 |
| 学生 | `student` | `student123` | 个人赛程 / 成绩 / 赛事浏览 |

> 生产环境请立即修改默认密码（安装向导中可直接设定管理员密码）。

---

## 🗺️ 功能总览（按角色）

### 超级管理员（SUPER_ADMIN）

拥有系统全部权限，在体育老师功能基础上额外提供：

| 模块 | 功能 |
|------|------|
| 批量创建 | 班级批量生成（按高中/初中/小学折叠选择）+ 用户批量生成 |
| 用户管理 | 按角色 Tab 查看/增删改、重置密码、Excel 导入、批量创建；班主任 Tab 可展开查看管辖班级的学生 |
| 裁判管理 | 裁判花名册（独立于登录账号）：增删改查、Excel 批量导入（专长项目支持 [a,b，c] 列表语法）、模板下载；智能编排时按「组次裁判数量」自动分配 |
| 号码簿规则 | 号码生成模板自定义（`{grade}{class}{seq:02d}` 等变量）+ 实时预览；**按名单顺序生成（补全空缺）与重排（覆盖）**，撞号自动顺延 |
| 编排规则 | 软约束开关 + 算法参数（尝试次数/超时/优化轮数） |
| 积分规则 | 名次积分表、并列处理、破纪录加分、参与分、接力倍数、团体总分口径 |
| 数据库迁移 | SQLite ↔ MySQL 在线热迁移（连接测试、异步迁移、进度查询），**无需重启** |
| 数据库备份 | 手动/自动备份、备份列表、下载、删除 |
| 健康检查 | 系统运行状态详情、操作日志查看 |
| 应用运行配置 | 服务端口、绑定地址（重启生效） |

### 体育老师（TEACHER）

| 页面 | 功能 |
|------|------|
| 首页 | **分步工作流（带序号一步一步：配置 → 录入 → 编排 → 统计；每步标注类型并为「录入」步骤提供入口，且有「建议当前步骤」高亮，按待办自动定位）**、待办事项、今日赛程、报名进度、关键统计、其他入口 |
| 班级管理 | 班级列表、展开查看学生、Excel 导入导出、批量创建、绑定班主任 |
| 运动员管理 | 多维筛选（年级/班级/关键词）、号码簿自动生成、批量导入导出 |
| 项目管理 | 预设模板、Excel/CSV 导入、启用/禁用、道数/预赛/计分配置 |
| 报名管理 | 报名列表、单个/批量审核（通过/拒绝）、报名统计、导出 |
| 智能编排 | 自动分组分道（贪心+优化）、预览、批量编排、手动调整、回滚、道次表导出 |
| 项目编排 | 赛程自动调度（天×时段×场地，1~n 并发位）、自定义项目顺序、田赛分组同期、手动调整、赛程导出 |
| 成绩管理 | 录入/修改/删除、Excel 导入、自动排名计算 |
| 排名积分 | 单项目排名、个人积分、团体总分、破纪录榜，三类均可导出 |
| 统计报表 | 秩序册（**Excel / Word 双形态**）/ 成绩册 / 统计报表（报名统计、道次表、成绩汇总、团体总分榜） |
| 系统设置 | 基本设置、积分规则、年级设置等 |

### 班主任（CLASS_TEACHER）

| 页面 | 功能 |
|------|------|
| 首页 | **分步工作流（① 录入班级名单 → ② 报名运动会项目 → ③ 查看本班赛程 → ④ 查看成绩获奖；已完成步骤打勾、自动高亮「建议当前步骤」）**、本班统计、最近报名、本班赛程 |
| 班级名单 | Excel 导入全班花名册（自动创建学生账号+运动员）、手动添加、模板下载 |
| 运动会报名 | 学号定位 → 项目卡片报名、统计仪表、未报名名单、报名表导出 |
| 赛程查看 | 本班运动员的组次、道次、时间 |
| 成绩查看 | 本班成绩 + 总分/金银铜汇总 |

### 裁判（REFEREE · 可登录）

裁判既是**被编排的人力资源**（由智能编排按项目「组次裁判数量」自动分配，专长优先 + 负载均衡），**也可以拥有登录账号**（角色 `ROLE_REFEREE`）自行登录查看本人执裁安排。裁判花名册与账号通过 `referee.user_id` 关联。

| 页面 | 功能 |
|------|------|
| 裁判管理（`/teacher/referees`，SA） | 花名册增删改查、Excel 批量导入（专长 `[a,b，c]`）、模板下载、**单个/批量开通登录账号**（账号列显示「已开通/未开通」） |
| 裁判工作安排（`/teacher/referee-board`，T/SA） | 按裁判聚合「项目/年级/性别/赛次/组次」分配，含未分配裁判 |
| **裁判工作台（`/referee/dashboard`，独立布局）** | 裁判登录后进入（琥珀色系）；**裁判本人看到「我的执裁安排」**（`GET /api/referee/me`），管理员/体育老师则看到全体裁判安排（可投屏） |

> **开通账号**：裁判管理页「开通账号」/「批量开通账号」→ `POST /api/system/referees/{id}/account`、`POST /api/system/referees/accounts/open-all`。用户名默认取手机号（无手机号则 `ref{id}`，冲突自动加后缀），初始密码默认 `123456`；也可在「用户管理」Excel 导入中直接把角色填 `REFEREE` 建账号。

### 学生（STUDENT）

| 页面 | 功能 |
|------|------|
| 首页 | 个人统计、我的报名、我的赛程 |
| 我的赛程 | 个人参赛时间安排 |
| 我的成绩 | 个人成绩、名次、积分、是否破纪录 |
| 项目浏览 | 全部项目（含本人是否已报名标记） |
| 个人中心 | 个人资料查看 |

> **入口收敛**：系统共 5 个角色入口——管理员 / 体育老师 / 班主任 / 裁判 / 学生，各自有独立布局与配色
> （统一由 `sports-frontend/src/styles/role-theme.css` 的 `role-root role-*` 变量驱动：
> 管理员=红橙、体育老师=蓝紫、班主任=翠绿、裁判=琥珀、学生=紫罗兰），
> 侧边栏选中态、角色徽章、工作台横幅/统计/卡片自动继承该角色配色，视觉语言一致。

---

## 📋 功能详解

### 1. 建站向导（首次启动）

首次启动未安装时，所有页面一律重定向到安装向导（`/setup`），三步完成：

```
① 站点信息（运动会名称、描述）
② 数据库配置（SQLite 零配置 / MySQL 连接测试）
③ 管理员账号（用户名、密码、确认密码）
→ 安装完成，向导永久锁定（任何人无法再次进入）
```

### 2. 登录页 & 入场动画

- **Loading 入场页**（`/loading`）：仿 FIFA 风格的 SPORTS 字母弹跳动画 + ⚽ 旋转，点击进入登录
- **登录页动画小人**：纯原生 CSS / Web Animations API 实现（已按 AGPL-3.0 合规移除 GSAP），跟随鼠标、眨眼、错误摇动
- **密码切换**：眼睛图标切换明文/圆点

### 3. 终端编码自动适配

JAR 启动时自动检测终端编码（Windows GBK / Linux UTF-8 / Mac UTF-8），Java 输出自适应，Logback 通过 `${file.encoding}` 占位符动态跟随。**无需手动 chcp**，纯 `java -jar` 即自适应。

### 4. 班级管理

管理员/体育老师端：

- 班级列表：名称、年级、编号、班主任、人数、参赛状态
- **展开查看学生**：点击班级行左侧箭头 → 展开该班学生姓名、学号、性别、号码
- Excel 导入/导出、新增/编辑/删除
- 批量创建班级 + 自动生成班主任账号
- **绑定班主任**（`bind-teacher`）：班主任端数据可见的前提

### 5. 班级名单 & 运动员

**班主任端「班级名单」**：

- Excel 导入全班花名册（学号/姓名/性别）→ 自动创建学生账号 + 运动员记录
- 手动添加单个学生、下载导入模板、支持重新导入（增量更新）

**管理员/体育老师端「运动员管理」**：

- 多维度筛选：年级/班级/关键词
- 号码簿自动生成（规则可在系统设置自定义）
- 批量导入/导出

### 6. 项目管理

- **预设模板**：跑步类（100m~1500m）、跳跃类（跳高/跳远）、投掷类（铅球/实心球）、接力类（4×100m）
- **Excel/CSV 导入**：批量导入项目，模板含表头+示例行
- **支持字段**：项目名称、代码、类别（径赛/田赛）、性别限制、道数、预赛开关、计分方式、纪录、**组次裁判数量**（`refereesPerGroup`）
- **组次裁判数量**：每个组次（heat/组/轮）需安排的裁判人数。田赛如立定跳远一组 5 人填 x 名裁判即填 x；拔河一组 3 人填 3；N 组并行仍按单组填写，系统自动为每组分别安排；留空/0 表示不安排裁判。该值驱动「智能编排」自动分配裁判（见 §8、§7.1）
- 启用/禁用开关、自定义新增/编辑/删除、**批量修改**（含组次裁判数量等调度字段一键套用到勾选项目）

### 7. 运动会报名

**班主任端**流程：

```
① 导入班级名单 → ② 输入学号自动定位姓名 → ③ 点击项目卡片报名
```

| 功能 | 说明 |
|------|------|
| 统计仪表 | 全班人数 / 已报名人次 / 已报名人数 / 未报名人数（橙色高亮） |
| 学号定位 | 输入学号 → 自动显示姓名、性别、年级、已报项目列表 |
| 项目卡片 | 每行6列彩色卡片，状态：已报名(绿) / 可报(蓝) / 不可报(灰) / 待输入学号(橙) |
| 报名清单 | 含学号列完整表格，支持取消 |
| 未报名名单 | 底部列出所有未报名学生，点击"去报名"一键填充学号 |
| 导出报名表 | 一键导出本班报名 Excel（含未报名学生） |

**约束规则**：性别匹配、每人最多3项、不可重复报名。

**教师端「报名管理」**：报名列表筛选、单个/批量审核（通过/拒绝）、报名统计、导出。

### 8. 智能编排 ⭐ 核心

#### 8.0 三级求解梯度（算法架构）

编排引擎按「问题复杂度 → 求解能力」分为三级，逐级向上增强、向下兼容：

| 级别 | 引擎 | 耗时 | 特性 |
|------|------|------|------|
| L1 规则模式 | `com.sports.schedule.rule`：`SnakeGrouping`（蛇形分组）+ `FixedLaneAssignment`（固定分道）+ `RuleBasedScheduler`（确定性 first-fit 时间编排） | **毫秒级** | 完全确定（同输入必同输出）、参数透明可解释、可穷举验证——竞品（豪杰/索美）的能力边界 |
| L2 启发式 | 贪心 + 冲突感知放置 + 匈牙利精确分道（`HungarianAssignment`） | 秒级 | 兜底与快速通道 |
| L3 优化模式 | Timefold 约束求解 + 遗传算法（GA）+ 大邻域搜索（LNS）+ 算法组合调度 | 秒级（可配预算） | 全局权衡兼项冲突/场地利用率/时长保真，带理论下界 gap 评估 |

**前端切换**：教师「项目编排」页顶部提供「规则模式 / 优化模式」单选按钮（选择记忆于 `localStorage`）。
**API 切换**：`POST /api/schedule/auto`，请求体 `mode` 字段——`"rule"` = 规则模式，缺省/`"optimize"` = 优化模式（完全向后兼容）。可选规则参数：`ruleLanePolicy`（`registration`/`performance`）、`ruleAdvanceCount`（晋级人数）、`ruleConflictBufferMinutes`（兼项缓冲）、`ruleConflictCheckEnabled`。

**关键设计**——规则模式不是「另一套系统」，而是同一管线的最低层：
1. **同源候选**：规则与求解使用同一「池解析 + 候选位置栅格」口径（`placementsOf`），两种模式产出可互替；
2. **同一自检**：规则模式的结果同样过 `ScheduleVerifier` 独立自检（场地重叠/赶场/漏排），规则不是法外之地；
3. **同一下界**：规则模式的结果同样计算理论下界 gap，用户能看到「规则解离最优还有多远」，据此判断要不要切优化模式；
4. **规则是兜底**：求解失败/超时零副作用回退（git log「求解失败零副作用降级」），规则层正是这个降级链的最底层。

> 📖 数学边界：L1 的蛇形分组在「组大小为偶数」时各 Group 种子强度**精确相等**（`balanceScore=0`，24人×3组经典模式），奇数大小受结构上界约束（测试固化）；但这只是「规则能做到的」——NP 难的兼项规避与场地权衡必须交给 L2/L3，这正是「重工程软件」与「电子化表单工具」的物种差异：**前者可以向下兼容后者，后者无法向上兼容前者**。

#### 8.1 组次×道次编排（智能编排）

```
Step 1: 获取已审核报名运动员 → 按班级分组
Step 2: 计算组数 = ceil(总人数 / 项目内并发人数)
Step 3: 按班级人数降序（大班优先）
Step 4: 贪心分配 → 每人分配到同班最少组的最早空位
Step 5: 局部优化 (5轮×500次随机交换)
Step 6: 结果验证 → 保存（支持版本回滚）
```

| 约束类型 | 说明 |
|----------|------|
| 硬约束 | 同年级不混编、性别分离 |
| 软约束 | 同班不同道、同班不同组（可在编排规则中开关） |

**项目内并发人数**（项目表单「项目内并发」/ `event.concurrency`）：同一时刻该项目可同时进行的人数——径赛＝每组道次数（留空按道次数）、**田赛＝工位数（X 人同时试跳/试掷）**。田赛同样按此值分批（`ceil(人数 / 并发)` 批），不再是一人一组。

支持：预览（不落库）、批量编排多个项目、手动调整、回滚、道次表导出。

**编排后自检（对抗式校验）** ⭐：编排完成后立即对结果做一次独立自检——由与生成逻辑**相互独立**的校验器重新核对硬约束（① 同一组不能同班；② 道次唯一且不越界；③ 同一赛次下运动员不跨组重复）。若发现硬约束被违反，引擎会**换随机种子自动重排**（最多 `ADVERSARIAL_MAX_ROUNDS = 5` 轮）直到满足或达上限；结果随编排响应返回 `selfCheck {valid, violations, rearrangeCount}`，前端在编排完成后即时提示。工具栏「自检」按钮可随时对**已落库**编排发起独立复检：`GET /api/arrange/events/{id}/verify` → `{valid, violations[], violationCount, checkedHeats}`，弹出报告列出每一条违反项。

**抽签（随机道次）** 🎲：项目开启「抽签」（`event.drawLots`）后，组内道次按**随机抽签**分配——xxx、yyy 等人在同一组内随机占位，而非按班级顺序固定「x 在 1 道、y 在 2 道」；仅作用于非人工锁定占用的道次。编排结果中开启抽签的组次标题会显示「🎲 抽签」徽标。可在项目表单或「批量修改」中开启。

**预留模拟空位（项目级编排）** 🟡：项目级编排时可**预留模拟空位并标注时间**（如决赛待定名额、轮空/弃位、转场预留）——空位不占用真实运动员，单独存于 `arrangement_reservation` 表，查看编排时以橙色虚线「预留空位」格并入对应组次（含「仅预留、无真实编排」的合成组次），并显示预留时间。接口：`GET/POST /api/arrange/events/{id}/reservations`、`POST .../reservations/reserve`（按组次自动预留 N 个空道，道次不足顺延到下一组次）、`DELETE /api/arrange/reservations/{id}`；前端工具栏「模拟空位」按钮打开预留对话框（年级/性别/赛次/起始组次/预留数/时间/备注，含列表与删除）。

**两阶段编排（报名后 → 预赛后）**：第一阶段＝**报名后**编排（`needHeats` 项目先排预赛，其余直接决赛）；第二阶段＝**预赛淘汰后**编排（录入成绩 → 立即计算晋级 → 生成决赛）。工具栏「重排全部决赛」按钮可在**全部预赛完成后**一次性重排所有已录成绩项目的决赛：`POST /api/arrange/finals/rebuild-all`（遍历 `needHeats` 项目 → 已录预赛成绩的「年级×性别」切片 → 重算晋级并生成决赛，返回 `{needHeatsEvents, rebuiltSlices, details}`）。

**裁判分配（可视化）**：执行编排后，每个组次卡片底部以蓝色徽标展示本组次分配的裁判姓名（来自「智能编排」自动分配，详见 §7.1）。工具栏「裁判调整」按钮可打开对话框，按「年级组 / 性别 / 赛次 / 组次」逐组勾选裁判（裁判池带专长提示），保存后立即生效，并写入审计日志 `ARRANGE_REFEREE_ADJUST`；再次「执行编排」会按「组次裁判数量」自动重排并覆盖手工调整。

**裁判编排开关** 🧑‍⚖️：可在「设置 → 编排规则 → 裁判编排」一键**启用/关闭裁判编排**（配置键 `arrange.referee_enabled`，默认**开启**）。关闭后编排**照常进行但不分配裁判**（项目「组次裁判数量」被忽略，并清理该切片旧分配）；**裁判池为空时同样自动跳过**，绝不阻断分组/分道等其它编排。编排页在关闭状态会显示「🧑‍⚖️ 裁判编排已关闭」提示。接口：`GET/PUT /api/arrange/referee-arrange-enabled`。

### 9. 项目编排（赛程编排）

将比赛项目自动调度到「天 × 时段 × 场地」时间表，**模型为「1~n 并发位」**（已废弃早期「串行/并行」开关）：

**编排模式切换（规则 / 优化）** 🎚️：工具栏「规则模式 / 优化模式」单选按钮——
- **规则模式**：确定性 first-fit（项目顺序 → 时间栅格 → 场地槽位），毫秒级、完全可复现、结果透明；响应含 `algorithmPortfolio.rule {placed, unplaced, residualConflicts, elapsedMillis, ...}` 观测信息
- **优化模式**（默认）：Timefold 求解 + GA 进化 + LNS 精修 + 算法组合调度，权衡兼项冲突 / 场地利用率 / 压缩保真，附理论下界 gap
- 两模式共用同一套并发位模型、自检（`/api/schedule/verify`）、下界评估与冲突检测；切换零副作用，随时可换回

| 概念 | 配置项 | 说明 |
|------|--------|------|
| 并发位数（并数） | `trackSlots` / `fieldSlots` | 同一时刻可同时进行几个项目（**1 = 串行**，n = 并行）。**并数上限取决于场地数量**——径赛最多占 1 个主场地、田赛最多占「田赛场地数」个，前端 `:max` 实时限制、保存时后端二次校验，超出即拦截 |
| 项目内并发 | `event.concurrency` | 单个项目内同时进行的人数（田赛工位数 / 径赛每组人数），决定时长 = `ceil(参赛数 / 并发) × 单轮用时`（径赛 `heatMinutes`/轮、田赛 `fieldPerAthleteMinutes`/轮），并受 `maxDurationMinutes` 封顶 |
| 自定义项目顺序 | `eventOrder` / `event.sortOrder` | 编排按 `eventOrder`（eventId 有序列表，田赛 + 径赛混排）进行，未列入的项目按 `sortOrder`（Excel「顺序号」列可批量导入）稳定追加。UI 支持置顶/上移/下移/置底与「按排序号重置」 |
| 田赛分组 | `fieldGroups` | `[{name, eventIds[]}]`：**同一组的田赛项目安排在同一时段并行进行**（组内项目数受 `fieldSlots` 约束，超出自动分波并提示） |
| 并行捆绑组 | `event.bundleGroup` | 项目级字母分组（如 `A` / `B` / `C`）：**填相同字母的田赛自动安排在同一时段并行**，优先级高于「田赛分组」配置；留空则由算法自动安排。**支持 Excel 导入**（「并行捆绑组」列） |

- 并发位与场地对应：径赛用第 1 个场地；田赛的 n 个并发位依次占用其余场地，场地不足时复用同一场地并给出 warning
- 场地录入：每个场地含**名称 + 编码**（如「田赛A区 / `FIELD_A`」），编码用于标识与展示；第 1 个场地为径赛主场地，其余供田赛并行，**并数上限取决于场地数量**，请先录全场地
- 旧配置平滑迁移：原 `trackMode`/`fieldMode`（serial/parallel）按 **serial→1、parallel→2** 自动换算为并发位数，历史配置不丢失
- 手动调整单项安排、导出赛程 Excel

> 💡 若编排结果出现「未能在同一时段并行」告警，通常是该时段容量或并发位数不足——提高「田赛并发位数」或增加场地即可。

### 10. 成绩 & 排名

- 成绩自动解析：`12.34`(秒) / `2:35.67`(分:秒) / `6.78`(米)
- 状态管理：valid / dq / dns / dnf
- 默认积分表：9-7-6-5-4-3-2-1（可自定义）
- 并列处理、破纪录加分、接力加倍、团体总分

### 11. 统计报表

| 报表 | 内容 |
|------|------|
| 秩序册 | 完整赛程手册，**Excel / Word(.docx) 双形态**；Word 版支持一键下载与「生成预赛后自动生成」开关（详见 [13. 秩序册（Word 版）](#13-秩序册word-版生成)） |
| 成绩册 | 完整成绩手册（可导出） |
| 报名统计表 | 各项目 / 班级报名人数、满额率（口径详见 [15. 报名进度 / 满额率统计口径](#15-报名进度--满额率统计口径)） |
| 道次表 | 项目 × 组别 × 跑道矩阵 |
| 成绩汇总表 | 成绩、排名、积分 |
| 团体总分榜 | 班级总分 + 金银铜 |

### 12. 数据库热迁移 & 备份

- **热迁移**（管理员 → 系统设置 → 数据库迁移）：SQLite ↔ MySQL 在线切换，连接测试 → 异步迁移 → 进度查询，**全程无需重启服务**
- **备份**（管理员 → 系统设置 → 数据库备份）：立即备份、备份列表、下载、删除

### 13. 秩序册（Word 版）生成

秩序册提供 **Excel（`.xlsx`，EasyExcel）** 与 **Word（`.docx`，真实排版含表）** 两种导出形态，两者入口均在「报表中心 → 秩序册」tab：

```
① 选择筛选条件（可选） → ② 生成秩序册 → ③ 导出Excel / 下载Word(.docx)
```

**Word 版技术实现**：`WordOrderBookService` 直接以 Office Open XML（WordprocessingML）标准手写 ZIP 包生成 `.docx`，**不依赖 Apache POI**，无需联网下载依赖、`mvn -o` 离线可构建。字体统一「宋体」、A4 页面，文档含：

| 章节 | 内容 |
|------|------|
| 封面 | 运动会名称 / 「秩 序 册」/ 举办时间 / 主办单位 / 编制日期 |
| 目录 | 一 ~ 五章索引 |
| 一、竞赛日程 | 天次 / 日期 / 时段 / 时间 / 项目 / 性别 / 年级 / 场地 |
| 二、竞赛项目设置 | 序号 / 编码 / 项目名称 / 类别 / 性别 / 年级组 / 道次 / 纪录（径赛、田赛、其他分章） |
| 三、参赛单位（班级） | 班级名称 / 年级 / 班主任 / 人数 |
| 四、分组与道次编排 | 按项目逐项成表，**预赛（preliminary）与决赛（final）分开列表**：组次 / 道次 / 号码 / 姓名 / 班级 / 年级 / 性别 |
| 五、运动员号码对照表 | 按 年级（系统顺序）→ 班级 → 名单 排序：号码 / 姓名 / 性别 / 年级 / 班级 |

所有表格带深蓝表头底纹、奇偶行浅色交替与完整边框；表头行跨页重复（`w:tblHeader`）。

**三种使用方式：**

| 方式 | 入口 | 说明 |
|------|------|------|
| 手动下载 | 报表中心 → 秩序册 → `下载Word(.docx)` | 浏览器直接下载最新内容 |
| 手动落盘 | `POST /api/excel/order-book/generate` | 生成到 `data/order_book/秩序册_<时间戳>.docx`，并同步覆盖 `秩序册_latest.docx`，返回 `{file, latest, generatedAt, size}` |
| 自动生成 | 道次编排 → 预赛卡片 → 「自动生成秩序册」开关 | **开启后每次「生成预赛」成功即自动落盘 Word 秩序册**（见下） |

**自动生成开关**：

- 位置：**道次编排 → 预赛卡片头部** `el-switch`（提示文案：「自动编排设置：开启后『生成预赛』自动生成 Word 秩序册」）
- 存储：`system_config` 表 `order_book.auto_generate`（`"true"/"false"`）
- 接口：`GET/POST /api/excel/order-book/auto`（Body `{"enabled": bool}`），位于 `/api/excel` 域 → **体育老师（TEACHER）与管理员均可用**
- 行为：开启后调用「生成预赛」（`/api/arrange/events/{id}/preliminary`，即 `round=preliminary` 编排）成功即自动生成；**生成失败仅记 warn、绝不影响预赛编排主流程**
- 生成的文档落盘在运行目录 `./data/order_book/`（可整目录打包带走；`_latest.docx` 始终指向最近一次生成）

### 14. 号码簿：模板规则 与 按名单顺序 生成 / 重排

号码簿 = 运动员的参赛号码。规则在「系统设置 → 号码簿规则」维护（**SA 专属**），生成动作有两个入口、语义刻意区分：

| 操作 | UI 按钮 | 后端 | 语义 |
|------|--------|------|------|
| **生成（补全空缺）** | `按名单顺序生成（补全空缺）` | `POST /api/system/number-rule/generate` | 只给**尚无号码**的运动员按名单顺序补号，**不覆盖已有**；班级内序号从「已有号码人数 + 1」起 |
| **重排（覆盖）** | `生成 / 重排号码簿` | `POST /api/system/number-rule/reassign` | **整体覆盖重编**：按名单顺序，班级内从 1 连续重编 |

> 教师端「运动员管理 → 批量生成号码」（`/api/athletes/batch-generate-numbers`，T/SA）按当前模板即时生效，适合导入新名单后快速发号；要**严格按 年级序 → 班级序 → 名单序** 排列号码时，用上表的两种名单顺序操作。

**排序基准**（两种名单顺序操作相同）：年级按「系统设置」的年级顺序 → 班级按班级序号（classOrder）→ 名单按导入顺序（运动员自增 id）。

**模板变量**（`/api/system/number-rule` 维护，支持实时预览 `POST .../number-rule/preview`）：

| 变量 | 示例值 | 说明 |
|------|--------|------|
| `{grade}` | `10` | 年级代号：按 年级映射（一年级 1 … 高一年级 10 … 高三年级 12）。**全称 / 简称自动归一**（运动员档案存「高一」也能命中映射键「高一年级」） |
| `{class}` | `01` | 班号两位补零（`auto_pad_zero` 可关）。取号优先级：classOrder>0 → 班级编码**末位**数字（`G10-01` → `1`）→ 班级名末位数字 |
| `{seq:02d}` | `07` | 班级内序号，按位宽补零 |
| `{grade_name}` `{class_name}` | `高一` `高一1班` | 原文照录 |
| `{gender}` `{gender_ch}` | `M` / `男` | 性别码 / 汉字 |
| `{year}` | `26` | 当前年份后两位 |
| `{school_code}` | `01` | 学校代码（可配置） |

**健壮性**：生成前登记全库已占用号码，遇撞号**自动顺延序号**（上限保护），跳过不中断整批；返回值含 `totalClasses / generated / already / skipped / sample`，前端提示条会报告「N 人因号码被占用而跳过」。

### 15. 报名进度 / 满额率统计口径

两处统计此前存在「分母为 0 / 同名项目互相覆盖」问题，v2.0.0 已统一口径如下：

**报名进度**（首页 Dashboard，`GET /api/statistics/registration-progress`，按年级）：

| 字段 | 口径 |
|------|------|
| 分母 `total` | 该年级**花名册真实运动员人数**（剔除已删除），不依赖手填/陈旧的 `class_info.student_count` 静态列 |
| 分子 `registered` | 该年级**已有审核通过报名**的**去重运动员数**（同一人报多项只计 1） |
| 展示 | `高一 355/360 人 · 99%`（单位统一为「人」，避免「人次/人数」错配导致 >100%） |

**各项目报名统计**（报表中心 → 统计报表，`GET /api/statistics/registration`）：

| 字段 | 口径 |
|------|------|
| 项目行 | **同名项目跨年级组自动聚合**（三个年级各有「100米」时合并为一行，数量相加、名额累加），不再“后者覆盖前者”丢数据 |
| 报名人数 | **有效报名 = 已审核（approved）+ 待审核（pending）**，剔除已拒绝 / 已取消 |
| 满额率 | `有效报名 / Σ 名额上限(max_participants)`；**未配置名额上限（0）显示「不限」** 而非误导性 0%；达 100% 进度条转绿 |

---

## 📡 API 接口完整参考

后端共 **19 个 Controller、174 个路由端点**（下表为主要业务端点），统一前缀 `/api`。反向代理子路径部署时（如 `/sportmg/`），前端请求 `/sportmg/api/...` 由后端智能剥离前缀后路由到下列端点。

### 通用约定

#### 认证方式

JWT Bearer Token。登录成功后获得 `accessToken`（24 小时）与 `refreshToken`（7 天），后续请求携带请求头：

```
Authorization: Bearer <accessToken>
```

#### 角色缩写

| 缩写 | 角色 | 说明 |
|:----:|------|------|
| SA | SUPER_ADMIN | 超级管理员 |
| T | TEACHER | 体育老师 |
| CT | CLASS_TEACHER | 班主任 |
| S | STUDENT | 学生 |

> 权限由 `SecurityConfig` URL 规则控制（按顺序匹配，第一条命中生效），代码中无方法级注解。

#### 统一响应结构 ApiResponse

```json
{
  "code": 200,            // 200成功 / 400参数错误 / 401认证失败 / 403权限不足 / 500系统异常
  "message": "success",   // 提示信息
  "data": { ... },        // 业务数据（为 null 时不输出）
  "timestamp": 1719000000000
}
```

#### 分页约定

- 参数：`page`（**从 1 开始**）、`size`
- 默认：`page=1`；`size` 因端点而异（运动员/班级/报名 = 20，班主任运动员 = 50，个人积分 = 10）
- 分页响应结构 `PageData`：`content`、`page`、`size`、`totalElements`、`totalPages`，并附兼容别名 `records`、`total`、`list`

#### 公开接口（无需 Token）

`/api/auth/login`、`/api/auth/refresh`、`/api/system/health`、`/api/setup/**`、`/swagger-ui/**`、`/api-docs/**`、三个导入模板（`/api/athletes/template`、`/api/system/users/template`、`/api/excel/template/**`），以及全部前端静态资源与 SPA 路由路径。

#### 文件上传

multipart 表单，参数名统一为 `file`，单文件/单请求上限 **50MB**。

#### 文件下载

导出多为 `.xlsx`（EasyExcel），秩序册 **Word 版为 `.docx`**（手写 OOXML）。统一通过 `Content-Disposition: attachment` 下载，文件名 UTF-8 编码（`filename*=UTF-8''...`），中文文件名在各类浏览器均正常。

---

### 1. 认证 Auth

前缀 `/api/auth`，6 个端点。

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| POST | `/api/auth/login` | Body `{username, password}` 均必填 | 公开 | 登录，返回 accessToken/refreshToken/user |
| POST | `/api/auth/logout` | Header Token | 已认证 | 登出（使 token 失效） |
| POST | `/api/auth/refresh` | Query `refreshToken` | 公开 | 刷新令牌 |
| POST | `/api/auth/change-password` | Body `{oldPassword, newPassword}` | 已认证 | 修改当前用户密码 |
| GET | `/api/auth/profile` | — | 已认证 | 获取当前用户信息 |
| PUT | `/api/auth/profile` | Body profile 对象 | 已认证 | 更新当前用户信息 |

---

### 2. 班级 Classes

前缀 `/api/classes`，10 个端点。

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| GET | `/api/classes` | Query page=1, size=20, grade? | CT/T/SA | 分页查询班级 |
| GET | `/api/classes/{id}` | Path id | CT/T/SA | 班级详情 |
| POST | `/api/classes` | Body ClassInfo | T/SA | 创建班级 |
| PUT | `/api/classes/{id}` | Path id, Body ClassInfo | T/SA | 更新班级 |
| DELETE | `/api/classes/{id}` | Path id | T/SA | 删除班级 |
| POST | `/api/classes/import` | multipart `file` | T/SA | Excel 导入班级 |
| GET | `/api/classes/export` | — | CT/T/SA | 导出班级数据 |
| GET | `/api/classes/template` | — | CT/T/SA | 下载班级导入模板 |
| POST | `/api/classes/batch` | Body 批量参数 | T/SA | 批量创建班级 |
| PUT | `/api/classes/{id}/bind-teacher` | Path id, Body `{username}` | T/SA | 绑定班主任到班级 |

---

### 3. 运动员 Athletes

前缀 `/api/athletes`，9 个端点。

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| GET | `/api/athletes` | Query page=1, size=20, grade?, classId?, keyword? | CT/T/SA | 分页查询运动员 |
| GET | `/api/athletes/{id}` | Path id | CT/T/SA | 运动员详情 |
| POST | `/api/athletes` | Body Athlete | T/SA | 创建运动员 |
| PUT | `/api/athletes/{id}` | Path id, Body Athlete | T/SA | 更新运动员 |
| DELETE | `/api/athletes/{id}` | Path id | T/SA | 删除运动员 |
| POST | `/api/athletes/import` | multipart `file` | T/SA | Excel 导入运动员 |
| GET | `/api/athletes/export` | — | CT/T/SA | 导出运动员数据 |
| POST | `/api/athletes/batch-generate-numbers` | Query grade?, classId? | T/SA | 批量生成号码簿 |
| GET | `/api/athletes/template` | — | 公开 | 下载导入模板 |

---

### 4. 项目 Events

前缀 `/api/events`，14 个端点。

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| GET | `/api/events` | Query grade?, gender?, eventType? | S/CT/T/SA | 项目列表（不分页） |
| GET | `/api/events/{id}` | Path id | S/CT/T/SA | 项目详情 |
| POST | `/api/events` | Body Event | T/SA | 创建项目 |
| PUT | `/api/events/{id}` | Path id, Body 待更新字段 | T/SA | **部分更新（PATCH）**：只覆盖请求中出现的字段 |
| PUT | `/api/events/batch` | Body `{ids:[], patch:{...}}` | T/SA | 批量部分更新（patch 中出现的字段生效） |
| POST | `/api/events/batch-status` | Body `{ids:[], enabled}` | T/SA | 批量启用/禁用 |
| PUT | `/api/events/{id}/status` | Path id, Body `{enabled}` | T/SA | 启用/禁用项目 |
| DELETE | `/api/events/{id}` | Path id | T/SA | 删除项目 |
| POST | `/api/events/presets` | Body categoryFilter | T/SA | 获取预设项目模板 |
| POST | `/api/events/import` | multipart `file` | T/SA | Excel 导入项目 |
| GET | `/api/events/export` | — | S/CT/T/SA | 导出项目数据（Excel/CSV 布局） |
| **GET** | **`/api/events/export/json`** | — | S/CT/T/SA | **导出项目 JSON（`{type,version,exportedAt,count,defaults,events[]}` 全字段 + 默认值）** |
| **GET** | **`/api/events/template/json`** | — | S/CT/T/SA | **下载 JSON 导入模板（默认值 + 示例）** |
| **POST** | **`/api/events/import/json`** | multipart `file`(.json) | T/SA | **导入项目 JSON（全字段往返；按 `code` 覆盖/新增，返回 `{total,created,updated,success,failed,errors[]}`）** |

> ⚠️ `PUT /api/events/{id}` 与 `/api/events/batch` 为**部分更新（PATCH）**：仅请求体中显式出现的字段会被写入，
> 其余字段（含 `isTrack`、`laneCount`、`category`、`concurrency`）保持原值——因此「批量修改项目内并发」不会误伤田赛标记。

**项目关键字段**：`concurrency`（项目内并发人数：径赛留空=按道次数、田赛默认 1）、`isTrack`（是否径赛）、`laneCount`（道次）、`isTeam`/`teamSize`（团体）、`gradeGroup`（年级组）、`gender`（性别组）、`maxDurationMinutes`/`intervalMinutes`（时长与间隔）、`sortOrder`（排序号，可经 Excel「顺序号」列批量导入）、`bundleGroup`（并行捆绑组字母，可经 Excel「并行捆绑组」列批量导入）、`refereesPerGroup`（组次裁判数量：每个组次所需裁判人数，智能编排时按此数自动分配裁判；留空/0=不安排裁判）、`drawLots`（抽签：组内道次随机分配）、`defaultVenue`/`defaultVenueCode`（默认场地/场地编码）、`scoringType`/`scoringRules`（计分）。

**项目字典 JSON 往返**：`export/json` 输出**全部字段 + `defaults` 默认值块**（`eventType/isTrack/laneCount/concurrency/groupSize/refereesPerGroup/drawLots/maxDurationMinutes/intervalMinutes/needHeats/maxPerHeat/advanceCount/scoringType/sortOrder/enabled` 等），既可用于**备份/跨机迁移**，也可**导出→编辑→导入**做批量维护。`import/json` 接受完整导出结构或裸数组，按 `code` 判定：**已存在→仅覆盖 JSON 中出现的字段（安全 PATCH 语义）；不存在→新建（缺省字段用默认值）**，逐条独立、单条失败不影响其余。Excel 布局列同步扩展了「组次裁判数量 / 抽签」两列，保持 Excel 与 JSON 口径一致。

---

### 5. 报名 Registrations

前缀 `/api/registrations`，11 个端点。

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| GET | `/api/registrations` | Query page=1, size=20, eventId?, classId?, status? | CT/T/SA | 分页查询报名 |
| GET | `/api/registrations/{id}` | Path id | CT/T/SA | 报名详情 |
| POST | `/api/registrations` | Body `{athleteId, eventId}` | CT/T/SA | 创建报名 |
| DELETE | `/api/registrations/{id}` | Path id | CT/T/SA | 取消报名 |
| POST | `/api/registrations/batch` | Body `{items:[{athleteId,eventId}]}` | CT/T/SA | 批量报名 |
| PUT | `/api/registrations/{id}/approve` | Path id, Body remark? | CT/T/SA | 审核通过 |
| PUT | `/api/registrations/{id}/reject` | Path id | CT/T/SA | 拒绝报名 |
| PUT | `/api/registrations/batch-approve` | Body `{ids:[...]}` | CT/T/SA | 批量通过 |
| PUT | `/api/registrations/batch-reject` | Body `{ids:[...]}` | CT/T/SA | 批量拒绝 |
| GET | `/api/registrations/statistics` | — | CT/T/SA | 报名统计 |
| GET | `/api/registrations/export` | — | CT/T/SA | 导出报名数据 |

---

### 6. 班主任端 ClassTeacher

前缀 `/api/class-teacher`，10 个端点，均要求 **CT/T/SA**，业务层再按当前用户绑定班级做数据隔离。

| 方法 | 端点 | 参数 | 说明 |
|------|------|------|------|
| POST | `/api/class-teacher/import-roster` | multipart `file`, Query classId? | 导入全班名单（自动建学生账号+运动员） |
| GET | `/api/class-teacher/athletes` | Query page=1, size=50 | 本班运动员列表 |
| GET | `/api/class-teacher/dashboard` | — | 班主任仪表盘（统计+最近报名+赛程） |
| POST | `/api/class-teacher/register` | Body `{athleteId, eventId}` | 为运动员报名（校验性别/限项/重复） |
| DELETE | `/api/class-teacher/register/{id}` | Path id | 取消报名 |
| GET | `/api/class-teacher/registrations` | — | 本班报名列表 |
| GET | `/api/class-teacher/registrations/export` | — | 导出本班报名表 |
| GET | `/api/class-teacher/schedule` | — | 本班赛程 |
| GET | `/api/class-teacher/results` | — | 本班成绩（含总分/金银铜汇总） |
| GET | `/api/class-teacher/events` | — | 可报名项目列表（启用中） |

---

### 7. 智能编排 Arrange

前缀 `/api/arrange`，26 个端点。

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| POST | `/api/arrange/events/{eventId}` | Path eventId, Body config | T/SA | 对指定项目执行自动编排（返回含 `selfCheck` 自检报告） |
| POST | `/api/arrange/preview` | Body config | T/SA | 预览编排（不落库） |
| GET | `/api/arrange/events/{eventId}` | Path eventId | S/CT/T/SA | 查看项目编排结果（含各组次裁判、预留空位） |
| PUT | `/api/arrange/events/{eventId}` | Path eventId, Body adjustments[] | T/SA | 手动调整编排 |
| PUT | `/api/arrange/{arrangementId}/lock` | Path id, Query locked | T/SA | 锁定/解锁单条编排（锁定后自动重排不覆盖） |
| DELETE | `/api/arrange/events/{eventId}` | Path eventId | T/SA | 清除该项目编排（含裁判分配、预留空位） |
| POST | `/api/arrange/batch` | Body `[eventIds]` | T/SA | 批量编排多个项目 |
| POST | `/api/arrange/events/{eventId}/rollback` | Path eventId | T/SA | 回滚编排 |
| POST | `/api/arrange/events/{eventId}/preliminary` | Path eventId, Body `{grade, gender}` | T/SA | 生成预赛编排 |
| POST | `/api/arrange/events/{eventId}/prelim-results` | Body `{grade, gender, items[]}` | T/SA | 录入预赛成绩 |
| POST | `/api/arrange/events/{eventId}/qualify` | Body `{grade, gender, advanceCount}` | T/SA | 预赛淘汰「立即计算」并生成决赛 |
| GET | `/api/arrange/events/{eventId}/qualifiers` | Path eventId, Query grade/gender | S/CT/T/SA | 查看晋级名单 |
| **GET** | **`/api/arrange/events/{eventId}/verify`** | Path eventId | S/CT/T/SA | **编排自检（对抗式校验）：`{valid, violations[], violationCount, checkedHeats}`** |
| **GET** | **`/api/arrange/events/{eventId}/reservations`** | Path eventId | S/CT/T/SA | **查看预留模拟空位** |
| **POST** | **`/api/arrange/events/{eventId}/reservations`** | Body `{grade, gender, round, heat, lane, scheduledTime, note}` | T/SA | **新增单个预留空位** |
| **POST** | **`/api/arrange/events/{eventId}/reservations/reserve`** | Body `{grade, gender, round, heat, count, scheduledTime, note}` | T/SA | **按组次自动预留 N 个空道** |
| **DELETE** | **`/api/arrange/reservations/{id}`** | Path id | T/SA | **删除预留空位** |
| **POST** | **`/api/arrange/finals/rebuild-all`** | — | T/SA | **全部预赛完成后一次性重排全部决赛** |
| **GET** | **`/api/arrange/referee-arrange-enabled`** | — | S/CT/T/SA | **查询是否启用「裁判编排」** |
| **PUT** | **`/api/arrange/referee-arrange-enabled`** | Body `{enabled}` | T/SA | **设置是否启用「裁判编排」（关闭后编排不分配裁判）** |
| GET | `/api/arrange/events/{eventId}/referees` | Path eventId | S/CT/T/SA | 查看该项目全部组次裁判分配（含姓名） |
| PUT | `/api/arrange/events/{eventId}/referees/heat` | Path eventId, Body `{grade, gender, round, heat, refereeIds[]}` | T/SA | 手工调整某组次裁判（重新自动编排会覆盖） |
| GET | `/api/arrange/events/{eventId}/export` | Path eventId | S/CT/T/SA | 导出道次表（Excel，含裁判列） |
| GET | `/api/arrange/export-all` | — | T/SA | 全量编排导出（JSON，含决赛，供 `arrange_result.json`） |
| GET | `/api/arrange/conflicts` | — | T/SA | 兼项冲突检测（清单 + 建议） |
| GET | `/api/arrange/conflicts/export` | — | T/SA | 兼项冲突清单导出（Excel） |

#### 7.1 裁判自动分配（smart referee assignment）

执行编排（`POST /events/{eventId}`）时，若项目 `refereesPerGroup > 0`，引擎为**每个组次**分别安排裁判，规则：

1. **数量**：每组次安排 `refereesPerGroup` 名（如立定跳远一组次 x 人 → 填 x；拔河一组 3 人 → 填 3；N 组并行仍按单组各安排，系统自动算好互不抢占）。
2. **专长优先**：裁判「专长项目」含本项目（编码或名称命中）者优先入选。
3. **负载均衡**：非专长裁判按历史被分配次数升序入选，避免个别人被连排。
4. **并行互不抢占**：同一 (项目×年级×性别×赛次) 切片内，各组次尽量不重复占用同一裁判；裁判池不足时复用并写入 `warnings[]`（如「裁判不足：…第N组次仅分配到 M 名（需 K 名）」）。
5. **落库**：结果写入 `event_referee` 表（key = event×grade×gender×round×heat），编排视图与道次表均挂载 `referees[]`（{id,name}）。
6. **手工调整**：`PUT /referees/heat` 可临时换人；重新执行自动编排会按 `refereesPerGroup` 重新分配并覆盖。

---

### 8. 赛程编排 Schedule

前缀 `/api/schedule`，5 个端点。

> ⚠️ 注意：`/api/schedule/**` 在 SecurityConfig 中无专属角色规则，落入兜底 `anyRequest().authenticated()`，即**任何已登录角色（含学生）均可访问**（含写操作）。如需收紧请补充角色规则。

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| GET | `/api/schedule` | — | 已认证 | 查看当前赛程 |
| POST | `/api/schedule/auto` | Body config?（可覆盖 trackSlots/fieldSlots/eventOrder/fieldGroups 等，不落库）；**`mode`**：`"rule"` 规则模式 / 缺省 `"optimize"` 优化模式；规则模式可选 `ruleLanePolicy` / `ruleAdvanceCount` / `ruleConflictBufferMinutes` / `ruleConflictCheckEnabled` | 已认证 | 按「并发位」模型自动编排赛程（双模式详见 [9. 项目编排（赛程编排）](#9-项目编排赛程编排)）；响应含 `mode`（实际使用的模式）与 `algorithmPortfolio.rule`（规则模式观测信息） |
| POST | `/api/schedule/save` | Body items[] | 已认证 | 手动保存赛程（整体替换） |
| DELETE | `/api/schedule` | — | 已认证 | 清空赛程 |
| GET | `/api/schedule/export` | — | 已认证 | 导出赛程（Excel，含「项目内并发」列） |

> 💡 自动编排返回 `warnings[]`（如「田赛分组部分项目未能安排在同一时段」）与 `autoArrange`（径赛自动生成决赛道次的结果统计），前端会提示。

---

### 9. 成绩 Results

前缀 `/api/results`，8 个端点。

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| GET | `/api/results` | Query eventId?, heat? | S/CT/T/SA | 成绩列表（不分页） |
| POST | `/api/results` | Body 成绩对象 | T/SA | 录入成绩 |
| PUT | `/api/results/{id}` | Path id, Body 成绩对象 | T/SA | 修改成绩 |
| DELETE | `/api/results/{id}` | Path id | T/SA | 删除成绩 |
| POST | `/api/results/import` | multipart `file` | T/SA | Excel 导入成绩 |
| POST | `/api/results/events/{eventId}/calculate-ranking` | Path eventId | T/SA | 计算项目排名 |
| GET | `/api/results/events/{eventId}/ranking` | Path eventId | S/CT/T/SA | 查看项目排名 |
| GET | `/api/results/events/{eventId}/export` | Path eventId | S/CT/T/SA | 导出项目成绩（Excel） |

---

### 10. 排名积分 Ranking

前缀 `/api/ranking`，8 个端点，均为 GET，要求 **S/CT/T/SA**。

| 方法 | 端点 | 参数 | 说明 |
|------|------|------|------|
| GET | `/api/ranking/team-score` | Query grade? | 团体总分 |
| GET | `/api/ranking/team-score/breakdown` | Query className(必填), grade? | 团体分项明细 |
| GET | `/api/ranking/individual-score` | Query grade?, eventId?, page=1, size=10 | 个人积分 |
| GET | `/api/ranking/records` | Query grade?, eventId? | 破纪录情况 |
| GET | `/api/ranking/events/{eventId}` | Path eventId | 单项目排名 |
| GET | `/api/ranking/individual-score/export` | — | 导出个人积分排名 |
| GET | `/api/ranking/team-score/export` | — | 导出团体总分 |
| GET | `/api/ranking/records/export` | — | 导出破纪录榜 |

---

### 11. 统计报表 Statistics

前缀 `/api/statistics`，8 个端点。

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| GET | `/api/statistics/todo` | — | CT/T/SA | 待办统计 |
| GET | `/api/statistics/registration-progress` | — | CT/T/SA | 报名进度 |
| GET | `/api/statistics/today-schedule` | — | CT/T/SA | 今日赛程 |
| GET | `/api/statistics/registration` | — | CT/T/SA | 报名统计 |
| GET | `/api/statistics/score` | — | CT/T/SA | 成绩统计 |
| POST | `/api/statistics/order-book` | — | T/SA | 生成秩序册 |
| POST | `/api/statistics/result-book` | — | T/SA | 生成成绩册 |
| GET | `/api/statistics/export` | — | CT/T/SA | 导出统计数据 |

> 💡 此处 `POST /api/statistics/order-book` / `result-book` 生成 **Excel 工作簿**；秩序册 **Word(.docx)** 版与「预赛后自动生成」开关的接口在 [15. Excel 导入导出 Excel](#15-excel-导入导出-excel)。报名进度 / 满额率的数据口径见 [15. 报名进度 / 满额率统计口径](#15-报名进度--满额率统计口径)。

---

### 12. 学生端 Student

前缀 `/api/student`，4 个端点，均为 GET，要求 **S/CT/T/SA**，数据按当前登录学号自动隔离。

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/student/home` | 学生首页（统计+我的报名+我的赛程） |
| GET | `/api/student/events` | 项目浏览（含 isRegistered 标记） |
| GET | `/api/student/schedule` | 我的赛程 |
| GET | `/api/student/results` | 我的成绩（含名次/积分/是否破纪录） |

---

### 13. 系统设置 System

前缀 `/api/system`，33 个端点。`/api/system/config/**`、`grades/**`、`meet-schedule/**`、`grade-order/**`、`arrange-rule/**` 为 T/SA（体育老师可调运动会配置）；`number-rule/**` 及用户管理 / 裁判管理 / 数据库 / 备份相关为 SA（号码规则全局唯一，仅超级管理员可改）。

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| GET | `/api/system/config` | — | T/SA | 获取全部系统配置 |
| GET | `/api/system/config/{key}` | Path key | T/SA | 获取单个配置 |
| PUT | `/api/system/config/{key}` | Path key, Body | T/SA | 更新单个配置 |
| PUT | `/api/system/config/basic` | Body 基本设置 | T/SA | 保存基本设置 |
| PUT | `/api/system/config/scoring` | Body 积分规则 | T/SA | 保存积分规则（旧接口） |
| GET | `/api/system/meet-schedule` | — | T/SA | 读取运动会日程配置（含并发位数/项目顺序/田赛分组；旧 trackMode/fieldMode 自动换算为 1~n） |
| PUT | `/api/system/meet-schedule` | Body 日程配置 | T/SA | 保存运动会日程配置 |
| GET | `/api/system/grade-order` | — | T/SA | 年级出场顺序（按 sortOrder 升序） |
| GET | `/api/system/number-rule` | — | SA | 获取号码簿规则 |
| PUT | `/api/system/number-rule` | Body 规则 | SA | 保存号码簿规则 |
| POST | `/api/system/number-rule/preview` | Body `{template, grade, className, seq, auto_pad_zero}` | SA | 预览号码生成效果 |
| POST | `/api/system/number-rule/reassign` | Body `grade?` | SA | 号码簿按名单顺序重排（覆盖：年级序→班级序→名单序，班级内从 1 重编） |
| POST | `/api/system/number-rule/generate` | Body `grade?` | SA | 号码簿按名单顺序生成（补全空缺，不覆盖已有；撞号自动顺延） |
| GET | `/api/system/arrange-rule` | — | SA | 获取编排规则 |
| PUT | `/api/system/arrange-rule` | Body 规则 | SA | 保存编排规则 |
| GET | `/api/system/scoring-rule` | — | SA | 获取积分规则 |
| PUT | `/api/system/scoring-rule` | Body 规则 | SA | 保存积分规则 |
| GET | `/api/system/app-config` | — | SA | 获取应用运行配置（端口等） |
| PUT | `/api/system/app-config` | Body 配置 | SA | 保存应用运行配置（重启生效） |
| GET | `/api/system/grades` | — | T/SA | 年级列表 |
| POST | `/api/system/grades` | Body `{name}` | T/SA | 新增年级 |
| PUT | `/api/system/grades/{id}` | Path id, Body | T/SA | 编辑年级 |
| DELETE | `/api/system/grades/{id}` | Path id | T/SA | 删除年级 |
| GET | `/api/system/health` | — | 公开 | 健康检查（`{status:"UP",...}`） |
| GET | `/api/system/health-detail` | — | SA | 健康检查详情 |
| GET | `/api/system/logs` | — | SA | 获取近期操作日志 |

---

### 14. 用户管理 Users

前缀 `/api/system/users`，9 个端点，除模板下载外**全部仅 SA**。

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| GET | `/api/system/users` | — | SA | 用户列表 |
| GET | `/api/system/users/{id}` | Path id | SA | 用户详情 |
| POST | `/api/system/users` | Body 用户信息 | SA | 创建用户 |
| PUT | `/api/system/users/{id}` | Path id, Body | SA | 更新用户 |
| DELETE | `/api/system/users/{id}` | Path id | SA | 删除用户 |
| PUT | `/api/system/users/{id}/reset-password` | Path id | SA | 重置为默认密码 |
| POST | `/api/system/users/import` | multipart `file` | SA | Excel 导入用户 |
| GET | `/api/system/users/template` | — | 公开 | 下载用户导入模板 |
| POST | `/api/system/users/batch` | Body 批量参数 | SA | 批量创建用户 |

---

### 14.1 裁判管理 Referees

前缀 `/api/system/referees`，9 个端点，**全部仅 SA**。裁判既是「被编排的人力资源」（通过智能编排引擎按「组次裁判数量」`event.refereesPerGroup` 分配到各个组次），**也可拥有登录账号**（角色 `ROLE_REFEREE`，经 `referee.user_id` 关联），登录后进入裁判工作台查看本人执裁安排（`GET /api/referee/me`）。

**前端入口**：教师端（仅 SA 可见）「裁判管理」页（`/teacher/referees`）——列表查看、新增/编辑（姓名必填、电话、专长项目多行文本，前端按 `[,，]` 切分）、删除、Excel 导入（带 Bearer Token）、下载导入模板。

**裁判工作安排（裁判视图）**：教师端「裁判工作安排」页（`/teacher/referee-board`，T/SA 可见）——按裁判聚合其在各「项目 / 年级 / 性别 / 赛次 / 组次」的编排分配（可展开查看明细、按姓名/专长搜索、统计分配组次数与未分配裁判）。数据来自 `GET /api/arrange/referee-board`（聚合 `event_referee`）。裁判本人登录后看到的是**自己的**安排（`GET /api/referee/me`），此页为管理端汇总视图。

**批量导入中心（管理员）**：Settings →「批量创建」标签页顶部提供四类名单导入入口——**学生名单**（→ 运动员管理页，列映射预览导入 `/excel/import-with-mapping`）、**班主任名单 / 体育老师**（→「用户管理」标签页，`/api/system/users/import` + 模板）、**裁判**（→「裁判管理」页，`/api/system/referees/import` + 模板）。

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| GET | `/api/system/referees` | — | SA | 裁判列表 |
| GET | `/api/system/referees/{id}` | Path id | SA | 裁判详情 |
| POST | `/api/system/referees` | Body `{name, phone?, specialties?}` | SA | 创建裁判 |
| PUT | `/api/system/referees/{id}` | Path id, Body | SA | 更新裁判 |
| DELETE | `/api/system/referees/{id}` | Path id | SA | 删除裁判（软删除） |
| POST | `/api/system/referees/import` | multipart `file` | SA | Excel 批量导入（按姓名 upsert） |
| GET | `/api/system/referees/template` | — | SA | 下载裁判导入模板 |
| **POST** | **`/api/system/referees/{id}/account`** | Path id, Body `{username?, password?}` | SA | **为某裁判开通登录账号（角色 REFEREE，默认用户名取手机号、密码 123456）** |
| **POST** | **`/api/system/referees/accounts/open-all`** | — | SA | **批量为未开通账号的裁判开通账号** |

**裁判端接口**：`GET /api/referee/me`（角色 `ROLE_REFEREE`/T/SA）——当前登录裁判本人的执裁安排（未绑定档案时返回 `linked=false`）。

**裁判实体字段**：`name`（姓名，唯一）、`phone`（电话）、`specialties`（专长项目，JSON 数组，如 `["立定跳远","拔河"]`）、`status`（active）、`userId`（关联登录账号，空=未开通）。
**专长项目 Excel 语法**：单元格可写 `[立定跳远,拔河，跳绳]`（中英文逗号混合），系统归一化为标准 JSON 落库；留空表示不限项目。
**与编排的关系**：裁判经「组次裁判数量」在智能编排中按组次自动分配，详见 [4. 智能编排](#4-智能编排)。

---

### 15. Excel 导入导出 Excel

前缀 `/api/excel`，13 个端点。

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| GET | `/api/excel/template/{type}` | Path type | 公开 | 下载指定类型模板 |
| POST | `/api/excel/preview` | multipart `file` | T/SA | 导入预览（多 Sheet） |
| POST | `/api/excel/import-with-mapping` | multipart `file`, Query mapping | T/SA | 带列映射导入 |
| POST | `/api/excel/import/athletes` | multipart `file` | T/SA | 导入运动员（兼容旧接口） |
| POST | `/api/excel/import/scores` | multipart `file` | T/SA | 导入成绩 |
| POST | `/api/excel/import/registrations` | multipart `file` | T/SA | 导入报名 |
| GET | `/api/excel/export/arrangement` | Query eventId(必填) | T/SA | 导出编排表 |
| GET | `/api/excel/export/order-book` | — | T/SA | 导出秩序册（Excel） |
| GET | `/api/excel/export/result-book` | — | T/SA | 导出成绩册 |
| GET | `/api/excel/export/order-book-docx` | — | T/SA | 导出秩序册（真实 Word .docx，含多张表格） |
| POST | `/api/excel/order-book/generate` | — | T/SA | 生成秩序册(Word)并落盘到 `data/order_book/` |
| GET | `/api/excel/order-book/auto` | — | T/SA | 读取「生成预赛/编排后自动生成秩序册」开关 |
| POST | `/api/excel/order-book/auto` | Body `enabled`(bool) | T/SA | 设置自动生成秩序册开关 |

> 💡 `export/order-book`（Excel 工作簿）与 `export/order-book-docx`（真实 Word 排版）为**两个独立生成引擎**，前者走 EasyExcel、后者由手写 OOXML 实现（零 POI 依赖）。Word 版结构、自动生成开关与落盘路径详见 [13. 秩序册（Word 版）生成](#13-秩序册word-版生成)。

#### 15.1 项目导入模板（表格2）列说明

模板 `项目表导入模板_表格2.xlsx` 列（A→Q）：代码 / 项目 / 是否田径 / 道次 / 顺序号 / 每组次几人 / 捆绑字母 / 并行数 / 场地编码 / 性别 / 年级组 / 是否团体 / 团体人数 / 场地 / 最大用时(分) / 间隔(分) / **组次裁判数量**。

- **组次裁判数量**（`refereesPerGroup`）：每个组次（heat/组/轮）需安排的裁判人数。田赛如立定跳远一组次 5 人需 x 名裁判 → 填 x；拔河一组 3 人 → 填 3；若 N 组并行仍按单组填写，智能编排为**每组**分别安排。留空 / 0 = 该项目不安排裁判。
- **并行数**：项目内并发人数（径赛=道次、田赛=工位数、游泳=泳道数），受场地 `parallelMax` 上限约束。

#### 15.2 裁判导入模板与「专长项目」列表语法

模板 `裁判导入模板.xlsx` 列：姓名 / 电话 / 专长项目（可选）。

- **专长项目**单元格支持列表语法 `[a,b，c]`（**中英文逗号混用均兼容**，如 `[立定跳远，拔河,50米蛙泳]`），导入后统一落库为标准 JSON 数组字符串（`["立定跳远","拔河","50米蛙泳"]`），用于智能编排时按专长优先分配裁判。

#### 15.3 导出物中的裁判列

- **道次表**（`GET /api/arrange/events/{id}/export`）：分组道次名单末尾追加「裁判」列，按 (年级|性别|赛次|组次) 挂载该组次分配的裁判姓名。
- **秩序册 Excel**（`export/order-book`）：Sheet「分组道次名单」追加「裁判」列。
- **秩序册 Word**（`export/order-book-docx`）：分组与道次编排表末尾追加「裁判」列。
- 三者数据同源：均来自 `event_referee` 表，重新执行自动编排后会随之刷新。

---

### 16. 数据库备份 Backup

前缀 `/api/backup`，4 个端点，**全部仅 SA**。

| 方法 | 端点 | 参数 | 说明 |
|------|------|------|------|
| GET | `/api/backup/list` | — | 备份文件列表 |
| POST | `/api/backup/now` | — | 立即手动备份 |
| DELETE | `/api/backup/{fileName}` | Path fileName | 删除备份文件 |
| GET | `/api/backup/download/{fileName}` | Path fileName | 下载备份文件 |

---

### 17. 数据库迁移 DbMigration

前缀 `/api/db-migration`，5 个端点，**全部仅 SA**。支持 SQLite ↔ MySQL 在线热迁移，无需重启。

| 方法 | 端点 | 参数 | 说明 |
|------|------|------|------|
| GET | `/api/db-migration/current` | — | 当前数据库信息 |
| GET | `/api/db-migration/targets` | — | 支持的目标数据库类型 |
| POST | `/api/db-migration/test` | Body target | 测试目标库连接 |
| POST | `/api/db-migration/start` | Body target | 启动迁移（异步） |
| GET | `/api/db-migration/progress/{taskId}` | Path taskId | 查询迁移进度 |

---

### 18. 建站向导 Setup

前缀 `/api/setup`，3 个端点，**全部公开**，但安装完成后 `install` / `check-db` 由业务层锁定（返回 403）。

| 方法 | 端点 | 参数 | 说明 |
|------|------|------|------|
| GET | `/api/setup/status` | — | 查询安装状态（前端据此决定是否进向导） |
| POST | `/api/setup/check-db` | Body 数据库连接参数 | 测试数据库连接（已安装则 403） |
| POST | `/api/setup/install` | Body `{siteName, dbType, ...}` | 执行安装（一次性，已安装则 403） |

---

### 19. 入场式评分 ParadeScore

前缀 `/api/parade-score`，5 个端点，均要求 **T/SA**。用于入场式 / 队列评分维护（独立计分，不并入项目成绩排名）。

| 方法 | 端点 | 参数 | 说明 |
|------|------|------|------|
| GET | `/api/parade-score` | Query grade? | 评分列表（可按年级筛选） |
| POST | `/api/parade-score` | Body `items[]` | 批量保存评分 |
| POST | `/api/parade-score/import` | multipart `file` | Excel 导入评分 |
| DELETE | `/api/parade-score/{id}` | Path id | 删除单条评分 |
| DELETE | `/api/parade-score` | Query grade? | 清空评分（可按年级） |

---

## 🗄 数据库设计

```
sys_user ──┐
           ├── class_info (teacher_user_id)
           │       │
           │       ├── athlete (class_info_id)
           │       │       │
           │       │       ├── registration (athlete_id + event_id)
           │       │       ├── arrangement  (athlete_id + event_id)
           │       │       └── result       (athlete_id + event_id)
           │       │
           │       └── event
           │
           └── system_config
```

| 表 | 说明 |
|----|------|
| `sys_user` | 用户/账号/角色，BCrypt 加密 |
| `class_info` | 班级，关联班主任 userId |
| `athlete` | 运动员，含学号、号码簿、班级关联 |
| `event` | 比赛项目，含预设模板 |
| `registration` | 报名记录，联合唯一约束 |
| `arrangement` | 编排结果，支持版本回滚 |
| `result` | 成绩记录，多状态管理 |
| `system_config` | 系统配置，JSON 存储 |

支持的数据库：**SQLite（默认，零配置）**、MySQL 8.0（生产）、H2（开发）。运行时可通过「数据库迁移」在线切换。

---

## 🚢 部署指南

### JAR 运行

```bash
# 默认 SQLite（零配置）
java -jar sports-2.6.5.jar

# 自定义端口 + 绑定地址（推荐写法）
java -jar sports-2.6.5.jar --app.port=8899 --app.host=::

# 等价的 Spring 标准写法
java -jar sports-2.6.5.jar --server.port=9090

# 后台运行
nohup java -jar sports-2.6.5.jar --app.port=8899 > app.log 2>&1 &
```

### 🔄 更换服务端口与绑定地址（优先级从高到低）

| 方式 | 操作 | 生效方式 |
|------|------|----------|
| ① 命令行参数 | `java -jar sports-2.6.5.jar --app.port=8899 --app.host=::`<br>`.\start.ps1 -Port 8899 -Host ::` / `start.bat --app.port=8899`<br>（也可用标准 `--server.port=9090`） | 立即（本次运行） |
| ② 环境变量 | `SERVER_PORT=9090 java -jar sports-2.6.5.jar`（Linux/macOS）<br>`$env:SERVER_PORT="9090"; java -jar ...`（PowerShell） | 立即（本次运行） |
| ③ 配置文件 | 编辑 `data/app-config.json`：`{"port": 9090, "host": "::"}` | 重启后生效 |
| ④ 界面操作 | 登录后 **系统设置 → 基本设置 → 服务端口** → 保存 → 重启应用 | 重启后生效 |

**说明：**
- 未做任何配置时默认端口为 **8080**、绑定全部网卡（0.0.0.0 / ::）。
- `--app.host` 支持 IPv4 / IPv6 与通配地址：`0.0.0.0`、`::`（IPv6 全网卡，兼容 IPv4）、`127.0.0.1` / `::1`。
- `data/app-config.json`、`sports_meet.db` 均相对**项目根目录**解析，请用根目录下的启动脚本或先 `cd` 到根目录。

### 🌐 反向代理子路径部署（如 `/sportmg/` 帽子）

系统原生支持挂在任意反向代理（cpolar / ngrok 子域隧道、nginx 子路径帽子等）下，**开箱即用、零额外配置**。关键设计：

- **前端路由采用 hash 模式**（`/#/login`、`/#/teacher/dashboard`）：浏览器地址中的路由部分在 `#` 之后，**永远不会发送到服务器**。因此无论反向代理是子域（`https://xxx.cpolar.cn`）还是子路径（`http://host/sportmg/`），后端只收到 `/` 或 `/sportmg/`，无需任何 `try_files` / `rewrite` 重写规则，从根上杜绝了"历史模式下深链刷新被误判为 SPA 路由导致白屏"的问题。
- 前端资源（js/css）采用相对路径构建；API 请求基于当前 URL 实时推断前缀（`/api` 或 `/sportmg/api`），后端内置「智能前缀剥离」过滤器，自动兼容两种转发形态。

> 💡 **cpolar / ngrok 用户**：直接把隧道指向本服务端口即可（如 `cpolar http 8899`），用分配的 `https://xxx.cpolar.cn` 访问，无需任何额外设置。若需挂到主站子路径（如 `/sportmg/`），也只需常规 `proxy_pass`，同样无需改写。

**形态 A：保留帽子转发**（推荐，后端收到 `/sportmg/...`，由后端智能剥离）

```nginx
location /sportmg/ {
    proxy_pass http://127.0.0.1:8080;          # 不带尾部斜杠 = 保留前缀
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
}
```

**形态 B：剥掉帽子转发**（后端收到 `/login`、`/api/...` 干净路径）

```nginx
location /sportmg/ {
    proxy_pass http://127.0.0.1:8080/;         # 带尾部斜杠 = 剥离前缀
    proxy_set_header Host $host;
    ...
}
```

两种形态下浏览器地址均保持 `http://host/sportmg/#/login` 形态（hash 模式），路由、资源、API 全部自适应；无反向代理时直接访问 `http://localhost:8080` 行为完全一致（地址为 `http://localhost:8080/#/login`）。访问 `http://host/sportmg/` 或 `http://localhost:8080/` 会自动进入登录/安装向导页。

**缓存策略**（防"升级后浏览器仍用旧壳"）：`index.html` 与 SPA 回退路径强制 `no-store`，`/assets/**` 带内容哈希的资源长缓存 1 年（升级后文件名自动变化）。

### 🔄 数据库迁移（SQLite ↔ MySQL）

管理员登录后进入 **系统设置 → 数据库迁移**：

1. 选择目标数据库类型（MySQL）并填写连接信息
2. 点击「测试连接」验证
3. 启动迁移（异步执行），实时查看进度
4. 迁移完成后自动切换，**全程无需重启服务**

### 硬件建议

| 规模 | 参赛人数 | CPU | 内存 |
|------|:---:|:---:|:---:|
| 小型 | <500 | 2核 | 2GB |
| 中型 | 500-1500 | 4核 | 4GB |
| 大型 | 1500+ | 4核+ | 8GB |

---

## 🛠 技术架构

| 层次 | 组件 | 版本 |
|------|------|:----:|
| 语言 | Java | 21 LTS |
| 框架 | Spring Boot | 3.4.5 |
| 安全 | Spring Security + JWT (jjwt) | 6.x / 0.12.6 |
| ORM | Spring Data JPA + Hibernate | 6.6 |
| API 文档 | springdoc-openapi | 2.8.5 |
| Excel | EasyExcel | 4.0.3 |
| 前端 | Vue 3 + Vite | 3.5 / 6.2 |
| UI | Element Plus | 2.14 |
| 动画 | 原生 CSS / Web Animations API | — |
| 状态管理 | Pinia | 4.0 |
| HTTP | Axios（动态 baseURL，支持反代前缀） | — |

```
┌──────────────────────────────────────────────────────────┐
│                       前端层                              │
│   管理员/教师端    │    班主任端    │     学生端          │
│        Vue 3 + Pinia + Vue Router + Axios + Element Plus │
└──────────────────────────┬───────────────────────────────┘
                           │ HTTP / JWT（相对路径 + 智能前缀推断）
┌──────────────────────────▼───────────────────────────────┐
│                     后端服务层                            │
│              Spring Boot 3.4 / Java 21                   │
│   Spring MVC │ Spring Security │ Spring Data JPA │ AOP   │
│   编排算法（规则模式 + Timefold/GA/LNS 优化） │ 赛程调度 │
│   排名积分 │ EasyExcel │ 热迁移                          │
└──────────────────────────┬───────────────────────────────┘
                           │
┌──────────────────────────▼───────────────────────────────┐
│   SQLite (默认)  │  H2 (开发)  │  MySQL 8.0 (生产)        │
└──────────────────────────────────────────────────────────┘
```

## 📜 项目脚本

| 脚本 | 平台 | 功能 |
|------|------|------|
| `build.ps1` | PowerShell | 一键编译：前端 Vite → 后端 Maven → jar 输出到根目录 |
| `start.ps1` | PowerShell | 自动检测终端编码 + 启动 `java -jar` |
| `start.bat` | CMD | 自动 `chcp 65001` + 启动 `java -jar` |

```powershell
.\build.ps1                 # 全量编译
.\build.ps1 -SkipFrontend   # 仅后端
.\build.ps1 -SkipBackend    # 仅前端
.\start.ps1                 # 启动（-Port 9090 自定义）
```

---

## 🔧 开发指南

### 手动编译

```bash
# 前端
cd sports-frontend && npm install && npx vite build

# 后端
cd sports-backend && .\mvnw.cmd clean package -Dmaven.test.skip=true

# 输出
copy sports-backend\target\sports-2.6.5.jar .
```

> ⚠️ 构建需 `-Dmaven.test.skip=true` 跳过测试编译（`src/test` 缺 `junit-platform-launcher`，既有问题）。

### 前端开发模式

```bash
cd sports-frontend
npm run dev
# → http://localhost:3000（代理到 localhost:8080）
```

### 反向代理兼容说明

- 前端 `vite.config.js` 构建时 `base: './'`（相对路径），`src/utils/base.js` 运行时按 URL 首段智能推断帽子前缀
- `vue-router` 使用 `createWebHistory(appBase())`，axios `baseURL` 使用 `apiBase()`，**所有 API 路径均动态拼接、严禁硬编码**
- 后端 `ProxyPrefixFilter`（过滤链最前）智能剥离帽子前缀，兼容保留/剥离两种转发形态

---

## ❓ FAQ

**Q1：中文乱码？**
JAR 已内置终端编码自动检测。Windows CMD 用户建议用 `start.bat`，PowerShell 用 `start.ps1`。

**Q2：如何重置数据？**
删除 `sports_meet.db`，重启自动重建并重新进入安装向导。

**Q3：班主任看不到报名入口？**
管理员需在"班级管理"中为班主任绑定班级（`bind-teacher`），然后班主任端才能看到本班数据。

**Q4：导入项目 Excel/CSV 模板有哪些列？**
项目管理支持 CSV 导入，模板表头为：`代码,项目,是否田径(是/否),道次(田赛写0),性别,年级组,是否团体(是/否),团体人数,项目内并发(人数),场地,最大用时(分),间隔(分),顺序号,并行捆绑组(同字母同批并行)`。各模块均可先下载导入模板（模板即最新格式，以模板为准）。

**Q5：班级名单导入后学生无法登录？**
导入时自动创建学生账号（用户名为学号，密码为学号），班主任端可查看。

**Q6：反向代理下 js/css 404？**
确认使用最新 jar（前端已改为相对路径构建）。若反代有缓存，清理缓存或确保 `index.html` 不被缓存（后端已强制 `no-store`）。

**Q7：`/api/schedule/**` 权限为何较宽？**
该路径在 SecurityConfig 中未配置专属角色规则，落入兜底 `anyRequest().authenticated()`，任何已登录角色可访问。如需收紧请补充角色规则。

**Q8：如何生成 Word 版秩序册？「自动生成秩序册」开关在哪？**
① 手动：报表中心 → 秩序册 → 生成秩序册 → `下载Word(.docx)`（也可 `POST /api/excel/order-book/generate` 落盘到 `data/order_book/`）。
② 自动：道次编排 → 预赛卡片头部打开「自动生成秩序册」，此后每次「生成预赛」成功即自动落盘（失败不影响编排）。开关存 `system_config: order_book.auto_generate`。

**Q9：号码簿「生成（补全空缺）」和「重排（覆盖）」有何区别？**
「生成」只给尚无号码的运动员按 年级→班级→名单 顺序补号、**不覆盖已有号码**（班级内从已有号码数 +1 起编，撞号自动顺延）；「重排」则**整体覆盖**、班级内从 1 连续重编。首次发号 / 补新导入名单建议用「生成」，想彻底统一号码序列用「重排」。

**Q10：「并发位数（并数）」怎么设？和田赛分组、并行捆绑组是什么关系？**
「并数」= 同一时刻能同时进行几个项目：**径赛设 1（串行，跑道独占）**，田赛按可用场地设 2~3（并行），**上限等于已录入的场地数量**（前端 `:max` 实时限制 + 保存时后端校验，超出即拦截）。它决定「能同时开几个项目」；「田赛分组」指定**哪几个田赛必须安排在同一时段并行**（同组占相同并发位，组内超出位数自动分波）；「并行捆绑组」是项目级更细的约束——**给多个项目填相同字母（A/B/C…）即强制它们同批并行**，优先级高于田赛分组，留空则自动编排。三者都可在项目 Excel/CSV 导入模板的「顺序号」「并行捆绑组」列批量设置。
「项目内并发」是另一层概念——**单个项目内同时进行的人数**（田赛工位数 / 径赛每组人数），影响该项目时长（`ceil(人数 / 并发) × 单轮用时`）。编排结果里的「未能在同一时段并行」告警，通常是时节段容量或田赛并数不足，先把并数调大或增加场地。

---

## 📄 开源协议

本项目基于 **GNU Affero General Public License v3.0 (AGPL-3.0)** 开源。详见 [LICENSE](./LICENSE)。

> 任何基于此项目的网络服务（含 SaaS 形式）分发，均需按 AGPL-3.0 向用户提供完整源代码。前端动画采用原生 CSS / Web Animations API 实现（已移除 GSAP，满足 AGPL-3.0 合规要求）。

---

> **版本**: v2.0.0 | **API 端点**: 19 Controller / 174 个 | **构建日期**: 2026-09-12
