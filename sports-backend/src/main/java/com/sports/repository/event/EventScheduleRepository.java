package com.sports.repository.event;

import com.sports.entity.event.EventSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EventScheduleRepository extends JpaRepository<EventSchedule, Long> {

    List<EventSchedule> findByOrderByDayAscSortOrderAscStartTimeAsc();

    List<EventSchedule> findByDayOrderBySortOrderAscStartTimeAsc(Integer day);

    @Modifying
    @Query("DELETE FROM EventSchedule")
    void deleteAllSchedules();

    long countByEventId(Long eventId);

    @Query("SELECT s FROM EventSchedule s WHERE s.event.id = :eventId")
    List<EventSchedule> findByEventId(@Param("eventId") Long eventId);

    /** 某 事件×年级 的全部赛程条目（含预赛/决赛轮次），用于二次编排定位预赛条目与决赛顺延起点 */
    List<EventSchedule> findByEventIdAndGrade(Long eventId, String grade);
}
