package com.sports.repository.parade;

import com.sports.entity.parade.ParadeScore;
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

    @Query("SELECT p FROM ParadeScore p WHERE p.projectCode = :projectCode AND p.deletedAt IS NULL ORDER BY p.score DESC")
    List<ParadeScore> findByProjectCode(@Param("projectCode") String projectCode);

    @Query("SELECT p FROM ParadeScore p WHERE p.projectCode = :projectCode AND p.classInfo.id = :classId AND p.deletedAt IS NULL")
    Optional<ParadeScore> findByProjectCodeAndClassId(@Param("projectCode") String projectCode, @Param("classId") Long classId);

    @Query("SELECT p FROM ParadeScore p WHERE p.deletedAt IS NULL ORDER BY p.score DESC")
    List<ParadeScore> findAllActive();

    @Query("SELECT p FROM ParadeScore p WHERE p.grade = :grade AND p.deletedAt IS NULL ORDER BY p.score DESC")
    List<ParadeScore> findByGrade(@Param("grade") String grade);

    /** 某届下全部入场式得分（含软删，回填用） */
    List<ParadeScore> findByMeetId(Long meetId);

    /** 尚未归属届的入场式得分（历史回填用） */
    List<ParadeScore> findByMeetIdIsNull();
}
