package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SecuritySessionRepo extends JpaRepository<SecuritySessionEntity, String> {

    /**
     * 查找用户的活跃会话
     */
    @Query("SELECT s FROM SecuritySessionEntity s WHERE s.userId = :userId AND s.sessionStatus = 'ACTIVE' ORDER BY s.lastActivityAt DESC")
    List<SecuritySessionEntity> findActiveByUserId(@Param("userId") String userId);

    /**
     * 根据令牌查找会话
     */
    @Query("SELECT s FROM SecuritySessionEntity s WHERE s.sessionToken = :token AND s.sessionStatus = 'ACTIVE'")
    Optional<SecuritySessionEntity> findByToken(@Param("token") String token);

    /**
     * 查找过期的会话
     */
    @Query("SELECT s FROM SecuritySessionEntity s WHERE s.sessionStatus = 'ACTIVE' AND s.expiresAt < :now")
    List<SecuritySessionEntity> findExpired(@Param("now") LocalDateTime now);

    /**
     * 查找空闲超时的会话
     */
    @Query("SELECT s FROM SecuritySessionEntity s WHERE s.sessionStatus = 'ACTIVE' AND s.lastActivityAt < :idleSince")
    List<SecuritySessionEntity> findIdleExpired(@Param("idleSince") LocalDateTime idleSince);

    /**
     * 删除过期的会话
     */
    @Query("DELETE FROM SecuritySessionEntity s WHERE s.expiresAt < :expireBefore")
    int deleteExpired(@Param("expireBefore") LocalDateTime expireBefore);

}
