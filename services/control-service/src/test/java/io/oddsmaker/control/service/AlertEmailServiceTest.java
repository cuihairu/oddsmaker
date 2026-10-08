package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.AlertEmailConfigEntity;
import io.oddsmaker.control.jpa.AlertEmailConfigRepo;
import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.SystemAlertEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 业务告警邮件通道测试：配置 upsert 校验、收件人解析、SMTP 未配置/未启用/发送异常静默降级、
 * 测试邮件与告警投递的消息构造。ObjectProvider mock 后按用例给/不给 JavaMailSender。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("业务告警邮件通道测试")
class AlertEmailServiceTest {

    private static final String GAME = "g";

    @Mock
    private AlertEmailConfigRepo repo;

    @Mock
    private ObjectProvider<JavaMailSender> mailSenderProvider;

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private AuditLogService auditLog;

    private AlertEmailService service;

    @BeforeEach
    void setUp() {
        service = new AlertEmailService(repo, mailSenderProvider, auditLog, "oddsmaker@test.local");
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private AlertEmailConfigEntity config(String recipients, boolean enabled) {
        AlertEmailConfigEntity cfg = new AlertEmailConfigEntity();
        cfg.id = "aec_1";
        cfg.gameId = GAME;
        cfg.recipients = recipients;
        cfg.enabled = enabled;
        return cfg;
    }

    // ===== save =====

    @Test
    @DisplayName("save：收件人空白/纯分隔符拒绝；环境留空归一化为 null；upsert 更新既有行并审计")
    void saveValidationAndUpsert() {
        assertThrows(IllegalArgumentException.class,
            () -> service.save(GAME, null, "   ", true, "op"));
        assertThrows(IllegalArgumentException.class,
            () -> service.save(GAME, null, ",,,", true, "op"));

        when(repo.findByGameId(GAME)).thenReturn(Optional.empty());
        AlertEmailConfigEntity created = service.save(GAME, " prod ", "a@x.com, b@y.com", true, "op");
        assertEquals(GAME, created.gameId);
        assertEquals("prod", created.environmentId);
        assertTrue(created.id.startsWith("aec_"));
        // 12 参审计重载（勿验成单参 log(AuditLogEntity)）
        verify(auditLog).log(any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any());

        // 既有行走更新分支（id 不变）
        AlertEmailConfigEntity existing = config("old@x.com", false);
        existing.environmentId = null;
        when(repo.findByGameId(GAME)).thenReturn(Optional.of(existing));
        AlertEmailConfigEntity updated = service.save(GAME, null, "new@x.com", false, "op");
        assertEquals("aec_1", updated.id);
        assertEquals("new@x.com", updated.recipients);
        assertFalse(updated.enabled);
    }

    // ===== 收件人解析 =====

    @Test
    @DisplayName("recipientList：逗号/分号/空白混排解析并去空项")
    void recipientListParsing() {
        AlertEmailConfigEntity cfg = config("a@x.com; b@y.com,c@z.com\n\td@w.com  ,,;", true);
        assertEquals(List.of("a@x.com", "b@y.com", "c@z.com", "d@w.com"), cfg.recipientList());
        assertEquals(List.of(), config(null, true).recipientList());
    }

    // ===== 投递降级 =====

    @Test
    @DisplayName("sendAlertEmail：未配置/未启用/SMTP 缺席均不投递且不抛异常")
    void sendAlertEmailSilentSkips() {
        SystemAlertEntity alert = alert("收入骤降");

        when(repo.findByGameId(GAME)).thenReturn(Optional.empty());
        assertFalse(service.sendAlertEmail(GAME, alert));

        when(repo.findByGameId(GAME)).thenReturn(Optional.of(config("a@x.com", false)));
        assertFalse(service.sendAlertEmail(GAME, alert));

        when(repo.findByGameId(GAME)).thenReturn(Optional.of(config("a@x.com", true)));
        when(mailSenderProvider.getIfAvailable()).thenReturn(null);
        assertFalse(service.sendAlertEmail(GAME, alert));

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("sendAlertEmail：启用 + SMTP 在场 → 消息含收件人/发件人/标题与正文")
    void sendAlertEmailHappyPath() {
        when(repo.findByGameId(GAME)).thenReturn(Optional.of(config("a@x.com, b@y.com", true)));
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);

        assertTrue(service.sendAlertEmail(GAME, alert("收入骤降")));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage msg = captor.getValue();
        assertEquals("oddsmaker@test.local", msg.getFrom());
        assertArrayEquals(new String[]{"a@x.com", "b@y.com"}, msg.getTo());
        assertEquals("[Oddsmaker] 告警：收入骤降", msg.getSubject());
        assertTrue(msg.getText().contains("REVENUE_DROP"));
    }

    @Test
    @DisplayName("sendAlertEmail：SMTP 发送异常降级为 false，不向告警链路传播")
    void sendAlertEmailSmtpFailure() {
        when(repo.findByGameId(GAME)).thenReturn(Optional.of(config("a@x.com", true)));
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
        org.mockito.Mockito.doThrow(new RuntimeException("smtp down"))
            .when(mailSender).send(any(SimpleMailMessage.class));

        assertFalse(service.sendAlertEmail(GAME, alert("收入骤降")));
    }

    // ===== 测试邮件 =====

    @Test
    @DisplayName("sendTestEmail：SMTP 未配置返回 sent=false；在场则发送测试消息")
    void sendTestEmail() {
        AlertEmailConfigEntity cfg = config("a@x.com", true);

        when(mailSenderProvider.getIfAvailable()).thenReturn(null);
        Map<String, Object> skipped = service.sendTestEmail(GAME, cfg, "op");
        assertEquals(Boolean.FALSE, skipped.get("sent"));
        assertEquals(1, skipped.get("recipients"));

        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
        Map<String, Object> sent = service.sendTestEmail(GAME, cfg, "op");
        assertEquals(Boolean.TRUE, sent.get("sent"));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertEquals("[Oddsmaker] 告警邮件通道测试", captor.getValue().getSubject());
    }

    // ===== 辅助 =====

    private SystemAlertEntity alert(String title) {
        SystemAlertEntity a = new SystemAlertEntity();
        a.id = "alert_1";
        a.gameId = GAME;
        a.title = title;
        a.description = "REVENUE_DROP: current 10.00 vs baseline 100.00";
        a.severity = SystemAlertEntity.Severity.CRITICAL;
        a.alertStatus = SystemAlertEntity.AlertStatus.OPEN;
        a.condition = "baseline_down";
        return a;
    }
}
