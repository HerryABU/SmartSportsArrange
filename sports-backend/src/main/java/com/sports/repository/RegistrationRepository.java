package com.sports.repository;

import com.sports.entity.Registration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RegistrationRepository extends JpaRepository<Registration, Long>, JpaSpecificationExecutor<Registration> {

    Optional<Registration> findByAthleteIdAndEventId(Long athleteId, Long eventId);

    List<Registration> findByEventId(Long eventId);

    List<Registration> findByAthleteId(Long athleteId);

    List<Registration> findByStatus(String status);

    List<Registration> findByEventIdAndStatus(Long eventId, String status);

    @Query("SELECT r FROM Registration r WHERE r.athlete.id = :athleteId")
    List<Registration> findAllByAthleteId(@Param("athleteId") Long athleteId);

    @Query("SELECT COUNT(r) FROM Registration r WHERE r.event.id = :eventId AND r.status = 'approved'")
    long countApprovedByEventId(@Param("eventId") Long eventId);

    @Query("SELECT COUNT(r) FROM Registration r WHERE r.athlete.classInfo.id = :classId AND r.event.id = :eventId AND r.status = 'approved'")
    long countByClassAndEvent(@Param("classId") Long classId, @Param("eventId") Long eventId);

    @Query("SELECT r FROM Registration r WHERE r.event.id = :eventId AND r.status = 'approved'")
    List<Registration> findApprovedByEventId(@Param("eventId") Long eventId);

    @Query("SELECT r FROM Registration r WHERE r.event.id = :eventId AND r.athlete.grade = :grade AND r.athlete.gender = :gender AND r.status = 'approved'")
    List<Registration> findApprovedByEventGradeGender(@Param("eventId") Long eventId,
                                                       @Param("grade") String grade,
                                                       @Param("gender") String gender);

    boolean existsByAthleteIdAndEventId(Long athleteId, Long eventId);

    @Query("SELECT r FROM Registration r WHERE r.athlete.id IN :athleteIds")
    List<Registration> findByAthleteIdIn(@Param("athleteIds") List<Long> athleteIds);

    @Query("SELECT r FROM Registration r WHERE r.athlete.id IN :athleteIds AND r.status != 'withdrawn'")
    List<Registration> findActiveByAthleteIdIn(@Param("athleteIds") List<Long> athleteIds);

    @Query("SELECT COUNT(r) FROM Registration r WHERE r.status = 'pending'")
    long countPending();
}
