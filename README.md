# 🏃 运动会智能编排系统

> Sports Meet Intelligent Arrangement System v2.0

基于 **Spring Boot 3.4 + Vue 3 + GSAP + Element Plus** 的全栈运动会管理系统。支持**管理员 / 体育老师 / 班主任 / 学生**多角色协作，覆盖**班级名单导入 → 运动会报名 → 智能编排 → 成绩录入 → 排名积分 → 报表导出**全流程。

---

## 目录

- [快速开始](#-快速开始)
- [默认账号](#-默认账号)
- [项目脚本](#-项目脚本)
- [技术架构](#-技术架构)
- [项目结构](#-项目结构)
- [功能详解](#-功能详解)
  - [登录页 & 入场动画](#1-登录页--入场动画)
  - [终端编码自动适配](#2-终端编码自动适配)
  - [系统配置 & 用户管理](#3-系统配置--用户管理)
  - [班级管理](#4-班级管理)
  - [班级名单 & 运动员](#5-班级名单--运动员)
  - [项目管理](#6-项目管理)
  - [运动会报名](#7-运动会报名)
  - [智能编排](#8-智能编排-核心)
  - [成绩 & 排名](#9-成绩--排名)
  - [统计报表](#10-统计报表)
- [API 接口](#-api-接口)
- [数据库设计](#-数据库设计)
- [部署指南](#-部署指南)
- [开发指南](#-开发指南)
- [FAQ](#-faq)

---

## 🚀 快速开始

### 前置要求

| 环境 | 版本 | 说明 |
|------|:----:|------|
| JDK | 21+ | Eclipse Temurin / OpenJDK |
| Node.js（仅开发） | 20+ | 前端构建 |
| Maven（仅开发） | 3.9+ | 或使用 `./mvnw` |

### 一键启动

> 本仓库 **不随附预编译 JAR**（构建产物已被 `.gitignore` 忽略）。请先按下方「从源码构建」生成 `sports-1.0.0.jar`，再启动：

```bash
# 构建（前端 Vite → 后端 Maven → 生成 JAR）
.\build.ps1

# 启动（默认 8080 端口，可用 -Port 9090 自定义）
.\start.ps1
```

如已生成 JAR，也可直接运行：

```bash
java -jar sports-1.0.0.jar
```

浏览器访问 **http://localhost:8080**

> ⚠️ Windows CMD 用户：执行前先运行 `chcp 65001`，或直接双击 `start.bat`。PowerShell 用户运行 `.\start.ps1`。JAR 已内置终端编码自动检测，非 UTF-8 终端会输出英文提示。

---

## 👥 默认账号

| 角色 | 账号 | 密码 | 权限范围 |
|------|------|------|----------|
| 超级管理员 | `admin` | `admin123` | 全部权限 + 用户管理 + 系统配置 |
| 体育老师 | `teacher` | `teacher123` | 编排 / 成绩 / 报表 / 基础数据 |
| 班主任 | `class_teacher` | `class123` | 本班名单 / 报名 / 赛程成绩查看 |
| 学生 | `student` | `student123` | 个人赛程 / 成绩 / 赛事浏览 |

> 生产环境请立即修改默认密码。

---

## 📜 项目脚本

项目根目录提供三个脚本：

| 脚本 | 平台 | 功能 |
|------|------|------|
| `build.ps1` | PowerShell | 一键编译：前端 Vite → 后端 Maven → jar 输出到根目录 |
| `start.ps1` | PowerShell | 自动检测终端编码 + 启动 `java -jar` |
| `start.bat` | CMD | 自动 `chcp 65001` + 启动 `java -jar` |

```powershell
# 编译（支持 -SkipFrontend / -SkipBackend 跳过步骤）
.\build.ps1

# 启动（默认 8080 端口，支持 -Port 9090 自定义）
.\start.ps1
```

---

## 🛠 技术架构

```
┌──────────────────────────────────────────────────────────┐
│                       前端层                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
│  │ 管理员/教师端 │  │  班主任端     │  │   学生端      │   │
│  │ Element Plus  │  │ Element Plus │  │ Element Plus  │   │
│  └──────────────┘  └──────────────┘  └──────────────┘   │
│     Vue 3 + Pinia + Vue Router + GSAP + Axios            │
└──────────────────────────┬───────────────────────────────┘
                           │ HTTP / JWT
┌──────────────────────────▼───────────────────────────────┐
│                     后端服务层                            │
│              Spring Boot 3.4 / Java 21                   │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐   │
│  │ Spring   │ │ Spring   │ │ Spring   │ │ AOP 日志 │   │
│  │ MVC      │ │ Security │ │ Data JPA │ │ + 缓存   │   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘   │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐   │
│  │ 编排算法 │ │ 排名积分 │ │ Excel    │ │ 统计报表 │   │
│  │ 贪心+优化│ │ 奖牌汇总 │ │ EasyExcel│ │ 秩序/成绩册│  │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘   │
└──────────────────────────┬───────────────────────────────┘
                           │
┌──────────────────────────▼───────────────────────────────┐
│                      数据层                               │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────────┐   │
│  │ SQLite   │  │   H2     │  │       MySQL 8.0      │   │
│  │ (默认)   │  │ (开发)   │  │      (生产环境)       │   │
│  └──────────┘  └──────────┘  └──────────────────────┘   │
└──────────────────────────────────────────────────────────┘
```

| 层次 | 组件 | 版本 |
|------|------|:----:|
| 语言 | Java | 21 LTS |
| 框架 | Spring Boot | 3.4.5 |
| 安全 | Spring Security + JWT | 6.x / 0.12 |
| ORM | Spring Data JPA + Hibernate | 6.6 |
| Excel | EasyExcel | 4.0.3 |
| 前端 | Vue 3 + Vite | 3.5 / 6.2 |
| UI | Element Plus | 2.14 |
| 动画 | GSAP | 3.12 |
| 状态管理 | Pinia | 4.0 |

---

## 📁 项目结构

```
sports/
├── README.md
├── build.ps1                ← 一键编译脚本（自动编码检测）
├── start.ps1                ← 一键启动脚本（PowerShell）
├── start.bat                ← 一键启动脚本（CMD）
├── sports-1.0.0.jar         ← 可执行 JAR
│
├── sports-backend/           ← Spring Boot 后端
│   ├── .mvn/jvm.config       Maven JVM 编码配置
│   ├── pom.xml
│   ├── mvnw / mvnw.cmd
│   └── src/main/
│       ├── java/com/sports/
│       │   ├── SportsApplication.java    启动入口 + 终端编码检测
│       │   ├── common/          ApiResponse, 异常处理
│       │   ├── config/          Security, CORS, DataInit, SPA回退
│       │   ├── security/        JWT 生成/验证/过滤器
│       │   ├── entity/          User, ClassInfo, Athlete, Event,
│       │   │                    Registration, Arrangement, Result, ...
│       │   ├── repository/      JPA Repository (9个)
│       │   ├── service/         业务逻辑 (11个)
│       │   ├── controller/      REST API (11个)
│       │   └── dto/             DTO + Excel 模型
│       └── resources/
│           ├── application.yml  主配置
│           └── static/          前端构建产物（嵌入JAR）
│
└── sports-frontend/            ← Vue 3 前端
    ├── index.html              GSAP CDN
    ├── vite.config.js          构建输出 → ../sports-backend/static
    └── src/
        ├── main.js
        ├── router/index.js     路由 + 鉴权守卫
        ├── stores/auth.js      Pinia 认证
        ├── utils/request.js    Axios 封装
        ├── layouts/            TeacherLayout, ClassTeacherLayout, StudentLayout
        └── views/
            ├── login/           Login.vue, Loading.vue
            ├── teacher/         Dashboard, Classes, Athletes, Events,
            │                    Registration, Arrange, Scores, Ranking, Reports, Settings
            ├── class-teacher/   Dashboard, Athletes, Registration, Schedule, Results
            └── student/         Home, Schedule, Results, Events
```

---

## 📋 功能详解

### 1. 登录页 & 入场动画

- **Loading 入场页**（`/loading`）：仿 FIFA 风格的 SPORTS 字母弹跳动画 + ⚽ 旋转，点击进入登录
- **登录页左侧**：4 个 GSAP 动画小人（紫/黑/橙/黄），跟随鼠标移动、眨眼、"偷窥"密码
- **密码切换**：点击眼睛图标 → 显示密码（保持可见），再点击 → 回到 `●●●` 圆点形式
- **错误摇动**：登录失败时小人集体振荡

### 2. 终端编码自动适配

JAR 启动时自动检测终端编码（Windows GBK / Linux UTF-8 / Mac UTF-8），Java 输出自适应：

```java
// SportsApplication.java
String consoleCharset = System.out.charset().name();  // GBK / UTF-8
System.setProperty("file.encoding", consoleCharset);  // 全栈跟随
```

Logback 通过 `${file.encoding}` 占位符动态跟随。**无需手动 chcp**，纯 `java -jar` 即自适应。

### 3. 系统配置 & 用户管理

**管理员**拥有完整的系统管理面板：

| Tab | 功能 |
|-----|------|
| 基本设置 | 运动会名称、日期、地点、状态 |
| 积分规则 | 前8名积分自定义、破纪录加分、参与分、报名限制 |
| 年级设置 | 年级组 CRUD |
| 批量创建 | 班级批量生成（按高中/初中/小学折叠选择）+ 用户批量生成 |
| 用户管理 | 按角色 Tab 查看，**班主任 Tab 可展开查看管辖班级的学生列表** |

### 4. 班级管理

管理员/体育老师端：

- 班级列表：名称、年级、编号、班主任、人数、参赛状态
- **展开查看学生**：点击班级行左侧箭头 → 展开该班学生姓名、学号、性别、号码
- Excel 导入/导出、新增/编辑/删除
- 批量创建班级 + 自动生成班主任账号

### 5. 班级名单 & 运动员

**班主任端「班级名单」**：

- Excel 导入全班花名册（学号/姓名/性别）→ 自动创建学生账号 + 运动员记录
- 手动添加单个学生
- 下载导入模板
- 支持重新导入（增量更新）

**管理员/体育老师端「运动员管理」**：

- 多维度筛选：年级/班级/关键词
- 号码簿自动生成
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

**约束规则**：性别匹配、每人最多3项、不可重复报名

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
| 软约束 | 同班不同道、同班不同组 |

### 9. 成绩 & 排名

- 成绩自动解析：`12.34`(秒) / `2:35.67`(分:秒) / `6.78`(米)
- 状态管理：valid / dq / dns / dnf
- 默认积分表：9-7-6-5-4-3-2-1
- 并列处理、破纪录加分、接力加倍、团体总分

### 10. 统计报表

| 报表 | 内容 |
|------|------|
| 报名统计表 | 各项目/班级报名人数 |
| 道次表 | 项目 × 组别 × 跑道矩阵 |
| 成绩汇总表 | 成绩、排名、积分 |
| 团体总分榜 | 班级总分 + 金银铜 |
| 秩序册/成绩册 | 完整赛程/成绩手册 |

---

## 📡 API 接口

### 认证

| 方法 | 端点 | 权限 |
|------|------|------|
| POST | `/api/auth/login` | 公开 |
| POST | `/api/auth/refresh` | 已认证 |
| POST | `/api/auth/change-password` | 已认证 |

### 班级 & 运动员

| 方法 | 端点 | 权限 |
|------|------|------|
| GET | `/api/classes` | 教师/管理员 |
| POST/PUT/DELETE | `/api/classes/{id}` | 教师/管理员 |
| POST | `/api/classes/import` | 教师 |
| POST | `/api/classes/batch` | 管理员 |
| GET | `/api/athletes?classId=X` | 教师/管理员/班主任 |
| POST | `/api/athletes` | 教师/班主任 |
| POST | `/api/athletes/import` | 教师 |

### 班主任端

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/class-teacher/import-roster` | 导入全班名单 Excel |
| GET | `/api/class-teacher/athletes` | 本班运动员 |
| GET | `/api/class-teacher/events` | 可选项目 |
| POST | `/api/class-teacher/register` | 报名项目 |
| DELETE | `/api/class-teacher/register/{id}` | 取消报名 |
| GET | `/api/class-teacher/registrations` | 报名列表 |
| GET | `/api/class-teacher/registrations/export` | 导出报名表 Excel |
| GET | `/api/class-teacher/dashboard` | 班级看板 |
| GET | `/api/class-teacher/schedule` | 赛程 |
| GET | `/api/class-teacher/results` | 成绩 |

### 项目 & 报名

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/events` | 项目列表 |
| POST | `/api/events` | 新增项目 |
| POST | `/api/events/import` | 导入 Excel/CSV |
| GET | `/api/events/export` | 导出 Excel |
| GET | `/api/registrations` | 报名列表 |
| POST | `/api/registrations` | 报名 |
| PUT | `/api/registrations/{id}/approve` | 审核通过 |

### 编排 & 成绩

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/arrange/events/{eventId}` | 智能编排 |
| GET | `/api/arrange/events/{eventId}` | 查看编排结果 |
| POST | `/api/results` | 录入成绩 |
| GET | `/api/results/events/{eventId}/ranking` | 排名 |
| GET | `/api/ranking/team-score` | 团体总分 |

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

---

## 🚢 部署指南

### JAR 运行

```bash
# 默认 SQLite（零配置）
java -jar sports-1.0.0.jar

# 指定端口
java -jar sports-1.0.0.jar --server.port=9090

# 后台运行
nohup java -jar sports-1.0.0.jar > app.log 2>&1 &
```

### 硬件建议

| 规模 | 参赛人数 | CPU | 内存 |
|------|:---:|:---:|:---:|
| 小型 | <500 | 2核 | 2GB |
| 中型 | 500-1500 | 4核 | 4GB |
| 大型 | 1500+ | 4核+ | 8GB |

---

## 🔧 开发指南

### 一键编译

```powershell
.\build.ps1                 # 全量编译
.\build.ps1 -SkipFrontend   # 仅后端
.\build.ps1 -SkipBackend    # 仅前端
```

### 手动编译

```bash
# 前端
cd sports-frontend && npm install && npx vite build

# 后端
cd sports-backend && .\mvnw.cmd clean package -DskipTests

# 输出
copy sports-backend\target\sports-1.0.0.jar .
```

### 前端开发模式

```bash
cd sports-frontend
npm run dev
# → http://localhost:3000（代理到 localhost:8080）
```

---

## ❓ FAQ

**Q1：中文乱码？**  
JAR 已内置终端编码自动检测。Windows CMD 用户建议用 `start.bat` 启动，PowerShell 用 `start.ps1`。

**Q2：如何重置数据？**  
删除 `sports_meet.db`，重启自动重建。

**Q3：班主任看不到报名入口？**  
管理员需在"班级管理"中为班主任绑定班级（`bind-teacher`），然后班主任端才能看到本班数据。

**Q4：导入Excel列名不匹配？**  
项目管理支持 CSV 导入，模板格式：`项目名称,项目代码,类别,性别限制,...`

**Q5：班级名单导入后学生无法登录？**  
导入时会自动创建学生账号（用户名为学号，密码为学号），班主任端可查看。

---

## 📄 开源协议

本项目基于 **GNU Affero General Public License v3.0 (AGPL-3.0)** 开源。详见 [LICENSE](./LICENSE)。

> 任何基于此项目的网络服务（含 SaaS 形式）分发，均需按 AGPL-3.0 向用户提供完整源代码。

---

> **版本**: v2.0 | **构建日期**: 2026-07-20
