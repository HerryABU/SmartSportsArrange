package com.sports.repository;

import com.sports.entity.ArrangementReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ArrangementReservationRepository extends JpaRepository<ArrangementReservation, Long> {

    List<ArrangementReservation> findByEventId(Long eventId);

    List<ArrangementReservation> findByEventIdAndRound(Long eventId, String round);

    List<ArrangementReservation> findByEventIdAndRoundAndGradeAndGenderAndHeat(
            Long eventId, String round, String grade, String gender, Integer heat);

    @Modifying
    @Query("DELETE FROM ArrangementReservation r WHERE r.event.id = :eventId")
    void deleteByEventId(@Param("eventId") Long eventId);
}
