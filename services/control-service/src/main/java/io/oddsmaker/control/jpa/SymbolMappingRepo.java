package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SymbolMappingRepo extends JpaRepository<SymbolMappingEntity, String> {

    @Query("SELECT m FROM SymbolMappingEntity m WHERE m.gameId = :gameId AND m.status = 'ACTIVE' ORDER BY m.uploadedAt DESC")
    List<SymbolMappingEntity> findActiveByGameId(@Param("gameId") String gameId);

    @Query("SELECT m FROM SymbolMappingEntity m WHERE m.gameId = :gameId AND m.platform = :platform AND m.appVersion = :version AND m.status = 'ACTIVE' ORDER BY m.uploadedAt DESC")
    List<SymbolMappingEntity> findActive(@Param("gameId") String gameId, @Param("platform") String platform, @Param("version") String version);
}
