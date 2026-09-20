package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MaintenanceWindowRepo extends JpaRepository<MaintenanceWindowEntity, String> {

    /**
     * 查找活跃的维护窗口
     */
    @Query("SELECT mw FROM MaintenanceWindowEntity mw WHERE mw.maintenanceStatus = 'IN_PROGRESS' AND mw.deletedAt IS NULL ORDER BY mw.scheduledStart DESC")
    List<MaintenanceWindowEntity> findActive();

    /**
     * 查找待开始且未发送过即将开始通知的维护窗口（notificationSent 一次性守卫，防每分钟重复派发）
     */
    @Query("SELECT mw FROM MaintenanceWindowEntity mw WHERE mw.maintenanceStatus = 'PENDING' AND mw.scheduledStart <= :now AND mw.deletedAt IS NULL AND (mw.notificationSent = false OR mw.notificationSent IS NULL) ORDER BY mw.scheduledStart ASC")
    List<MaintenanceWindowEntity> findPendingUnnotified(@Param("now") LocalDateTime now);

    /**
     * 查找应该结束且未发送过结束通知的维护窗口（endNotificationSent 一次性守卫）
     */
    @Query("SELECT mw FROM MaintenanceWindowEntity mw WHERE mw.maintenanceStatus = 'IN_PROGRESS' AND mw.scheduledEnd <= :now AND mw.deletedAt IS NULL AND (mw.endNotificationSent = false OR mw.endNotificationSent IS NULL)")
    List<MaintenanceWindowEntity> findShouldEndUnnotified(@Param("now") LocalDateTime now);

    /**
     * 查找超期的维护窗口
     */
    @Query("SELECT mw FROM MaintenanceWindowEntity mw WHERE mw.maintenanceStatus = 'IN_PROGRESS' AND mw.scheduledEnd < :now AND mw.deletedAt IS NULL")
    List<MaintenanceWindowEntity> findOverdue(@Param("now") LocalDateTime now);

    /**
     * 查找即将到来的维护
     */
    @Query("SELECT mw FROM MaintenanceWindowEntity mw WHERE mw.maintenanceStatus IN ('SCHEDULED', 'PENDING') AND mw.scheduledStart > :now AND mw.deletedAt IS NULL ORDER BY mw.scheduledStart ASC")
    List<MaintenanceWindowEntity> findUpcoming(@Param("now") LocalDateTime now);

    /**
     * 统计维护状态
     */
    @Query("SELECT COUNT(mw) FROM MaintenanceWindowEntity mw WHERE mw.maintenanceStatus = :status AND mw.deletedAt IS NULL")
    long countByStatus(@Param("status") MaintenanceWindowEntity.MaintenanceStatus status);
}
