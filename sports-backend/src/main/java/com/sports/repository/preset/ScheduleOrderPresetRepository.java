package com.sports.repository.preset;

import com.sports.entity.preset.ScheduleOrderPreset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 编排顺序预设方案仓储。类级 {@code @SQLRestriction} 只返回未删除行；
 * 恢复必须走原生 SQL（Hibernate 6 下 findById 也会被软删过滤挡住）。
 */
@Repository
public interface ScheduleOrderPresetRepository extends JpaRepository<ScheduleOrderPreset, Long> {

    List<ScheduleOrderPreset> findAllByOrderBySortOrderAscIdAsc();

    /**
     * 含软删行：原生 SQL 绕过类级 {@code @SQLRestriction}，让列表能显示「已删除」并提供恢复入口。
     * 直接 SELECT * 保持列名与实体列映射一致（Hibernate 6 原生实体查询按列名匹配）。
     */
    @Query(value = "SELECT * FROM schedule_order_preset ORDER BY sort_order ASC, id ASC", nativeQuery = true)
    List<ScheduleOrderPreset> findAllIncludingDeleted();

    Optional<ScheduleOrderPreset> findByName(String name);

    @Modifying
    @Query(value = "UPDATE schedule_order_preset SET deleted_at = NULL WHERE id = :id", nativeQuery = true)
    int restore(@Param("id") Long id);
}
