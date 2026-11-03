package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.MailClaimEntity;
import io.oddsmaker.control.jpa.MailClaimRepo;
import io.oddsmaker.control.jpa.MailEntity;
import io.oddsmaker.control.jpa.MailRepo;
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
 * 运营邮件系统：全服/个人邮件、附件发放、过期清理。
 *
 * 附件发放语义：claim 固化领取时的附件快照并幂等去重（唯一约束 mailId+playerKey），
 * 游戏服凭 claimedAttachments 发放道具；全服邮件惰性展开，不为每个玩家预生成记录。
 */
@Service
public class MailService {

    private static final Logger logger = LoggerFactory.getLogger(MailService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MailRepo mailRepo;

    @Autowired
    private MailClaimRepo claimRepo;

    @Autowired
    private GameRepo gameRepo;

    @Autowired
    private AuditLogService auditLog;

    @Transactional(readOnly = true)
    public List<MailEntity> list(String gameId) {
        requireGame(gameId);
        return mailRepo.findByGameIdAndDeletedAtIsNullOrderByCreatedAtDesc(gameId);
    }

    @Transactional(readOnly = true)
    public MailEntity get(String id) {
        return mailRepo.findByIdAndDeletedAtIsNull(id).orElse(null);
    }

    @Transactional
    public MailEntity create(MailEntity mail, String operator) {
        requireGame(mail.gameId);
        if (mail.title == null || mail.title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (mail.content == null || mail.content.isBlank()) {
            throw new IllegalArgumentException("content is required");
        }
        if (mail.scope == MailEntity.Scope.INDIVIDUAL
                && (mail.recipients == null || mail.recipients.isBlank())) {
            throw new IllegalArgumentException("recipients are required for INDIVIDUAL mail");
        }
        validateAttachments(mail.attachments);
        if (mail.expireAt != null && !mail.expireAt.isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("expireAt must be in the future");
        }

        mail.id = "mail_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        mail.title = mail.title.trim();
        mail.status = MailEntity.Status.DRAFT;
        mail.createdBy = operator;

        MailEntity saved = mailRepo.save(mail);
        auditLog.logCreate("op_mail", saved.id, saved.title, operator, operator, null,
            Map.of("gameId", saved.gameId, "scope", saved.scope.name(),
                "recipients", saved.scope == MailEntity.Scope.INDIVIDUAL ? String.valueOf(saved.recipients) : "all"));
        return saved;
    }

    /** 发送：草稿 → 已发送 */
    @Transactional
    public MailEntity send(String id, String operator) {
        MailEntity mail = requireMail(id);
        if (mail.status != MailEntity.Status.DRAFT) {
            throw new IllegalStateException("Only draft mail can be sent");
        }
        if (mail.expireAt != null && !mail.expireAt.isAfter(LocalDateTime.now())) {
            throw new IllegalStateException("Mail expireAt has already passed");
        }
        mail.status = MailEntity.Status.SENT;
        mail.sentAt = LocalDateTime.now();
        MailEntity saved = mailRepo.save(mail);
        auditLog.logUpdate("op_mail", saved.id, saved.title, operator, operator, null,
            Map.of("gameId", saved.gameId, "action", "send"));
        logger.info("Mail sent: {}", id);
        return saved;
    }

    /** 软删除（仅草稿/已过期） */
    @Transactional
    public boolean delete(String id, String operator) {
        MailEntity mail = mailRepo.findByIdAndDeletedAtIsNull(id).orElse(null);
        if (mail == null) return false;
        if (mail.status == MailEntity.Status.SENT) {
            throw new IllegalStateException("Sent mail must expire before deletion");
        }
        mail.deletedAt = LocalDateTime.now();
        mailRepo.save(mail);
        auditLog.logDelete("op_mail", mail.id, mail.title, operator, operator, null);
        return true;
    }

    /** 玩家收件箱：游戏服拉取 */
    @Transactional(readOnly = true)
    public List<MailEntity> inbox(String gameId, String environmentId, String playerKey) {
        requireGame(gameId);
        if (playerKey == null || playerKey.isBlank()) {
            throw new IllegalArgumentException("playerKey is required");
        }
        String envId = environmentId == null ? "" : environmentId;
        List<MailEntity> mails = mailRepo.findInbox(gameId, envId, playerKey.trim(), LocalDateTime.now());
        // LIKE 匹配后再精确过滤 recipients（防子串误命中）
        return mails.stream()
            .filter(m -> m.visibleTo(playerKey.trim(), LocalDateTime.now()))
            .toList();
    }

    /**
     * 领取附件：幂等（重复领取返回原凭据），过期拒绝。
     * 返回 null 表示邮件不可领取。
     */
    public MailClaimEntity claim(String mailId, String playerKey) {
        if (playerKey == null || playerKey.isBlank()) {
            throw new IllegalArgumentException("playerKey is required");
        }
        playerKey = playerKey.trim();

        // 先查领取记录：幂等返回
        MailClaimEntity existing = claimRepo.findByMailIdAndPlayerKey(mailId, playerKey).orElse(null);
        if (existing != null) {
            return existing;
        }

        MailEntity mail = requireMail(mailId);
        if (!mail.claimable(LocalDateTime.now())) {
            return null;
        }
        if (!mail.visibleTo(playerKey, LocalDateTime.now())) {
            return null;
        }

        MailClaimEntity claim = new MailClaimEntity();
        claim.id = "mc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        claim.mailId = mail.id;
        claim.gameId = mail.gameId;
        claim.playerKey = playerKey;
        claim.claimedAttachments = mail.attachments;  // 固化快照
        claim.claimedAt = LocalDateTime.now();
        try {
            return claimRepo.save(claim);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 并发下唯一约束兜底：返回已存在的领取记录
            return claimRepo.findByMailIdAndPlayerKey(mailId, playerKey).orElseThrow();
        }
    }

    /** 过期清理：到期邮件标记 EXPIRED（不可再领取，保留审计与领取凭据） */
    @Scheduled(fixedDelay = 300_000)
    @Transactional
    public void sweep() {
        List<MailEntity> expired = mailRepo.findByStatusAndExpireAtLessThanEqualAndDeletedAtIsNull(
            MailEntity.Status.SENT, LocalDateTime.now());
        for (MailEntity mail : expired) {
            mail.status = MailEntity.Status.EXPIRED;
            mailRepo.save(mail);
            auditLog.logUpdate("op_mail", mail.id, mail.title, "scheduler", "scheduler", null,
                Map.of("gameId", mail.gameId, "action", "expire"));
            logger.info("Mail expired: {}", mail.id);
        }
    }

    private void validateAttachments(String attachments) {
        if (attachments == null || attachments.isBlank()) return;
        try {
            List<?> list = JSON.readValue(attachments, List.class);
            for (Object item : list) {
                if (!(item instanceof Map)) {
                    throw new IllegalArgumentException("attachment must be an object");
                }
                Map<?, ?> m = (Map<?, ?>) item;
                if (m.get("type") == null || m.get("id") == null) {
                    throw new IllegalArgumentException("attachment requires type and id");
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("attachments must be a JSON array", e);
        }
    }

    private MailEntity requireMail(String id) {
        return mailRepo.findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> new IllegalArgumentException("Mail not found: " + id));
    }

    private void requireGame(String gameId) {
        gameRepo.findById(gameId)
            .filter(g -> g.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameId));
    }
}
