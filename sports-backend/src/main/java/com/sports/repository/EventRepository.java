package com.sports.repository;

import com.sports.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EventRepository extends JpaRepository<Event, Long>, JpaSpecificationExecutor<Event> {

    Optional<Event> findByCode(String code);

    List<Event> findByCategory(String category);

    List<Event> findByGenderLimit(String genderLimit);

    List<Event> findByIsEnabledTrue();

    List<Event> findByCategoryAndGenderLimit(String category, String genderLimit);

    List<Event> findByIsEnabledTrueOrderBySortOrderAsc();

    Optional<Event> findByNameAndIsEnabledTrue(String name);

    List<Event> findByIsEnabledTrueAndNameContaining(String name);

    boolean existsByCode(String code);
}
