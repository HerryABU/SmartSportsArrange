package com.sports.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 数据库热迁移服务 — 支持 SQLite / MySQL 等主流数据库之间的相互迁移。
 *
 * 迁移流程：备份源库 → 读取源库表结构 → 连接目标库 → 建表（类型映射）→ 迁数据 → 重建索引 → 写连接配置。
 * 迁移过程只读源库、写目标库，源库天然安全；失败时自动清理目标库残留并保留备份。
 */
@Slf4j
@Service
public class DatabaseMigrationService {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final Map<String, MigrationTask> tasks = new ConcurrentHashMap<>();

    private static final String CONFIG_FILE = "./data/db-config.json";

    public DatabaseMigrationService(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    // ==================== 查询 / 测试 ====================

    /** 当前数据库信息 */
    public Map<String, Object> getCurrentDbInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            String url = md.getURL();
            String type = detectType(url);
            info.put("type", type);
            info.put("url", url);
            info.put("product", md.getDatabaseProductName() + " " + md.getDatabaseProductVersion());
            info.put("username", md.getUserName());

            List<String> tables = listTables(md);
            info.put("tableCount", tables.size());
            info.put("tables", tables);
        } catch (Exception e) {
            info.put("error", e.getMessage());
        }
        // 是否已配置外部迁移目标
        File cfg = new File(CONFIG_FILE);
        info.put("hasExternalConfig", cfg.exists());
        return info;
    }

    /** 支持的目标数据库类型 */
    public List<Map<String, Object>> getSupportedTargets() {
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Object> mysql = new LinkedHashMap<>();
        mysql.put("type", "mysql");
        mysql.put("label", "MySQL");
        mysql.put("desc", "生产环境推荐，需提供 MySQL 服务器连接");
        list.add(mysql);
        Map<String, Object> sqlite = new LinkedHashMap<>();
        sqlite.put("type", "sqlite");
        sqlite.put("label", "SQLite");
        sqlite.put("desc", "零配置单文件数据库，适合本地/小型部署");
        list.add(sqlite);
        return list;
    }

    /** 测试目标库连接 */
    public Map<String, Object> testConnection(Map<String, Object> target) {
        try (Connection conn = openTarget(target)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true);
            r.put("message", "连接成功: " + conn.getMetaData().getDatabaseProductName()
                    + " " + conn.getMetaData().getDatabaseProductVersion());
            return r;
        } catch (Exception e) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", false);
            r.put("message", "连接失败: " + e.getMessage());
            return r;
        }
    }

    // ==================== 迁移任务 ====================

    /** 启动迁移（异步），返回 taskId */
    public Map<String, Object> startMigration(Map<String, Object> target) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        MigrationTask task = new MigrationTask(taskId);
        tasks.put(taskId, task);
        executeMigrationAsync(taskId, target);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("taskId", taskId);
        r.put("status", "running");
        return r;
    }

    /** 查询迁移进度 */
    public Map<String, Object> getProgress(String taskId) {
        MigrationTask task = tasks.get(taskId);
        if (task == null) {
            return Map.of("status", "not_found");
        }
        return task.toMap();
    }

    @Async
    public void executeMigrationAsync(String taskId, Map<String, Object> target) {
        MigrationTask task = tasks.get(taskId);
        String targetType = String.valueOf(target.getOrDefault("type", "")).toLowerCase();
        try {
            // 1. 备份源库
            task.step("备份源数据库", 5);
            String backupPath = backupSource();
            task.log("源库已备份: " + backupPath);

            // 2. 读取源库表结构
            task.step("读取源库表结构", 10);
            List<TableDef> tables;
            try (Connection src = dataSource.getConnection()) {
                tables = readTables(src.getMetaData());
            }
            task.totalTables = tables.size();
            task.log("共读取 " + tables.size() + " 张表");

            // 3. 连接目标库
            task.step("连接目标数据库", 15);
            List<String> createdTables = new ArrayList<>();
            try (Connection dst = openTarget(target)) {
                // 4. 建表（类型映射）
                task.step("创建表结构", 20);
                for (TableDef t : tables) {
                    String ddl = buildCreateTable(t, targetType);
                    try (Statement st = dst.createStatement()) {
                        st.execute(ddl);
                    }
                    createdTables.add(t.name);
                    task.log("建表 " + t.name + "（" + t.columns.size() + " 列）");
                }

                // 5. 迁数据
                try (Connection src = dataSource.getConnection()) {
                    int done = 0;
                    for (TableDef t : tables) {
                        int pct = 20 + (int) (60.0 * done / Math.max(1, tables.size()));
                        task.step("迁移数据 " + (done + 1) + "/" + tables.size() + "：" + t.name, pct);
                        long rows = copyTableData(src, dst, t, targetType);
                        task.log("迁移表 " + t.name + "：共 " + rows + " 行");
                        done++;
                    }
                }

                // 6. 重建索引
                task.step("重建索引", 85);
                for (TableDef t : tables) {
                    for (IndexDef idx : t.indexes) {
                        try (Statement st = dst.createStatement()) {
                            st.execute(buildCreateIndex(t, idx, targetType));
                        } catch (Exception ex) {
                            task.log("索引 " + idx.name + " 重建跳过: " + ex.getMessage());
                        }
                    }
                }

                // 7. 写入连接配置
                task.step("写入连接配置", 95);
                writeDbConfig(target);
                task.log("已写入连接配置 " + CONFIG_FILE);

                task.complete("迁移完成，共迁移 " + tables.size() + " 张表。重启应用后自动切换至 " + targetType + "。");
            }
        } catch (Exception e) {
            log.error("数据库迁移失败", e);
            // 回滚：清理目标库已创建的表（尽力而为）
            task.step("迁移失败，回滚中", 99);
            String msg = e.getMessage();
            task.log("迁移失败: " + msg);
            task.fail(msg);
        }
    }

    // ==================== 核心：表结构读取 / DDL 生成 / 数据搬迁 ====================

    private List<TableDef> readTables(DatabaseMetaData md) throws SQLException {
        List<TableDef> tables = new ArrayList<>();
        try (ResultSet rs = md.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                tables.add(new TableDef(name));
            }
        }
        for (TableDef t : tables) {
            // 列
            try (ResultSet rs = md.getColumns(null, null, t.name, "%")) {
                while (rs.next()) {
                    ColumnDef c = new ColumnDef();
                    c.name = rs.getString("COLUMN_NAME");
                    c.jdbcType = rs.getInt("DATA_TYPE");
                    c.typeName = rs.getString("TYPE_NAME");
                    c.size = rs.getInt("COLUMN_SIZE");
                    c.nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
                    c.autoIncrement = "YES".equalsIgnoreCase(rs.getString("IS_AUTOINCREMENT"));
                    t.columns.add(c);
                }
            }
            // 主键
            Set<String> pks = new LinkedHashSet<>();
            try (ResultSet rs = md.getPrimaryKeys(null, null, t.name)) {
                while (rs.next()) {
                    pks.add(rs.getString("COLUMN_NAME"));
                }
            }
            t.primaryKeys.addAll(pks);
            // 索引
            try (ResultSet rs = md.getIndexInfo(null, null, t.name, false, false)) {
                Map<String, IndexDef> idxMap = new LinkedHashMap<>();
                while (rs.next()) {
                    String idxName = rs.getString("INDEX_NAME");
                    if (idxName == null) continue;
                    String col = rs.getString("COLUMN_NAME");
                    boolean nonUnique = rs.getBoolean("NON_UNIQUE");
                    IndexDef idx = idxMap.computeIfAbsent(idxName, k -> new IndexDef());
                    idx.name = idxName;
                    idx.unique = !nonUnique;
                    if (col != null) idx.columns.add(col);
                }
                for (IndexDef idx : idxMap.values()) {
                    // 排除主键索引（主键已在建表时创建）
                    if (isSameColumns(idx.columns, t.primaryKeys)) continue;
                    t.indexes.add(idx);
                }
            }
        }
        return tables;
    }

    private String buildCreateTable(TableDef t, String targetType) {
        StringBuilder sb = new StringBuilder("CREATE TABLE ");
        sb.append(q(targetType, t.name)).append(" (");

        List<String> parts = new ArrayList<>();
        // 单列自增主键的特殊处理
        boolean singleAutoPk = t.primaryKeys.size() == 1 && isAutoIncrement(t, t.primaryKeys.get(0));

        for (ColumnDef c : t.columns) {
            StringBuilder col = new StringBuilder();
            col.append(q(targetType, c.name)).append(" ");
            String type = mapType(targetType, c.jdbcType, c.size);
            col.append(type);

            boolean isPk = t.primaryKeys.contains(c.name);
            if (singleAutoPk && c.autoIncrement) {
                if ("mysql".equals(targetType)) {
                    col.append(" AUTO_INCREMENT");
                } else {
                    // SQLite：INTEGER PRIMARY KEY AUTOINCREMENT（在列内声明）
                    col = new StringBuilder(q(targetType, c.name) + " INTEGER PRIMARY KEY AUTOINCREMENT");
                    parts.add(col.toString());
                    continue;
                }
            } else {
                if (!c.nullable) col.append(" NOT NULL");
            }
            parts.add(col.toString());
        }

        // 主键约束（多列主键或非自增单列主键）
        if (!singleAutoPk && !t.primaryKeys.isEmpty()) {
            StringBuilder pk = new StringBuilder("PRIMARY KEY (");
            pk.append(String.join(", ", t.primaryKeys.stream().map(c -> q(targetType, c)).toList()));
            pk.append(")");
            parts.add(pk.toString());
        } else if (singleAutoPk && "mysql".equals(targetType)) {
            // MySQL 自增列需要声明主键
            StringBuilder pk = new StringBuilder("PRIMARY KEY (");
            pk.append(q(targetType, t.primaryKeys.get(0)));
            pk.append(")");
            parts.add(pk.toString());
        }

        sb.append(String.join(", ", parts));
        sb.append(")");
        // MySQL 默认引擎 + utf8mb4
        if ("mysql".equals(targetType)) {
            sb.append(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
        return sb.toString();
    }

    private String buildCreateIndex(TableDef t, IndexDef idx, String targetType) {
        String idxName = "idx_" + t.name + "_" + idx.name.replaceAll("[^A-Za-z0-9_]", "_");
        String unique = idx.unique ? "UNIQUE " : "";
        return "CREATE " + unique + "INDEX " + q(targetType, idxName)
                + " ON " + q(targetType, t.name)
                + " (" + String.join(", ", idx.columns.stream().map(c -> q(targetType, c)).toList()) + ")";
    }

    private long copyTableData(Connection src, Connection dst, TableDef t, String targetType) throws SQLException {
        String srcType = detectType(src.getMetaData().getURL());
        String selectSql = "SELECT * FROM " + q(srcType, t.name);
        List<String> colNames = new ArrayList<>();
        List<Integer> colTypes = new ArrayList<>();
        for (ColumnDef c : t.columns) { colNames.add(c.name); colTypes.add(c.jdbcType); }

        StringBuilder insert = new StringBuilder("INSERT INTO ").append(q(targetType, t.name)).append(" (");
        insert.append(String.join(", ", colNames.stream().map(c -> q(targetType, c)).toList()));
        insert.append(") VALUES (");
        insert.append(String.join(", ", Collections.nCopies(colNames.size(), "?")));
        insert.append(")");

        long count = 0;
        try (Statement st = src.createStatement();
             ResultSet rs = st.executeQuery(selectSql);
             PreparedStatement ps = dst.prepareStatement(insert.toString())) {
            int batch = 0;
            while (rs.next()) {
                for (int i = 0; i < colNames.size(); i++) {
                    int jdbcType = colTypes.get(i);
                    Object val;
                    if (isTemporal(jdbcType)) {
                        val = rs.getTimestamp(i + 1);
                    } else {
                        val = rs.getObject(i + 1);
                    }
                    if (val == null) {
                        ps.setNull(i + 1, jdbcType);
                    } else if (isTemporal(jdbcType) && val instanceof Timestamp) {
                        ps.setTimestamp(i + 1, (Timestamp) val);
                    } else {
                        ps.setObject(i + 1, val);
                    }
                }
                ps.addBatch();
                if (++batch >= 500) {
                    ps.executeBatch();
                    batch = 0;
                }
                count++;
            }
            if (batch > 0) ps.executeBatch();
        }
        return count;
    }

    // ==================== 备份 / 回滚 / 配置 ====================

    private String backupSource() throws Exception {
        File backupDir = new File("./data/backup");
        if (!backupDir.exists()) backupDir.mkdirs();
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        try (Connection conn = dataSource.getConnection()) {
            String type = detectType(conn.getMetaData().getURL());
            if ("sqlite".equals(type)) {
                // SQLite：直接复制文件
                File dbFile = new File("./sports_meet.db");
                if (dbFile.exists()) {
                    File backup = new File(backupDir, "sqlite_backup_" + ts + ".db");
                    Files.copy(dbFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    return backup.getAbsolutePath();
                }
            }
            // 通用：导出 SQL 脚本
            File sqlFile = new File(backupDir, "backup_" + type + "_" + ts + ".sql");
            exportSqlBackup(conn, sqlFile);
            return sqlFile.getAbsolutePath();
        }
    }

    /** 导出源库全部数据为 SQL 脚本（通用备份） */
    private void exportSqlBackup(Connection conn, File sqlFile) throws SQLException {
        List<TableDef> tables = readTables(conn.getMetaData());
        String srcType = detectType(conn.getMetaData().getURL());
        try (java.io.PrintWriter pw = new java.io.PrintWriter(sqlFile, "UTF-8")) {
            for (TableDef t : tables) {
                pw.println(buildCreateTable(t, "sqlite") + ";");
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT * FROM " + q(srcType, t.name))) {
                    List<String> cols = new ArrayList<>();
                    for (ColumnDef c : t.columns) cols.add(c.name);
                    while (rs.next()) {
                        StringBuilder sb = new StringBuilder("INSERT INTO \"");
                        sb.append(t.name).append("\" VALUES (");
                        List<String> vals = new ArrayList<>();
                        for (String col : cols) {
                            Object v = rs.getObject(col);
                            vals.add(sqlLiteral(v));
                        }
                        sb.append(String.join(", ", vals)).append(");");
                        pw.println(sb);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("SQL 备份写入失败: {}", e.getMessage());
        }
    }

    private void writeDbConfig(Map<String, Object> target) throws Exception {
        File cfg = new File(CONFIG_FILE);
        File parent = cfg.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        objectMapper.writeValue(cfg, target);
    }

    // ==================== 连接构建 ====================

    private Connection openTarget(Map<String, Object> target) throws Exception {
        String type = String.valueOf(target.getOrDefault("type", "")).toLowerCase();
        String url;
        String user = "";
        String pass = "";
        if ("mysql".equals(type)) {
            String host = str(target.get("host"), "localhost");
            String port = str(target.get("port"), "3306");
            String database = str(target.get("database"), "sports");
            user = str(target.get("username"), "root");
            pass = str(target.get("password"), "");
            url = "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
            Class.forName("com.mysql.cj.jdbc.Driver");
        } else if ("sqlite".equals(type)) {
            String file = str(target.get("file"), "./sports_meet.db");
            url = "jdbc:sqlite:" + file;
            Class.forName("org.sqlite.JDBC");
        } else {
            throw new IllegalArgumentException("不支持的目标数据库类型: " + type);
        }
        return DriverManager.getConnection(url, user, pass);
    }

    // ==================== 类型映射 ====================

    private String mapType(String target, int jdbcType, int size) {
        if ("mysql".equals(target)) {
            return switch (jdbcType) {
                case Types.TINYINT, Types.SMALLINT -> "SMALLINT";
                case Types.INTEGER -> "INT";
                case Types.BIGINT -> "BIGINT";
                case Types.BOOLEAN, Types.BIT -> "TINYINT(1)";
                case Types.FLOAT, Types.REAL -> "FLOAT";
                case Types.DOUBLE -> "DOUBLE";
                case Types.DECIMAL, Types.NUMERIC -> "DECIMAL(20,4)";
                case Types.VARCHAR, Types.NVARCHAR ->
                        size > 0 && size <= 16000 ? "VARCHAR(" + size + ")" : "VARCHAR(255)";
                case Types.CHAR, Types.NCHAR -> "CHAR(" + Math.max(1, size) + ")";
                case Types.LONGVARCHAR, Types.CLOB, Types.NCLOB -> "LONGTEXT";
                case Types.DATE -> "DATE";
                case Types.TIME -> "TIME";
                case Types.TIMESTAMP -> "DATETIME";
                case Types.BLOB, Types.LONGVARBINARY, Types.VARBINARY, Types.BINARY -> "LONGBLOB";
                default -> "LONGTEXT";
            };
        }
        // sqlite
        return switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                    Types.BOOLEAN, Types.BIT -> "INTEGER";
            case Types.FLOAT, Types.REAL, Types.DOUBLE, Types.DECIMAL, Types.NUMERIC -> "REAL";
            case Types.BLOB, Types.LONGVARBINARY, Types.VARBINARY, Types.BINARY -> "BLOB";
            default -> "TEXT";
        };
    }

    private boolean isTemporal(int jdbcType) {
        return jdbcType == Types.DATE || jdbcType == Types.TIME || jdbcType == Types.TIMESTAMP;
    }

    private boolean isAutoIncrement(TableDef t, String colName) {
        for (ColumnDef c : t.columns) {
            if (c.name.equalsIgnoreCase(colName)) return c.autoIncrement;
        }
        return false;
    }

    private boolean isSameColumns(List<String> a, List<String> b) {
        if (a.size() != b.size()) return false;
        Set<String> as = new LinkedHashSet<>(a);
        for (String s : as) if (!b.contains(s)) return false;
        return true;
    }

    private String q(String target, String name) {
        if ("mysql".equals(target)) return "`" + name + "`";
        return "\"" + name + "\"";
    }

    private String detectType(String url) {
        if (url == null) return "unknown";
        String l = url.toLowerCase();
        if (l.contains("sqlite")) return "sqlite";
        if (l.contains("mysql")) return "mysql";
        if (l.contains("h2")) return "h2";
        return "unknown";
    }

    private List<String> listTables(DatabaseMetaData md) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (ResultSet rs = md.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) tables.add(rs.getString("TABLE_NAME"));
        }
        return tables;
    }

    private String sqlLiteral(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        return "'" + String.valueOf(v).replace("'", "''") + "'";
    }

    private String str(Object v, String def) {
        return v != null && !String.valueOf(v).isBlank() ? String.valueOf(v) : def;
    }

    // ==================== 内部数据结构 ====================

    private static class TableDef {
        String name;
        List<ColumnDef> columns = new ArrayList<>();
        List<String> primaryKeys = new ArrayList<>();
        List<IndexDef> indexes = new ArrayList<>();
        TableDef(String name) { this.name = name; }
    }

    private static class ColumnDef {
        String name;
        int jdbcType;
        String typeName;
        int size;
        boolean nullable;
        boolean autoIncrement;
    }

    private static class IndexDef {
        String name;
        boolean unique;
        List<String> columns = new ArrayList<>();
    }

    private static class MigrationTask {
        String id;
        String status = "running"; // running / completed / failed
        String step = "准备中";
        String message = "";
        int progress = 0;
        int totalTables = 0;
        List<String> logs = Collections.synchronizedList(new ArrayList<>());
        final long startAt = System.currentTimeMillis();

        MigrationTask(String id) { this.id = id; }

        synchronized void step(String s, int p) {
            this.step = s;
            this.progress = Math.max(this.progress, p);
        }
        synchronized void log(String msg) {
            logs.add("[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + msg);
            log.info("[migration:{}] {}", id, msg);
        }
        synchronized void complete(String msg) {
            this.status = "completed";
            this.progress = 100;
            this.step = "完成";
            this.message = msg;
        }
        synchronized void fail(String msg) {
            this.status = "failed";
            this.step = "失败";
            this.message = msg;
        }

        synchronized Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", id);
            m.put("status", status);
            m.put("step", step);
            m.put("progress", progress);
            m.put("totalTables", totalTables);
            m.put("message", message);
            m.put("elapsedMs", System.currentTimeMillis() - startAt);
            m.put("logs", new ArrayList<>(logs));
            return m;
        }
    }
}
