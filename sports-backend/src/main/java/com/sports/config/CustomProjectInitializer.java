package com.sports.config;

import com.sports.service.parade.CustomProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 自定义项目区：SQLite 落地兜底 + 默认「入场式」项目创建。
 *
 * <p>SQLite 下 Hibernate 不会落地复合唯一约束，这里显式补齐；并兜底确保
 * {@code custom_project} 表与 {@code parade_score} 的新列（project_code/name/type）存在。
 * H2 / MySQL 由 Hibernate 自动建表建列，本类仅跳过 SQLite 专用 DDL。</p>
 */
@Slf4j
@Component
@Order(37) // 在 MeetBackfillInitializer(@Order(36)) 之后：默认届已就位
@RequiredArgsConstructor
public class CustomProjectInitializer implements CommandLineRunner {

    private final DataSource dataSource;
    private final CustomProjectService customProjectService;

    @Override
    public void run(String... args) {
        ensureSchema();
        customProjectService.ensureDefaultProject();
    }

    private void ensureSchema() {
        try (Connection con = dataSource.getConnection()) {
            if (con.getMetaData() == null
                    || !con.getMetaData().getDatabaseProductName().toLowerCase().contains("sqlite")) {
                return; // H2 / MySQL 由 Hibernate 负责
            }
            // 1) custom_project 表（兜底；Hibernate 通常已建，IF NOT EXISTS 幂等）
            try (Statement st = con.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS custom_project ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "code VARCHAR(40) NOT NULL, "
                        + "name VARCHAR(60) NOT NULL, "
                        + "type VARCHAR(20), "
                        + "meet_id BIGINT, "
                        + "sort_order INTEGER NOT NULL DEFAULT 0, "
                        + "count_in_total INTEGER NOT NULL DEFAULT 1, "
                        + "created_at TIMESTAMP, "
                        + "updated_at TIMESTAMP, "
                        + "deleted_at TIMESTAMP)");
                log.info("[custom-project] 已确保 custom_project 表存在（SQLite）");
            } catch (Exception ex) {
                log.warn("[custom-project] 创建 custom_project 表失败: {}", ex.getMessage());
            }
            // 2) parade_score 新列（Hibernate 一般会加，这里幂等兜底）
            for (String col : new String[]{"project_code", "project_name", "type"}) {
                if (!hasColumn(con, "parade_score", col)) {
                    try (Statement st = con.createStatement()) {
                        if ("project_code".equals(col)) st.execute("ALTER TABLE parade_score ADD COLUMN project_code VARCHAR(40)");
                        else if ("project_name".equals(col)) st.execute("ALTER TABLE parade_score ADD COLUMN project_name VARCHAR(60)");
                        else st.execute("ALTER TABLE parade_score ADD COLUMN type VARCHAR(20)");
                        log.info("[custom-project] SQLite 已补建列 parade_score.{}", col);
                    } catch (Exception ex) {
                        log.warn("[custom-project] 补建 parade_score.{} 失败: {}", col, ex.getMessage());
                    }
                }
            }
            // 3) 同届项目编码唯一（SQLite 不落地 @Index(unique=true) → 显式补齐）
            try (Statement st = con.createStatement()) {
                st.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_custom_project_code_meet "
                        + "ON custom_project(code, meet_id)");
                log.info("[custom-project] 已确保 custom_project(code, meet_id) 唯一索引");
            } catch (Exception ex) {
                log.warn("[custom-project] 创建 custom_project 唯一索引失败（可能已存在重复数据，请清理后重启）: {}", ex.getMessage());
            }
        } catch (Exception ex) {
            log.warn("[custom-project] 初始化 schema 时出错: {}", ex.getMessage());
        }
    }

    private boolean hasColumn(Connection con, String table, String column) throws Exception {
        try (ResultSet rs = con.getMetaData().getColumns(null, null, table, column)) {
            return rs.next();
        }
    }
}
