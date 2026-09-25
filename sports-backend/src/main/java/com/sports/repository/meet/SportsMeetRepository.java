package com.sports.repository.meet;

import com.sports.entity.meet.SportsMeet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SportsMeetRepository extends JpaRepository<SportsMeet, Long> {

    /** 当前届（active=true），至多一个 */
    Optional<SportsMeet> findByActiveTrue();

    /** 全部届，按年份降序、届次降序（最新在前） */
    @Query("SELECT m FROM SportsMeet m ORDER BY m.year DESC, m.edition DESC")
    List<SportsMeet> findAllOrdered();

    long countByActiveTrue();
}
