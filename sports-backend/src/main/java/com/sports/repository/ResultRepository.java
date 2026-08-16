package com.sports.repository;

import com.sports.entity.Result;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ResultRepository extends JpaRepository<Result, Long>, JpaSpecificationExecutor<Result> {

    List<Result> findByEventId(Long eventId);

    Optional<Result> findByEventIdAndAthleteId(Long eventId, Long athleteId);

    List<Result> findByEventIdOrderByTotalRankAsc(Long eventId);

    List<Result> findByEventIdOrderByTimeSecondsAsc(Long eventId);

    List<Result> findByAthleteId(Long athleteId);

    List<Result> findByIsRecordTrue();

    @Query("SELECT r FROM Result r WHERE r.event.id = :eventId AND r.heat = :heat")
    List<Result> findByEventIdAndHeat(@Param("eventId") Long eventId, @Param("heat") Integer heat);

    @Query("SELECT r FROM Result r WHERE r.event.id = :eventId ORDER BY r.score DESC")
    List<Result> findByEventIdOrderByScoreDesc(@Param("eventId") Long eventId);

    @Query("SELECT r FROM Result r WHERE r.status = 'valid'")
    List<Result> findAllValid();

    @Query("SELECT r FROM Result r WHERE r.event.id = :eventId AND r.status = 'valid'")
    List<Result> findValidByEventId(@Param("eventId") Long eventId);

    boolean existsByEventIdAndAthleteId(Long eventId, Long athleteId);

    @Query("SELECT COUNT(DISTINCT r.event.id) FROM Result r WHERE r.status = 'valid'")
    long countDistinctEventWithResults();

    @Query("SELECT r FROM Result r WHERE r.athlete.id IN :athleteIds")
    List<Result> findByAthleteIdIn(@Param("athleteIds") List<Long> athleteIds);

    @Query("SELECT r FROM Result r WHERE r.athlete.id IN :athleteIds AND r.status = 'valid'")
    List<Result> findValidByAthleteIdIn(@Param("athleteIds") List<Long> athleteIds);
}
