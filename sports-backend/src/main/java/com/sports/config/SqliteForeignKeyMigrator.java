package com.sports.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SQLite 物理外键「落地器」——把 JPA 里声明过的 {@code @ManyToOne} 软连接，真正落成 SQLite 的
 * {@code FOREIGN KEY} 约束。
 *
 * <p><b>为什么需要它：</b>Hibernate 用 {@code ALTER TABLE ... ADD CONSTRAINT} 建 FK，而 SQLite
 * 不支持该语法 → FK 被<b>静默丢弃</b>（实测全新库 {@code foreign_key_list} 全空）。SQLite 加 FK 只能
 * 在建表时内联声明，故必须按官方「12 步」<b>重建表</b>：</p>
 * <ol>
 *   <li>关闭外键强制（{@code PRAGMA foreign_keys=OFF}，事务外）；</li>
 *   <li>取原建表 SQL，剥掉已有 FK 子句，在结尾 {@code )} 前内联注入 {@code foreign key(...) references ...}；</li>
 *   <li>建新表 → {@code INSERT ... SELECT} 拷数据 → {@code DROP} 旧表 → {@code RENAME} 新表为原名；</li>
 *   <li>重建该表原有的索引（DROP TABLE 会连带删掉索引，含唯一索引）；</li>
 *   <li>{@code PRAGMA foreign_key_check} 复核；全过程包在事务里，失败即回滚；</li>
 *   <li>恢复外键强制。</li>
 * </ol>
 *
 * <p><b>幂等：</b>只在「期望的 FK 未全部就位」时才重建；已就位则跳过。执行前会把整库文件备份一份
 * （{@code *.fkbackup-<时间戳>}）。非 SQLite（H2/MySQL）直接跳过——它们由 Hibernate 自动建 FK。</p>
 *
 * <p><b>执行顺序：</b>{@code @Order(20)}，必须晚于 {@link SqliteConstraintInitializer}（{@code @Order(10)}），
 * 这样重建时才能捕获并重建 {@code ux_*} 唯一索引。</p>
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class SqliteForeignKeyMigrator implements CommandLineRunner {

    /** 一条外键：子表列 → 父表(列)。 */
    public record Fk(String column, String parentTable, String parentColumn) {}

    /** 需要落地的外键清单（按依赖顺序：先父表后子表无所谓，父表必须已存在）。 */
    static final Map<String, List<Fk>> SPEC;

    static {
        Map<String, List<Fk>> m = new LinkedHashMap<>();
        m.put("class_info", List.of(new Fk("teacher_user_id", "sys_user", "id")));
        m.put("athlete", List.of(new Fk("class_info_id", "class_info", "id")));
        m.put("registration", List.of(
                new Fk("athlete_id", "athlete", "id"),
                new Fk("event_id", "event", "id")));
        m.put("result", List.of(
                new Fk("event_id", "event", "id"),
                new Fk("athlete_id", "athlete", "id")));
        m.put("arrangement", List.of(
                new Fk("event_id", "event", "id"),
                new Fk("athlete_id", "athlete", "id")));
        m.put("arrangement_reservation", List.of(new Fk("event_id", "event", "id")));
        m.put("event_schedule", List.of(new Fk("event_id", "event", "id")));
        m.put("event_referee", List.of(new Fk("event_id", "event", "id")));
        m.put("parade_score", List.of(new Fk("class_info_id", "class_info", "id")));
        SPEC = Collections.unmodifiableMap(m);
    }

    /** 匹配已有（可能来自历史库）的 foreign key 子句，用于重建前剥离，避免重复注入。 */
    private static final Pattern EXISTING_FK = Pattern.compile(
            "(?is)\\s*,?\\s*foreign\\s+key\\s*\\([^)]*\\)\\s*references\\s*[^(]*\\([^)]*\\)");

    private final DataSource dataSource;

    @Override
    public void run(String... args) {
        try (Connection con = dataSource.getConnection()) {
            if (!isSqlite(con)) {
                return;
            }
            backupDatabaseFile(con);
            int rebuilt = migrate(con, SPEC);
            log.info("[db-fk] SQLite 物理外键{}", rebuilt > 0 ? ("迁移完成：重建 " + rebuilt + " 张表") : "已就绪（无需重建）");
        } catch (Exception e) {
            log.error("[db-fk] SQLite 外键迁移失败（改动已回滚）: {}", e.getMessage(), e);
        }
    }

    // ==================== 核心（package-private 便于单测） ====================

    /**
     * 对 {@code spec} 中每张表：若期望 FK 未全部就位，则重建该表并内联 FK。返回实际重建的表数。
     */
    int migrate(Connection con, Map<String, List<Fk>> spec) throws SQLException {
        boolean origin = con.getAutoCommit();
        int rebuilt = 0;
        try {
            con.setAutoCommit(true);
            exec(con, "PRAGMA foreign_keys=OFF");
            for (Map.Entry<String, List<Fk>> e : spec.entrySet()) {
                String table = e.getKey();
                List<Fk> want = e.getValue();
                if (!tableExists(con, table) || hasAllFks(con, table, want)) {
                    continue;
                }
                rebuild(con, table, want);
                rebuilt++;
            }
            reportDanglingReferences(con);
        } finally {
            try {
                exec(con, "PRAGMA foreign_keys=ON");
            } catch (SQLException ignore) {
                // 恢复失败不掩盖主异常
            }
            con.setAutoCommit(origin);
        }
        return rebuilt;
    }

    private void rebuild(Connection con, String table, List<Fk> fks) throws SQLException {
        String ddl = tableDdl(con, table);
        if (ddl == null) {
            return;
        }
        List<String> indexSqls = indexDdls(con, table);
        String newTable = table + "_fknew";

        // 1) 剥掉已有 FK 子句，再在结尾 ) 前内联注入完整 FK 集合
        String body = EXISTING_FK.matcher(ddl).replaceAll("");
        int close = body.lastIndexOf(')');
        if (close < 0) {
            throw new SQLException("无法解析建表 SQL: " + table);
        }
        String head = body.substring(0, close).stripTrailing();
        while (head.endsWith(",")) {
            head = head.substring(0, head.length() - 1).stripTrailing();
        }
        StringBuilder fk = new StringBuilder();
        for (Fk f : fks) {
            if (fk.length() > 0) {
                fk.append(",\n    ");
            }
            fk.append("foreign key (").append(f.column()).append(") references ")
              .append(f.parentTable()).append("(").append(f.parentColumn()).append(")");
        }
        String newDdl = head + ",\n    " + fk + "\n" + body.substring(close);
        newDdl = newDdl.replaceFirst("(?i)create\\s+table\\s+(if\\s+not\\s+exists\\s+)?" + Pattern.quote(table) + "\\b",
                "CREATE TABLE " + newTable);

        // 2) 重建（事务保护，失败整体回滚）
        boolean origin = con.getAutoCommit();
        con.setAutoCommit(false);
        try {
            exec(con, "DROP TABLE IF EXISTS " + newTable);
            exec(con, newDdl);
            exec(con, "INSERT INTO " + newTable + " SELECT * FROM " + table);
            exec(con, "DROP TABLE " + table);
            exec(con, "ALTER TABLE " + newTable + " RENAME TO " + table);
            for (String is : indexSqls) {
                exec(con, is);
            }
            con.commit();
            log.info("[db-fk] 已重建表 {} 并内联 {} 个外键（重建 {} 个索引）", table, fks.size(), indexSqls.size());
        } catch (SQLException ex) {
            con.rollback();
            throw ex;
        } finally {
            con.setAutoCommit(origin);
        }
    }

    private void reportDanglingReferences(Connection con) {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("PRAGMA foreign_key_check")) {
            int violations = 0;
            while (rs.next()) {
                violations++;
                if (violations <= 10) {
                    log.warn("[db-fk] 悬空引用: table={} rowid={} parent={}",
                            rs.getString(1), rs.getString(2), rs.getString(3));
                }
            }
            if (violations > 0) {
                log.warn("[db-fk] foreign_key_check 发现 {} 处悬空引用（历史数据所致，未自动删除）", violations);
            }
        } catch (SQLException e) {
            log.warn("[db-fk] foreign_key_check 执行失败: {}", e.getMessage());
        }
    }

    // ==================== 工具 ====================

    private boolean isSqlite(Connection con) {
        try {
            String p = con.getMetaData().getDatabaseProductName();
            return p != null && p.toLowerCase().contains("sqlite");
        } catch (SQLException e) {
            return false;
        }
    }

    private void backupDatabaseFile(Connection con) {
        String file = mainDatabaseFile(con);
        if (file == null || file.isEmpty()) {
            return;
        }
        Path src = Path.of(file);
        if (!Files.isRegularFile(src)) {
            return;
        }
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        Path dst = Path.of(file + ".fkbackup-" + stamp);
        try {
            Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES);
            log.info("[db-fk] 已备份数据库文件 -> {}", dst.getFileName());
        } catch (Exception e) {
            log.warn("[db-fk] 备份数据库文件失败（继续迁移）: {}", e.getMessage());
        }
    }

    private String mainDatabaseFile(Connection con) {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("PRAGMA database_list")) {
            while (rs.next()) {
                if ("main".equalsIgnoreCase(rs.getString(2))) {
                    return rs.getString(3);
                }
            }
        } catch (SQLException ignore) {
            // 内存库等场景取不到，忽略
        }
        return null;
    }

    private boolean tableExists(Connection con, String table) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private String tableDdl(Connection con, String table) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private List<String> indexDdls(Connection con, String table) throws SQLException {
        java.util.List<String> out = new java.util.ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name=? AND sql IS NOT NULL")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    private boolean hasAllFks(Connection con, String table, List<Fk> want) throws SQLException {
        java.util.Set<String> present = new java.util.HashSet<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA foreign_key_list(" + table + ")")) {
            while (rs.next()) {
                // 列序: id, seq, table, from, to, on_update, on_delete, match（用下标，避免保留字列名取值不稳）
                present.add(rs.getString(4) + "->" + rs.getString(3));
            }
        }
        for (Fk f : want) {
            if (!present.contains(f.column() + "->" + f.parentTable())) {
                return false;
            }
        }
        return true;
    }

    private void exec(Connection con, String sql) throws SQLException {
        try (Statement st = con.createStatement()) {
            st.execute(sql);
        }
    }
}
