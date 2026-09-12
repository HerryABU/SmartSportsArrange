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

    /**
     * 同一 事件×运动员 可能同时存在 预赛(preliminary) 与 决赛(final) 两条编排（二次编排后），
     * 单条 Optional 语义会触发 NonUniqueResultException。
     * 取数规则：优先决赛轮；无决赛时取最新一条（id 最大）。
     */
    @Query("SELECT a FROM Arrangement a WHERE a.event.id = :eventId AND a.athlete.id = :athleteId ORDER BY CASE WHEN COALESCE(a.round, 'final') = 'final' THEN 0 ELSE 1 END ASC, a.id DESC")
    List<Arrangement> findByEventIdAndAthleteIdAllRounds(@Param("eventId") Long eventId, @Param("athleteId") Long athleteId);

    default Optional<Arrangement> findByEventIdAndAthleteId(Long eventId, Long athleteId) {
        List<Arrangement> rows = findByEventIdAndAthleteIdAllRounds(eventId, athleteId);
        if (rows.isEmpty()) return Optional.empty();
        // 优先决赛轮（round 为 null 视作 final，历史 NULL 行兼容），取该轮 id 最大的一条；否则取全轮 id 最大
        return rows.stream()
                .filter(a -> "final".equals(a.getRound() == null ? "final" : a.getRound()))
                .max(java.util.Comparator.comparing(Arrangement::getId))
                .or(() -> rows.stream().max(java.util.Comparator.comparing(Arrangement::getId)));
    }

    /** 按赛次查询编排（历史 NULL 行视作 final） */
    @Query("SELECT a FROM Arrangement a WHERE a.event.id = :eventId AND COALESCE(a.round, 'final') = :round ORDER BY a.heat ASC, a.lane ASC")
    List<Arrangement> findByEventIdAndRoundOrderByHeatAscLaneAsc(@Param("eventId") Long eventId, @Param("round") String round);

    /** 全部赛次编排（兼容历史调用/导出） */
    List<Arrangement> findByEventIdOrderByHeatAscLaneAsc(Long eventId);

    @Query("SELECT a FROM Arrangement a WHERE a.event.id = :eventId AND COALESCE(a.round, 'final') = :round")
    List<Arrangement> findByEventIdAndRound(@Param("eventId") Long eventId, @Param("round") String round);

    @Query("SELECT a FROM Arrangement a WHERE a.event.id = :eventId AND COALESCE(a.round, 'final') = :round AND a.grade = :grade AND a.gender = :gender")
    List<Arrangement> findByEventRoundGradeGender(@Param("eventId") Long eventId,
                                                  @Param("round") String round,
                                                  @Param("grade") String grade,
                                                  @Param("gender") String gender);

    @Query("SELECT a FROM Arrangement a WHERE a.event.id = :eventId AND COALESCE(a.round, 'final') = :round AND a.heat = :heat")
    List<Arrangement> findByEventRoundHeat(@Param("eventId") Long eventId,
                                           @Param("round") String round,
                                           @Param("heat") Integer heat);

    @Query("SELECT a FROM Arrangement a WHERE a.event.id = :eventId AND COALESCE(a.round, 'final') = :round AND a.athlete.id = :athleteId")
    Optional<Arrangement> findByEventRoundAthleteId(@Param("eventId") Long eventId,
                                                    @Param("round") String round,
                                                    @Param("athleteId") Long athleteId);

    @Modifying
    @Query("DELETE FROM Arrangement a WHERE a.event.id = :eventId AND COALESCE(a.round, 'final') = :round")
    void deleteByEventIdAndRound(@Param("eventId") Long eventId, @Param("round") String round);

    @Modifying
    @Query("DELETE FROM Arrangement a WHERE a.event.id = :eventId AND COALESCE(a.round, 'final') = :round AND a.grade = :grade AND a.gender = :gender")
    void deleteByEventRoundGradeGender(@Param("eventId") Long eventId, @Param("round") String round,
                                       @Param("grade") String grade, @Param("gender") String gender);

    @Query("SELECT a FROM Arrangement a WHERE a.event.id = :eventId AND COALESCE(a.round, 'final') = :round AND a.qualified = true ORDER BY a.prelimRank ASC")
    List<Arrangement> findQualifiedByEventIdAndRound(@Param("eventId") Long eventId, @Param("round") String round);

    @Query("SELECT COUNT(a) FROM Arrangement a WHERE a.event.id = :eventId AND a.round = 'preliminary'")
    long countPreliminaryByEventId(@Param("eventId") Long eventId);

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
