package com.sports;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

@SpringBootApplication
@EnableCaching
@EnableAsync
@EnableScheduling
public class SportsApplication {

    public static void main(String[] args) {
        // === Java 适配目标终端编码，消除乱码（Win/Linux/Mac 通用）===
        autoDetectConsoleEncoding();
        // === -h / --help：仅打印帮助页并退出，不启动 Web 服务（主进程）===
        if (isHelpRequested(args)) {
            printHelp();
            System.exit(0);
        }
        // === 运行目录 .env 文件：提供数据库/运行配置（被下方 db-config.json 覆盖）===
        applyEnvFile();
        // === 数据库热迁移：若存在外部连接配置，启动时自动切换数据源 ===
        applyExternalDbConfig();
        // === 应用运行配置：自定义端口/绑定地址（--app.port / --app.host 等）===
        applyAppConfig(args);
        SpringApplication.run(SportsApplication.class, args);
    }

    /** 是否请求显示帮助页（-h / --help / /? / -? / /h） */
    private static boolean isHelpRequested(String[] args) {
        for (String a : args) {
            if (a == null) continue;
            String s = a.trim();
            if (s.equals("-h") || s.equalsIgnoreCase("--help")
                    || s.equals("/?") || s.equals("-?") || s.equalsIgnoreCase("/h")) {
                return true;
            }
        }
        return false;
    }

    /** 打印使用帮助页（端口 / 网口 / 数据库选型），不启动服务 */
    private static void printHelp() {
        String v = detectVersion();
        String[] lines = {
            "==============================================================",
            " 运动会智能编排系统 (SmartSportsArrange)  v" + v,
            "==============================================================",
            "",
            "用法：",
            "  java -jar sports-2.7.3.jar [选项]",
            "",
            "说明：传入 -h / --help 时，仅打印本帮助并立即退出，不会启动 Web 服务。",
            "",
            "--------------------------------------------------------------",
            "一、网络（端口 / 网口 / 绑定地址）",
            "--------------------------------------------------------------",
            "  端口（port）设置优先级（高 -> 低）：",
            "    1) 命令行  --app.port=8899          本次运行生效（推荐）",
            "    2) 环境变量 SERVER_PORT=8899        docker -e 等场景",
            "    3) 文件 data/app-config.json 的 port 字段  设置界面保存，重启生效",
            "    4) 默认 8080",
            "    （标准 Spring 参数 --server.port=8899 亦可用，优先级更高）",
            "",
            "  网口 / 绑定地址（host）设置：",
            "    --app.host=0.0.0.0        绑定全部网卡（默认，局域网可访问）",
            "    --app.host=127.0.0.1      仅本机回环，不接受外部连接",
            "    --app.host=::             绑定全部 IPv6 网卡",
            "    --app.host=192.168.1.10   仅绑定指定网卡 IP",
            "    也可用 data/app-config.json 的 host 字段，或标准 --server.address=...",
            "    不设置时绑定全部网卡。",
            "    运行目录 .env 文件同样支持 SERVER_PORT / SERVER_ADDRESS 键（重启生效）。",
            "",
            "  访问地址示例： http://<本机IP>:<port>/",
            "",
            "--------------------------------------------------------------",
            "二、数据库（三选一，默认 SQLite 零配置）",
            "--------------------------------------------------------------",
            "  ① SQLite（默认，零配置，文件 ./sports_meet.db）",
            "        java -jar sports-2.7.3.jar",
            "        纯文件库，无需安装数据库服务，适合单机 / 演示。",
            "",
            "  ② H2（Java 原生嵌入式数据库，文件模式 ./data/sports_meet）",
            "        java -jar sports-2.7.3.jar --spring.profiles.active=h2",
            "        随 JVM 启动、无需外部服务，兼容 MySQL 模式（MODE=MySQL）。",
            "",
            "  ③ MySQL（生产环境，需先建库）",
            "        java -jar sports-2.7.3.jar --spring.profiles.active=mysql",
            "",
            "  ▶ 通过 .env 文件配置（推荐部署方式）：",
            "        在 jar 同目录放置 .env，启动自动读取，例如切换 MySQL：",
            "          SPRING_DATASOURCE_URL=jdbc:mysql://host:3306/sports_meet?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
            "          SPRING_DATASOURCE_USERNAME=root",
            "          SPRING_DATASOURCE_PASSWORD=root",
            "          SPRING_DATASOURCE_DRIVER_CLASS_NAME=com.mysql.cj.jdbc.Driver",
            "          SPRING_JPA_DATABASE_PLATFORM=org.hibernate.dialect.MySQLDialect",
            "        也可直接 export 这些变量后启动（Spring 原生支持 OS 环境变量）。",
            "",
            "  数据库热迁移（在线切换，无需改代码）：",
            "        系统设置 -> 数据库迁移 中操作，写入 data/db-config.json；",
            "        重启应用后自动按该文件切换数据源（SQLite / H2 / MySQL 互转），",
            "        且优先级高于 .env 文件。迁移过程不中断服务。",
            "",
            "--------------------------------------------------------------",
            "三、其它常用选项",
            "--------------------------------------------------------------",
            "  --spring.profiles.active=<profile>   激活配置 Profile（h2 / mysql）",
            "  --help / -h / /?                     显示本帮助并退出",
            "  完整配置见 application.yml 及各 application-<db>.yml。",
            "",
            "==============================================================",
        };
        for (String l : lines) {
            System.out.println(l);
        }
    }

    /** 读取应用版本（优先取 jar 清单 Implementation-Version，回退常量） */
    private static String detectVersion() {
        try {
            String v = SportsApplication.class.getPackage().getImplementationVersion();
            if (v != null && !v.isBlank()) return v;
        } catch (Exception ignored) {
            // 非 jar 运行（如 IDE 内）取不到清单，回退常量
        }
        return "2.7.3";
    }

    /**
     * 读取 data/app-config.json（设置界面保存）与命令行参数，覆盖服务端口/绑定地址。
     * 支持的用户接口（优先级从高到低）：
     *   1) 命令行 --app.port=8899 --app.host=::（本次运行生效，推荐）
     *   2) 环境变量 SERVER_PORT（docker -e 等）
     *   3) data/app-config.json 的 port / host 字段（系统设置界面保存，重启生效）
     *   4) 默认端口 8080、绑定全部网卡
     * 标准 Spring 参数 --server.port / --server.address 仍可直接使用（优先级更高）。
     */
    private static void applyAppConfig(String[] args) {
        // 在 Spring 上下文刷新前显式锁定服务端口，避免在某些运行环境下
        // application.yml 中的 server.port 未被正确解析（表现为绑定随机端口 0）。
        final int defaultPort = 8080;

        // 读取 data/app-config.json（不存在则为 null）
        Map<String, Object> cfg = readAppConfigFile();

        // ---- 端口解析 ----
        int port = defaultPort;
        String portSource = "默认";
        String cliPort = argValue(args, "app.port");
        if (cliPort != null && cliPort.isBlank()) cliPort = null;
        if (cliPort != null) {
            Integer p = parsePort(cliPort);
            if (p != null) { port = p; portSource = "命令行 --app.port"; }
        }
        if (portSource.equals("默认")) {
            String envPort = System.getenv("SERVER_PORT");
            if (envPort != null && !envPort.isBlank()) {
                Integer p = parsePort(envPort.trim());
                if (p != null) { port = p; portSource = "环境变量 SERVER_PORT"; }
            }
        }
        if (portSource.equals("默认") && cfg != null) {
            Object cp = cfg.get("port");
            if (cp != null) {
                Integer p = cp instanceof Number n ? n.intValue() : parsePort(String.valueOf(cp));
                if (p != null) { port = p; portSource = "data/app-config.json"; }
            }
        }
        System.setProperty("server.port", String.valueOf(port));
        System.out.println("[app-config] 使用端口: " + port + "（来源: " + portSource + "）");

        // ---- 绑定地址解析（默认绑定全部网卡，不设置 server.address）----
        String host = null;
        String hostSource = null;
        String cliHost = argValue(args, "app.host");
        if (cliHost != null && cliHost.isBlank()) cliHost = null;
        if (cliHost != null) { host = cliHost; hostSource = "命令行 --app.host"; }
        else if (cfg != null && cfg.get("host") != null && !String.valueOf(cfg.get("host")).isBlank()) {
            host = String.valueOf(cfg.get("host")); hostSource = "data/app-config.json(host)";
        }
        // 未显式指定 host 时，按 bindMode 推导（ipv4 / ipv6 / localhost / all / ip）
        if (host == null && cfg != null) {
            String mode = str(cfg.get("bindMode"), null);
            if (mode != null && !mode.isBlank()) {
                String derived = hostForBindMode(mode);
                if (derived != null) { host = derived; hostSource = "data/app-config.json(bindMode)"; }
            }
        }
        if (host != null) {
            System.setProperty("server.address", host);
            System.out.println("[app-config] 绑定地址: " + host + "（来源: " + hostSource + "）");
        }
    }

    /** 从命令行参数中取 --key=value 的值；未提供返回 null */
    private static String argValue(String[] args, String key) {
        String prefix = "--" + key + "=";
        for (String a : args) {
            if (a != null && a.startsWith(prefix)) {
                String v = a.substring(prefix.length()).trim();
                if (!v.isEmpty()) return v;
            }
        }
        return null;
    }

    /** 解析合法端口（1-65535），非法返回 null */
    private static Integer parsePort(String s) {
        try {
            int p = Integer.parseInt(s);
            return (p > 0 && p < 65536) ? p : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 将网络绑定模式转换为 server.address 实际值。
     *   ipv4      -> 0.0.0.0（仅 IPv4 全部网卡）
     *   ipv6      -> ::     （IPv6 双栈，可同时接受 IPv4-mapped）
     *   localhost -> 127.0.0.1（仅本机回环）
     *   all       -> null  （不显式设置 = 框架默认绑定全部网卡）
     *   ip        -> null  （自定义 IP 需配合 host 字段，本函数不推导）
     * 返回 null 表示「不显式设置绑定地址」。
     */
    private static String hostForBindMode(String mode) {
        return switch (mode.toLowerCase()) {
            case "ipv4" -> "0.0.0.0";
            case "ipv6" -> "::";
            case "localhost", "loopback" -> "127.0.0.1";
            default -> null; // all / ip / 未知：不显式设置
        };
    }

    /** 读取 data/app-config.json；不存在或解析失败返回 null */
    private static Map<String, Object> readAppConfigFile() {
        File cfg = new File("./data/app-config.json");
        if (!cfg.exists()) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(cfg, Map.class);
        } catch (Exception e) {
            System.err.println("[app-config] 读取应用运行配置失败，回退默认配置: " + e.getMessage());
            return null;
        }
    }

    /**
     * 读取 data/db-config.json（数据库迁移后写入），覆盖数据源连接与方言。
     * 迁移完成后重启应用即自动切换至目标数据库，无需手动改配置。
     */
    private static void applyExternalDbConfig() {
        File cfg = new File("./data/db-config.json");
        if (!cfg.exists()) return;
        try {
            Map<String, Object> c = new com.fasterxml.jackson.databind.ObjectMapper().readValue(cfg, Map.class);
            String type = String.valueOf(c.getOrDefault("type", "")).toLowerCase();
            String host = str(c.get("host"), "localhost");
            String port = str(c.get("port"), "3306");
            String database = str(c.get("database"), "sports");
            String username = str(c.get("username"), "root");
            String password = str(c.get("password"), "");

            if ("mysql".equals(type)) {
                System.setProperty("spring.datasource.url", "jdbc:mysql://" + host + ":" + port + "/" + database
                        + "?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true");
                System.setProperty("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
                System.setProperty("spring.datasource.username", username);
                System.setProperty("spring.datasource.password", password);
                System.setProperty("spring.jpa.database-platform", "org.hibernate.dialect.MySQLDialect");
                System.setProperty("spring.jpa.properties.hibernate.dialect", "org.hibernate.dialect.MySQLDialect");
                System.out.println("[db-config] 使用外部 MySQL 数据源: " + host + ":" + port + "/" + database);
            } else if ("sqlite".equals(type)) {
                String file = str(c.get("file"), "./sports_meet.db");
                String sqliteUrl = "jdbc:sqlite:" + file;
                // 开启外键强制（SQLite 默认关闭）；URL 可能已带 query 参数
                if (!sqliteUrl.contains("foreign_keys=")) {
                    sqliteUrl += (sqliteUrl.contains("?") ? "&" : "?") + "foreign_keys=ON";
                }
                System.setProperty("spring.datasource.url", sqliteUrl);
                System.setProperty("spring.datasource.driver-class-name", "org.sqlite.JDBC");
                System.setProperty("spring.datasource.username", "");
                System.setProperty("spring.datasource.password", "");
                System.setProperty("spring.jpa.database-platform", "org.hibernate.community.dialect.SQLiteDialect");
                System.setProperty("spring.jpa.properties.hibernate.dialect", "org.hibernate.community.dialect.SQLiteDialect");
                System.out.println("[db-config] 使用外部 SQLite 数据源: " + file);
            }
        } catch (Exception e) {
            System.err.println("[db-config] 读取数据库配置失败，回退默认配置: " + e.getMessage());
        }
    }

    /**
     * 读取运行目录下的 .env 文件（若存在），将其中的数据库相关配置写入 System properties，
     * 供 Spring Boot 通过 relaxed binding 覆盖 application.yml 的默认数据源。
     * 若 .env 不存在（首次运行），则先生成一个默认模板（SQLite 默认，MySQL/H2 以注释示例给出），
     * 用户可编辑后重启生效；生成的模板与 application.yml 默认值一致，首次运行行为不变。
     * 支持的键（标准 Spring 环境变量名）：
     *   SPRING_DATASOURCE_URL                JDBC 连接串
     *   SPRING_DATASOURCE_USERNAME           用户名
     *   SPRING_DATASOURCE_PASSWORD           密码
     *   SPRING_DATASOURCE_DRIVER_CLASS_NAME  JDBC 驱动类
     *   SPRING_JPA_DATABASE_PLATFORM         Hibernate 方言（切换 MySQL/H2 时必须设置）
     * 其它以 SPRING_ 开头的键会按 relaxed binding 规则（下划线转点、转小写）原样透传。
     * 优先级（高 -> 低）：命令行 --spring.datasource.url > 本文件(.env) > data/db-config.json（界面迁移）> application.yml。
     * 注：Spring Boot 原生亦支持 OS 环境变量，故 export 上述变量后启动同样生效；本函数仅为“放下 .env 即生效”提供便利。
     */
    private static void applyEnvFile() {
        File env = new File("./.env");
        if (!env.exists()) {
            writeDefaultEnv(env);
        }
        if (!env.exists()) return; // 模板生成失败则回退默认配置
        try {
            List<String> lines = Files.readAllLines(env.toPath(), StandardCharsets.UTF_8);
            boolean any = false;
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim();
                String val = unquote(line.substring(eq + 1).trim());
                if (key.isEmpty()) continue;
                switch (key) {
                    case "SPRING_DATASOURCE_URL":
                        System.setProperty("spring.datasource.url", val); any = true; break;
                    case "SPRING_DATASOURCE_USERNAME":
                        System.setProperty("spring.datasource.username", val); any = true; break;
                    case "SPRING_DATASOURCE_PASSWORD":
                        System.setProperty("spring.datasource.password", val); any = true; break;
                    case "SPRING_DATASOURCE_DRIVER_CLASS_NAME":
                        System.setProperty("spring.datasource.driver-class-name", val); any = true; break;
                    case "SPRING_JPA_DATABASE_PLATFORM":
                        System.setProperty("spring.jpa.database-platform", val); any = true; break;
                    case "SERVER_PORT":
                        System.setProperty("server.port", val); any = true; break;
                    case "SERVER_ADDRESS":
                        System.setProperty("server.address", val); any = true; break;
                    default:
                        if (key.startsWith("SPRING_")) {
                            System.setProperty(key.toLowerCase().replace('_', '.'), val); any = true;
                        }
                }
            }
            if (any) System.out.println("[env] 已加载运行目录 .env 数据库配置");
        } catch (Exception e) {
            System.err.println("[env] 读取 .env 失败，回退默认配置: " + e.getMessage());
        }
    }

    /** 首次运行：在运行目录生成 .env 默认模板（SQLite 默认，MySQL/H2 以注释示例给出） */
    private static void writeDefaultEnv(File env) {
        try {
            Files.writeString(env.toPath(), DEFAULT_ENV_TEMPLATE, StandardCharsets.UTF_8);
            System.out.println("[env] 首次运行：已在运行目录生成 .env 模板（默认 SQLite），可编辑后重启生效");
        } catch (Exception e) {
            System.err.println("[env] 生成 .env 模板失败，回退默认配置: " + e.getMessage());
        }
    }

    /** .env 默认模板：SQLite 默认生效；MySQL/H2 以注释示例给出，切换时务必带 SPRING_JPA_DATABASE_PLATFORM */
    private static final String DEFAULT_ENV_TEMPLATE = """
# ============================================================================
# 运动会智能编排系统 (SmartSportsArrange) — 运行目录数据库 / 运行配置
# ============================================================================
# 用法：编辑本文件后重启 java -jar 即生效（无需 source / export）。
# 也可直接 export 这些变量后启动（Spring Boot 原生支持 OS 环境变量）。
# 优先级：命令行 --spring.*  >  本文件(.env)  >  data/db-config.json（界面热迁移）  >  application.yml
#
# 切换数据库时务必同时设置 SPRING_JPA_DATABASE_PLATFORM（Hibernate 方言），
# 否则会用默认的 SQLiteDialect 去连 MySQL/H2 而报错。
# 注：本文件已被 .gitignore 忽略，不会随仓库提交（避免泄露数据库密码）。
# ============================================================================

# ---------- ① SQLite（默认，零配置）----------
SPRING_DATASOURCE_URL=jdbc:sqlite:./sports_meet.db?foreign_keys=ON
SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.sqlite.JDBC
SPRING_JPA_DATABASE_PLATFORM=org.hibernate.community.dialect.SQLiteDialect

# ---------- ② MySQL（生产环境，需先建库）----------
# SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/sports_meet?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
# SPRING_DATASOURCE_USERNAME=root
# SPRING_DATASOURCE_PASSWORD=your_mysql_password
# SPRING_DATASOURCE_DRIVER_CLASS_NAME=com.mysql.cj.jdbc.Driver
# SPRING_JPA_DATABASE_PLATFORM=org.hibernate.dialect.MySQLDialect

# ---------- ③ H2（嵌入式，文件模式）----------
# SPRING_DATASOURCE_URL=jdbc:h2:./data/sports_meet;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
# SPRING_DATASOURCE_USERNAME=sa
# SPRING_DATASOURCE_PASSWORD=
# SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.h2.Driver
# SPRING_JPA_DATABASE_PLATFORM=org.hibernate.dialect.H2Dialect

# ---------- ④ 服务端口与网络绑定（参考 NVS .env 风格）----------
# 也可用 data/app-config.json 的 port / host / bindMode 字段（系统设置界面保存，重启生效）。
#   SERVER_PORT    服务端口（默认 8080）
#   SERVER_ADDRESS 绑定地址：0.0.0.0=全部 IPv4 网卡；::=IPv6 双栈；127.0.0.1=仅本机；或指定网卡 IP
# 等价写法（优先级更高）：--app.port=8899  --app.host=::
# SERVER_PORT=8080
# SERVER_ADDRESS=0.0.0.0
""";

    /** 去除值两侧的引号（' 或 "），支持含空格/特殊字符的值 */
    private static String unquote(String s) {
        if (s.length() >= 2
                && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String str(Object v, String def) {
        return v != null && !String.valueOf(v).isBlank() ? String.valueOf(v) : def;
    }

    /** 自动检测终端，Java 输出适配终端编码（Win/Linux/Mac 通用） */
    private static void autoDetectConsoleEncoding() {
        // 1. 获取终端实际编码（Windows=GBK, Linux/Mac=UTF-8）
        String consoleCharset = System.out.charset().name();

        // 2. JVM 属性对齐终端编码（后续 Logback/Spring 都会跟随）
        System.setProperty("file.encoding", consoleCharset);
        System.setProperty("sun.stdout.encoding", consoleCharset);
        System.setProperty("sun.stderr.encoding", consoleCharset);

        // 3. 重建 System.out/err，直连 OS 底层，编码对齐终端
        try {
            var cs = System.out.charset();
            System.setOut(new PrintStream(
                    new FileOutputStream(FileDescriptor.out), true, cs));
            System.setErr(new PrintStream(
                    new FileOutputStream(FileDescriptor.err), true, cs));
        } catch (Exception e) {
            try {
                var cs = System.out.charset();
                System.setOut(new PrintStream(System.out, true, cs));
                System.setErr(new PrintStream(System.err, true, cs));
            } catch (Exception ignored) {}
        }
    }

    /** 启动时自动创建数据目录和数据库文件父目录 */
    @Bean
    ApplicationRunner ensureDataDirs() {
        return args -> {
            List<String> dirs = List.of(
                "./data", "./data/logs", "./data/uploads",
                "./data/exports", "./data/backup", "./data/avatars"
            );
            for (String dir : dirs) {
                File f = new File(dir);
                if (!f.exists()) {
                    boolean created = f.mkdirs();
                    System.out.println("[init] " + (created ? "创建目录" : "目录已存在") + ": " + f.getAbsolutePath());
                }
            }

            // SQLite: 确保数据库文件父目录存在
            File dbFile = new File("./sports_meet.db");
            File dbParent = dbFile.getAbsoluteFile().getParentFile();
            if (dbParent != null && !dbParent.exists()) {
                dbParent.mkdirs();
                System.out.println("[init] 创建数据库目录: " + dbParent.getAbsolutePath());
            }

            // H2: 确保 data 目录存在（H2 文件模式存储在此）
            File h2Dir = new File("./data");
            if (!h2Dir.exists()) {
                h2Dir.mkdirs();
            }

            System.out.println("[init] 数据目录初始化完成");
        };
    }
}
