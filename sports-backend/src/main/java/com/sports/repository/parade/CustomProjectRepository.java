package com.sports.repository.parade;

import com.sports.entity.parade.CustomProject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomProjectRepository extends JpaRepository<CustomProject, Long> {

    @Query("SELECT p FROM CustomProject p WHERE p.deletedAt IS NULL ORDER BY p.sortOrder ASC, p.id ASC")
    List<CustomProject> findAllActive();

    @Query("SELECT p FROM CustomProject p WHERE p.meet.id = :meetId AND p.deletedAt IS NULL ORDER BY p.sortOrder ASC, p.id ASC")
    List<CustomProject> findByMeetId(@Param("meetId") Long meetId);

    @Query("SELECT p FROM CustomProject p WHERE p.code = :code AND p.deletedAt IS NULL")
    Optional<CustomProject> findByCode(@Param("code") String code);

    @Query("SELECT p FROM CustomProject p WHERE p.code = :code AND p.meet.id = :meetId AND p.deletedAt IS NULL")
    Optional<CustomProject> findByCodeAndMeet(@Param("code") String code, @Param("meetId") Long meetId);

    @Query("SELECT p FROM CustomProject p WHERE p.meet.id = :meetId AND p.deletedAt IS NULL")
    List<CustomProject> findByMeetIdForBackfill(@Param("meetId") Long meetId);
}
