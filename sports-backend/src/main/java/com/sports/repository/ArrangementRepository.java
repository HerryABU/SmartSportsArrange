package com.sports.repository;

import com.sports.entity.Arrangement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ArrangementRepository extends JpaRepository<Arrangement, Long>, JpaSpecificationExecutor<Arrangement> {

    List<Arrangement> findByEventId(Long eventId);

    Optional<Arrangement> findByEventIdAndAthleteId(Long eventId, Long athleteId);

    List<Arrangement> findByEventIdAndGradeAndGender(Long eventId, String grade, String gender);

    List<Arrangement> findByEventIdOrderByHeatAscLaneAsc(Long eventId);

    List<Arrangement> findByEventIdAndHeatOrderByLaneAsc(Long eventId, Integer heat);

    @Query("SELECT MAX(a.version) FROM Arrangement a WHERE a.event.id = :eventId")
    Integer findMaxVersionByEventId(@Param("eventId") Long eventId);

    @Query("SELECT a FROM Arrangement a WHERE a.event.id = :eventId AND a.version = :version")
    List<Arrangement> findByEventIdAndVersion(@Param("eventId") Long eventId, @Param("version") Integer version);

    @Modifying
    @Query("DELETE FROM Arrangement a WHERE a.event.id = :eventId")
    void deleteByEventId(@Param("eventId") Long eventId);

    @Modifying
    @Query("DELETE FROM Arrangement a WHERE a.event.id = :eventId AND a.version = :version")
    void deleteByEventIdAndVersion(@Param("eventId") Long eventId, @Param("version") Integer version);

    @Query("SELECT COUNT(a) FROM Arrangement a WHERE a.event.id = :eventId")
    long countByEventId(@Param("eventId") Long eventId);

    @Query("SELECT COUNT(DISTINCT a.event.id) FROM Arrangement a")
    long findDistinctEventCount();

    @Query("SELECT a FROM Arrangement a WHERE a.event.id = :eventId AND a.athlete.classInfo.id = :classId")
    List<Arrangement> findByEventIdAndClassId(@Param("eventId") Long eventId, @Param("classId") Long classId);

    @Query("SELECT a FROM Arrangement a WHERE a.event.id IN :eventIds AND a.athlete.id = :athleteId")
    List<Arrangement> findByEventIdsAndAthleteId(@Param("eventIds") List<Long> eventIds, @Param("athleteId") Long athleteId);

    @Query("SELECT a FROM Arrangement a WHERE a.athlete.classInfo.id = :classId")
    List<Arrangement> findByClassId(@Param("classId") Long classId);
}
