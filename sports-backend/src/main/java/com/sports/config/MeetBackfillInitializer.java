package com.sports.config;

import com.sports.entity.meet.SportsMeet;
import com.sports.repository.parade.ParadeScoreRepository;
import com.sports.repository.registration.RegistrationRepository;
import com.sports.repository.result.ResultRepository;
import com.sports.service.meet.MeetService;
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
 * 历史数据回填默认届（幂等）。
 *
 * <p>成绩 / 报名 / 入场式得分此前没有届的概念，全部归为「默认届」。本类在启动时为这些表
 * 中 {@code meet_id IS NULL} 的行补上默认（active）届，使全量多届模型自洽。</p>
 *
 * <p>附加职责：SQLite 的 {@code ddl-auto=update} 对新增普通列不一定可靠，这里显式确保
 * {@code meet_id} 列存在（缺失则 {@code ALTER TABLE ADD COLUMN}），避免 Hibernate 漏建列导致
 * 后续保存/查询失败（H2 / MySQL 由 Hibernate 负责，这里跳过）。</p>
 */
@Slf4j
@Component
@Order(36) // 在 MeetDataInitializer(@Order(35)) 之后：默认届已就位
@RequiredArgsConstructor
public class MeetBackfillInitializer implements CommandLineRunner {

    private final MeetService meetService;
    private final ResultRepository resultRepository;
    private final RegistrationRepository registrationRepository;
    private final ParadeScoreRepository paradeScoreRepository;
    private final DataSource dataSource;

    @Override
    public void run(String... args) {
        SportsMeet def = meetService.ensureDefaultMeet();
        ensureMeetIdColumns();

        int r = backfill(resultRepository.findByMeetIdIsNull(), def, "result");
        int reg = backfill(registrationRepository.findByMeetIdIsNull(), def, "registration");
        int parade = backfill(paradeScoreRepository.findByMeetIdIsNull(), def, "parade_score");        log.info("[meet-backfill] 历史数据已归属默认届 {}：成绩 {} 条、报名 {} 条、入场式 {} 条",
                def.getName(), r, reg, parade);
    }

    private int backfill(java.util.List<?> rows, SportsMeet def, String table) {
        int n = 0;
        if (rows.isEmpty()) return 0;
        for (Object o : rows) {
            if (o instanceof com.sports.entity.result.Result x) { if (x.getMeet() == null) { x.setMeet(def); resultRepository.save(x); n++; } }
            else if (o instanceof com.sports.entity.registration.Registration x) { if (x.getMeet() == null) { x.setMeet(def); registrationRepository.save(x); n++; } }
            else if (o instanceof com.sports.entity.parade.ParadeScore x) { if (x.getMeet() == null) { x.setMeet(def); paradeScoreRepository.save(x); n++; } }
        }
        return n;
    }

    private void ensureMeetIdColumns() {
        try (Connection con = dataSource.getConnection()) {
            if (con.getMetaData() == null || !con.getMetaData().getDatabaseProductName().toLowerCase().contains("sqlite")) {
                return; // H2 / MySQL 由 Hibernate 建列
            }
            for (String table : new String[]{"result", "registration", "parade_score"}) {
                if (!hasColumn(con, table, "meet_id")) {
                    try (Statement st = con.createStatement()) {
                        st.execute("ALTER TABLE " + table + " ADD COLUMN meet_id BIGINT");
                        log.info("[meet-backfill] SQLite 已补建列 {}.meet_id", table);
                    } catch (Exception ex) {
                        log.warn("[meet-backfill] 补建 {}.meet_id 失败: {}", table, ex.getMessage());
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("[meet-backfill] 检查/补建 meet_id 列时出错: {}", ex.getMessage());
        }
    }

    private boolean hasColumn(Connection con, String table, String column) throws Exception {
        try (ResultSet rs = con.getMetaData().getColumns(null, null, table, column)) {
            return rs.next();
        }
    }
}
