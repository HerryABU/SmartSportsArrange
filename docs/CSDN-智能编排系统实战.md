# 从0到1：用 Spring Boot 3.4 + Vue3 做一个能"智能排道次"的运动会编排系统（附核心算法）

> 每年运动会，最让人头大的不是比赛本身，而是**报名统计、手工排道次、成绩汇总**这三座大山。本文分享一个开源全栈项目 **SmartSportsArrange** 的设计与核心实现，重点拆解它的"智能编排算法"和一个大多数 Java 项目都会踩的**终端中文乱码**坑。代码已脱敏自真实生产级实现，可直接借鉴。

---

## 一、背景：为什么需要一个编排系统

传统做法里，体育老师要面对这些痛点：

- 几百名学生手工录入 Excel，班级、学号、性别一多就乱；
- 径赛项目按班级报名后，**道次和分组全靠人工拍脑袋**，很容易出现"某班整组包揽""同班学生挤在同一条道"的不公平情况；
- 成绩录入、破纪录加分、团体总分计算，纯手工极易算错；
- 秩序册、成绩册要反复排版导出。

SmartSportsArrange 的目标，就是把这些流程**搬到一个系统里**，覆盖「班级名单导入 → 报名 → 智能编排 → 成绩录入 → 排名积分 → 报表导出」全流程，并支持管理员 / 体育老师 / 班主任 / 学生四类角色协作。

---

## 二、技术架构与功能全景

后端 **Spring Boot 3.4（Java 21）**，前端 **Vue 3 + Vite + Element Plus + Pinia（动效走纯 CSS 过渡，无第三方动画库）**，前后端最终打包进**同一个可执行 JAR**，零配置启动。

```
┌──────────────────────────────────────────────┐
│  前端层  Vue3 + Element Plus（管理员/教师/班主任/学生端） │
└───────────────────────┬──────────────────────┘
                        │  HTTP / JWT
┌───────────────────────▼──────────────────────┐
│  后端层  Spring Boot 3.4                       │
│  Security+JWT | JPA | 编排算法 | 排名积分 | Excel  │
└───────────────────────┬──────────────────────┘
                        │
┌───────────────────────▼──────────────────────┐
│  数据层  SQLite(默认) / H2(开发) / MySQL(生产)  │
└──────────────────────────────────────────────┘
```

| 层次 | 技术 | 说明 |
|------|------|------|
| 语言/框架 | Java 21 / Spring Boot 3.4.5 | 基线版本 |
| 安全 | Spring Security + JWT 0.12 | 无状态鉴权 |
| ORM | Spring Data JPA + Hibernate | 实体 8 张表 |
| Excel | EasyExcel 4.0.3 | 名单/成绩批量导入导出 |
| 前端 | Vue 3 + Vite 6 + Element Plus 2.14 | CSS 变量主题 + 过渡动效（无 GSAP） |

---

## 三、核心实现思路

### 3.1 智能编排算法：贪心分配 + 局部优化

编排的本质，是把一堆已报名运动员填进 `组数 × 道数` 的矩阵里，同时满足**硬约束（同年级不混编、性别分离）**与**软约束（同班不同道、同班不同组）**。

算法分两大步：

**第一步：贪心分配（按班级从大到小填）**

先把运动员按班级分组，班级人数多的优先；每个运动员放入"当前同班人数最少的组"，尽量把同班打散。

```java
// 1. 计算需要的组数
int heats = (int) Math.ceil((double) athleteCount / lanes);

// 2. 按班级分组，并按人数降序
Map<Long, List<Athlete>> classAthletes = athletes.stream()
        .collect(Collectors.groupingBy(
                a -> a.getClassInfo() != null ? a.getClassInfo().getId() : 0L,
                LinkedHashMap::new, Collectors.toList()));
List<Map.Entry<Long, List<Athlete>>> sortedClasses = classAthletes.entrySet().stream()
        .sorted((e1, e2) -> Integer.compare(e2.getValue().size(), e1.getValue().size()))
        .collect(Collectors.toList());

// 3. 贪心：每个运动员放入"同班人数最少且未满"的组
Athlete[][] matrix = new Athlete[heats][lanes];
for (Map.Entry<Long, List<Athlete>> entry : sortedClasses) {
    Long classId = entry.getKey();
    for (Athlete athlete : entry.getValue()) {
        int bestHeat = -1, minSameClass = Integer.MAX_VALUE, minTotal = Integer.MAX_VALUE;
        for (int h = 0; h < heats; h++) {
            int sameClassInHeat = heatClassCounts.get(h).getOrDefault(classId, 0);
            int totalInHeat = heatClassCounts.get(h).values().stream().mapToInt(Integer::intValue).sum();
            if (totalInHeat >= lanes) continue;            // 该组已满
            if (sameClassInHeat < minSameClass ||
                (sameClassInHeat == minSameClass && totalInHeat < minTotal)) {
                minSameClass = sameClassInHeat; minTotal = totalInHeat; bestHeat = h;
            }
        }
        // 在该组找第一个空道填入
        for (int l = 0; l < lanes; l++) {
            if (matrix[bestHeat][l] == null) { matrix[bestHeat][l] = athlete; break; }
        }
    }
}
```

**第二步：局部优化（随机交换 + 代价函数）**

贪心结果未必最优。项目用了一个轻量的**模拟退火式随机交换**：随机挑两条道上的运动员互换，用代价函数评估是否更优，更优才保留。

```java
for (int round = 0; round < OPTIMIZATION_ROUNDS; round++) {   // 5 轮
    for (int attempt = 0; attempt < MAX_SWAP_ATTEMPTS; attempt++) {  // 每轮 500 次
        int h1 = (int)(Math.random() * heats), h2 = (int)(Math.random() * heats);
        // ...随机选 l1 / l2，交换 a1、a2...
        double costBefore = calculateCost(matrix, heatClassCounts, h1, h2, l1, l2, ...);
        matrix[h1][l1] = a2; matrix[h2][l2] = a1;            // 试探交换
        double costAfter  = calculateCost(matrix, simulatedCounts, h1, h2, l1, l2, ...);
        if (costAfter < costBefore) { heatClassCounts = simulatedCounts; }  // 更优则接受
        else { matrix[h1][l1] = a1; matrix[h2][l2] = a2; }   // 否则回滚
    }
}
```

代价函数对三类软约束施加不同权重：

```java
private double calculateCost(...) {
    double cost = 0;
    if (preferDiffHeat)      cost += (同班同组数 - 1) * 10.0;   // 同班同组惩罚最重
    if (preferDiffLane)      cost += 5.0;                       // 跨组同班同道
    if (banSameClassSameLane)cost += 20.0;                      // 同组同班不同道（最应避免）
    return cost;
}
```

实测几百人规模的编排**十几毫秒**即可完成，还支持 `version` 版本号与一键回滚——每次重排生成新版本，不满意随时 `rollback()`。

### 3.2 终端编码自动适配：一行都不用 chcp

Java 程序在 Windows 的 CMD（GBK）下跑，日志里的中文经常变成乱码。常规做法是让用户先 `chcp 65001`，但这很反人性。项目在 `main` 最开头做了**终端编码自动检测**：

```java
public static void main(String[] args) {
    autoDetectConsoleEncoding();                 // 先对齐编码，再启动 Spring
    SpringApplication.run(SportsApplication.class, args);
}

private static void autoDetectConsoleEncoding() {
    String consoleCharset = System.out.charset().name();   // Win=GBK, Linux/Mac=UTF-8
    System.setProperty("file.encoding", consoleCharset);
    System.setProperty("sun.stdout.encoding", consoleCharset);
    System.setProperty("sun.stderr.encoding", consoleCharset);
    // 重建 System.out/err，直连 OS 底层，彻底对齐终端编码
    var cs = System.out.charset();
    System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, cs));
    System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, cs));
}
```

关键是 `System.out.charset()` 拿到的是**终端实际编码**，把它写进 JVM 系统属性后，Logback、Spring 全会跟随。纯 `java -jar` 启动即自适应，**Windows / Linux / Mac 无需任何额外命令**。

### 3.3 单体 Jar 一体化部署：前后端一个包带走

很多同学前后端分离后，部署要起两个服务、配跨域，麻烦。这个项目把前端构建产物**直接打进后端 JAR**：

- `vite.config.js` 的构建输出指向 `../sports-backend/src/main/resources/static`；
- 后端用 `SpaFallbackConfig` 做 SPA 回退（未知路由转发到 `index.html`）；
- 一键脚本 `build.ps1`：前端 Vite → 后端 Maven → 根目录产出 `sports-1.0.0.jar`。

最终用户拿到一个 JAR：

```bash
java -jar sports-1.0.0.jar        # 默认 SQLite，零配置，访问 http://localhost:8080
```

---

## 四、关键代码说明小结

| 模块 | 设计要点 | 可取之处 |
|------|----------|----------|
| 编排算法 | 贪心打散 + 代价函数驱动的局部优化 | 简单、快、可解释，支持版本回滚 |
| 编码适配 | `main` 入口检测终端 charset 并对齐 JVM | 一处改动，彻底解决乱码 |
| 部署 | 前端产物嵌入后端 static + SPA 回退 | 单 JAR 交付，运维极简 |
| 数据 | SQLite 默认 / H2 开发 / MySQL 生产 | 零配置开箱即用，可平滑上生产 |

---

## 五、实践经验总结（避坑指南）

1. **编码问题别靠 `chcp` 硬扛**：从 `System.out.charset()` 入手对齐 JVM，比让用户改终端更稳。
2. **编排别追求"全局最优"**：几百人的组合爆炸用精确算法不划算，贪心 + 随机局部优化在工程上是最优解。
3. **结果要可回滚**：任何"自动生成"的数据都应该带版本号，否则一旦生成错乱，只能手工改库。
4. **默认数据库选 SQLite**：课程/毕设项目零配置就能跑，后期需要并发再切 MySQL，JPA 换方言即可。
5. **前后端一体化打包**：对小项目，把 Vue 产物塞进 Spring Boot 的 `static`，部署体验提升一个量级。

---

## 六、结语与互动

SmartSportsArrange 已开源，采用 **AGPL-3.0** 协议，包含完整的多角色前端、REST API、编排算法与 Excel 导入导出，开箱即用。如果你也在做校园信息化、毕业设计或全栈练手项目，欢迎参考或二次开发。

- 项目地址：`https://github.com/HerryABU/SmartSportsArrange`
- 如果你对**编排算法的约束权重**有更好的想法，或者遇到过更奇葩的编码乱码场景，欢迎在评论区交流～
- 觉得有用的话，点个赞 / 收藏，后续我会再写一篇「如何用 EasyExcel 做万级名单的高性能导入」。

**你认为校园类的管理系统，最该被"自动化"的是哪一步？** 评论区聊聊你的经历。
