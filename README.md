# 🏃 运动会智能编排系统

> Sports Meet Intelligent Arrangement System v2.0.0

基于 **Spring Boot 3.4 + Vue 3 + Element Plus** 的全栈运动会管理系统。支持**超级管理员 / 体育老师 / 班主任 / 学生**多角色协作，覆盖**建站向导 → 班级名单导入 → 运动会报名 → 智能分组编排 → 赛程编排 → 成绩录入 → 排名积分 → 报表导出**全流程。

核心亮点：

- ⚙️ **零配置建站**：首次启动进入可视化安装向导（参考 WordPress / Discuz 体验），配置站点、数据库、管理员账号后即装即用
- 🔀 **数据库热迁移**：SQLite ↔ MySQL 在线切换，**全程无需重启服务**
- 🧠 **智能编排引擎**：贪心 + 局部优化算法自动分组分道，规则完全可配置
- 🌐 **反向代理子路径部署**：原生支持挂在任意帽子前缀下（如 `/sportmg/`），前端相对路径 + 后端智能剥离前缀，**代码零硬编码**
- 📊 **全流程 Excel 化**：名单 / 项目 / 报名 / 成绩 全部支持模板导入导出，秩序册 / 成绩册 / 报表一键生成

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
  - [Excel 导入导出 Excel](#15-excel-导入导出-excel)
  - [数据库备份 Backup](#16-数据库备份-backup)
  - [数据库迁移 DbMigration](#17-数据库迁移-dbmigration)
  - [建站向导 Setup](#18-建站向导-setup)
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
java -jar sports-2.0.0.jar
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
| 号码簿规则 | 号码生成模板自定义（`{grade}{class}{seq:02d}` 等变量）+ 实时预览 |
| 编排规则 | 软约束开关 + 算法参数（尝试次数/超时/优化轮数） |
| 积分规则 | 名次积分表、并列处理、破纪录加分、参与分、接力倍数、团体总分口径 |
| 数据库迁移 | SQLite ↔ MySQL 在线热迁移（连接测试、异步迁移、进度查询），**无需重启** |
| 数据库备份 | 手动/自动备份、备份列表、下载、删除 |
| 健康检查 | 系统运行状态详情、操作日志查看 |
| 应用运行配置 | 服务端口、绑定地址（重启生效） |

### 体育老师（TEACHER）

| 页面 | 功能 |
|------|------|
| 首页 | 待办事项、今日赛程、报名进度、关键统计 |
| 班级管理 | 班级列表、展开查看学生、Excel 导入导出、批量创建、绑定班主任 |
| 运动员管理 | 多维筛选（年级/班级/关键词）、号码簿自动生成、批量导入导出 |
| 项目管理 | 预设模板、Excel/CSV 导入、启用/禁用、道数/预赛/计分配置 |
| 报名管理 | 报名列表、单个/批量审核（通过/拒绝）、报名统计、导出 |
| 智能编排 | 自动分组分道（贪心+优化）、预览、批量编排、手动调整、回滚、道次表导出 |
| 项目编排 | 赛程自动调度（天×时段×场地）、手动调整、赛程导出 |
| 成绩管理 | 录入/修改/删除、Excel 导入、自动排名计算 |
| 排名积分 | 单项目排名、个人积分、团体总分、破纪录榜，三类均可导出 |
| 统计报表 | 秩序册 / 成绩册 / 统计报表（报名统计、道次表、成绩汇总、团体总分榜） |
| 系统设置 | 基本设置、积分规则、年级设置等 |

### 班主任（CLASS_TEACHER）

| 页面 | 功能 |
|------|------|
| 首页 | 本班统计、最近报名、本班赛程 |
| 班级名单 | Excel 导入全班花名册（自动创建学生账号+运动员）、手动添加、模板下载 |
| 运动会报名 | 学号定位 → 项目卡片报名、统计仪表、未报名名单、报名表导出 |
| 赛程查看 | 本班运动员的组次、道次、时间 |
| 成绩查看 | 本班成绩 + 总分/金银铜汇总 |

### 学生（STUDENT）

| 页面 | 功能 |
|------|------|
| 首页 | 个人统计、我的报名、我的赛程 |
| 我的赛程 | 个人参赛时间安排 |
| 我的成绩 | 个人成绩、名次、积分、是否破纪录 |
| 项目浏览 | 全部项目（含本人是否已报名标记） |
| 个人中心 | 个人资料查看 |

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
- **支持字段**：项目名称、代码、类别（径赛/田赛）、性别限制、道数、预赛开关、计分方式、纪录
- 启用/禁用开关、自定义新增/编辑/删除

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

```
Step 1: 获取已审核报名运动员 → 按班级分组
Step 2: 计算组数 = ceil(总人数 / 跑道数)
Step 3: 按班级人数降序（大班优先）
Step 4: 贪心分配 → 每人分配到同班最少组的最早空位
Step 5: 局部优化 (5轮×500次随机交换)
Step 6: 结果验证 → 保存（支持版本回滚）
```

| 约束类型 | 说明 |
|----------|------|
| 硬约束 | 同年级不混编、性别分离 |
| 软约束 | 同班不同道、同班不同组（可在编排规则中开关） |

支持：预览（不落库）、批量编排多个项目、手动调整、回滚、道次表导出。

### 9. 项目编排（赛程编排）

- 将比赛项目自动调度到「天 × 时段 × 场地」时间表（贪心负载均衡）
- 支持配置天数、时段、场地、每时段时长、默认项目用时
- 手动调整单项安排、导出赛程 Excel

### 10. 成绩 & 排名

- 成绩自动解析：`12.34`(秒) / `2:35.67`(分:秒) / `6.78`(米)
- 状态管理：valid / dq / dns / dnf
- 默认积分表：9-7-6-5-4-3-2-1（可自定义）
- 并列处理、破纪录加分、接力加倍、团体总分

### 11. 统计报表

| 报表 | 内容 |
|------|------|
| 秩序册 | 完整赛程手册（可导出） |
| 成绩册 | 完整成绩手册（可导出） |
| 报名统计表 | 各项目/班级报名人数 |
| 道次表 | 项目 × 组别 × 跑道矩阵 |
| 成绩汇总表 | 成绩、排名、积分 |
| 团体总分榜 | 班级总分 + 金银铜 |

### 12. 数据库热迁移 & 备份

- **热迁移**（管理员 → 系统设置 → 数据库迁移）：SQLite ↔ MySQL 在线切换，连接测试 → 异步迁移 → 进度查询，**全程无需重启服务**
- **备份**（管理员 → 系统设置 → 数据库备份）：立即备份、备份列表、下载、删除

---

## 📡 API 接口完整参考

后端共 **18 个 Controller、147 个端点**，统一前缀 `/api`。反向代理子路径部署时（如 `/sportmg/`），前端请求 `/sportmg/api/...` 由后端智能剥离前缀后路由到下列端点。

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

导出均为 `.xlsx`（EasyExcel），通过 `Content-Disposition: attachment` 下载。

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

前缀 `/api/events`，9 个端点。

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| GET | `/api/events` | Query grade?, gender?, eventType? | S/CT/T/SA | 项目列表（不分页） |
| GET | `/api/events/{id}` | Path id | S/CT/T/SA | 项目详情 |
| POST | `/api/events` | Body Event | T/SA | 创建项目 |
| PUT | `/api/events/{id}` | Path id, Body Event | T/SA | 更新项目 |
| PUT | `/api/events/{id}/status` | Path id, Body `{enabled}` | T/SA | 启用/禁用项目 |
| DELETE | `/api/events/{id}` | Path id | T/SA | 删除项目 |
| POST | `/api/events/presets` | Body categoryFilter | T/SA | 获取预设项目模板 |
| POST | `/api/events/import` | multipart `file` | T/SA | Excel 导入项目 |
| GET | `/api/events/export` | — | S/CT/T/SA | 导出项目数据 |

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

前缀 `/api/arrange`，8 个端点。

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| POST | `/api/arrange/events/{eventId}` | Path eventId, Body config | T/SA | 对指定项目执行自动编排 |
| POST | `/api/arrange/preview` | Body config | T/SA | 预览编排（不落库） |
| GET | `/api/arrange/events/{eventId}` | Path eventId | S/CT/T/SA | 查看项目编排结果 |
| PUT | `/api/arrange/events/{eventId}` | Path eventId, Body adjustments[] | T/SA | 手动调整编排 |
| DELETE | `/api/arrange/events/{eventId}` | Path eventId | T/SA | 清除该项目编排 |
| POST | `/api/arrange/batch` | Body `[eventIds]` | T/SA | 批量编排多个项目 |
| POST | `/api/arrange/events/{eventId}/rollback` | Path eventId | T/SA | 回滚编排 |
| GET | `/api/arrange/events/{eventId}/export` | Path eventId | S/CT/T/SA | 导出道次表（Excel） |

---

### 8. 赛程编排 Schedule

前缀 `/api/schedule`，5 个端点。

> ⚠️ 注意：`/api/schedule/**` 在 SecurityConfig 中无专属角色规则，落入兜底 `anyRequest().authenticated()`，即**任何已登录角色（含学生）均可访问**（含写操作）。如需收紧请补充角色规则。

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| GET | `/api/schedule` | — | 已认证 | 查看当前赛程 |
| POST | `/api/schedule/auto` | Body config? | 已认证 | 自动编排赛程 |
| POST | `/api/schedule/save` | Body items[] | 已认证 | 手动保存赛程（整体替换） |
| DELETE | `/api/schedule` | — | 已认证 | 清空赛程 |
| GET | `/api/schedule/export` | — | 已认证 | 导出赛程（Excel） |

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

前缀 `/api/system`，21 个端点。

| 方法 | 端点 | 参数 | 权限 | 说明 |
|------|------|------|------|------|
| GET | `/api/system/config` | — | T/SA | 获取全部系统配置 |
| GET | `/api/system/config/{key}` | Path key | T/SA | 获取单个配置 |
| PUT | `/api/system/config/{key}` | Path key, Body | T/SA | 更新单个配置 |
| PUT | `/api/system/config/basic` | Body 基本设置 | T/SA | 保存基本设置 |
| PUT | `/api/system/config/scoring` | Body 积分规则 | T/SA | 保存积分规则（旧接口） |
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

### 15. Excel 导入导出 Excel

前缀 `/api/excel`，9 个端点。

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
java -jar sports-2.0.0.jar

# 自定义端口 + 绑定地址（推荐写法）
java -jar sports-2.0.0.jar --app.port=8899 --app.host=::

# 等价的 Spring 标准写法
java -jar sports-2.0.0.jar --server.port=9090

# 后台运行
nohup java -jar sports-2.0.0.jar --app.port=8899 > app.log 2>&1 &
```

### 🔄 更换服务端口与绑定地址（优先级从高到低）

| 方式 | 操作 | 生效方式 |
|------|------|----------|
| ① 命令行参数 | `java -jar sports-2.0.0.jar --app.port=8899 --app.host=::`<br>`.\start.ps1 -Port 8899 -Host ::` / `start.bat --app.port=8899`<br>（也可用标准 `--server.port=9090`） | 立即（本次运行） |
| ② 环境变量 | `SERVER_PORT=9090 java -jar sports-2.0.0.jar`（Linux/macOS）<br>`$env:SERVER_PORT="9090"; java -jar ...`（PowerShell） | 立即（本次运行） |
| ③ 配置文件 | 编辑 `data/app-config.json`：`{"port": 9090, "host": "::"}` | 重启后生效 |
| ④ 界面操作 | 登录后 **系统设置 → 基本设置 → 服务端口** → 保存 → 重启应用 | 重启后生效 |

**说明：**
- 未做任何配置时默认端口为 **8080**、绑定全部网卡（0.0.0.0 / ::）。
- `--app.host` 支持 IPv4 / IPv6 与通配地址：`0.0.0.0`、`::`（IPv6 全网卡，兼容 IPv4）、`127.0.0.1` / `::1`。
- `data/app-config.json`、`sports_meet.db` 均相对**项目根目录**解析，请用根目录下的启动脚本或先 `cd` 到根目录。

### 🌐 反向代理子路径部署（如 `/sportmg/` 帽子）

系统原生支持挂在任意反向代理子路径下：`http://主站/:port/{name}/【正常业务路径】`，其中 `{name}`（如 `sportmg`）只是例子，**任意帽子均可、严禁也不需要在代码里写死**。前端资源（js/css）采用相对路径构建，后端内置「智能前缀剥离」过滤器，自动兼容两种反向代理形态：

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

两种形态下浏览器地址均保持 `http://host/sportmg/login` 形态，路由、资源、API 全部自适应；无反向代理时直接访问 `http://localhost:8080` 行为完全一致。访问 `http://host/sportmg/` 会自动进入登录/安装向导页。

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
│   编排算法 │ 赛程调度 │ 排名积分 │ EasyExcel │ 热迁移      │
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
copy sports-backend\target\sports-2.0.0.jar .
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

**Q4：导入 Excel 列名不匹配？**
项目管理支持 CSV 导入，模板格式：`项目名称,项目代码,类别,性别限制,...`。各模块均可先下载导入模板。

**Q5：班级名单导入后学生无法登录？**
导入时自动创建学生账号（用户名为学号，密码为学号），班主任端可查看。

**Q6：反向代理下 js/css 404？**
确认使用最新 jar（前端已改为相对路径构建）。若反代有缓存，清理缓存或确保 `index.html` 不被缓存（后端已强制 `no-store`）。

**Q7：`/api/schedule/**` 权限为何较宽？**
该路径在 SecurityConfig 中未配置专属角色规则，落入兜底 `anyRequest().authenticated()`，任何已登录角色可访问。如需收紧请补充角色规则。

---

## 📄 开源协议

本项目基于 **GNU Affero General Public License v3.0 (AGPL-3.0)** 开源。详见 [LICENSE](./LICENSE)。

> 任何基于此项目的网络服务（含 SaaS 形式）分发，均需按 AGPL-3.0 向用户提供完整源代码。前端动画采用原生 CSS / Web Animations API 实现（已移除 GSAP，满足 AGPL-3.0 合规要求）。

---

> **版本**: v2.0.0 | **API 端点**: 18 Controller / 147 个 | **构建日期**: 2026-09-04
