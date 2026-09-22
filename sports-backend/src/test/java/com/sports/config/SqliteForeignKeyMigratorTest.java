package com.sports.config;

import com.sports.config.SqliteForeignKeyMigrator.Fk;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link SqliteForeignKeyMigrator}：重建表后 FK 内联生效、数据保留、索引（含唯一索引）重建、幂等。
 *
 * <p>用独立命名的 shared 内存库（{@code file:<uuid>?mode=memory&cache=shared}）——不能用
 * {@code file::memory:?cache=shared}（全局同名，会串测试）。</p>
 */
class SqliteForeignKeyMigratorTest {

    private static final String URL =
            "jdbc:sqlite:file:fkmig_" + UUID.randomUUID() + "?mode=memory&cache=shared";

    @Test
    void rebuildsTableWithInlineFk_preservesData_recreatesIndexes_andIsIdempotent() throws Exception {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl(URL);

        try (Connection con = ds.getConnection()) {
            exec(con, "CREATE TABLE parent(id integer primary key, name varchar(20))");
            exec(con, "CREATE TABLE child(id integer primary key, parent_id bigint, note varchar(20))");
            exec(con, "CREATE INDEX idx_child_parent ON child(parent_id)");
            exec(con, "CREATE UNIQUE INDEX ux_child_note ON child(note)");
            exec(con, "INSERT INTO parent(id,name) VALUES(1,'a')");
            exec(con, "INSERT INTO child(id,parent_id,note) VALUES(1,1,'x')");
            exec(con, "INSERT INTO child(id,parent_id,note) VALUES(2,1,'y')");

            SqliteForeignKeyMigrator migrator = new SqliteForeignKeyMigrator(ds);
            Map<String, List<Fk>> spec = Map.of(
                    "child", List.of(new Fk("parent_id", "parent", "id")));

            // 1) 首次应重建 1 张表
            assertEquals(1, migrator.migrate(con, spec), "应重建 1 张表");

            // 2) FK 已内联到 child
            Set<String> fks = fkSet(con, "child");
            assertTrue(fks.contains("parent_id->parent"),
                    "child.parent_id 应存在指向 parent 的外键，实际=" + fks);

            // 3) 数据被完整搬运
            assertEquals(2, count(con, "child"), "重建后数据行数应保持");
            assertEquals(1, count(con, "parent"), "父表未被触碰");

            // 4) 索引（普通 + 唯一）都被重建（DROP TABLE 会连带删索引）
            Set<String> idx = indexSet(con, "child");
            assertTrue(idx.contains("idx_child_parent"), "普通索引应被重建，实际=" + idx);
            assertTrue(idx.contains("ux_child_note"), "唯一索引应被重建，实际=" + idx);

            // 5) 幂等：FK 已就位则不再重建
            assertEquals(0, migrator.migrate(con, spec), "第二次应跳过（无需重建）");

            // 6) 外键真正生效：指向不存在父行的插入必须被拒
            exec(con, "PRAGMA foreign_keys=ON");
            SQLException ex = assertThrows(SQLException.class,
                    () -> exec(con, "INSERT INTO child(id,parent_id,note) VALUES(9,999,'z')"),
                    "指向不存在父行的插入应被外键拒绝");
            assertTrue(ex.getMessage().toLowerCase().contains("foreign key"),
                    "异常信息应提及外键，实际=" + ex.getMessage());
        }
    }

    private static void exec(Connection con, String sql) throws SQLException {
        try (Statement st = con.createStatement()) {
            st.execute(sql);
        }
    }

    private static int count(Connection con, String table) throws SQLException {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static Set<String> fkSet(Connection con, String table) throws SQLException {
        Set<String> out = new HashSet<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA foreign_key_list(" + table + ")")) {
            while (rs.next()) {
                out.add(rs.getString(4) + "->" + rs.getString(3)); // from -> parent table
            }
        }
        return out;
    }

    private static Set<String> indexSet(Connection con, String table) throws SQLException {
        Set<String> out = new HashSet<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA index_list(" + table + ")")) {
            while (rs.next()) {
                out.add(rs.getString(2)); // name
            }
        }
        return out;
    }
}
