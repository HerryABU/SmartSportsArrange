package com.sports.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.File;
import java.io.PrintWriter;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多数据库备份服务 — 支持 SQLite（文件复制）/ MySQL（导出 SQL）定期与手动备份。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackupService {

    private static final String BACKUP_DIR = "./data/backup";

    private final DataSource dataSource;

    // ==================== 定期备份 ====================

    /** 每天凌晨 3 点自动备份 */
    @Scheduled(cron = "0 0 3 * * ?")
    public void scheduledBackup() {
        try {
            String path = doBackup("auto");
            log.info("定期备份完成: {}", path);
            cleanupOldBackups(30);
        } catch (Exception e) {
            log.error("定期备份失败", e);
        }
    }

    // ==================== 手动备份 ====================

    public Map<String, Object> backupNow() {
        try {
            String path = doBackup("manual");
            log.info("手动备份完成: {}", path);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true);
            r.put("path", path);
            r.put("fileName", new File(path).getName());
            return r;
        } catch (Exception e) {
            throw new RuntimeException("备份失败: " + e.getMessage());
        }
    }

    // ==================== 列表 / 删除 ====================

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        File dir = new File(BACKUP_DIR);
        if (!dir.exists()) return result;
        File[] files = dir.listFiles((d, name) ->
                name.endsWith(".db") || name.endsWith(".sql"));
        if (files == null) return result;
        for (File f : files) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fileName", f.getName());
            m.put("size", f.length());
            m.put("sizeLabel", humanSize(f.length()));
            m.put("lastModified", f.lastModified());
            m.put("time", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(new java.util.Date(f.lastModified())));
            result.add(m);
        }
        result.sort((a, b) -> Long.compare((long) b.get("lastModified"), (long) a.get("lastModified")));
        return result;
    }

    public void delete(String fileName) {
        // 防目录穿越：仅允许删除备份目录内的文件
        String safe = new File(fileName).getName();
        File f = new File(BACKUP_DIR, safe);
        if (f.exists() && f.isFile()) {
            f.delete();
            log.info("删除备份: {}", safe);
        }
    }

    /** 下载备份（返回文件对象供 Controller 使用） */
    public File getBackupFile(String fileName) {
        String safe = new File(fileName).getName();
        File f = new File(BACKUP_DIR, safe);
        if (f.exists() && f.isFile()) return f;
        throw new IllegalArgumentException("备份文件不存在: " + safe);
    }

    // ==================== 核心备份 ====================

    private String doBackup(String tag) throws Exception {
        File dir = new File(BACKUP_DIR);
        if (!dir.exists()) dir.mkdirs();
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        try (Connection conn = dataSource.getConnection()) {
            String type = detectType(conn.getMetaData().getURL());
            if ("sqlite".equals(type)) {
                String url = conn.getMetaData().getURL();
                String filePath = url.replaceFirst("jdbc:sqlite:", "");
                File src = new File(filePath);
                if (!src.exists()) {
                    throw new IllegalStateException("SQLite 数据库文件不存在: " + filePath);
                }
                File backup = new File(dir, "sqlite_" + tag + "_" + ts + ".db");
                // 用 VACUUM INTO 生成一致性快照（避免复制时连接占用导致不一致）
                try (Statement st = conn.createStatement()) {
                    st.execute("VACUUM INTO '" + backup.getAbsolutePath().replace('\\', '/') + "'");
                } catch (Exception e) {
                    // 兜底：直接复制文件
                    log.warn("VACUUM INTO 失败，改用文件复制: {}", e.getMessage());
                    java.nio.file.Files.copy(src.toPath(), backup.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                return backup.getAbsolutePath();
            }
            // MySQL / 其他：导出 SQL 脚本
            File sqlFile = new File(dir, type + "_" + tag + "_" + ts + ".sql");
            exportSql(conn, sqlFile);
            return sqlFile.getAbsolutePath();
        }
    }

    private void exportSql(Connection conn, File sqlFile) throws Exception {
        String type = detectType(conn.getMetaData().getURL());
        DatabaseMetaData md = conn.getMetaData();
        List<String> tables = new ArrayList<>();
        try (ResultSet rs = md.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) tables.add(rs.getString("TABLE_NAME"));
        }
        String quote = "mysql".equals(type) ? "`" : "\"";
        try (PrintWriter pw = new PrintWriter(sqlFile, "UTF-8")) {
            pw.println("-- Database backup: " + LocalDateTime.now());
            for (String t : tables) {
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT * FROM " + quote + t + quote)) {
                    ResultSetMetaData rsmd = rs.getMetaData();
                    int colCount = rsmd.getColumnCount();
                    while (rs.next()) {
                        StringBuilder cols = new StringBuilder();
                        StringBuilder vals = new StringBuilder();
                        for (int i = 1; i <= colCount; i++) {
                            if (i > 1) { cols.append(", "); vals.append(", "); }
                            cols.append(quote).append(rsmd.getColumnName(i)).append(quote);
                            vals.append(sqlLiteral(rs.getObject(i)));
                        }
                        pw.println("INSERT INTO " + quote + t + quote
                                + " (" + cols + ") VALUES (" + vals + ");");
                    }
                }
            }
        }
    }

    private String sqlLiteral(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        return "'" + String.valueOf(v).replace("'", "''") + "'";
    }

    private String detectType(String url) {
        if (url == null) return "sqlite";
        String l = url.toLowerCase();
        if (l.contains("mysql")) return "mysql";
        if (l.contains("h2")) return "h2";
        return "sqlite";
    }

    /** 清理超过 keepDays 天的旧备份 */
    private void cleanupOldBackups(int keepDays) {
        File dir = new File(BACKUP_DIR);
        if (!dir.exists()) return;
        long cutoff = System.currentTimeMillis() - keepDays * 24L * 3600 * 1000;
        File[] files = dir.listFiles((d, name) -> name.endsWith(".db") || name.endsWith(".sql"));
        if (files == null) return;
        for (File f : files) {
            if (f.lastModified() < cutoff) {
                f.delete();
                log.info("清理过期备份: {}", f.getName());
            }
        }
    }

    private String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / 1048576.0);
    }
}
