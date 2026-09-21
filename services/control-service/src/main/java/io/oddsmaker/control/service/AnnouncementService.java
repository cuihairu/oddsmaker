package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.AnnouncementEntity;
import io.oddsmaker.control.jpa.AnnouncementRepo;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 公告系统：创建/发布/定时发布/定时下线全生命周期管理。
 * sweep() 每分钟扫描到期公告，驱动 SCHEDULED→PUBLISHED 与 PUBLISHED→OFFLINE 迁移。
 */
@Service
public class AnnouncementService {

    private static final Logger logger = LoggerFactory.getLogger(AnnouncementService.class);

    @Autowired
    private AnnouncementRepo announcementRepo;

    @Autowired
    private GameRepo gameRepo;

    @Autowired
    private GameEnvironmentRepo environmentRepo;

    @Autowired
    private AuditLogService auditLog;

    @Transactional(readOnly = true)
    public List<AnnouncementEntity> list(String gameId) {
        requireGame(gameId);
        return announcementRepo.findByGameIdAndDeletedAtIsNullOrderByPriorityDescCreatedAtDesc(gameId);
    }

    @Transactional(readOnly = true)
    public AnnouncementEntity get(String id) {
        return announcementRepo.findById(id)
            .filter(a -> a.deletedAt == null)
            .orElse(null);
    }

    @Transactional
    public AnnouncementEntity create(AnnouncementEntity announcement, String operator) {
        requireGame(announcement.gameId);
        if (announcement.title == null || announcement.title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (announcement.content == null || announcement.content.isBlank()) {
            throw new IllegalArgumentException("content is required");
        }
        if (announcement.environmentId != null && !announcement.environmentId.isBlank()) {
            var env = environmentRepo.findById(announcement.environmentId)
                .filter(e -> e.deletedAt == null && e.gameId.equals(announcement.gameId))
                .orElseThrow(() -> new IllegalArgumentException(
                    "Environment does not belong to game: " + announcement.environmentId));
            announcement.environmentId = env.id;
        } else {
            announcement.environmentId = null;  // 全环境
        }

        announcement.id = "ann_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        announcement.title = announcement.title.trim();
        announcement.createdBy = operator;

        if (announcement.scheduledAt != null) {
            // 定时发布：时间必须在未来
            if (!announcement.scheduledAt.isAfter(LocalDateTime.now())) {
                throw new IllegalArgumentException("scheduledAt must be in the future");
            }
            announcement.status = AnnouncementEntity.Status.SCHEDULED;
        } else {
            announcement.status = AnnouncementEntity.Status.DRAFT;
        }

        AnnouncementEntity saved = announcementRepo.save(announcement);
        auditLog.logCreate("announcement", saved.id, saved.title, operator, operator, null,
            Map.of("gameId", saved.gameId, "status", saved.status.name(),
                "channel", String.valueOf(saved.channel)));
        logger.info("Announcement created: {} (status={})", saved.id, saved.status);
        return saved;
    }

    @Transactional
    public AnnouncementEntity update(String id, AnnouncementEntity req, String operator) {
        AnnouncementEntity existing = requireAnnouncement(id);
        if (existing.status == AnnouncementEntity.Status.PUBLISHED
                || existing.status == AnnouncementEntity.Status.OFFLINE) {
            throw new IllegalStateException("Only draft or scheduled announcements can be edited");
        }
        if (req.title != null && !req.title.isBlank()) existing.title = req.title.trim();
        if (req.content != null && !req.content.isBlank()) existing.content = req.content;
        if (req.channel != null) existing.channel = req.channel;
        if (req.priority != null) existing.priority = req.priority;
        if (req.scheduledAt != null) {
            if (!req.scheduledAt.isAfter(LocalDateTime.now())) {
                throw new IllegalArgumentException("scheduledAt must be in the future");
            }
            existing.scheduledAt = req.scheduledAt;
            existing.status = AnnouncementEntity.Status.SCHEDULED;
        }
        if (req.autoOfflineAt != null) existing.autoOfflineAt = req.autoOfflineAt;

        AnnouncementEntity saved = announcementRepo.save(existing);
        auditLog.logUpdate("announcement", saved.id, saved.title, operator, operator, null,
            Map.of("gameId", saved.gameId, "status", saved.status.name()));
        return saved;
    }

    /** 立即发布 */
    @Transactional
    public AnnouncementEntity publish(String id, String operator) {
        AnnouncementEntity existing = requireAnnouncement(id);
        if (existing.status == AnnouncementEntity.Status.PUBLISHED) {
            throw new IllegalStateException("Announcement already published");
        }
        if (existing.status == AnnouncementEntity.Status.OFFLINE) {
            throw new IllegalStateException("Offline announcement cannot be published again");
        }
        existing.status = AnnouncementEntity.Status.PUBLISHED;
        existing.publishedAt = LocalDateTime.now();
        existing.scheduledAt = null;
        AnnouncementEntity saved = announcementRepo.save(existing);
        auditLog.logUpdate("announcement", saved.id, saved.title, operator, operator, null,
            Map.of("gameId", saved.gameId, "action", "publish"));
        logger.info("Announcement published: {}", id);
        return saved;
    }

    /** 定时发布（已排期可改期） */
    @Transactional
    public AnnouncementEntity schedule(String id, LocalDateTime at, String operator) {
        if (at == null || !at.isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("scheduledAt must be in the future");
        }
        AnnouncementEntity existing = requireAnnouncement(id);
        if (existing.status == AnnouncementEntity.Status.PUBLISHED
                || existing.status == AnnouncementEntity.Status.OFFLINE) {
            throw new IllegalStateException("Only draft announcements can be scheduled");
        }
        existing.scheduledAt = at;
        existing.status = AnnouncementEntity.Status.SCHEDULED;
        AnnouncementEntity saved = announcementRepo.save(existing);
        auditLog.logUpdate("announcement", saved.id, saved.title, operator, operator, null,
            Map.of("gameId", saved.gameId, "action", "schedule", "scheduledAt", at.toString()));
        return saved;
    }

    /** 手动下线 */
    @Transactional
    public AnnouncementEntity offline(String id, String operator) {
        AnnouncementEntity existing = requireAnnouncement(id);
        if (existing.status != AnnouncementEntity.Status.PUBLISHED) {
            throw new IllegalStateException("Only published announcements can be taken offline");
        }
        existing.status = AnnouncementEntity.Status.OFFLINE;
        existing.offlineAt = LocalDateTime.now();
        AnnouncementEntity saved = announcementRepo.save(existing);
        auditLog.logUpdate("announcement", saved.id, saved.title, operator, operator, null,
            Map.of("gameId", saved.gameId, "action", "offline"));
        return saved;
    }

    /** 软删除（仅草稿/已下线可删） */
    @Transactional
    public boolean delete(String id, String operator) {
        AnnouncementEntity existing = announcementRepo.findById(id)
            .filter(a -> a.deletedAt == null)
            .orElse(null);
        if (existing == null) return false;
        if (existing.status == AnnouncementEntity.Status.PUBLISHED
                || existing.status == AnnouncementEntity.Status.SCHEDULED) {
            throw new IllegalStateException("Published or scheduled announcements must be taken offline first");
        }
        existing.deletedAt = LocalDateTime.now();
        announcementRepo.save(existing);
        auditLog.logDelete("announcement", existing.id, existing.title, operator, operator, null);
        return true;
    }

    /** 游戏服拉取活跃公告 */
    @Transactional(readOnly = true)
    public List<AnnouncementEntity> listActive(String gameId, String environmentId) {
        requireGame(gameId);
        // environmentId 允许传环境名（dev/prod）或环境ID
        String resolvedEnv = resolveEnvironmentId(gameId, environmentId);
        // resolveEnvironmentId 三条返回路径（空入参→""、按 ID 命中→env.id、
        // 回落透传 environmentIdOrName）均不产生 null，无需 null 兜底
        return announcementRepo.findActive(gameId, resolvedEnv, LocalDateTime.now());
    }

    /**
     * 定时扫描：到点发布 + 到点下线。每分钟执行。
     */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();

        List<AnnouncementEntity> due = announcementRepo
            .findByStatusAndScheduledAtLessThanEqualAndDeletedAtIsNull(AnnouncementEntity.Status.SCHEDULED, now);
        for (AnnouncementEntity a : due) {
            a.status = AnnouncementEntity.Status.PUBLISHED;
            a.publishedAt = now;
            a.scheduledAt = null;
            announcementRepo.save(a);
            auditLog.logUpdate("announcement", a.id, a.title, "scheduler", "scheduler", null,
                Map.of("gameId", a.gameId, "action", "auto_publish"));
            logger.info("Announcement auto-published: {}", a.id);
        }

        List<AnnouncementEntity> expired = announcementRepo
            .findByStatusAndAutoOfflineAtLessThanEqualAndDeletedAtIsNull(AnnouncementEntity.Status.PUBLISHED, now);
        for (AnnouncementEntity a : expired) {
            a.status = AnnouncementEntity.Status.OFFLINE;
            a.offlineAt = now;
            announcementRepo.save(a);
            auditLog.logUpdate("announcement", a.id, a.title, "scheduler", "scheduler", null,
                Map.of("gameId", a.gameId, "action", "auto_offline"));
            logger.info("Announcement auto-offline: {}", a.id);
        }
    }

    private AnnouncementEntity requireAnnouncement(String id) {
        return announcementRepo.findById(id)
            .filter(a -> a.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Announcement not found: " + id));
    }

    private void requireGame(String gameId) {
        gameRepo.findById(gameId)
            .filter(g -> g.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameId));
    }

    private String resolveEnvironmentId(String gameId, String environmentIdOrName) {
        if (environmentIdOrName == null || environmentIdOrName.isBlank()) {
            return "";
        }
        var byId = environmentRepo.findById(environmentIdOrName)
            .filter(e -> e.deletedAt == null && e.gameId.equals(gameId));
        if (byId.isPresent()) return byId.get().id;
        return environmentRepo.findByGameIdAndNameAndDeletedAtIsNull(
                gameId, environmentIdOrName.trim().toLowerCase()).stream()
            .findFirst()
            .map(e -> e.id)
            .orElse(environmentIdOrName);  // 保守透传，由查询自然过滤
    }
}
