package com.sports.repository.preset;

import com.sports.entity.preset.EventTemplatePreset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 项目预设模板方案仓储；软删过滤同 ScheduleOrderPresetRepository。 */
@Repository
public interface EventTemplatePresetRepository extends JpaRepository<EventTemplatePreset, Long> {

    List<EventTemplatePreset> findAllByOrderBySortOrderAscIdAsc();

    /** 含软删行：原生 SQL 绕过类级 {@code @SQLRestriction}，供列表显示「已删除 + 恢复」入口 */
    @Query(value = "SELECT * FROM event_template_preset ORDER BY sort_order ASC, id ASC", nativeQuery = true)
    List<EventTemplatePreset> findAllIncludingDeleted();

    Optional<EventTemplatePreset> findByName(String name);

    @Modifying
    @Query(value = "UPDATE event_template_preset SET deleted_at = NULL WHERE id = :id", nativeQuery = true)
    int restore(@Param("id") Long id);
}
