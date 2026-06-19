package io.oddsmaker.control.experiment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ExperimentRepo extends JpaRepository<ExperimentEntity, String> {
    List<ExperimentEntity> findByGameIdAndEnvironmentId(String gameId, String environmentId);
    List<ExperimentEntity> findByGameIdAndEnvironmentIdAndStatus(String gameId, String environmentId, String status);

    @Query("SELECT e FROM ExperimentEntity e WHERE " +
           "(:gameId IS NULL OR e.gameId=:gameId) AND " +
           "(:environmentId IS NULL OR e.environmentId=:environmentId) AND " +
           "(:status IS NULL OR e.status=:status) " +
           "ORDER BY e.updatedAt DESC")
    Page<ExperimentEntity> search(@Param("gameId") String gameId,
                                  @Param("environmentId") String environmentId,
                                  @Param("status") String status,
                                  Pageable pageable);

    @Query("SELECT e FROM ExperimentEntity e WHERE e.gameId=:gameId AND e.environmentId=:environmentId AND e.status='running' ORDER BY e.updatedAt DESC")
    List<ExperimentEntity> findRunningConfigs(@Param("gameId") String gameId,
                                              @Param("environmentId") String environmentId);

    @Query("SELECT e FROM ExperimentEntity e WHERE e.gameId=:gameId AND e.environmentId=:environmentId AND (:status IS NULL OR e.status=:status) ORDER BY e.updatedAt DESC")
    Page<ExperimentEntity> pageBy(@Param("gameId") String gameId,
                                  @Param("environmentId") String environmentId,
                                  @Param("status") String status,
                                  Pageable pageable);
}
