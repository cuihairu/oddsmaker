package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AnnouncementRepo extends JpaRepository<AnnouncementEntity, String> {

    List<AnnouncementEntity> findByGameIdAndDeletedAtIsNullOrderByPriorityDescCreatedAtDesc(String gameId);

    /** 定时发布扫描：到点未发布的 */
    List<AnnouncementEntity> findByStatusAndScheduledAtLessThanEqualAndDeletedAtIsNull(
        AnnouncementEntity.Status status, LocalDateTime now);

    /** 定时下线扫描：已发布且超过下线时间的 */
    List<AnnouncementEntity> findByStatusAndAutoOfflineAtLessThanEqualAndDeletedAtIsNull(
        AnnouncementEntity.Status status, LocalDateTime now);

    /** 活跃公告：已发布、未到下线时间、环境匹配或全环境 */
    @Query("SELECT a FROM AnnouncementEntity a WHERE a.gameId = :gameId AND a.status = 'PUBLISHED' "
        + "AND a.deletedAt IS NULL "
        + "AND (a.autoOfflineAt IS NULL OR a.autoOfflineAt > :now) "
        + "AND (a.environmentId IS NULL OR a.environmentId = :environmentId) "
        + "ORDER BY a.priority DESC, a.publishedAt DESC")
    List<AnnouncementEntity> findActive(@Param("gameId") String gameId,
                                        @Param("environmentId") String environmentId,
                                        @Param("now") LocalDateTime now);
}
