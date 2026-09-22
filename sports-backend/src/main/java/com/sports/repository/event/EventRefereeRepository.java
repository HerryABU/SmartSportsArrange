package com.sports.repository.event;

import com.sports.entity.event.EventReferee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 组次-裁判分配结果 Repository
 */
@Repository
public interface EventRefereeRepository extends JpaRepository<EventReferee, Long> {

    /** 某项目的全部组次裁判分配 */
    List<EventReferee> findByEventId(Long eventId);

    /** 清空某项目的全部分配（重新编排前调用） */
    void deleteByEventId(Long eventId);

    /** 清空某 (项目×年级×性别×赛次) 切片的分配（重新编排该切片前调用） */
    void deleteByEventIdAndGradeAndGenderAndRound(Long eventId, String grade, String gender, String round);

    /** 精确查询某组次的分配（编排视图用） */
    List<EventReferee> findByEventIdAndGradeAndGenderAndRound(Long eventId, String grade, String gender, String round);

    /** 忽略性别的组次分配查询（getArrangement 按 年级×赛次×组次 聚合时取并集） */
    List<EventReferee> findByEventIdAndGradeAndRoundAndHeat(Long eventId, String grade, String round, Integer heat);

    /** 手工调整：定位某组次分配行 */
    List<EventReferee> findByEventIdAndGradeAndGenderAndRoundAndHeat(Long eventId, String grade, String gender, String round, Integer heat);
}
