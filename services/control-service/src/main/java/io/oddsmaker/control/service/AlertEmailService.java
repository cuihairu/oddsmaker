package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.AlertEmailConfigEntity;
import io.oddsmaker.control.jpa.AlertEmailConfigRepo;
import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.SystemAlertEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * 业务告警邮件通道（调研「通知通道三件套收敛」落点：webhook 之外的第二通道）。
 * <ul>
 *   <li>每游戏一条收件配置（{@link AlertEmailConfigEntity}，唯一约束 game_id）；</li>
 *   <li>SMTP 未配置（JavaMailSender bean 缺省）时投递跳过——告警链路不因邮件失败受阻；</li>
 *   <li>触发挂点在 {@link MetricAlertService#fireOrAccumulate}（新建告警时派发）。</li>
 * </ul>
 */
@Service
@Transactional
public class AlertEmailService {

    private static final Logger logger = LoggerFactory.getLogger(AlertEmailService.class);

    private final AlertEmailConfigRepo repo;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final AuditLogService auditLog;
    private final String mailFrom;

    public AlertEmailService(AlertEmailConfigRepo repo,
                             ObjectProvider<JavaMailSender> mailSenderProvider,
                             AuditLogService auditLog,
                             @Value("${oddsmaker.alerts.mail.from:oddsmaker@localhost}") String mailFrom) {
        this.repo = repo;
        this.mailSenderProvider = mailSenderProvider;
        this.auditLog = auditLog;
        this.mailFrom = mailFrom;
    }

    @Transactional(readOnly = true)
    public AlertEmailConfigEntity config(String gameId) {
        return repo.findByGameId(gameId).orElse(null);
    }

    /** 每游戏一条：存在则更新（upsert）；recipients 解析后为空视为非法 */
    public AlertEmailConfigEntity save(String gameId, String environmentId, String recipients,
                                       boolean enabled, String operator) {
        if (recipients == null || recipients.isBlank()
            || recipients.split("[,;\\s]+").length == 0) {
            throw new IllegalArgumentException("收件人不能为空");
        }
        AlertEmailConfigEntity cfg = repo.findByGameId(gameId).orElseGet(() -> {
            AlertEmailConfigEntity created = new AlertEmailConfigEntity();
            created.id = "aec_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            created.gameId = gameId;
            return created;
        });
        cfg.environmentId = environmentId == null || environmentId.isBlank() ? null : environmentId.trim();
        cfg.recipients = recipients.trim();
        cfg.enabled = enabled;
        cfg = repo.save(cfg);

        auditLog.log(
            AuditLogEntity.AuditAction.UPDATE,
            "alert_email_config",
            cfg.id,
            gameId,
            "alert email channel enabled=" + enabled,
            AuditLogEntity.AuditResult.SUCCESS,
            operator,
            null,
            null,
            null,
            null,
            Map.of("gameId", gameId, "recipients", cfg.recipientList().size())
        );
        return cfg;
    }

    /**
     * 告警触发投递：未启用/未配置/SMTP 缺席/发送异常均静默降级（返回 false），绝不阻断告警链路
     */
    public boolean sendAlertEmail(String gameId, SystemAlertEntity alert) {
        AlertEmailConfigEntity cfg = repo.findByGameId(gameId).orElse(null);
        if (cfg == null || !cfg.enabled) {
            return false;
        }
        return deliver(gameId, cfg.recipientList(),
            String.format("[Oddsmaker] 告警：%s", alert.title),
            String.format(
                "告警：%s%n级别：%s%n状态：%s%n条件：%s%n描述：%s%n%n游戏：%s%n告警 ID：%s%n时间：%s",
                alert.title,
                alert.severity,
                alert.alertStatus,
                alert.condition,
                alert.description,
                gameId,
                alert.id,
                alert.lastOccurredAt != null ? alert.lastOccurredAt : alert.createdAt
            ));
    }

    /** 测试邮件（配置页「发送测试」按钮） */
    public Map<String, Object> sendTestEmail(String gameId, AlertEmailConfigEntity cfg, String operator) {
        boolean sent = deliver(gameId, cfg.recipientList(),
            "[Oddsmaker] 告警邮件通道测试",
            "这是一条测试邮件：游戏 " + gameId + " 的告警邮件通道已配置成功。操作人：" + operator);
        return Map.of("sent", sent, "recipients", cfg.recipientList().size());
    }

    // ===== 私有辅助 =====

    private boolean deliver(String gameId, java.util.List<String> recipients, String subject, String body) {
        if (recipients.isEmpty()) {
            logger.warn("alert email skipped (no recipients): game={}", gameId);
            return false;
        }
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            logger.info("alert email skipped (SMTP not configured): game={}", gameId);
            return false;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(recipients.toArray(new String[0]));
            message.setSubject(subject);
            message.setText(body);
            sender.send(message);
            logger.info("alert email sent: game={}, to={} recipient(s)", gameId, recipients.size());
            return true;
        } catch (Exception e) {
            // SMTP 失败只记账不抛出：告警的可观测面在 webhook/告警历史，邮件是尽力而为通道
            logger.warn("alert email send failed: game={}, error={}", gameId, e.getMessage());
            return false;
        }
    }
}
