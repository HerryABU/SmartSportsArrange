package com.sports.repository.orderbook;

import com.sports.entity.orderbook.OrderBookSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 秩序册目录（章节）仓储。
 *
 * <p>软删除由实体的 {@code @SQLRestriction("deleted_at IS NULL")} 兜底，这里不要再加
 * {@code deletedAt IS NULL} 条件——那会在「恢复」接口上线后显得自相矛盾。</p>
 */
public interface OrderBookSectionRepository extends JpaRepository<OrderBookSection, Long> {

    /** 某一届的全部目录，按层级与排序取（含未启用的，设计器要能看见）。 */
    @Query("SELECT s FROM OrderBookSection s WHERE s.meet.id = :meetId ORDER BY s.level, s.sortOrder, s.id")
    List<OrderBookSection> findByMeet(@Param("meetId") Long meetId);

    /** 某一届下某个父目录的子目录（二级用）。 */
    @Query("SELECT s FROM OrderBookSection s WHERE s.meet.id = :meetId AND s.parentId = :parentId ORDER BY s.sortOrder, s.id")
    List<OrderBookSection> findChildren(@Param("meetId") Long meetId, @Param("parentId") String parentId);

    /**
     * 某届目录条数（默认章节是否已铺过）。
     *
     * <p>必须写成显式 {@code s.meet.id}：<code>meet</code> 是 {@link com.sports.entity.meet.SportsMeet}
     * 实体引用，派生查询直接拿 {@code Long} 比较会被 Hibernate 6 判为
     * “Cannot compare SportsMeet with Long” 而启动失败。</p>
     */
    @Query("SELECT COUNT(s) FROM OrderBookSection s WHERE s.meet.id = :meetId")
    long countByMeet(@Param("meetId") Long meetId);

    /** 同层最大排序值 +1，供新增目录时落位到末尾。 */
    @Query("SELECT COALESCE(MAX(s.sortOrder), 0) FROM OrderBookSection s "
            + "WHERE s.meet.id = :meetId AND (s.parentId = :parentId OR (:parentId IS NULL AND s.parentId IS NULL))")
    int maxSortOrder(@Param("meetId") Long meetId, @Param("parentId") String parentId);

    /** 软删除（避免顺序调整时把别人的引用删断）。 */
    @Modifying
    @Query("UPDATE OrderBookSection s SET s.deletedAt = CURRENT_TIMESTAMP WHERE s.id = :id")
    void softDelete(@Param("id") Long id);
}
