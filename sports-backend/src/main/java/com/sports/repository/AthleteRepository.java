package com.sports.repository;

import com.sports.entity.Athlete;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AthleteRepository extends JpaRepository<Athlete, Long>, JpaSpecificationExecutor<Athlete> {

    Optional<Athlete> findByNumber(String number);

    Optional<Athlete> findByStudentId(String studentId);

    List<Athlete> findByName(String name);

    List<Athlete> findByClassInfoId(Long classId);

    List<Athlete> findByGrade(String grade);

    @Query("SELECT a FROM Athlete a WHERE a.classInfo.id = :classId AND a.grade = :grade")
    List<Athlete> findByClassIdAndGrade(@Param("classId") Long classId, @Param("grade") String grade);

    @Query("SELECT COUNT(a) FROM Athlete a WHERE a.classInfo.id = :classId")
    long countByClassId(@Param("classId") Long classId);

    @Query("SELECT a.number FROM Athlete a WHERE a.grade = :grade AND a.number IS NOT NULL ORDER BY a.number DESC")
    List<String> findNumbersByGrade(@Param("grade") String grade);

    @Query("SELECT a.number FROM Athlete a WHERE a.classInfo.id = :classId AND a.number IS NOT NULL ORDER BY a.number DESC")
    List<String> findNumbersByClassId(@Param("classId") Long classId);
}
