package com.sports.repository;

import com.sports.entity.ClassInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClassInfoRepository extends JpaRepository<ClassInfo, Long>, JpaSpecificationExecutor<ClassInfo> {

    Optional<ClassInfo> findByName(String name);

    Optional<ClassInfo> findByGradeAndName(String grade, String name);

    Optional<ClassInfo> findByCode(String code);

    List<ClassInfo> findByGrade(String grade);

    List<ClassInfo> findByTeacherUserId(Long teacherUserId);

    List<ClassInfo> findByIsParticipatingTrue();

    boolean existsByName(String name);

    boolean existsByCode(String code);
}
