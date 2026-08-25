# SmartSportsArrange 质量保障报告（QA）

> 项目：运动会智能编排系统（SmartSportsArrange）
> 技术栈：Spring Boot 3.4.5 + Java 21 + Vue 3 + SQLite/H2/MySQL
> 测试范围：功能完整性检查 · 单元测试 · 全方位冒烟测试
> 日期：2026-08-24

---

## 一、结论速览

| 维度 | 结果 | 说明 |
|------|------|------|
| 功能完整性检查 | ✅ PASS | 21 个控制器 / README 接口均有实现，无空壳 stub；GSAP 仅存于文档/示例（AGPL 合规） |
| 单元测试 | ✅ PASS | **37/37 通过**，7 个测试类；暴露并修复 3 个生产缺陷 |
| 冒烟测试 | ✅ PASS | 启动、SQLite 初始化、前端 200、JWT 登录 + 鉴权端点 200 |
| 缺陷修复（共 4 处） | ✅ 已修复并重建 jar | 3 处来自单测，1 处（随机端口）来自冒烟 |
| 交付物 | ✅ `sports-1.0.0.jar` 已重建 | 备份：`sports-1.0.0.jar.bak`（原始）、`sports-1.0.0.jar.bak2` |

**本次共发现并修复 4 个真实缺陷**，其中 1 个（团队排名倒置、号码生成崩溃）属于严重逻辑错误，会在生产环境直接产出错误结果；另 1 个（随机端口）会导致 `java -jar` 在没有明确参数的情况下绑定不可预测的端口，严重影响可用性。

---

## 二、功能完整性检查（PASS）

- 后端 21 个 Controller 覆盖：认证、用户/角色、班级、运动员、项目、报名、编排、成绩、排名、系统设置、数据库迁移、Excel 导入导出等；对应 README 与需求文档中的接口清单，**无空实现、无 TODO 占位**。
- 前端 Vue 3 已构建并内嵌于 jar（`BOOT-INF/classes/static`），` welcome page: static/index.html` 正常挂载。
- 默认账户 `admin/admin123`、`teacher/teacher123`、`class_teacher/class123`、`student/student123` 均可登录。
- 许可证合规：前端已彻底移除 GSAP（仅文档/示例提及），符合 AGPL-3.0。

---

## 三、单元测试（37/37 PASS）

### 3.1 执行情况
Maven wrapper（`mvnw`）在本环境损坏，无法直接走 `mvn test`。改用**自建 JUnit Platform Launcher**（`src/test/java/RunTests.java`），在 PowerShell 中以原生 `javac/java` + Windows 路径完成编译与执行（关键点：原生 `javac.exe` 不识别 Git-Bash 的 `/c/...` 路径，必须用 Windows 反斜杠路径与 `*.jar` 通配）。

- 测试类（7 个）：
  - `security/JwtUtilTest`
  - `service/NumberRuleServiceTest`
  - `service/RegistrationServiceTest`
  - `service/ArrangementServiceTest`
  - `service/ResultServiceTest`
  - `service/RankingServiceTest`
  - `service/SystemServiceTest`
- 结果：**37 tests found · 37 successful · 0 failed**（最终运行 `test_run4.log`：`ALL PASSED`，`RUN_EXIT=0`）。

### 3.2 单元测试暴露并修复的 3 个生产缺陷

| # | 位置 | 缺陷 | 后果 | 修复 |
|---|------|------|------|------|
| B1 | `NumberRuleService.render()` | `"%0"+width+"d"` → 拼出 `"%002d"`，抛 `DuplicateFormatFlagsException` | 任何带 `:NNd` 宽度（如默认 `{seq:02d}`）的号码生成**直接崩溃** | 改为 `String.format("%"+width+"d", ...)` |
| B2 | `RankingService.getTeamScores()` | `.reversed()` 作用于**每一个** `thenComparing` 步骤，逐级相互抵消 | 团队排名**完全倒置**（低分排第一） | 比较链末尾统一 `.reversed()` 一次 |
| B3 | `NumberRuleService` 预览 | `ClassInfo` 硬编码 `classOrder(1)`，忽略真实班级名 | 预览中班级信息错误 | 改用 `ClassInfo.builder().name(className)` |

> B1 与 B2 均为"会产出错误结果/直接异常"的级别，已随 jar 重建一并修复并验证。

### 3.3 测试自身修正（非生产代码）
- `NumberRuleServiceTest`：期望 `A052` → 正确应为 `A0502`（classOrder=5→"05"，seq=2→"02"）。
- `RankingServiceTest`：个人总分分页测试数据构造错误（5 个结果被误认为 5 名运动员），已修正。
- `SystemServiceTest`：Jackson 反序列化 JSON 数字为 `Integer/Long`，断言 `9.0` 应改为 `(Number).doubleValue()`；并补 `import java.util.Map`。

---

## 四、冒烟测试（PASS）

### 4.1 端到端验证（重建 jar，端口 9090 规避本机 8080 被占）
| 检查项 | 结果 |
|--------|------|
| 应用启动 | ✅ `Started SportsApplication in ~10s` |
| 数据库初始化 | ✅ Hikari + SQLite（`sports_meet.db`）连接成功 |
| 前端首页 | ✅ `GET /` → HTTP 200 |
| 管理员登录 | ✅ `POST /api/auth/login` → 有效 JWT（`data.accessToken`，`role=ROLE_SUPER_ADMIN`） |
| 鉴权端点 | ✅ 带 token `GET /api/students` → HTTP 200；无 token → 401/403 |
| 默认端口修正后 | ✅ 日志 `Tomcat initialized with port 8080`（修复前为 `port 0`） |

### 4.2 冒烟测试发现并修复的第 4 个缺陷（高危）

**现象**：`java -jar sports-1.0.0.jar` 绑定**随机端口**（实测 7476 / 8383 / 13701），而非 `application.yml` 配置的 `server.port: 8080`。即使用户不传任何参数，应用也会监听一个不可预测的端口，导致"启动成功却找不到服务"。该问题**在原交付 jar（`.bak`）中同样存在**，属历史缺陷。

**排查**：已排除
- 环境变量（`PORT` / `SERVER_PORT` 为空，无 `KUBERNETES_*` / `VCAP_*` 云环境变量）；
- 外部配置文件（项目根目录无 `application.properties` / `config/`）；
- 代码覆盖（全仓仅 `SportsApplication.applyAppConfig()` 设置 `server.port`，且仅在 `data/app-config.json` 存在时）；
- `application.yml` 内容（经 `unzip` 提取比对，与源码逐字节一致，`server: port: 8080` 结构正确）。

根因指向运行期 `server.port` 未被正确解析为 8080（解析为 0 → 随机端口），属 Spring 配置解析层面的异常表现。

**修复**：在 `SportsApplication.applyAppConfig()` 中，于 `SpringApplication.run(...)` **之前**显式设置文档约定默认端口：
```java
final int defaultPort = 8080;
File cfg = new File("./data/app-config.json");
if (!cfg.exists()) {
    System.setProperty("server.port", String.valueOf(defaultPort));
    // 打印 [app-config] 使用默认端口: 8080
    return;
}
// app-config.json 存在时仍按原逻辑读取自定义端口，无有效端口则回退 defaultPort
```
`--server.port=XXXX` 与 `data/app-config.json` 的自定义端口优先级仍高于该默认值，行为兼容。

**验证**：修复后启动日志由 `Tomcat initialized with port 0` 变为 `Tomcat initialized with port 8080`，确认默认端口生效。

> 环境提示：本机 8080 被 `nvs-server.exe`（NVIDIA，PID 15264）占用，属第三方服务冲突，与本项目无关；在 8080 空闲的机器上应用将直接监听 8080。

---

## 五、交付物状态

- **`sports-1.0.0.jar`**：已包含全部 4 处修复并重建（`BOOT-INF/classes` 已更新，静态资源与 `BOOT-INF/lib` 完好，`jar tf` 校验通过）。
- 备份：
  - `sports-1.0.0.jar.bak` —— 原始未修改版本。
  - `sports-1.0.0.jar.bak2` —— 仅含前 3 个逻辑修复（随机端口修复前）的版本。

---

## 六、遗留风险与建议

1. **随机端口根因未彻底溯源**：已用显式默认端口绕过，但若需从根本解决，建议排查 Spring Boot 3.4.5 在本运行环境下 `server.port` 默认解析异常的具体来源（可加 `-Ddebug` 或 `Environment` 探针定位 property source 顺序）。
2. **Maven wrapper 损坏**：本环境 `mvnw` 不可用，已用独立 JUnit Launcher 替代。建议在 CI 中使用完整 Maven 环境执行 `mvn test`，将 7 个测试类纳入门禁。
3. **测试覆盖建议扩展**：当前未覆盖 Controller 层（HTTP 层）与前端 E2E；后续可补充 `@WebMvcTest` / MockMvc 与 Playwright 冒烟，进一步前移缺陷发现点。
4. **数据库迁移（SQLite ↔ MySQL）热切换**：功能已存在，建议补充针对迁移后数据一致性的专项测试。

---

## 附：执行命令与产物索引（供复现）

- 单测执行：`RunTests.java`（JUnit Platform Launcher）
- 重编译/重打包流水线：`jar xf` 抽取 `BOOT-INF/lib` → `javac -parameters -cp <libs>+lombok` 编译 main → `jar uf` 更新 `BOOT-INF/classes`（**必须在 PowerShell 用 Windows 路径**）
- 关键日志：`C:\sports_test\test_run4.log`（单测 ALL PASSED）、`C:\sports_test\rejar2.log`（重打包 MAIN_COMPILE_EXIT=0 / JAR_UPDATE_EXIT=0）、`C:\sports_test\smoke_final.result.txt`（冒烟 PASS）

---

## 七、端口可配置化（新增需求：支持换端口）

**结论：✅ 全部通过。** 系统支持 4 种换端口方式（优先级从高到低：命令行 > 环境变量 > 配置文件 > 界面默认），并在重建后的 `sports-1.0.0.jar` 上逐项实测验证。

| 方式 | 实测 | 结果 |
|------|------|------|
| ① 命令行 `--server.port=9208` | `Tomcat initialized with port 9208` + health 200 | ✅ |
| ② 环境变量 `SERVER_PORT=8088` | 启动日志 `使用环境变量端口 SERVER_PORT=8088`，`Tomcat initialized with port 8088` | ✅（本次新增支持） |
| ③ 配置文件 `data/app-config.json` `{"port":9107}`（界面保存） | `PUT /api/system/app-config` 写入成功；纯 `java -jar` 重启后绑定 9107，health 200 | ✅ |
| ④ 界面「系统设置 → 应用运行配置 → 服务端口」 | 前端（含 jar 内打包版本）与后端 `GET/PUT /api/system/app-config` 均正常 | ✅ |
| 默认（无任何配置） | 日志 `使用默认端口: 8080`；本机 8080 被 nvs-server.exe 占用时报 `Port 8080 was already in use`（证明随机端口缺陷已修复） | ✅ |

**本次代码/文件改动：**
- `SportsApplication.applyAppConfig()`：新增 `SERVER_PORT` 环境变量支持（docker `-e` 场景），并保持原有 `--server.port` / `app-config.json` / 默认 8080 的优先级链。
- `start.ps1`：固定工作目录到脚本根目录（`data/app-config.json`、`sports_meet.db` 相对根目录解析）；`-Port` 未传时自动读取 `data/app-config.json`，否则默认 8080。
- `start.bat`：开头增加 `cd /d "%~dp0"`，从任意目录双击均可正确找到配置与数据库。
- `README.md`：新增「🔄 更换服务端口（四种方式）」章节。
- `sports-1.0.0.jar` 已重建（备份 `sports-1.0.0.jar.bak3`）；验证日志：`C:\sports_test\rejar3.log` / `rejar3b.log`（MAIN_COMPILE_EXIT=0 / JAR_UPDATE_EXIT=0）、`port_env.log` / `port_cli.log` / `port_default.log` / `port_test2.log` / `port_test3.log`。

**遗留提示：** 若需从根本解决「为何 `application.yml` 的 8080 曾解析为 0」，仍建议加 `-Ddebug` 定位 property source 顺序（已用显式默认绕过，不影响上述 4 种换端口方式）。
