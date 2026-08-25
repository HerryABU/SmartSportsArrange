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
        // === 数据库热迁移：若存在外部连接配置，启动时自动切换数据源 ===
        applyExternalDbConfig();
        // === 应用运行配置：自定义端口/绑定地址（--app.port / --app.host 等）===
        applyAppConfig(args);
        SpringApplication.run(SportsApplication.class, args);
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
            host = String.valueOf(cfg.get("host")); hostSource = "data/app-config.json";
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
                System.setProperty("spring.datasource.url", "jdbc:sqlite:" + file);
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
