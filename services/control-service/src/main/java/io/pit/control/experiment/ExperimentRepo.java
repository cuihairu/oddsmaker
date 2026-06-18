package io.pit.control.experiment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ExperimentRepo extends JpaRepository<ExperimentEntity, String> {
    List<ExperimentEntity> findByTenantIdAndAppId(String tenantId, String appId);
    List<ExperimentEntity> findByTenantIdAndAppIdAndStatus(String tenantId, String appId, String status);

    @Query("SELECT e FROM ExperimentEntity e WHERE e.tenantId=:tid AND e.appId=:aid AND (:st IS NULL OR e.status=:st) ORDER BY e.updatedAt DESC")
    Page<ExperimentEntity> pageBy(@Param("tid") String tenantId, @Param("aid") String appId, @Param("st") String status, Pageable pageable);
}
