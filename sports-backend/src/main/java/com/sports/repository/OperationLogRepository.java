package com.sports.repository;

import com.sports.entity.OperationLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OperationLogRepository extends JpaRepository<OperationLog, Long>, JpaSpecificationExecutor<OperationLog> {

    List<OperationLog> findByUserId(Long userId);

    List<OperationLog> findByModule(String module);

    List<OperationLog> findByOperation(String operation);

    Page<OperationLog> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
}
