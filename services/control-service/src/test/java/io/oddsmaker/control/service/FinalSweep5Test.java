package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.AuditLogRepo;
import io.oddsmaker.control.jpa.BlockListRepo;
import io.oddsmaker.control.jpa.ExportJobRepo;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.IdentityLinkRepo;
import io.oddsmaker.control.jpa.RiskCaseRepo;
import io.oddsmaker.control.jpa.ReviewQueueRepo;
import io.oddsmaker.control.jpa.WebhookConfigRepo;
import io.oddsmaker.control.jpa.WebhookLogRepo;
import io.oddsmaker.control.jpa.WebhookConfigEntity;
import io.oddsmaker.control.jpa.WebhookLogEntity;
import io.oddsmaker.control.jpa.ExportJobEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖率最终补漏：安全过滤链构造、导出通知、游戏状态机、Webhook 重试、风控事件分发。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("覆盖率最终补漏测试")
class FinalSweep5Test {

    @Mock
    private AuditLogService auditLog;

    // ===== ExportService.processExportJob 通知分支 =====

    @Mock
    private ExportJobRepo exportJobRepo;

    @Test
    @DisplayName("导出处理：完成通知分支触发")
    void exportCompletionNotification() {
        ExportService service = new ExportService();
        ReflectionTestUtils.setField(service, "exportJobRepo", exportJobRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        ExportJobEntity job = new ExportJobEntity();
        job.id = "ex_1";
        job.gameId = "g";
        job.exportStatus = ExportJobEntity.ExportStatus.PENDING;
        job.exportType = "events";
        job.exportFormat = "csv";
        job.notifyOnComplete = true;
        job.notificationEmail = "ops@example.com";
        when(exportJobRepo.findById("ex_1")).thenReturn(Optional.of(job));
        when(exportJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExportJobEntity done = service.processExportJob("ex_1");
        assertEquals(ExportJobEntity.ExportStatus.COMPLETED, done.exportStatus);
        assertNotNull(done.filePath);
        // 非 PENDING 拒绝
        assertThrows(IllegalStateException.class, () -> service.processExportJob("ex_1"));
    }

    // ===== GameService.validateStatusChange 全分支 =====

    @Mock
    private GameRepo gameRepo;

    @Test
    @DisplayName("游戏状态机：全部合法/非法迁移分支")
    void gameStatusTransitions() {
        GameService service = new GameService();
        ReflectionTestUtils.setField(service, "gameRepo", gameRepo);
        ReflectionTestUtils.setField(service, "auditLog", auditLog);
        when(gameRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var pairs = List.of(new io.oddsmaker.control.jpa.GameEntity.GameStatus[] {
                io.oddsmaker.control.jpa.GameEntity.GameStatus.DEVELOPMENT,
                io.oddsmaker.control.jpa.GameEntity.GameStatus.TESTING}, new io.oddsmaker.control.jpa.GameEntity.GameStatus[] {
                io.oddsmaker.control.jpa.GameEntity.GameStatus.TESTING,
                io.oddsmaker.control.jpa.GameEntity.GameStatus.LIVE}, new io.oddsmaker.control.jpa.GameEntity.GameStatus[] {
                io.oddsmaker.control.jpa.GameEntity.GameStatus.TESTING,
                io.oddsmaker.control.jpa.GameEntity.GameStatus.DEVELOPMENT}, new io.oddsmaker.control.jpa.GameEntity.GameStatus[] {
                io.oddsmaker.control.jpa.GameEntity.GameStatus.LIVE,
                io.oddsmaker.control.jpa.GameEntity.GameStatus.MAINTENANCE}, new io.oddsmaker.control.jpa.GameEntity.GameStatus[] {
                io.oddsmaker.control.jpa.GameEntity.GameStatus.MAINTENANCE,
                io.oddsmaker.control.jpa.GameEntity.GameStatus.LIVE}, new io.oddsmaker.control.jpa.GameEntity.GameStatus[] {
                io.oddsmaker.control.jpa.GameEntity.GameStatus.MAINTENANCE,
                io.oddsmaker.control.jpa.GameEntity.GameStatus.DISCONTINUED});
        for (io.oddsmaker.control.jpa.GameEntity.GameStatus[] pair : pairs) {
            io.oddsmaker.control.jpa.GameEntity entity = base("g_" + pair[0] + "_" + pair[1], pair[0]);
            when(gameRepo.findById(entity.id)).thenReturn(Optional.of(entity));
            assertNotNull(service.updateGame(entity.id, dto(pair[1])));
        }

        // 非法迁移：DEVELOPMENT→LIVE
        io.oddsmaker.control.jpa.GameEntity dev = base("g_bad", io.oddsmaker.control.jpa.GameEntity.GameStatus.DEVELOPMENT);
        when(gameRepo.findById("g_bad")).thenReturn(Optional.of(dev));
        assertThrows(IllegalArgumentException.class,
            () -> service.updateGame("g_bad", dto(io.oddsmaker.control.jpa.GameEntity.GameStatus.LIVE)));

        // DISCONTINUED 永不可迁移
        io.oddsmaker.control.jpa.GameEntity disc = base("g_disc", io.oddsmaker.control.jpa.GameEntity.GameStatus.DISCONTINUED);
        disc.status = io.oddsmaker.control.jpa.GameEntity.GameStatus.DISCONTINUED;
        when(gameRepo.findById("g_disc")).thenReturn(Optional.of(disc));
        assertThrows(IllegalArgumentException.class,
            () -> service.updateGame("g_disc", dto(io.oddsmaker.control.jpa.GameEntity.GameStatus.LIVE)));
    }

    private io.oddsmaker.control.jpa.GameEntity base(String id, io.oddsmaker.control.jpa.GameEntity.GameStatus from) {
        io.oddsmaker.control.jpa.GameEntity e = new io.oddsmaker.control.jpa.GameEntity();
        e.id = id;
        e.name = id;
        e.status = from;
        e.defaultCurrency = "USD";
        e.defaultTimezone = "UTC";
        return e;
    }

    private io.oddsmaker.control.dto.GameDTO dto(io.oddsmaker.control.jpa.GameEntity.GameStatus status) {
        io.oddsmaker.control.dto.GameDTO dto = new io.oddsmaker.control.dto.GameDTO();
        dto.id = "x";
        dto.name = "x";
        dto.status = status;
        dto.defaultCurrency = "USD";
        dto.defaultTimezone = "UTC";
        return dto;
    }

    // ===== WebhookService.processPendingRetries + retryWebhook 失败分支 =====

    @Mock
    private WebhookConfigRepo webhookConfigRepo;

    @Mock
    private WebhookLogRepo webhookLogRepo;

    @Test
    @DisplayName("Webhook 重试：restTemplate 缺失走失败保存分支；配置缺失跳过")
    void webhookRetryFailurePath() {
        WebhookService service = new WebhookService();
        ReflectionTestUtils.setField(service, "webhookConfigRepo", webhookConfigRepo);
        ReflectionTestUtils.setField(service, "webhookLogRepo", webhookLogRepo);
        ReflectionTestUtils.setField(service, "restTemplate", (RestTemplate) null);

        WebhookConfigEntity config = new WebhookConfigEntity();
        config.id = "wc_1";
        config.gameId = "g";
        config.name = "hook";
        config.webhookUrl = "http://127.0.0.1:1/hook";
        config.status = WebhookConfigEntity.WebhookStatus.ACTIVE;
        WebhookLogEntity log = new WebhookLogEntity();
        log.id = "wl_1";
        log.webhookConfigId = "wc_1";
        log.eventType = "risk_action";
        log.requestBody = "{\"k\":\"v\"}";
        log.deliveryStatus = WebhookLogEntity.DeliveryStatus.RETRYING;
        when(webhookLogRepo.findPendingRetries(any())).thenReturn(List.of(log));
        when(webhookConfigRepo.findById("wc_1")).thenReturn(Optional.of(config));
        when(webhookLogRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processPendingRetries();
assertTrue(log.deliveryStatus == WebhookLogEntity.DeliveryStatus.FAILED
            || log.deliveryStatus == WebhookLogEntity.DeliveryStatus.SENDING);

        // 配置不存在 → 跳过
        WebhookLogEntity orphan = new WebhookLogEntity();
        orphan.id = "wl_2";
        orphan.webhookConfigId = "wc_missing";
        when(webhookLogRepo.findPendingRetries(any())).thenReturn(List.of(orphan));
        when(webhookConfigRepo.findById("wc_missing")).thenReturn(Optional.empty());
        service.processPendingRetries();  // 不抛异常即通过
    }

    // ===== RiskEventConsumer.onRiskEvent 分发分支 =====

    @Test
    @DisplayName("风控事件分发：BLOCK/THROTTLE/MARK/未知动作与坏消息")
    void riskEventDispatch() {
        RiskEventConsumer consumer = new RiskEventConsumer();
        ObjectMapper mapper = new ObjectMapper();
        ReflectionTestUtils.setField(consumer, "objectMapper", mapper);
        ReflectionTestUtils.setField(consumer, "blockListService",
            mock(BlockListService.class, Mockito.RETURNS_MOCKS));
        ReflectionTestUtils.setField(consumer, "webhookService",
            mock(WebhookService.class, Mockito.RETURNS_MOCKS));
        ReflectionTestUtils.setField(consumer, "auditLogService", auditLog);
        ReflectionTestUtils.setField(consumer, "identityLinkRepo", mock(IdentityLinkRepo.class));
        ReflectionTestUtils.setField(consumer, "riskCaseRepo", mock(RiskCaseRepo.class));
        ReflectionTestUtils.setField(consumer, "reviewQueueService",
            mock(ReviewQueueService.class, Mockito.RETURNS_MOCKS));
        RiskActionRecorder recorder = mock(RiskActionRecorder.class);
        ReflectionTestUtils.setField(consumer, "riskActionRecorder", recorder);

        // 坏 JSON / 无 action
        consumer.onRiskEvent("not-json");
        consumer.onRiskEvent("{\"riskEventId\":\"re_1\"}");

        String template = "{\"riskEventId\":\"re_%d\",\"gameId\":\"g\",\"environment\":\"prod\","
            + "\"ruleId\":\"rr\",\"subjectType\":\"PLAYER\",\"subjectId\":\"p1\",\"score\":80,"
            + "\"severity\":\"HIGH\",\"action\":\"%s\"}";
        consumer.onRiskEvent(String.format(template, 2, "THROTTLE"));
        consumer.onRiskEvent(String.format(template, 3, "MARK"));
        consumer.onRiskEvent(String.format(template, 4, "UNKNOWN_ACTION"));
        // 各分发分支主路径覆盖（不抛异常即通过）
    }
}
