package com.sports.repository;

import com.sports.entity.Venue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VenueRepository extends JpaRepository<Venue, Long> {

    List<Venue> findByEnabledTrueOrderBySortOrderAsc();

    List<Venue> findAllByOrderBySortOrderAsc();

    Optional<Venue> findByCodeAndEnabledTrue(String code);

    Optional<Venue> findByCode(String code);

    boolean existsByCode(String code);
}
