package com.sports.repository.venue;

import com.sports.entity.venue.Venue;
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
