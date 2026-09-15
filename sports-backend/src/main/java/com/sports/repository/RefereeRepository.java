package com.sports.repository;

import com.sports.entity.Referee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 裁判 Repository
 */
@Repository
public interface RefereeRepository extends JpaRepository<Referee, Long>, JpaSpecificationExecutor<Referee> {

    /** 按姓名查找（@SQLRestriction 自动排除软删除记录） */
    Optional<Referee> findByName(String name);

    /** 是否存在同名且未删除的裁判 */
    boolean existsByName(String name);
}
