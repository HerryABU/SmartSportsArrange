package com.sports.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SQLite 复合唯一约束「落地器」。
 *
 * <p><b>背景：</b>Hibernate 用 {@code ALTER TABLE ... ADD CONSTRAINT} 建复合唯一约束与 FK，
 * 而 SQLite 不支持该语法 → 这些约束会被<b>静默丢弃</b>。实测（Hibernate 6.6 / SQLite 3.49）
 * 全新库上：FK 一个没有；{@code @UniqueConstraint} 复合唯一也没有；连 {@code @Index(unique=true)}
 * 也不落地；只有单列 {@code unique=true} 与普通索引能建成。后果是
 * 「同一人同一项目重复报名 / 重复成绩 / 重复编排」在 SQLite 上毫无约束。</p>
 *
 * <p>本类在 Hibernate 建表之后，用 {@code CREATE UNIQUE INDEX IF NOT EXISTS} 在 SQLite 上
 * 显式补齐这些复合唯一约束（唯一索引同样具备唯一性强制）。H2 / MySQL 由 Hibernate 依
 * {@code @UniqueConstraint} 自动建约束，本类直接跳过。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqliteConstraintInitializer implements CommandLineRunner {

    private final DataSource dataSource;

    /** 需在 SQLite 上显式落地的复合唯一索引（键=索引名，值=DDL）。 */
    private static final Map<String, String> UNIQUE_INDEXES = new LinkedHashMap<>();

    static {
        // 同一人同一项目只能报名一次
        UNIQUE_INDEXES.put("ux_registration_athlete_event",
                "CREATE UNIQUE INDEX IF NOT EXISTS ux_registration_athlete_event "
                        + "ON registration(athlete_id, event_id)");
        // 同一人同一项目同一赛次只能有一条成绩
        UNIQUE_INDEXES.put("ux_result_event_athlete_round",
                "CREATE UNIQUE INDEX IF NOT EXISTS ux_result_event_athlete_round "
                        + "ON result(event_id, athlete_id, round)");
        // 同一人同一项目同一赛次只能有一条编排
        UNIQUE_INDEXES.put("ux_arrangement_event_athlete_round",
                "CREATE UNIQUE INDEX IF NOT EXISTS ux_arrangement_event_athlete_round "
                        + "ON arrangement(event_id, athlete_id, round)");
    }

    @Override
    public void run(String... args) {
        try (Connection con = dataSource.getConnection()) {
            String product = con.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase().contains("sqlite")) {
                // H2 / MySQL：Hibernate 已依据 @UniqueConstraint 建好约束，无需额外处理
                return;
            }
            int ok = 0;
            for (Map.Entry<String, String> e : UNIQUE_INDEXES.entrySet()) {
                try (Statement st = con.createStatement()) {
                    st.execute(e.getValue());
                    ok++;
                } catch (Exception ex) {
                    log.error("[db-constraint] 创建 SQLite 唯一索引 {} 失败（可能已存在重复数据，"
                            + "请先清理重复行后重启）: {}", e.getKey(), ex.getMessage());
                }
            }
            log.info("[db-constraint] SQLite 复合唯一约束已确保 {}/{} 项（报名/成绩/编排 防重复）",
                    ok, UNIQUE_INDEXES.size());
        } catch (Exception ex) {
            log.warn("[db-constraint] 初始化 SQLite 唯一约束时出错: {}", ex.getMessage());
        }
    }
}
