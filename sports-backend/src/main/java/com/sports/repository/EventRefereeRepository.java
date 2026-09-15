package com.sports.repository;

import com.sports.entity.EventReferee;
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
}
