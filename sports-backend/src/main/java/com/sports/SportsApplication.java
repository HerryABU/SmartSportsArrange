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

@SpringBootApplication
@EnableCaching
@EnableAsync
@EnableScheduling
public class SportsApplication {

    public static void main(String[] args) {
        // === Java 适配目标终端编码，消除乱码（Win/Linux/Mac 通用）===
        autoDetectConsoleEncoding();
        SpringApplication.run(SportsApplication.class, args);
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
