package com.sports.repository;

import com.sports.entity.ParadeScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ParadeScoreRepository extends JpaRepository<ParadeScore, Long> {

    @Query("SELECT p FROM ParadeScore p WHERE p.classInfo.id = :classId AND p.deletedAt IS NULL")
    Optional<ParadeScore> findByClassId(@Param("classId") Long classId);

    @Query("SELECT p FROM ParadeScore p WHERE p.deletedAt IS NULL ORDER BY p.score DESC")
    List<ParadeScore> findAllActive();

    @Query("SELECT p FROM ParadeScore p WHERE p.grade = :grade AND p.deletedAt IS NULL ORDER BY p.score DESC")
    List<ParadeScore> findByGrade(@Param("grade") String grade);
}
