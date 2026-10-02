package com.sports.repository.orderbook;

import com.sports.entity.orderbook.OrderBookEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** 秩序册细则（内容条目）仓储。软删除同 {@link OrderBookSectionRepository}。 */
public interface OrderBookEntryRepository extends JpaRepository<OrderBookEntry, Long> {

    /** 某个目录下的全部细则，按排序取（含未启用的，设计器要能编辑）。 */
    @Query("SELECT e FROM OrderBookEntry e WHERE e.section.id = :sectionId ORDER BY e.sortOrder, e.id")
    List<OrderBookEntry> findBySection(@Param("sectionId") Long sectionId);

    /** 某一届的全部细则（联表目录），用于整体预览与导出。 */
    @Query("SELECT e FROM OrderBookEntry e JOIN e.section s WHERE s.meet.id = :meetId ORDER BY s.sortOrder, e.sortOrder, e.id")
    List<OrderBookEntry> findByMeet(@Param("meetId") Long meetId);

    /** 某一届下指定数据源的系统细则（保证一个板块只铺一次）。 */
    @Query("SELECT e FROM OrderBookEntry e JOIN e.section s "
            + "WHERE s.meet.id = :meetId AND e.sourceKey = :sourceKey")
    List<OrderBookEntry> findBySource(@Param("meetId") Long meetId, @Param("sourceKey") String sourceKey);

    /** 同 {@link OrderBookSectionRepository#countByMeet}：走显式 {@code e.section.id} 避免实体/Long 比较。 */
    @Query("SELECT COUNT(e) FROM OrderBookEntry e WHERE e.section.id = :sectionId")
    long countBySection(@Param("sectionId") Long sectionId);

    /** 同目录最大排序值 +1。 */
    @Query("SELECT COALESCE(MAX(e.sortOrder), 0) FROM OrderBookEntry e WHERE e.section.id = :sectionId")
    int maxSortOrder(@Param("sectionId") Long sectionId);

    /** 把某个目录下的细则全部软删（目录被删时收口）。 */
    @Modifying
    @Query("UPDATE OrderBookEntry e SET e.deletedAt = CURRENT_TIMESTAMP WHERE e.section.id = :sectionId")
    void softDeleteBySection(@Param("sectionId") Long sectionId);

    /** 软删除。 */
    @Modifying
    @Query("UPDATE OrderBookEntry e SET e.deletedAt = CURRENT_TIMESTAMP WHERE e.id = :id")
    void softDelete(@Param("id") Long id);
}
