# 🏃 运动会智能编排系统

> Sports Meet Intelligent Arrangement System v2.8.5

基于 **Spring Boot 3.4 + Vue 3 + Element Plus** 的全栈运动会管理系统。支持**超级管理员 / 体育老师 / 班主任 / 学生**多角色协作，覆盖**建站向导 → 班级名单导入 → 运动会报名 → 智能分组编排 → 赛程编排 → 成绩录入 → 排名积分 → 报表导出**全流程。

核心亮点：

- ⚙️ **零配置建站**：首次启动进入可视化安装向导（参考 WordPress / Discuz 体验），配置站点、数据库、管理员账号后即装即用
- 🔀 **数据库热迁移**：SQLite ↔ MySQL 在线切换，**全程无需重启服务**
- 🧮 **三级求解梯度 + AI 档**：规则模式（确定性规则引擎，毫秒级） ↔ 优化模式（Timefold 约束求解 + GA/LNS/MNSA/ALNS/Fix-and-Optimize 精修链），前端再可选 **AI 模式**（优化链之上叠加 ONNX 全本地推理：算法选择器 + 冲突簇 GNN + 推理时自对抗 + AI 派遣道次），一键三档切换，**向下完全兼容竞品规则、向上独占求解与 AI 能力**
- 🧠 **智能编排引擎**：贪心 + 局部优化算法自动分组分道，规则完全可配置
- 🤖 **AI 编排核心（ONNX 全本地推理）**：Python 训练 + ONNX 交付 + Java 推理，**生产环境不依赖 Python 解释器**。含算法选择器（硬解 / 取消路径）、冲突簇 GNN（16 维节点特征 + 带权邻接 + 4 层消息传递 + 跳跃连接）、**货真价实的 GAN**（生成器 G + 神经网络判别器 D minimax 对抗）、**推理时自对抗**（G 采样 → 精修器 → D 评判 → 多轮择优）、多步预测（Direct/Recursive/MIMO）、自步学习课程、**道次编排 AI**。模型随 jar 交付，缺失/异常自动回退规则编排
- 🏐 **球赛赛制生成**：循环赛（圆桌轮转，**连续主/客场 ≤ 2**）/ 淘汰赛（轮空 + 种子 + **同单位回避** + 真实双淘汰）/ 混合赛制（小组循环 → 交叉淘汰，同组出线队首轮必不相遇）/ **排球赛**，可适配为可排任务进时间槽编排
- 🔍 **可解性诊断（不可解冲突输出给程序）**：拆批 + 下界分析（容量缺口 / 最少天数 / 冲突图团 / 超大单元）+ 结构化 JSON 冲突报告与可执行建议（延长天数 / 加并发位 / 取消报名），**「排不下」不再是终点**
- 📊 **编排进度可见**：长链路编排走「异步提交 + 进度轮询」，前端展示阶段文案与百分比进度条，不再干等转圈
- 🌐 **反向代理 / 内网穿透友好**：前端采用 hash 路由（`/#/...`），服务器永远只收到 `/` 或 `/sportmg/`，**cpolar / ngrok 子域隧道、nginx 子路径帽子均开箱即用**，无需任何重写规则，彻底规避深链刷新白屏
- 📥 **多表导入可视化重解析**：一次选中多张表（一个工作簿多 Sheet，或一次多个 .xlsx）后，**逐表指定类型、逐列把 Excel 表头点选到目标字段、逐表勾选是否导入**，改完即按这份指定**重新解析**（不再沿用首次的自动猜测结果），并可展开**原始网格数据**逐格核对（列名 / 样例值 / 类型判定一目了然），确认无误再落库
- 📊 **全流程 Excel 化**：名单 / 项目 / 报名 / 成绩 全部支持模板导入导出，秩序册 / 成绩册 / 报表一键生成
- 📄 **真实 Word 秩序册**：原生 OOXML（手写 ZIP 包组装，**零 Apache POI 依赖、离线可构建**）生成含封面 / 目录 / 多章表格的 `.docx`，支持一键下载与按开关自动落盘
- 📑 **秩序册设计器**：自定义目录（多级章节、启停、上下移、加二级、软删可恢复）+ 章节细则（自由文字，或挂载 SCHEDULE / EVENTS / CLASSES / ARRANGE / NUMBERS 系统板块），**右侧即时 Word 在线预览**与一键 `.docx` 下载，**预览与导出同一份渲染**——改什么立刻看见什么
- 🔢 **号码簿双模式**：模板 / 正则自定义之外，支持**按名单顺序**「补全生成（不覆盖）/ 覆盖重排」两种操作，撞号自动顺延不中断
- ⏱ **1~n 并发位编排**：径赛 / 田赛各自可设「并数」（**1 = 串行，n = 并行，并数上限取决于场地数量**），项目内可设并发人数（田赛 X 人同时试跳/试掷）；支持**自定义项目顺序**（Excel 导入带「顺序号」列）、**田赛分组同期**，以及**项目级并行捆绑组**（填相同字母 A/B/C 的田赛自动同批并行，优先于配置分组）

---

## 目录

- [快速开始](#-快速开始)
- [默认账号](#-默认账号)
- [功能总览（按角色）](#-功能总览按角色)
- [功能详解](#-功能详解)（§1–§22：建站向导 → 多表导入重解析 / 秩序册设计器 / 场地管理 / 审计日志 / 实时协作 / 自定义项目区）
- [API 接口完整参考](#-api-接口完整参考)（**独立编号 §1–§26**：认证 / 班级 / 运动员 / 项目 / 报名 / 班主任端 / 智能编排 / 赛程编排 / 成绩 / 排名 / 统计 / 学生端 / 系统设置 / 用户与裁判管理 / Excel / 备份 / 迁移 / 建站向导 / 入场式评分 / 届运动会 / 行政时间保护 / 通知 / 审计日志 / 场地管理 / 自定义项目 / 实时协作 / 秩序册 / 多表重解析）
- [数据库设计](#-数据库设计)
- [部署指南](#-部署指南)
- [AI 编排核心](#-ai-编排核心)（训练侧 `sports-ai/` / ONNX 模型契约 / GAN 自对抗 / 可解性诊断 / 球赛赛制 / 道次 AI）
- [技术架构](#-技术架构)
- [项目脚本](#-项目脚本)
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
java -jar sports-2.8.5.jar
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
| 裁判 | 无预置账号 | — | 由管理员在「裁判管理」页开通（用户名默认取手机号，初始密码 `123456`） |

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
| 编排规则 | 软约束开关 + 算法参数（尝试次数/超时/优化轮数）；新增「设为无限轮」「设为不限时」一键按钮（轮数/优化轮数/时间限制可设为 0=无限） |
| 积分规则 | 名次积分表、并列处理、破纪录加分、参与分、接力倍数、团体总分口径 |
| 数据库迁移 | SQLite ↔ MySQL 在线热迁移（连接测试、异步迁移、进度查询），**无需重启** |
| 数据库备份 | 手动/自动备份、备份列表、下载、删除 |
| 健康检查 | 系统运行状态详情、操作日志查看 |
| 应用运行配置 | 服务端口、绑定地址（重启生效） |
| 场地管理 | 场地录入（名称 + 编码 + 类型 + 并行上限 `parallelMax`）、启用 / 禁用、删除；并行上限决定赛程「并数」上限 |
| 审计日志 | 操作日志查看（编排自动 / 保存 / 清空、报名审核、成绩录入、裁判调整等关键操作的审计留痕） |

### 体育老师（TEACHER）

| 页面 | 功能 |
|------|------|
| 首页 | **分步工作流（带序号一步一步：配置 → 录入 → 编排 → 统计；每步标注类型并为「录入」步骤提供入口，且有「建议当前步骤」高亮，按待办自动定位）**、待办事项、今日赛程、报名进度、关键统计、其他入口 |
| 班级管理 | 班级列表、展开查看学生、Excel 导入导出、批量创建、绑定班主任 |
| 运动员管理 | 多维筛选（年级/班级/关键词）、号码簿自动生成、批量导入导出；**支持入学/毕业年份录入，列表展示当前届年级、年份码、校验号与毕业标记** |
| 项目管理 | 预设模板、Excel/CSV 导入、启用/禁用、道数/预赛/计分配置 |
| 报名管理 | 报名列表、单个/批量审核（通过/拒绝）、报名统计、导出 |
| 智能编排 | 自动分组分道（贪心+优化）、预览、批量编排、手动调整、回滚、道次表导出 |
| 项目编排 | 赛程自动调度（天×时段×场地，1~n 并发位）、自定义项目顺序、田赛分组同期、手动调整、赛程导出 |
| 成绩管理 | 录入/修改/删除、Excel 导入、自动排名计算；新增「批量导入全部项目成绩」（始终可用，一次导入多 Sheet） |
| 排名积分 | 单项目排名、个人积分、团体总分、破纪录榜，三类均可导出 |
| 统计报表 | 秩序册（**Excel / Word 双形态**）/ 成绩册 / 统计报表（报名统计、道次表、成绩汇总、团体总分榜） |
| 跨届进步榜 | 对比最近两届同名次进步，支持「指定项目」/「全部项目汇总」两种口径 |
| 届 / 运动会 | 创建多届「第X届Y季节运动会」、一键切换当前届、编辑/删除；成绩/报名/入场式均绑定届次 |
| 系统设置 | 基本设置、积分规则、年级设置等 |
| 行政时间保护 | 「规避时间」配置（全校统一避让 / 班主任个人时段 / 裁判个人时段），编排与裁判编排自动避让；站内信通知中心（未读角标 + 已读） |
| 秩序册设计 | 侧栏「⑥ 秩序册」：目录树（新增 / 重命名 / 启停 / 上下移 / 加二级 / 软删后可恢复）、章节细则（自由文字或系统板块）、Word 在线预览 + `.docx` 下载 |

### 班主任（CLASS_TEACHER）

| 页面 | 功能 |
|------|------|
| 首页 | **分步工作流（① 录入班级名单 → ② 报名运动会项目 → ③ 查看本班赛程 → ④ 查看成绩获奖；已完成步骤打勾、自动高亮「建议当前步骤」）**、本班统计、最近报名、本班赛程 |
| 班级名单 | Excel 导入全班花名册（自动创建学生账号+运动员）、手动添加、模板下载 |
| 运动会报名 | 学号定位 → 项目卡片报名、统计仪表、未报名名单、报名表导出 |
| 赛程查看 | 本班运动员的组次、道次、时间 |
| 成绩查看 | 本班成绩 + 总分/金银铜汇总 |
| 通知中心 | 站内信（取消项目 / 冲突消解等通知），未读角标提示 |

### 裁判（REFEREE · 可登录）

裁判既是**被编排的人力资源**（由智能编排按项目「组次裁判数量」自动分配，专长优先 + 负载均衡），**也可以拥有登录账号**（角色 `ROLE_REFEREE`）自行登录查看本人执裁安排。裁判花名册与账号通过 `referee.user_id` 关联。

| 页面 | 功能 |
|------|------|
| 裁判管理（`/teacher/referees`，**仅 SA 可见**，入口在教师端菜单） | 花名册增删改查、Excel 批量导入（专长 `[a,b，c]`）、模板下载、**单个/批量开通登录账号**（账号列显示「已开通/未开通」） |
| 裁判工作安排（`/teacher/referee-board`，T/SA） | 按裁判聚合「项目/年级/性别/赛次/组次」分配，含未分配裁判 |
| **裁判工作台（`/referee/dashboard`，独立布局）** | 裁判登录后进入（琥珀色系）；**裁判本人看到「我的执裁安排」**（`GET /api/referee/me`），管理员/体育老师则看到全体裁判安排（可投屏） |
| **通知中心** | 站内信铃铛（受保护时段跳过执裁等通知），未读角标实时提示 |

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
- **仅已报名（默认开启）**：列表默认只显示已审核报名的运动员；关闭后显示全名单（「仅已报名」开关）
- 号码簿自动生成（规则可在系统设置自定义）
- 批量导入/导出

### 6. 项目管理

- **预设模板**：跑步类（100m~1500m）、跳跃类（跳高/跳远）、投掷类（铅球/实心球）、接力类（4×100m）
- **Excel/CSV 导入**：批量导入项目，模板含表头+示例行
- **支持字段**：项目名称、代码、类别（径赛/田赛）、性别限制、道数、预赛开关、计分方式、纪录、**组次裁判数量**（`refereesPerGroup`）
- **组次裁判数量**：每个组次（heat/组/轮）需安排的裁判人数。田赛如立定跳远一组 5 人填 x 名裁判即填 x；拔河一组 3 人填 3；N 组并行仍按单组填写，系统自动为每组分别安排；留空/0 表示不安排裁判。该值驱动「智能编排」自动分配裁判（见 §8、§7.1）
- 启用/禁用开关、自定义新增/编辑/删除、**批量修改**（含组次裁判数量等调度字段一键套用到勾选项目）
- **表格补齐可见列**：项目管理表格现默认展示「性别 / 年级组 / 最大报名人数」三列（此前隐藏，仅导出可见）

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
| L1 规则模式 | `com.sports.schedule.rule`：`SnakeGrouping`（蛇形分组）+ `FixedLaneAssignment`（固定分道）+ `RuleBasedScheduler`（确定性 first-fit 时间编排） | **毫秒级** | 完全确定（同输入必同输出）、参数透明可解释、可穷举验证——传统电子化表单工具的能力边界 |
| L2 启发式 | 贪心 + 冲突感知放置 + 匈牙利精确分道（`HungarianAssignment`） | 秒级 | 兜底与快速通道 |
| L3 优化模式 | Timefold 约束求解 + 遗传算法（GA）+ 大邻域搜索（LNS）+ 算法组合调度 | 秒级（可配预算） | 全局权衡兼项冲突/场地利用率/时长保真，带理论下界 gap 评估 |
| **L4 AI 模式**（新增第三档「编排模式」） | 优化链之上叠加 **ONNX 全本地推理**：算法选择器判「硬解 / 取消」、冲突簇 GNN 定着色优先级、**推理时自对抗**（G 采样 → 精修器 → D 评判 → 多轮择优）、**AI 派遣款型**排布道次、AI 可解性诊断 | **分钟级**（默认 `aiAdversarialRounds=3` 自对抗轮数，叠加优化链与多轮前向推理；**普通办公机 / 默认配置按分钟计**，仅在高性能服务器 + 调低自对抗轮数或小规模场景下才回落为秒级） | 不换引擎只加 AI：跑完优化链再跑一遍生成对抗并结构化回显；模型缺失自动降级为优化模式，**绝不编排失败** |

**前端切换**：教师「项目编排」页顶部提供「规则模式 / 优化模式 / **AI 模式**」三档单选按钮（选择记忆于 `localStorage`，AI 档为紫红渐变以区分），主按钮随档位变文案（按规则编排 / 一键编排赛程 / **AI 智能编排**）。**所有编排入口都带 AI 档**：赛程编排页（同步 + 异步）、赛程页「再次排道」（沿用当前模式的 `styleRule`）、道次编排页工具条「AI 模式」按钮（切到 AI 派遣款型并直接打开编排弹窗）、道次页分组款型旁「AI 模式」小按钮（可单独切换，不弹窗）。
**API 切换**：`POST /api/schedule/auto`，请求体 `mode` 字段——`"rule"` = 规则模式，`"optimize"` = 优化模式，`"ai"` = AI 模式（缺省仍是 `optimize`，完全向后兼容）。可选参数：规则参数 `ruleLanePolicy`（`registration`/`performance`）、`ruleAdvanceCount`（晋级人数）、`ruleConflictBufferMinutes`（兼项缓冲）、`ruleConflictCheckEnabled`；AI 参数 `aiAdversarialRounds`（推理时自对抗轮数，默认 `3`，`0` 关闭自对抗只保留 AI 派遣）、`aiLaneStyle`（AI 派遣款型，默认 `"ai"`）。
**道次入口**：`POST /api/arrange/events/{eventId}/rearrange` body 增 `styleRule`（`'ai'` = 按模型派遣优先级重排，省略 = 既有口径）。

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

**编排模式切换（规则 / 优化 / AI）** 🎚️：工具栏「规则模式 / 优化模式 / **AI 模式**」三档单选按钮（AI 档紫红渐变区分）——
- **规则模式**：确定性 first-fit（项目顺序 → 时间栅格 → 场地槽位），毫秒级、完全可复现、结果透明；响应含 `algorithmPortfolio.rule {placed, unplaced, residualConflicts, elapsedMillis, passesRun, winningStrategy, unlimitedMode, converged, ...}` 观测信息（其中 `unlimitedMode`/`converged`/`passesRun` 对应「兼项冲突规避轮数」填 0 时的无限收敛行为，详见下方配置表该行）
- **优化模式**（默认）：Timefold 求解 + GA 进化 + LNS 精修 + **MNSA 多邻域退火 + ALNS 自适应大邻域搜索 + Fix-and-Optimize 冲突切片精确修复**（精修链逐级接力，每层只接受更优），权衡兼项冲突 / 场地利用率 / 压缩保真，附理论下界 gap
- **AI 模式**（新增）：完整跑一遍优化链，**再跑一遍 ONNX 推理时自对抗**（G 采样 → 精修器精修 → D 评判 → 多轮择优，择优准则「残余冲突优先、并列看 D 分」，并把单次生成结果一并作为候选池保证不劣化），同时全链路使用 **AI 派遣款型**排道次。结果页如实回显 `aiReport` / `algorithmPortfolio.aiReport`：
  `laneStyle` / `rounds` / `adversarial`（`enabled`｜`unavailable`｜`skipped`｜`error`） / `dScore`（判别器评分） / `conflict`（候选残余冲突） / `conflictBefore`（单次生成基线） / `improved`（是否优于单次生成） / `note`（模型缺失会写明「已降级为优化模式」，**绝不静默**）
- 三模式共用同一套并发位模型、自检（`/api/schedule/verify`）、下界评估与冲突检测；切换零副作用，随时可换回

| 概念 | 配置项 | 说明 |
|------|--------|------|
| 并发位数（并数） | `trackSlots` / `fieldSlots` | 同一时刻可同时进行几个项目（**1 = 串行**，n = 并行）。**并数上限取决于场地数量**——径赛最多占 1 个主场地、田赛最多占「田赛场地数」个，前端 `:max` 实时限制、保存时后端二次校验，超出即拦截 |
| 项目内并发 | `event.concurrency` | 单个项目内同时进行的人数（田赛工位数 / 径赛每组人数），决定时长 = `ceil(参赛数 / 并发) × 单轮用时`（径赛 `heatMinutes`/轮、田赛 `fieldPerAthleteMinutes`/轮），并受 `maxDurationMinutes` 封顶 |
| 自定义项目顺序 | `eventOrder` / `event.sortOrder` | 编排按 `eventOrder`（eventId 有序列表，田赛 + 径赛混排）进行，未列入的项目按 `sortOrder`（Excel「顺序号」列可批量导入）稳定追加。UI 支持置顶/上移/下移/置底与「按排序号重置」 |
| 田赛分组 | `fieldGroups` | `[{name, eventIds[]}]`：**同一组的田赛项目安排在同一时段并行进行**（组内项目数受 `fieldSlots` 约束，超出自动分波并提示） |
| 并行捆绑组 | `event.bundleGroup` | 项目级字母分组（如 `A` / `B` / `C`）：**填相同字母的田赛自动安排在同一时段并行**，优先级高于「田赛分组」配置；留空则由算法自动安排。**支持 Excel 导入**（「并行捆绑组」列） |
| 兼项冲突规避轮数 | `conflictAvoidancePasses` | 自动编排时为压低运动员「兼项赶场」冲突而进行的多策略重试轮数。**填 `0` = 无限轮**：逐趟换排序策略（原始序 → 按报名人数降序 → 按兼项度降序 → 确定性洗牌）重试，直到连续 **16 趟无改进**（判定已收敛到兼项最低）或触达 **150 趟硬上限**；非零值夹在 **1–64** 之间。返回 `algorithmPortfolio` 会带上实际 `passesRun` / `winningStrategy` / `unlimitedMode` / `converged`，前端可直观看到跑的是哪套策略、是否收敛 |
| 自动推算天数 | `autoDays` | 运动会日程可不写死天数：开启后（或 `days≤0`），系统按「每天时段总容量 × 并发位」反推需要排多少天（首日时段作模板复制），结果返回 `estimatedDays`。前端「运动会日程配置」提供「自动推算」开关 |

- 并发位与场地对应：径赛用第 1 个场地；田赛的 n 个并发位依次占用其余场地，场地不足时复用同一场地并给出 warning
- 场地录入：每个场地含**名称 + 编码**（如「田赛A区 / `FIELD_A`」），编码用于标识与展示；第 1 个场地为径赛主场地，其余供田赛并行，**并数上限取决于场地数量**，请先录全场地
- 旧配置平滑迁移：原 `trackMode`/`fieldMode`（serial/parallel）按 **serial→1、parallel→2** 自动换算为并发位数，历史配置不丢失
- 手动调整单项安排、导出赛程 Excel
- **分批间隙大间隔 🛌**：按「项目内并发」正常分批后，若两批之间当天仍有空闲时段，算法在「冲突数、时段都相同」的前提下**优先拉开间隔**（让运动员休息更足）；仅当同天确有空档时才后移安排，**绝不凭空膨胀赛程**（无空档回退最早可行点）。间隔取舍严格排在「冲突数 > 时段」之后——绝不为拉开间隔而引入兼项冲突

> 💡 若编排结果出现「未能在同一时段并行」告警，通常是该时段容量或并发位数不足——提高「田赛并发位数」或增加场地即可。

#### 9.1 空时间限制（自动推算天数）⏱

「运动会日程配置」中比赛天数可开启「自动推算」（`autoDays`）。开启后系统不再按固定天数铺排，而是：

1. 先按报名+项目参数算出每个单元的「原始时长 + 间隔」；
2. 除以「**每天时段总容量（各时段分钟数之和）× 并发位**」，向上取整得出需要排多少天；
3. 以**首日时段为模板**复制出对应天数（每天时段结构一致），结果随编排响应返回 `estimatedDays`。

> 该逻辑为纯函数 `DaysEstimator`，不触碰求解器；显式指定 `days` 仍优先，二者互斥时 `autoDays=true` 或 `days≤0` 触发自动推算。

#### 9.2 兼项高频统计（设定项目顺序的依据）📊

编排前先看「哪些项目常被同一运动员同时报名」，能提前预判兼项冲突、辅助设定 `eventOrder`。`GET /api/arrange/co-occurrence` 从**已审核报名**构建「项目×项目共现矩阵」，返回：

- `pairs`：高频共现项目排行（共现人次降序），如「100米 × 200米」被同一批人反复同时报；
- `eventHeat`：每项目的兼项热度（被多少不同运动员兼报了其它项目）。

#### 9.3 行政时间保护（规避时间）🚧

部分时段因行政原因（闭幕式、全校大会、某老师/裁判临时 unavailable）需要主动避让。新增「行政时间保护」实体（`entity/protection/AdminTimeProtection`），按 `targetType` 分三类：

| `targetType` | 含义 | 编排接入方式 |
|------|------|------|
| `GLOBAL` | 全校统一避让时段 | **切分时间窗**（硬约束）：该段整段时间不可排任何项目，solver 与贪心都用切分后的窗口，零侵入放置逻辑 |
| `TEACHER` | 某班主任个人不可用时段 | **按项目黑名单**：其班级参与的项目在受保护时段被阻挡（穿透 `placeOne/placeBatch/applySolved/findBestSlot` 的 `blockedIntervals` 参数，`day=-1` 表示全天） |
| `REFEREE` | 某裁判个人不可用时段 | **执裁过滤**：`assignReferees` 前 `blockedReferees()` 排除落在赛程时间窗内受保护的裁判 |

配置入口 `GET/POST/PUT/DELETE /api/protections`，教师端「行政时间保护」管理页可用。裁判既是约束对象，也是保护需求者。

#### 9.4 兼项冲突·取消某人某项目 ✂️

当兼项冲突无法硬解时，提供「取消」路径（区别于 §9 的「继续硬解 / 无限轮消解」）：

1. **统计**：`GET /api/arrange/conflicts/statistics` 汇总「哪些项目互相撞车」（事件对列表 + 涉及运动员数），先看清冲突结构；
2. **取消**：`POST /api/arrange/conflicts/cancel`（Body `items[]` + `notifyTeacher` 开关）——对选定「运动员×项目」退报名（`registration` 置 withdrawn）+ **同步移出编排/道次**（杜绝残留）+ 重算冲突；
3. **分流班主任**：`notifyTeacher=true` 时生成**内通知**（站内信定向到对应班主任）；`GET /api/arrange/conflicts/class-export` 生成**外通知**——按班级汇总的冲突/取消统计表 Excel，转交班主任。

#### 9.5 通知中心（站内信）🔔

「内通知」通道：`entity/notification/Notification`（定向用户 / 角色广播 + 已读标记），接口 `GET /api/notifications`（`unread-count` / `{id}/read` / `read-all`）。教师 / 班主任 / 裁判三端布局顶部均挂载 `NotificationBell` 铃铛组件，未读角标实时提示，点击展开可逐条查看或一键全部已读。取消项目、冲突消解、裁判被避让等事件均可触发站内信。

> 外通知（Excel 统计表）见 §9.4 的 `class-export`，与内通知互补，满足「通知班主任」的两种落地形态。

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
| **兼项运动员统计** ⭐ | **兼项人数分布（1项/N项）+ 项目对共现排行 + 兼项运动员明细 + Excel 导出**（口径见 [11.1](#111-兼项运动员自动统计)） |

#### 11.1 兼项运动员自动统计 ⭐

同一名运动员报名多个项目即「兼项」，它是编排冲突的总源头。系统在**教师端「项目编排」页兼项卡片**中**自动统计**（页面挂载即加载，编排成功后自动刷新，无需点击按钮）：

- **兼项人数分布**：`[1项, 2项, 3项, 4项, …]` 直方图（前端为彩色分布条，一眼看出「几项兼项的人最多」）；
- **项目对共现排行**：哪两个项目同时报的人最多（兼项冲突最密集的组合，是优先处理对象）；
- **兼项运动员明细表**：姓名、号码布、性别、班级、年级、**兼项数量**、**兼项项目对**、**全部报名项目**，按兼项数量降序；
- **一键导出**：导出为 Excel（EasyExcel），可直接发班主任或裁判组。

口径说明（重要）：

1. 统计对象为**已审核（`approved`）的报名记录**，未审核报名不参与统计，但会写入 `warnings[]` 提示「N 条未审核报名未计入」；
2. 「兼项数量」= 该运动员已审核报名项目数，`distribution[i]` 表示**报了 i+1 个项目**的人数，所有档位合计 = 全部参与统计的运动员数（不是兼项人数）；
3. `pairs` 按「项目对」去重计数（A×B 与 B×A 同一对），所以一个报了 4 项的人最多贡献 C(4,2)=6 对；
4. 与「兼项冲突检测（`/conflicts`）」是**两个维度**：冲突检测看**已落库编排**里的真实撞车，兼项统计看**报名层**的高频兼项组合——前者用于编排，后者用于排兵布阵与决策谁该退项。

接口：

| 方法 | 端点 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/arrange/co-occurrence` | T/SA | 兼项统计（含明细表，见上表） |
| GET | `/api/arrange/co-occurrence/export` | T/SA | 兼项运动员明细导出（Excel） |

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

两处统计此前存在「分母为 0 / 同名项目互相覆盖」问题，自早期版本 v2.0.0 起已统一口径如下：

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

### 16. 场地管理（Venue）

场地是编排引擎「并行上限」的数据来源：每个场地的 `parallelMax` = 该场地同一时刻可并行进行的项目数（**1 = 串行，n = 并行**），项目通过 `defaultVenueCode` 绑定到具体场地后受该上限约束。径赛主场地 + 田赛场地数共同决定「赛程并数」上限（详见 [9. 项目编排](#9-项目编排赛程编排)）。

| 操作 | 说明 |
|------|------|
| 录入场地 | 每个场地含**名称 + 编码**（`主跑道 / TRACK-01`、`跳远区A / FIELD-A`），编码唯一、与项目 `defaultVenueCode` 对应 |
| 启用 / 禁用 | `enabled` 控制是否参与编排；禁用场地不占并发位 |
| 并行上限 | `parallelMax` 即该场地并发位数；编排时由后端二次校验，超出即拦截 |

> 场地 CRUD 接口见 [§24 场地管理](#24-场地管理venues)。

### 17. 审计日志（Audit）

关键写操作（编排自动 / 保存 / 清空、报名审核、成绩录入、裁判调整等）均经 `AuditService` 落审计日志，记录操作类型、操作人、时间。管理员可在 **系统设置 → 健康检查 → 操作日志** 查看，或通过接口按动作筛选：

```
GET /api/audit/logs?action=SCHEDULE_AUTO&limit=100
```

详见 [§23 审计日志](#23-审计日志audit)。

### 18. 实时协作（Collaboration）

多端同时打开编排页时，系统通过**轻量轮询**暴露「赛程版本号 + 增量事件流」，避免两人改同一份赛程互相覆盖：

- `GET /api/collaboration/version` → 当前全局版本号（前端初次握手）；
- `GET /api/collaboration/events?since=<版本号>` → 拉取 `since` 之后的增量事件（最近 200 条环）；前端每隔几秒轮询，一旦拿到他人改动即提示「赛程已被 XXX 更新」并刷新视图，**冲突在保存前暴露**（非 WebSocket / SSE，纯轮询，部署零额外配置）。

### 19. 自定义项目区（Custom Project）

入场式 / 队列等「非竞赛类」展示项目可走自定义项目区维护（独立于常规 `event` 竞赛项目，不进入成绩排名）：`GET /api/custom-project` 列表、`POST /api/custom-project` 保存（含 `code / name / type / sortOrder / countInTotal`）、`DELETE /api/custom-project/{id}` 删除。详见 [§25 自定义项目](#25-自定义项目custom-project)。

### 20. 在线说明书（Help）📖

系统内置的**网页版说明书**（顶栏「📖 说明书」入口），无需另外打开文档站点，随时可查：

- **16 章完整手册**：建站向导 → 班级名单 → 项目报名 → 智能编排（规则/优化/AI 三档）→ 赛程编排 → 兼项统计 → 成绩排名 → 统计报表 → 数据库迁移 → 秩序册 → 号码簿 → 报名进度 → 场地管理 → 审计日志 → 实时协作 → 自定义项目，逐章标注「谁需要用」「在哪点按钮」；
- **搜索直达**：说明书顶部搜索框，输入关键词（如「兼项」「AI 模式」「导出」）即时过滤章节， Enter 跳到第一节命中；
- **打印/存 PDF**：一键调用浏览器打印（右侧「🖨 打印」按钮），可另存为 PDF 发给同事；
- **目录自动高亮**：左侧目录随滚动自动定位当前章节，点击可跳转；
- **FAQ 速查**：按「编排失败怎么办 / 数据存哪 / 换电脑 / 多角色权限 / 打印」等高频问题分组，直给操作步骤。

> 💡 说明书与本项目 README 同口径同步更新——每次功能变更（如本次兼项自动统计、AI 编排模式）都会同步补写对应章节。

### 21. 多表导入：按表指定后重新解析（可视化列映射 + 网格预览）

教师端「① 导入报名 → 多表导入」（`/teacher/bulk-import`）在完成首轮「选择文件 → 解析表结构」之后，
**不是直接开导，而是把解析结果摊开给你审**，审完还能改：

1. **逐表指定类型**：每张探测出来的表一个卡片，下拉选它到底是哪一类（`grade / class / roster / eventsimple / signup / event / score …`）。
   表头认错了就在这里改，改完这一张立即重解析。
2. **逐列映射字段**：卡片内是「Excel 表头 → 目标字段」的逐列下拉（如「姓名」→ `name`、「学号」→ `studentId`）。
   列建议按「**该类型处理器真正读取的字段的别名**」排序，而不是把整张表的别名摊平后瞎猜——
   所以运动项目表的「项目名称」稳定落在 `name`、「项目类型」落在 `category`，不会被 `eventCode` 的短别名「项目」抢走。
3. **逐表导入开关**：不想导的那张表直接取消勾选（例如模板里留了示例「成绩表」，此时还没成绩，勾了必然整行失败）。
4. **按指定重新解析**：`POST /api/excel/multi/reparse` 只带你这版指定（哪几张表、什么类型、什么列映射、导不导），
   **按这份口径重跑解析并刷新预览**，不再沿用系统首次的猜测结果。
5. **可视化网格核对**：`POST /api/excel/multi/sheet-data` 按「文件序号 + Sheet 序号」分页取回**原始单元格**，
   前端以网格形式逐格展示（含表头与样例值），导入前肉眼最后过一遍。
6. **表头识别口径**（判定顺序：**人工指定 → 表头指纹（≥2 列命中该类型字段）→ 填写说明页 `notice` → Sheet 名关键词**）。
   表头永远比 Sheet 名可靠，所以即使把项目表另存成名为「填写说明」的 Sheet，也会按表头正确判成项目表。

   - **补全的录入字段**：运动项目表（精简版）的「性别 / 最大报名人数」；项目表（表格2）的
     「是否田径 / 道次 / 顺序号 / 每组次几人 / 捆绑字母 / 并行数 / 场地 / 场地编码 / 年级组 /
     是否团体 / 团体人数 / 最大用时(分) / 间隔(分) / 组次裁判数量 / 抽签(是/否) / 最大报名人数」。
     这些列此前在预览里显示成「未识别列」、导入时被处理器**静默丢弃**（表头认得、数据进不去，且全程不报错），
     现已逐列补齐落库；`processEventRow` 与 `ExcelColumnMapping` 的别名表一一对应，改一列要同步改两处。
     注意「场地」（场地名称，如「田径场」）与「场地编码」（如 `TRACK`）是两列，不会互相串位。
   - **填写说明页**：模板自带的「填写说明」页判为 `notice`，说明栏直接给出
     「填写说明 / 注释页，不参与导入（无业务数据，已自动跳过）」，默认不勾选，也不计入顶部「已识别类型」统计。

> 设计取舍：**「重新解析」与「最终导入」是两步**。自动识别给的是**候选**，你改完指定后以你的为准；
> 网格预览读的是**原始单元格**，和真正入库走的是同一份数据源，所以「预览里看到的」就是「导入进去的」。

---

### 22. 秩序册设计器（自定义目录 + 细则 + Word 在线预览）

侧栏「⑥ 秩序册」（`/teacher/order-book`）把秩序册从「一键生成、改不了」变成**可编排的文档**：

**① 目录树**（`OrderBookSection`）

| 能力 | 说明 |
|------|------|
| 多级目录 | 一级目录下可继续「加二级」，缩进式展示，排序即生成顺序 |
| 启停 | 每个目录一个开关，关掉不进文档也不进预览 |
| 上下移 | 同父内上移 / 下移（顶部 / 底部两个方向），跨级拖动不改归属 |
| 软删可恢复 | 删除是软删（`deleted_at`），删完可在下拉里「恢复」回来，**误点一下不会丢整章** |
| 默认章节 | 首次打开自动铺「竞赛日程 / 竞赛项目 / 班级名单 / 编排对阵 / 号码簿」五个系统级目录（`kind=SYSTEM`），可改标题、可停用 |

**② 章节细则**（`OrderBookEntry`）

- **自由文字**（`contentType=TEXT`）：正文写在 textarea，空行分段成段落，直接进文档；适合「比赛须知」「注意事项」「领导讲话」。
- **系统板块**（`contentType=TABLE`，`sourceKey`）：挂载现成数据表格，下拉选项与后端白名单严格同集
  （`SCHEDULE` 赛程 / `EVENTS` 项目 / `CLASSES` 班级名单 / `ARRANGE` 编排对阵 / `NUMBERS` 号码簿）。
  选一个非法值会被后端归一化成 `NULL` 并输出「数据源尚未实现」占位，**不会静默丢章**。

**③ Word 在线预览 + 导出**

预览走 `GET /api/order-book/preview`（`text/html`），前端把 HTML 塞进 iframe `srcdoc` 渲染（iframe 子请求带不上 token，不能用 `src` 直连）。
导出走 `GET /api/order-book/preview/docx`。**两者同源**：文档模型 `OrderBookDoc`（`DocBlock` 块序列）
由 `OrderBookDocumentBuilder` 一次构建，HTML 渲染器与 OOXML 渲染器各自消费同一份模型，
所以「预览里长什么样，导出就是什么样」，不会出现两边数据打架。

**④ 权限**

`/api/order-book/**` 的写操作（目录增删、细则编排、Word 导出）限 **体育老师 / 超级管理员**；
只读预览（学生 / 班主任 / 老师 / 超管）放开——此前只落到 `anyRequest()`，**任何登录账号都能改秩序册**，已一并收紧。

---

## 📡 API 接口完整参考


后端共 **26 个 Controller、220+ 个路由端点**（下表为主要业务端点，含「届 / 运动会」「行政时间保护」「通知」「审计日志」「场地管理」「自定义项目」「实时协作」控制器），统一前缀 `/api`。反向代理子路径部署时（如 `/sportmg/`），前端请求 `/sportmg/api/...` 由后端智能剥离前缀后路由到下列端点。

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

前缀 `/api/athletes`，10 个端点。

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| GET | `/api/athletes` | Query page=1, size=20, grade?, classId?, keyword?, **registeredOnly?** | CT/T/SA | 分页查询运动员（**`registeredOnly=true` 仅返回已审核报名者**） |
| GET | `/api/athletes/ids` | Query grade?, classId?, **registeredOnly?** | CT/T/SA | 批量取运动员 id（支持 `registeredOnly` 过滤，供前端批量操作） |
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
| **POST** | **`/api/arrange/events/{eventId}/rearrange`** | Body `{grade, gender, round}`（auto→null） | T/SA | **再次排道**：按报名实际性别逐组调 arrange 重新编排（成绩录入后调整道次/分组） |
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
| **GET** | **`/api/arrange/co-occurrence`** | — | T/SA | **兼项统计（自动）**：`total` 运动员数 / `multiEventCount` 兼项人数 / `distribution`（1项…N项分布）/ `pairs`（项目对共现排行）/ `athletes[]`（**兼项运动员明细**：姓名、号码布、性别、班级、年级、兼项数量、项目对、报名项目）/ 可解性相关信息。教师端页面**打开即自动加载**，无按钮点击 |
| **GET** | **`/api/arrange/co-occurrence/export`** | — | T/SA | **兼项运动员明细导出**（EasyExcel：序号/姓名/号码布/性别/班级/年级/兼项数量/兼项项目对/全部报名项目） |
| **GET** | **`/api/arrange/conflicts/statistics`** | — | T/SA | **冲突统计**：汇总「哪些项目互相撞车」（事件对 + 涉及运动员数），供取消决策 |
| **POST** | **`/api/arrange/conflicts/cancel`** | Body `{items:[{athleteId,eventId}], notifyTeacher:bool}` | T/SA | **取消某人某项目**：退报名 + 同步移出编排 + 重算冲突；`notifyTeacher=true` 触发站内信通知班主任 |
| **GET** | **`/api/arrange/conflicts/class-export`** | — | T/SA | **外通知**：按班级汇总的冲突/取消统计表 Excel，转交班主任 |

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
| POST | `/api/schedule/auto` | Body config?（可覆盖 trackSlots/fieldSlots/eventOrder/fieldGroups 等，不落库）；**`mode`**：`"rule"` 规则模式 / `"optimize"` 优化模式 / `"ai"` AI 模式 / 缺省 `"optimize"` 优化模式；规则模式可选 `ruleLanePolicy` / `ruleAdvanceCount` / `ruleConflictBufferMinutes` / `ruleConflictCheckEnabled`；AI 模式可选 `aiAdversarialRounds` / `aiLaneStyle` | 已认证 | 按「并发位」模型自动编排赛程（三模式详见 [9. 项目编排（赛程编排）](#9-项目编排赛程编排)）；响应含 `mode`（实际使用的模式）、`aiReport`（AI 模式自对抗汇总）与 `algorithmPortfolio.rule` / `algorithmPortfolio.aiReport`（观测信息） |
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
**与编排的关系**：裁判经「组次裁判数量」在智能编排中按组次自动分配，详见功能详解「8. 智能编排 ⭐ 核心」一节。

---

### 15. Excel 导入导出 Excel

前缀 `/api/excel`，15 个端点。

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| GET | `/api/excel/template/{type}` | Path type | 公开 | 下载指定类型模板（`multiworkbook` = 多表模板） |
| POST | `/api/excel/preview` | multipart `file` | T/SA | 导入预览（多 Sheet） |
| POST | `/api/excel/import-with-mapping` | multipart `file`, Query mapping | T/SA | 带列映射导入（单表） |
| POST | `/api/excel/multi/preview` | multipart `files`(多选), `hasHeader` | T/SA | **多表探测**：逐文件逐 Sheet 报表名/表头/判定类型/列映射/可否导入 |
| POST | `/api/excel/import-multi` | multipart `files`(多选), `hasHeader`, `sheets`(JSON 覆盖) | T/SA | **多表导入**：一个工作簿多 Sheet 或一次多个文件 |
| POST | `/api/excel/multi/reparse` | Body `sheets`(JSON 指定) | T/SA | **按指定重新解析**：按前端给定的表类型 / 列映射 / 导入开关重跑解析与预览 |
| POST | `/api/excel/multi/sheet-data` | Query `fileIndex`,`sheetIndex`,`page`,`pageSize` | T/SA | **可视化网格数据**：取某文件某 Sheet 的原始单元格（分页），供前端逐格核对 |
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

#### 15.3 多表导入（一个 Excel 多张表 / 一次多个文件）

**前端入口**：教师端「① 导入报名 → 多表导入」（`/teacher/bulk-import`），三步式：选择文件 → 解析表结构 → 开始导入。

一条命令导入多张表：既可以是一个工作簿里的多个 Sheet（如把**年级 / 班级 / 全名单 / 报名表**拆成独立表），
也可以一次选中多个 .xlsx 文件。系统按「**Sheet 名 + 表头指纹**」自动识别每张表的类型，并按**依赖顺序**处理。

**三条规矩**（都有单测钉住）：

1. **按依赖顺序处理，而不是按 Sheet 物理顺序**：年级 → 班级 → 全名单 → 项目 → 报名 → 成绩。
   制表人把「报名表」排在「名单表」前面是常事，按物理顺序会让整张报名表全行失败。
2. **认不出类型的 Sheet 一律跳过并给出原因**，绝不默认按「运动员表」硬导 —— 那会把班级表写成运动员且全程不报错。
   识别优先级：**人工指定 → 表头指纹 → Sheet 名关键词 → 跳过**。
3. **逐表逐行如实计数**：成功 / 跳过(已存在) / 失败(带行号原因)。同一份工作簿可放心重跑：

| 表 | 重跑行为 | 二次导入计入 |
|----|---------|-------------|
| 年级表 / 班级表 / 运动项目表 / 报名表 | 唯一键冲突（已存在 / 已报名） | **跳过** |
| 全名单表（按学号 upsert） | 存在则**覆盖更新**、不存在则新建 | **成功**（数据被写入） |

   即「重跑不产生重复数据」是硬保证（有端到端用例验证：连导两次运动员总数不变），
   但「成功/跳过」的归类按各表的写入语义如实反映。

**运动员解析双口径**：成绩 / 报名行按「号码 → 学号」顺序解析运动员，**学号非空时即使号码缺失也能正确落库**（此前只按号码，学号为空的导入会整行失败）；其余类型仍按原口径。

**支持的导入类型**（`Sheet 名` 关键词 / 表头自动识别）：

| 类型 | 建议 Sheet 名 | 表头（列序） |
|------|--------------|-------------|
| `grade` | 年级表 | 年级 / 序号 |
| `class` | 班级表 | 班级名称 / 班级编码 / 年级 / 班主任 |
| `roster` | 全名单表 | 年级 / 班级 / 姓名 / 学号 / 性别 |
| `eventsimple` | 运动项目表 | 项目代码 / 项目名称 / 每组人数 / 每批组数 / 项目类型 / 场地号 / 每批所需时间(分) |
| `signup` | 报名表 | 年级 / 班级 / 姓名 / 学号 / 性别 / 项目 / 组号 |
| `event` | 项目表（表格2） | 见 [15.1](#151-项目导入模板表格2列说明) |
| `score` | 成绩表 | 项目编码 / 运动员号码 / 运动员姓名 / 成绩 / 组别 / 道次 / 风速 / 备注 |
| `athlete` / `registration` / `user` | 运动员表 / 报名表(旧) / 用户表 | 见各自单表模板 |

> 模板：`GET /api/excel/template/multiworkbook` 下载**多表模板**（一个工作簿含上述前 5 张常用表 + 填写说明），
> Sheet 名与表头都按识别口径命名，照此填写即可一次导入；用不到的表整表删除即可。
> 模板不含「成绩表」（成绩在编排之后录入，样本行会引用不存在的号码而必然失败），需要时自行加一张 Sheet 即可，同样支持识别。

**逐表覆盖**（表头实在对不上时）：`import-multi` 可带 `sheets` 覆盖项（JSON 数组），
如 `[{"file":"a.xlsx","sheet":"Sheet1","type":"roster","columnMap":{"0":"grade","1":"name","2":"studentId"}}]`，
按「文件名 + Sheet 名」或「文件序号 + Sheet 序号」匹配。

**列映射口径**：自动映射为「**精确 → 最长别名**」，并按类型优先落在该类型处理器真正读取的字段上
（例如表格2 的「项目名称」落 `name`、运动项目表的「项目类型」落 `category`，不会被 `eventCode` 的短别名「项目」抢走）。

手写 `sheets` 覆盖项虽然快，但「哪一列进哪个字段」要靠脑记。**多表导入设计器**把这件事摊到界面上：逐表选类型、逐列下拉点选字段、逐表勾选是否导入，改完即按这份指定重新解析，还能展开原始网格逐格核对。

#### 15.4 导出物中的裁判列

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

### 20. 届 / 运动会 Meets

前缀 `/api/meets`，8 个端点，要求 **T/SA**。运动会作为数据库一等公民：可创建多届、一键切换当前届，成绩 / 报名 / 入场式评分均绑定届次（历史数据自动归并默认届）；运动员的「当前年级 / 年份码 / 校验号 / 毕业标记」按当前届实时推算。

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| GET | `/api/meets` | — | T/SA | 届次列表（按年份降序、届次降序） |
| GET | `/api/meets/active` | — | T/SA | 当前启用届次 |
| GET | `/api/meets/current` | — | T/SA | 当前届次（与 active 同义，供前端 appStore 拉取） |
| POST | `/api/meets` | Body SportsMeet | T/SA | 创建届次（系统自动拼接名称「第X届Y季节运动会」） |
| PUT | `/api/meets/{id}` | Path id, Body 待更新字段 | T/SA | 编辑届次（部分更新） |
| POST | `/api/meets/{id}/activate` | Path id | T/SA | 设为当前届（同时取消其它届的 active） |
| DELETE | `/api/meets/{id}` | Path id | T/SA | 删除届次 |
| POST | `/api/meets/recompute-graduation` | — | T/SA | 立即按各运动员「毕业年份 vs 当前届年份」重算毕业标记 |

**SportsMeet 关键字段**：`edition`（届次，如 3）、`season`（季节，**自由文本**，如 秋季 / 春季 / 运动会 / 田径运动会）、`year`（年份）、`name`（自动拼接：`第{edition}届{season}运动会`）、`location`（地点）、`active`（是否当前届）、`startDate` / `endDate` / `remark`。

> 💡 前端入口：体育老师端「届 / 运动会」页（`/teacher/meets`）——列表 / 新建 / 编辑 / 设为当前届 / 删除；启动会经 `MeetDataInitializer` 自动保证至少存在一个默认届（第 1 届 / 秋季 / 本年 / 启用）。

---

### 21. 行政时间保护 Protections

前缀 `/api/protections`，4 个端点，要求 **T/SA**（体育老师 / 超级管理员）。「规避时间」三类：`GLOBAL`（全校统一避让时段，硬约束切分时间窗）、`TEACHER`（班主任个人不可用时段，其班级项目避让）、`REFEREE`（裁判个人不可用时段，执裁时跳过）。

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| GET | `/api/protections` | — | T/SA | 列出全部规避时间 |
| POST | `/api/protections` | Body `{targetType, targetId?, day, startTime, endTime, reason, enabled?}` | T/SA | 新增规避时间 |
| PUT | `/api/protections/{id}` | Path id, Body 同上（部分更新） | T/SA | 更新规避时间 |
| DELETE | `/api/protections/{id}` | Path id | T/SA | 删除规避时间 |

**字段**：`targetType`∈{GLOBAL,TEACHER,REFEREE}；`targetId`：TEACHER/REFEREE 时填对应用户/裁判 id，GLOBAL 留空；`day`：受保护日序（1=第 1 天，GLOBAL 切分所有天可填 -1 或逐天建多条）；`startTime`/`endTime`：当日 `HH:mm`（如 `09:00`/`10:30`）；`reason`：备注（闭幕式/大会/临时 unavailable 等）。

> 编排接入：GLOBAL→切分时间窗（整段不可排）；TEACHER→按项目黑名单（其班级项目避让）；REFEREE→`assignReferees` 前排除受保护裁判。详见 [9.3 行政时间保护](#93-行政时间保护规避时间)。

---

### 22. 通知 Notifications

前缀 `/api/notifications`，4 个端点，要求 **已认证**（站内信，按当前登录用户隔离）。班主任 / 教师 / 裁判三端布局顶部铃铛实时拉取未读角标。

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| GET | `/api/notifications` | — | 已认证 | 当前用户站内信列表（含已读/未读标记、类型、标题、内容） |
| GET | `/api/notifications/unread-count` | — | 已认证 | 未读数量 `{count}`（铃铛角标用） |
| PUT | `/api/notifications/{id}/read` | Path id | 已认证 | 标记单条已读 |
| PUT | `/api/notifications/read-all` | — | 已认证 | 全部标记已读 |

**触发场景**：取消某人某项目（`notifyTeacher=true` 时定向班主任）、冲突消解、裁判因保护时段被跳出执裁等事件由后端 `NotificationService.notifyUser/notifyRole` 写入，前端 `NotificationBell` 轮询 `/unread-count` 显示角标。

---

### 23. 审计日志 Audit

前缀 `/api/audit`，1 个端点，**仅 SA/T**。

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| GET | `/api/audit/logs` | Query `action?`, `limit=100` | SA/T | 查询审计日志（按操作类型筛选，返回最近 `limit` 条） |

> 记录点：编排自动 / 保存 / 清空（`SCHEDULE_AUTO` / `SAVE` / `CLEAR`）、报名审核、成绩录入、裁判调整（`ARRANGE_REFEREE_ADJUST`）等；详见 [17. 审计日志](#17-审计日志audit)。

---

### 24. 场地管理 Venues

前缀 `/api/venues`，5 个端点，要求 **T/SA**（体育老师 / 超级管理员）。场地即编排「并行上限」的数据来源（详见 [16. 场地管理](#16-场地管理venue)）。

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| GET | `/api/venues` | — | T/SA | 全部场地列表 |
| GET | `/api/venues/enabled` | — | T/SA | 仅启用场地列表 |
| POST | `/api/venues` | Body `Venue{code,name,type,parallelMax?,capacity?,sortOrder?,enabled?}` | T/SA | 新增场地（`code` 唯一） |
| PUT | `/api/venues/{id}` | Path id, Body 部分字段 | T/SA | 更新场地（部分更新） |
| DELETE | `/api/venues/{id}` | Path id | T/SA | 删除场地（软删除） |

**字段**：`code`（编码，唯一，如 `TRACK-01` / `FIELD-A`）、`name`（名称）、`type`（track/field/pool/other）、`parallelMax`（该场地并发位数，1=串行，n=并行）、`capacity`、`sortOrder`、`enabled`。

> 项目通过 `defaultVenueCode` 绑定场地，从而受 `parallelMax` 约束；赛程「并数」上限由「径赛主场地 + 田赛场地数」决定。

---

### 25. 自定义项目 CustomProject

前缀 `/api/custom-project`，3 个端点，要求 **T/SA**。入场式 / 队列等非竞赛展示项目区（不进入成绩排名）。

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| GET | `/api/custom-project` | — | T/SA | 自定义项目列表 |
| POST | `/api/custom-project` | Body `{id?, code?, name, type?, sortOrder?, countInTotal?}` | T/SA | 保存（创建 / 更新） |
| DELETE | `/api/custom-project/{id}` | Path id | T/SA | 删除 |

---

### 26. 实时协作 Collaboration

前缀 `/api/collaboration`，2 个端点，**已认证**即可访问（轻量轮询，非 WebSocket / SSE，部署零额外配置）。

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| GET | `/api/collaboration/version` | — | 已认证 | 当前全局赛程版本号（前端初次握手） |
| GET | `/api/collaboration/events` | Query `since=0` | 已认证 | 拉取版本号 > `since` 的增量事件（事件环仅保留最近 200 条） |

> 设计：打开编排页记录当前版本号，每隔几秒 `events?since=版本号`，一旦拿到他人改动即提示「赛程已被 XXX 更新」并刷新视图，**冲突在保存前暴露**。详见 [18. 实时协作](#18-实时协作collaboration)。

### 27. 秩序册 OrderBook

前缀 `/api/order-book`，17 个端点。写操作（目录 / 细则 / 导出）限 **T/SA**，
只读预览（`/layout`、`/preview`）已认证即可。

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| GET | `/api/order-book/sections` | Query `meetId` | T/SA | 目录树（含未启用的，供设计器编辑） |
| POST | `/api/order-book/sections/ensure-default` | — | T/SA | 铺默认五个系统级目录（幂等） |
| POST | `/api/order-book/sections` | Body `meetId`,`parentId`,`title`,`level` | T/SA | 新增目录 |
| PUT | `/api/order-book/sections/{id}` | Body `title`,`enabled`,`sortOrder` | T/SA | 改标题 / 启停 |
| PUT | `/api/order-book/sections/{id}/move` | Query `dir`(up/down/top/bottom) | T/SA | 同父内排序调整 |
| DELETE | `/api/order-book/sections/{id}` | — | T/SA | 软删目录（连带收口其下细则，可恢复） |
| POST | `/api/order-book/sections/{id}/restore` | — | T/SA | 恢复被软删的目录 |
| GET | `/api/order-book/entries` | Query `sectionId` | T/SA | 某目录的细则列表 |
| POST | `/api/order-book/entries` | Body `sectionId`,`title`,`kind`,`sourceKey`,`contentType`,`content` | T/SA | 新增细则 |
| PUT | `/api/order-book/entries/{id}` | Body 同上 | T/SA | 修改细则 |
| PUT | `/api/order-book/entries/{id}/move` | Query `dir` | T/SA | 同目录内排序调整 |
| DELETE | `/api/order-book/entries/{id}` | — | T/SA | 软删细则（可恢复） |
| POST | `/api/order-book/entries/{id}/restore` | — | T/SA | 恢复被软删的细则 |
| GET | `/api/order-book/layout` | Query `meetId` | 已认证 | 整本秩序册的结构树（含未启用） |
| GET | `/api/order-book/layout/enabled` | Query `meetId` | 已认证 | 只含启用节点的树 |
| GET | `/api/order-book/preview` | Query `grade`（可选） | 已认证 | **Word 在线预览**（`text/html`，iframe `srcdoc` 渲染） |
| GET | `/api/order-book/preview/docx` | Query `grade`（可选） | T/SA | 下载 `.docx`（与预览同源渲染） |

> 预览接口返回的是**渲染后的完整 HTML 文档**（A4 版式 + 打印样式），不是 JSON；
> 前端用 `srcdoc` 内联，因为 iframe 的子请求不会带上登录态。详见 [22. 秩序册设计器](#22-秩序册设计器自定义目录--细则--word-在线预览)。

---

### 28. 多表导入重新解析（Excel multi reparse）

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| POST | `/api/excel/multi/reparse` | Body `sheets[]` = `{fileIndex,sheetIndex,type,columnMap,include}` | T/SA | 按前端指定重新解析（不落库，只出预览） |
| POST | `/api/excel/multi/sheet-data` | Query `fileIndex`,`sheetIndex`,`page`,`pageSize` | T/SA | 取某文件某 Sheet 的原始网格（分页） |

> 与 [15. 15. Excel 导入导出](#15-excel-导入导出-excel) 的 `import-multi` 关系：
> **`multi/reparse` 只解析不落库**，`import-multi` 才真正写入；两者的「表 → 类型 → 列映射」使用完全同一套口径，
> 所以「预览怎么显示，导入就怎么写」。详见 [21. 多表导入](#21-多表导入按表指定后重新解析可视化列映射--网格预览)。

---

## 🗄 数据库设计


```
sys_user ──┐
           ├── class_info (teacher_user_id)
           │       │
           │       ├── athlete (class_info_id)
           │       │       │
           │       │       ├── registration (athlete_id + event_id, meet_id)
           │       │       ├── arrangement  (athlete_id + event_id)
           │       │       └── result       (athlete_id + event_id, meet_id)
           │       │
           │       └── event
           │
           ├── sports_meet  ──▶ result / registration / parade_score（经 meet_id 绑定届次）
           │
           └── system_config
```

| 表 | 说明 |
|----|------|
| `sys_user` | 用户/账号/角色，BCrypt 加密 |
| `class_info` | 班级，关联班主任 userId |
| `athlete` | 运动员，含学号、号码簿、班级关联、`enrollYear`/`graduateYear`（入/毕年份，拼接为 `20252028` 形式）、`graduated`（毕业标记）；`currentGrade`/`yearCode`/`checkNo` 为按当前届实时推算的瞬态展示字段（不落库） |
| `event` | 比赛项目，含预设模板 |
| `registration` | 报名记录，联合唯一约束；含 `meet_id`（绑定届次） |
| `arrangement` | 编排结果，支持版本回滚 |
| `result` | 成绩记录，多状态管理；含 `meet_id`（绑定届次） |
| `sports_meet` | **届 / 运动会**（数据库一等公民）：`edition`/`season`/`year`/`name`/`location`/`active`/`startDate`/`endDate`/`remark`；成绩 / 报名 / 入场式评分均经 `meet_id` 关联 |
| `parade_score` | 入场式评分，独立计分；含 `meet_id`（绑定届次） |
| `system_config` | 系统配置，JSON 存储 |
| `admin_time_protection` | 行政时间保护（规避时间）：`targetType`(GLOBAL/TEACHER/REFEREE) / `targetId` / `day` / `startTime` / `endTime` / `reason` / `enabled` |
| `notification` | 站内信通知：`recipientUserId` / `role` / `title` / `content` / `type` / `read`（已读标记），按接收人隔离 |
| `venue` | 场地（运动场馆 / 区域）：`code`（唯一编码）/ `name` / `type`(track/field/pool/other) / `parallelMax`（并发位数，1=串行）/ `capacity` / `sortOrder` / `enabled`；**编排并行上限的数据来源**，项目经 `defaultVenueCode` 绑定 |
| `custom_project` | 自定义项目区（入场式 / 队列等非竞赛展示项目，不进成绩排名）：`code` / `name` / `type` / `sortOrder` / `countInTotal` |
| `audit_log` | 操作审计日志：`action` / `actor` / `detail` / `createdAt`；编排自动 / 保存 / 清空、报名审核、成绩录入、裁判调整等关键写操作留痕 |
| `event_referee` | 裁判分配：`event×grade×gender×round×heat` 为键，记录每组次分配的裁判（姓名 / id）；道次表与秩序册的「裁判」列数据来源 |
| `arrangement_reservation` | 预留模拟空位：组次级占位（不含真实运动员），含预留时间 / 备注，并入对应组次展示 |

支持的数据库：**SQLite（默认，零配置）**、MySQL 8.0（生产）、H2（开发）。运行时可通过「数据库迁移」在线切换。

---

## 🚢 部署指南

### JAR 运行

```bash
# 默认 SQLite（零配置）
java -jar sports-2.8.5.jar

# 自定义端口 + 绑定地址（推荐写法）
java -jar sports-2.8.5.jar --app.port=8899 --app.host=::

# 等价的 Spring 标准写法
java -jar sports-2.8.5.jar --server.port=9090

# 后台运行
nohup java -jar sports-2.8.5.jar --app.port=8899 > app.log 2>&1 &
```

### 🔄 更换服务端口与绑定地址（优先级从高到低）

| 方式 | 操作 | 生效方式 |
|------|------|----------|
| ① 命令行参数 | `java -jar sports-2.8.5.jar --app.port=8899 --app.host=::`<br>`.\start.ps1 -Port 8899 -Host ::` / `start.bat --app.port=8899`<br>（也可用标准 `--server.port=9090`） | 立即（本次运行） |
| ② 环境变量 | `SERVER_PORT=9090 java -jar sports-2.8.5.jar`（Linux/macOS）<br>`$env:SERVER_PORT="9090"; java -jar ...`（PowerShell） | 立即（本次运行） |
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


**形态 C：HTTPS 反向代理（cpolar / nginx SSL）** —— 注意这一档和上面两档不是互斥关系，
它管的是「加密入口 + 转发头」，子路径帽子可以照常叠加使用。

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;   # 关键：把 https 告诉后端
    proxy_set_header X-Forwarded-Host $host;
    proxy_set_header X-Forwarded-Port $server_port;
}
```

后端已内置两处配套配置（`application.yml` + `SecurityConfig`），**升级后无需手动加**：

1. `server.forward-headers-strategy: FRAMEWORK` —— 识别 `X-Forwarded-*`，
   让 Spring 眼里的 scheme/端口跟着代理走（否则后端永远自认 `http://x:8080`）。
2. 显式 `CorsConfigurationSource`（允许源用 pattern `*`、暴露 `Authorization` 头）——
   原先是 `.cors(cors -> {})`（空配置）。

> ⚠️ **踩过的坑：挂到 https 代理后面白屏，直连 `http://localhost:8080` 却完全正常。**
> 根因很隐蔽：构建产物里 `<script type="module" crossorigin>` / `<link rel="stylesheet"
> crossorigin>` 会让浏览器**连同源的 JS/CSS 请求也带上 `Origin` 头**（普通 XHR 同源请求不带）。
> 本机直连时，Spring 眼里的请求地址与 `Origin` 天然同源，`DefaultCorsProcessor` 走
> `isSameOriginRequest` 直接放行；一挂到 `https://xxx.cpolar.cn` 后面，Origin 是 `https://...`、
> 而 Spring 眼里的还是 `http://...:8080`（第 1 条没开时），两者被判为跨源 → `CorsFilter`
> 直接 403 → **index.html 能回来、但 `assets/*.js` 与 `assets/*.css` 全 403 → 整页白屏**。
> 所以表现是"接口没问题、页面一片白"，且只在代理场景复现，直连测不出来。
> 两条配置一起上（forward-headers + 显式 CORS）后，`https` 入口首屏、`#/loading`、
> 点击进入系统 → 登录页 → 接口调用全链路均正常。
> 自检：`curl -k -H "Origin: https://域名" https://代理入口/assets/xxx.js` 应返回 `200`
> 并带 `Access-Control-Allow-Origin`（修复前是 `403`）。

**隧道/反向代理排障速查（先按症状对号入座，再动手）**

| 症状 | 根因 | 处置 |
|------|------|------|
| 入口 `502` / 隧道打不开 | **隧道指向的端口上没有服务在监听**。cpolar 日志里会出现 `Failed to open private leg http://localhost:XXXX: connectex: ... actively refused` | 确认服务已启动，且 `netstat -ano \| findstr :XXXX` 能看到 LISTENING；端口以 `data/app-config.json` 的 `port` 为准（`start.ps1 -Port` 可临时改） |
| 首屏能开、页面一片白，接口正常 | `assets/*.js` / `*.css` 被 CORS 拦成 403 | 本项目已内置修复；确认升级到含 `forward-headers-strategy` 的版本 |
| 接口 401 / 登录后反复掉线 | 代理吞了 `Authorization` 头 | nginx 加 `proxy_set_header Authorization $http_authorization;` |
| 局域网内其它机器访问不了本机端口 | Windows 防火墙未放行 JVM 入站（默认拦） | 管理员 PowerShell：`New-NetFirewallRule -DisplayName "sports-8080" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow` |
| 隧道地址变了打不开 | cpolar 免费/基础套餐每次重启会换域名 | 从 cpolar 面板或日志 `NewTunnel ... "Url":"https://xxx"` 取当前域名 |

三条自检命令（把 `<入口>` 换成隧道/代理地址）：

```bash
curl -k -o /dev/null -w "%{http_code}\n" https://<入口>/                                  # 应 200（502=服务没起）
curl -k -o /dev/null -w "%{http_code}\n" -H "Origin: https://<入口>" https://<入口>/assets/index-xxx.js   # 应 200（403=CORS 没生效）
netstat -ano | findstr :8080                                                              # 应有 LISTENING
```

> 💡 cpolar 用户注意：隧道配置的 `addr` 端口必须与实际监听端口一致。本项目 jar 默认 **8080**，
> 若你把服务跑在别的端口（例如 `start.ps1 -Port 8899`），就要同步改 cpolar 隧道配置里的
> `addr`，否则隧道会一直 502。

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

## 🧠 AI 编排核心

**训练侧与部署侧彻底解耦**：训练用 Python（根目录独立目录 `sports-ai/`，**不在 `sports-backend` 内**，不参与任何后端构建），交付用 ONNX，推理在 JVM 内由 onnxruntime 完成——**生产环境不依赖 Python 解释器**。

```
sports-ai/（Python 3.12 + venv）            sports-backend/（Java 21 + Spring Boot）
  data/  合成数据与特征契约                    com.sports.schedule.ai
  models/ 选择器 + 冲突簇 GNN                    ├─ ModelSource         模型加载（classpath / file）
  generative/ GAN + 精修器                       ├─ OnnxInferenceService   选择器 + GNN
  forecast/ 多步预测                             ├─ SchemeGeneratorService GAN 生成器
  curriculum/ 自步学习                           ├─ AdversarialSchemeService 推理时自对抗
  solve/ 拆批 + 分批装箱 + 可解性                 ├─ LaneAdvisorService     道次 AI
  tournament/ 球赛赛制 + 适配层                   └─ AiController  /api/ai/status
  models/*.onnx ──导出──► src/main/resources/models/ ──打包──► jar（单包交付）
```

### 一、模型清单（随 jar 交付，共 8 个 / ≈1.3 MB）

| 模型 | 文件 | 类型 | 作用 |
|:--|:--|:--|:--|
| 算法选择器 | `algorithm_selector.onnx` | 残差 MLP | 16 维实例特征 → **硬解** vs **取消报名**路径（标签 = 真实可解性，含团下界）|
| 冲突簇 GNN | `conflict_gnn.onnx` | 4 层 GNN | 冲突图 → 各单元**着色优先级**，中心冲突簇先着色（构造启发式初始顺序）|
| GAN 生成器 | `scheme_generator.onnx` | GNN + Gumbel-Softmax | 冲突图 + 噪声 → 时间槽着色方案（**直接学习着色**）|
| GAN 判别器 | `scheme_discriminator.onnx` | 神经网络 | 方案 → 真/假（**推理时自对抗**用它给候选打分）|
| 对抗精修器 | `scheme_refiner.onnx` | 残差 GNN | 初始方案 → 精修方案（把推理时迭代自对抗**蒸馏成一次前向**）|
| 多步预测 | `forecast_mimo.onnx` / `forecast_direct.onnx` | 序列模型 | 预测未来 H 步时间槽，让**回溯提前发生**（Direct / Recursive / MIMO 三策略）|
| 道次 AI | `lane_advisor.onnx` | Learning-to-Rank | 运动员特征 → **派遣优先级**（输出排序而非分组，与现有管线零阻抗）|

### 二、关键设计

- **动态节点数**：GNN 是归纳式的，权重与节点数无关，因此 ONNX 用 `dynamic_axes` —— 推理时 shape 随实例变化，**不补齐、无规模硬上限**（GNN 支持任何单元数，实测 n=7 与 n=133 均正常）。训练时补齐到 256 只为 batch 拼接。
- **16 维节点特征 + 带权邻接**：节点特征含 决赛轮次 / 年级 / 时长占比 / 冲突暴露量 / 超大单元标记 等；邻接权重 = **共享运动员数归一化**，表达冲突强度（二值邻接丢掉了这个信息）。
- **货真价实的 GAN**：生成器 G 与**可学习的神经网络判别器 D**（不是规则校验器）做 minimax 对抗，真样本由贪心图着色 oracle 提供；G 侧 loss = 骗过 D + **GenCO 式组合约束**（兼项冲突期望 + 行政时间保护禁止列表 + 槽容量）；用 straight-through Gumbel-Softmax 保证真假样本同为 one-hot 形态，D 只能靠「约束是否真满足」区分。
- **推理时自对抗（不只训练时）**：Java 端 `AdversarialSchemeService` 每轮「G 采样 → 精修器精修 → **D 评判** → 计算真实残余冲突 → 择优」，并把单次生成基线纳入候选，保证 **自对抗绝不劣于单次生成**。
- **失败即降级**：模型缺失 / 加载失败 / 推理异常一律返回空、回退既有规则编排，**接口始终能出方案**；`GET /api/ai/status` 可观测当前跑在 AI 路径还是规则路径、每个模型来自 jar 还是外部目录。

### 三、可解性诊断（不可解冲突输出给程序）

经典求解层 `sports_ai/solve/`（与神经生成互补）：

- `heats.py` **拆批**：单元 `rawDuration = 组数 × 每批时长` 可达数百分钟，远超单时段容量——不拆批在数据结构上就放不进任何位置（这才是「大规模不可解」的真根因）；拆批后同单元不同批次之间**无兼项冲突**，摊开反而降冲突。
- `feasibility.py` **下界分析**：按池容量缺口 / 最少天数 / 冲突图最大团 / 超大单元。
- `scheduler.py` **分批装箱 + 局部搜索**：四约束（容量 / 池匹配 / 兼项 / 预赛→决赛偏序），正排 + 倒排双起点取优，代价为**字典序元组**（未排组次 → 未排人次 → 兼项重叠）。
- `report.py` **结构化报告**（schema `sports-ai/infeasibility-report@1`）：

```json
{"schema":"sports-ai/infeasibility-report@1","feasible":false,
 "summary":{"placed":137,"unplaced":5,"unplacedUnits":["100m@高2@预赛"]},
 "conflicts":[{"type":"capacity_shortfall","pool":"径赛","shortfallMinutes":55,"minDaysRequired":3},
              {"type":"clique_exceeds_periods","cliqueSize":5,"availablePeriods":4},
              {"type":"oversized_unit","minSplits":9},
              {"type":"athlete_clash","athlete":510,"units":["100m@高3@预赛","PU@高3@决赛"]}],
 "actions":[{"action":"extend_days","needDays":3},{"action":"add_lanes","scope":"径赛"},
            {"action":"cancel_entry","athlete":510}]}
```

Java 侧 `com.sports.schedule.analysis.ScheduleFeasibilityService` 产出**同构**报告，随编排响应（`portfolioInfo.feasibility`）返回；前端在不可解时展示「可排 X/Y 组次」+ 冲突类型 + 建议动作。**「排不下」不再是终点，而是给班主任/教务处的可处理数据。**

### 四、球赛赛制生成

`POST /api/tournament/generate`（`round_robin` / `elimination` / `hybrid` / `volleyball`）：

| 赛制 | 关键约束 |
|:--|:--|
| 循环赛 | 圆桌轮转法；**连续主/客场 ≤ 2**（逐场贪心主客分配，旧实现用 `(轮次+序号)%2` 保证不了连续性）；支持单/双/分组循环 |
| 淘汰赛 | 轮空 + 种子半区（1/2 号种子决赛才相遇）+ **同单位回避**（局部交换爬山，只换种子位不改轮空结构）；**真实双淘汰**（败者组 2k−2 轮，每场标 `feedsFrom`）|
| 混合赛制 | 蛇形分组 → 组内循环 → **交叉淘汰**（名次反向配对，同组出线队首轮必不相遇）|
| 排球 | 分组循环 + 交叉赛（`volleyball`）|

`tournament/adapt.py` 把赛制结构适配为**可排任务**（偏序 + **队员名单**）——队员名单让球赛与田径**共用同一张冲突图**，跨大类兼项冲突自动成立。

### 五、道次编排 AI

款型 `ai`（`ArrangeStyle.AI`，"AI 派遣"）：AI 模型给出**派遣优先级**，后端 `argsort` 后走既有蛇形分组/分道实现——**只换「谁先派」，不换「怎么分」**，对现有管线零阻抗；模型不可用自动回退成绩种子。实测前半段排序重合度 **0.786**（随机基线 0.5）。`GET /api/arrange/styles` 自动列出该款型。

### 六、编排进度可见

编排耗时分档：规则模式毫秒级、大规模优化模式（求解 → GA/LNS/MNSA/ALNS/Fix-and-Optimize 精修链 → 对抗自检）秒级到十秒级、**AI 模式分钟级**（多轮自对抗 + 逐轮 ONNX 前向推理）。因此 AI 模式推荐走异步入口：`POST /api/schedule/auto/async` 返回 `taskId`，前端轮询 `GET /api/schedule/progress/{taskId}` 展示**阶段文案 + 百分比进度条**（准备 5% → 求解 35% → 精修 65% → 自检 88% → 收尾 97%），同步接口 `POST /api/schedule/auto` 行为完全不变（会阻塞至完成，仅适合小规模或高性能服务器）。

### 七、训练与配置

```powershell
# 训练侧（独立 venv，见 sports-ai/README.md）
cd sports-ai
.\scripts\setup_venv.ps1     # 创建 Python 3.12 venv 并装依赖
.\scripts\train.ps1          # 合成数据 → 训练全部模型 → 导出 ONNX → onnxruntime 自检
```

```yaml
sports:
  schedule:
    ai:
      enabled: true                 # false = 完全禁用 AI（回退纯规则）
      model-dir: classpath:/models  # jar 内（单包交付）；也可指向 file:/opt/ai-models 热替换
      execution-provider: cpu      # cpu | directml | cuda | auto（详见下节）
      intra-op-threads: 0           # 0 = 交给 ONNX Runtime 按核数自适应
      inter-op-threads: 0
```

> 改外部模型目录即可**不重新打包**热替换模型；模型缺失时编排静默回退规则路径——因此 `GET /api/ai/status` 会逐个模型报告「来自 jar 还是外部、是否加载」，避免「AI 其实没跑」被误认为正常。

### 🚀 GPU 加速与 Windows ARM64

> ⚠️ **先看平台现状**：`com.microsoft:onnxruntime` 的 **CPU 版只内置四个平台**
> native 库——`win-x64` / `linux-x64` / `linux-aarch64` / `osx-aarch64`，
> **不含 `win-arm64`**。也就是说默认打出来的 jar 在**原生 Windows on ARM
> （骁龙等）上根本起不来**，会直接抛 `UnsatisfiedLinkError`。

**为什么 Windows ARM64 只能用 DirectML**（依据 ONNX Runtime 官方 EP 兼容表）：

| EP | NVIDIA | AMD | Intel | 高通 / Win on ARM | Win x64 | **Win ARM64** | 额外依赖 |
|---|:---:|:---:|:---:|:---:|:---:|:---:|---|
| **DirectML** | ✅ | ✅ | ✅ | ✅ **首选** | ✅ | ✅ **Full** | 无（DirectX 12 系统自带） |
| CUDA | ✅ | ❌ | ❌ | ❌ | ✅ | ❌ | CUDA 12 + cuDNN 9 |
| CPU | — | — | — | — | ✅ | ❌（本项目依赖缺 native 库） | 无 |

**怎么打包**（依赖用 profile 切换，**不要同时引**——三个变体的 Java 类完全同名，
同时打进一个 jar 会 duplicate class）：

```bash
# 默认：CPU，跨平台最通用
mvn -o clean package -DskipTests

# Windows GPU 加速（NVIDIA / AMD / Intel / 高通都覆盖，含 ARM64）
mvn -o clean package -DskipTests -Ponnx-gpu-directml

# Linux 服务器 + NVIDIA 独显（性能最好，jar 体积 +200MB）
mvn -o clean package -DskipTests -Ponnx-gpu-cuda
```

**怎么启用**（或改 `application.yml` / 命令行 `--sports.schedule.ai.execution-provider=directml`）：

| 取值 | 行为 |
|------|------|
| `cpu` | 纯 CPU，默认。零外部依赖 |
| `directml` | DirectX 12 GPU。**Windows ARM64 唯一可用选项** |
| `cuda` | 仅 NVIDIA 独显 |
| `auto` | 按 `directml → cuda → cpu` 依次探测，全失败回落 CPU |

> **降级不阻塞**：GPU EP 缺依赖 / 缺驱动 / 初始化失败时会**静默回落 CPU** 并记录原因，
> 编排照常出方案（同一份 ONNX 图在 CPU 与 GPU 上只差浮点末位）。
> 但**只换配置不换依赖包**是常见错配——此时会走降级路径。
> `GET /api/ai/status` 的 `execution` 字段会如实报出 `requested` / `effective` /
> `gpu` / `degradeReason` / `probe` / OS 架构，一眼看清「到底跑在哪儿」：

```json
"execution": {
  "requested": "directml", "effective": "cpu", "gpu": false,
  "degradeReason": "directml 不可用，已回落 CPU",
  "probe": ["directml: 不可用（NoClassDefFoundError: ai/onnxruntime/providers/DmlExecutionProvider)"],
  "platform": "Windows 11/amd64"
}
```

### 🧠 训练侧 GPU

训练脚本此前**完全没有 device 判断**（`model = Xxx(...)` 之后直接 `loss.backward()`），
现在统一走 `sports-ai/sports_ai/device.py`，所有训练入口都支持 `--device`：

```bash
cd sports-ai
./venv/Scripts/python.exe -m sports_ai.train_selector --samples 4000 --epochs 30            # auto（默认）
./venv/Scripts/python.exe -m sports_ai.train_selector --samples 4000 --device cuda          # 强制 GPU
./venv/Scripts/python.exe -m sports_ai.train_gnn --device cuda
./venv/Scripts/python.exe -m sports_ai.lane_advisor --iters 1500 --device cuda
./venv/Scripts/python.exe -m sports_ai.curriculum.train_self_paced --device cuda
./venv/Scripts/python.exe -m sports_ai.forecast.train_forecast --device cuda
./venv/Scripts/python.exe -m sports_ai.generative.train_refiner --device cuda
```

| 取值 | 行为 |
|------|------|
| `auto`（默认） | CUDA → MPS（Apple Silicon）→ CPU |
| `cuda` / `mps` | 强制。**不可用时直接报错**，不静默回落（避免"以为在 GPU 上"白等几小时） |
| `cpu` | 显式指定，与改造前行为完全一致 |

训练开始会打印 `[device] 训练设备: cuda:0 (NVIDIA ...)`，日志里一眼可辨。
另有 `--no-amp` 关闭混合精度（仅 CUDA 生效）。

> 🛡️ **模型覆盖保护**：训练脚本一律把结果写回 `models/*.pt`，一次小样本冒烟
> （`--samples 200`）就会把正式权重当场冲掉，而 `.pt` **不在 git 跟踪范围内**。
> 现在所有训练脚本保存前会自动备份（`[guard] 已备份既有模型 → xxx.pt.bak.smoke-200`），
> `train_selector` 在样本量 < 1000 时还会额外打印警告。
> 被备份污染的 `*_stats.json` / `*_metrics.json` 记得 `git checkout` 还原——
> 它们是导出 ONNX 时固化归一化的依据，与权重必须成对。

### 📊 怎么确认「AI 真的按这些点位与输入推理了」

模型加载成功 ≠ 在推理。要坐实「输入 → 输出」的因果链，用下面三步（都不需要读代码）：

**① 确认模型已加载**（任一为 false 就说明这次跑的是规则路径）

```bash
curl -s -H "Authorization: Bearer <token>" http://localhost:8080/api/ai/status
# 期望：advisory / schemeGenerator / adversarial / laneAdvisor 四项的 loaded 均为 true
```

**② 确认编排过程真的调用了模型**——跑一次编排后 grep 日志，出现以下四类即坐实：

| 日志关键字 | 出自 | 含义 |
|-----------|------|------|
| `AI 编排模型就绪` | `OnnxInferenceService` | 选择器 + 冲突图 GNN 已加载 |
| `推理时自对抗: N 轮，基线冲突=X → 最终冲突=Y, D=Z, 精修=true` | `AdversarialSchemeService` | GAN 真的跑了多轮，D 是判别器实时打分 |
| `道次 AI 派遣: event=…, N 名运动员按 AI 优先级排序（款型 AI）` | `LaneAdvisorService` | 道次优先级来自模型而非默认顺序 |
| `兼项统计: … 名运动员（其中 M 名兼项…）` | `EventCooccurrenceService` | 兼项共现矩阵（冲突图的边） |

反过来，出现 `模型缺失` / `模型加载失败` / `推理失败…回退规则编排` 任一条，即表示**这次编排没走 AI**。

**③ 注意「冲突恒为 0」不等于 AI 没跑。** 若兼项统计为 **0 名兼项**（即每名运动员只报一项），
则冲突目标函数恒等于 0，此时自对抗会显示 `基线冲突=0.000 → 最终冲突=0.000`——
这是**数据决定的**（没有冲突可躲），不是模型没工作；`D=` 分值仍在随输入变化，说明 G/D 网络在推理。
想看到 AI 真正「择优」，必须造出有兼项的数据（同一运动员报 2 项以上）。

**实测对照**（同一模型、同一套代码，只改输入的兼项关系）：

| 输入 | 兼项人数 | 判别器 D 分 | 说明 |
|------|:---:|:---:|------|
| 单项全员 | 0 / 720 | `-2.231` | 冲突恒 0，无可躲 |
| 加报兼项 | 18 / 36 | `-1.455` | 冲突目标非 0，D 分随之改变 |

编排返回体里的 `aiReport` / `algorithmPortfolio.aiReport` 也会如实给出
`laneStyle`（AI 派遣款型）、`rounds`（自对抗轮数）、`dScore`（判别器打分）、
`conflictBefore` / `conflict`（择优前后冲突）、`improved`（是否真改进了）、
`residualConflicts`（落库后残余冲突），以及 `adversarial: enabled | unavailable`
（模型不可用时**如实报 unavailable，绝不静默**）。

> ⚠️ `improved: false` + `conflictBefore = conflict = 0` 是**正常且正确**的：
> 求解器主方案已把冲突降到 0，AI 候选方案自然无从改进——基线本就 0 冲突。
> 这时判断 AI 是否在工作，要看 `dScore` 是否随输入变化，而不是看 `improved`。

> 单测 `OnnxInferenceServiceTest#adviceActuallyDependsOnInput` 把上述因果链固化成了回归钉子：
> 单元时长 ×30 → 取消概率必须变；兼项关系从「无边」变成「a-b 相连」→ GNN 优先级必须变。
> 若哪天模型改成「无论输入都返回同一份常量」，该用例会立刻变红。

---

## 🛠 技术架构

| 层次 | 组件 | 版本 |
|------|------|:----:|
| 语言 | Java | 21 LTS |
| 框架 | Spring Boot | 3.4.5 |
| 安全 | Spring Security + JWT (jjwt) | 6.x / 0.12.6 |
| ORM | Spring Data JPA + Hibernate | 6.6 |
| 约束求解 | Timefold Solver（构造启发式 + 禁忌/迟接受/模拟退火） | 2.6.0 |
| AI 推理 | ONNX Runtime（JVM 内推理，`com.microsoft.onnxruntime`） | 1.26.0 |
| AI 训练（独立） | Python + PyTorch → ONNX（`sports-ai/`，不参与后端构建） | 3.12 / 2.14 |
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
│   编排进度条（异步任务轮询） │ 可解性诊断告警             │
└──────────────────────────┬───────────────────────────────┘
                           │ HTTP / JWT（相对路径 + 智能前缀推断）
┌──────────────────────────▼───────────────────────────────┐
│                     后端服务层                            │
│              Spring Boot 3.4 / Java 21                   │
│   Spring MVC │ Spring Security │ Spring Data JPA │ AOP   │
│   编排算法（规则模式 + Timefold/GA/LNS/MNSA/ALNS/FixOpt）  │
│   AI 编排核心（ONNX Runtime：选择器/GNN/GAN/道次 AI）      │
│   球赛赛制生成 │ 可解性诊断 │ 赛程调度                     │
│   排名积分 │ EasyExcel │ 热迁移                          │
└──────────────────────────┬───────────────────────────────┘
                           │ 仅离线训练时（生产不依赖）
┌──────────────────────────▼───────────────────────────────┐
│   sports-ai/（Python 3.12 + PyTorch）──导出 .onnx──► jar  │
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
.\build.ps1                 # 全量编译：前端 vite build → 后端 clean package → jar 输出到项目根目录
.\build.ps1 -SkipFrontend   # 仅后端（复用现有前端产物）
.\build.ps1 -SkipBackend    # 仅前端（不重新打包）
.\start.ps1                 # 启动（-Port 9090 自定义）
```

> 打包时 `build.ps1` 会自动把训练侧 `sports-ai/models/*.onnx` 同步进 `sports-backend/src/main/resources/models` 并打进 jar，**最终产物只有一个 `sports-2.8.5.jar`**（内含前端静态资源 + 8 个 ONNX 模型），部署无需额外目录。
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
copy sports-backend\target\sports-2.8.5.jar .
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
- `vue-router` 使用 `createWebHashHistory(appBase() ? appBase() + '/' : '/')`（hash 模式，与部署章节一致），axios `baseURL` 使用 `apiBase()`，**所有 API 路径均动态拼接、严禁硬编码**
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

> **版本**: v2.8.5 | **API 端点**: 33 Controller / 276 个 | **构建日期**: 2026-10-02
