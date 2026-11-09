package io.oddsmaker.control.jpa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JPA 实体业务方法深度覆盖（LAY-010 拆分）：管道作业/导出作业/健康检查/SDK 密钥/遥测配置/webhook 配置/配额的状态判定与迁移分支。
 */
@DisplayName("JPA 实体填充字段后的业务方法分支覆盖")
class EntitiesDeepOpsInfraTest {

    @Test
    @DisplayName("PipelineJobEntity：状态判定/生命周期迁移/指标计算分支")
    void pipelineJobEntity() {
        PipelineJobEntity j = new PipelineJobEntity();
        j.pipelineId = "pipe-1";
        j.gameId = "g1";
        j.jobName = "etl";

        // 初始 PENDING
        assertTrue(j.isPending());
        assertFalse(j.isRunning());
        assertFalse(j.isCompleted());
        assertFalse(j.isFailed());
        assertFalse(j.isCancelled());
        assertFalse(j.isRetryable());

        // start：进入 RUNNING 并计算排队时长
        j.createdAt = LocalDateTime.now().minusMinutes(5);
        j.start();
        assertTrue(j.isRunning());
        assertNotNull(j.startedAt);
        assertTrue(j.queueTimeMs >= 290000);

        // 运行中：只有 startedAt → 以当前时刻计时长
        j.startedAt = LocalDateTime.now().minusMinutes(10);
        assertTrue(j.getExecutionDurationMinutes() >= 9);
        // 手动构造完成区间 → 精确时长
        j.completedAt = j.startedAt.plusMinutes(30);
        assertEquals(30, j.getExecutionDurationMinutes());
        // 均为空 → 0
        assertEquals(0, new PipelineJobEntity().getExecutionDurationMinutes());

        // complete：进入 COMPLETED 并落指标
        j.completedAt = null;
        j.durationMs = null;
        j.complete(1000L, 100L);
        assertTrue(j.isCompleted());
        assertNotNull(j.completedAt);
        assertEquals(1000L, j.processedRows);
        assertEquals(100L, j.errorRows);
        j.durationMs = 500L; // 直接给定以免同毫秒为 0
        assertEquals(2000.0, j.getProcessingRate(), 0.001);
        assertEquals(10.0, j.getErrorRate(), 0.001);
        // 退化分支：durationMs 为空 / processedRows 为 0
        j.durationMs = null;
        assertEquals(0.0, j.getProcessingRate(), 0.001);
        j.processedRows = 0L;
        assertEquals(0.0, j.getErrorRate(), 0.001);

        // fail：进入 FAILED，重试计数与可重试判断
        PipelineJobEntity fj = new PipelineJobEntity();
        fj.startedAt = LocalDateTime.now().minusMinutes(2);
        fj.maxRetries = 3;
        fj.fail("boom");
        assertTrue(fj.isFailed());
        assertEquals("boom", fj.errorMessage);
        assertEquals(1, fj.retryCount);
        assertTrue(fj.isRetryable()); // 1 < 3
        fj.retry();
        assertEquals(PipelineJobEntity.JobStatus.RETRYING, fj.jobStatus);
        assertEquals(2, fj.retryCount);
        fj.fail("again");
        assertEquals(3, fj.retryCount);
        assertFalse(fj.isRetryable()); // 3 >= 3
        assertNotNull(fj.durationMs);

        // maxRetries 为空 → 只要 FAILED 即可重试
        fj.maxRetries = null;
        assertTrue(fj.isRetryable());

        // cancel
        fj.cancel();
        assertTrue(fj.isCancelled());
        assertNotNull(fj.completedAt);
    }

    @Test
    @DisplayName("ExportJobEntity：状态迁移/可下载判定/文件大小展示/状态描述")
    void exportJobEntity() {
        ExportJobEntity e = new ExportJobEntity();
        e.gameId = "g1";
        e.exportType = "events";
        e.fileName = "events.csv";

        assertTrue(e.isPending());
        assertFalse(e.isProcessing());

        // 处理中 + 进度更新
        e.markAsProcessing();
        assertTrue(e.isProcessing());
        assertNotNull(e.startedAt);
        e.updateProgress(50L, 25);
        assertEquals(50L, e.exportedRows);
        assertEquals(25, e.progressPercent);

        // 完成：可下载、7 天后过期
        e.fileSizeBytes = 512L;
        assertEquals("512 B", e.getFileSizeDisplay());
        e.markAsCompleted("/tmp/f.csv", 2048L, 100L);
        assertTrue(e.isCompleted());
        assertEquals("/tmp/f.csv", e.filePath);
        assertEquals(2048L, e.fileSizeBytes);
        assertEquals(100L, e.exportedRows);
        assertEquals(100, e.progressPercent);
        assertNotNull(e.executionTimeMs);
        assertTrue(e.expiresAt.isAfter(LocalDateTime.now().plusDays(6)));
        assertEquals("2.0 KB", e.getFileSizeDisplay());

        // 文件大小各级展示
        e.fileSizeBytes = 512L * 1024;
        assertEquals("512.0 KB", e.getFileSizeDisplay());
        e.fileSizeBytes = 2L * 1024 * 1024;
        assertEquals("2.0 MB", e.getFileSizeDisplay());
        e.fileSizeBytes = 3L * 1024 * 1024 * 1024;
        assertEquals("3.0 GB", e.getFileSizeDisplay());
        e.fileSizeBytes = null;
        assertEquals("Unknown", e.getFileSizeDisplay());

        // 过期分支：时间过期 → 不可下载
        e.fileSizeBytes = 2048L;
        e.expiresAt = LocalDateTime.now().minusMinutes(1);
        assertTrue(e.isExpired());
        // 状态过期 → isExpired 直接成立
        e.exportStatus = ExportJobEntity.ExportStatus.EXPIRED;
        assertTrue(e.isExpired());
        // 恢复完成态且未过期 → 又可下载
        e.exportStatus = ExportJobEntity.ExportStatus.COMPLETED;
        e.expiresAt = LocalDateTime.now().plusDays(1);
        assertFalse(e.isExpired());

        // 失败/取消
        e.markAsFailed("disk full");
        assertTrue(e.isFailed());
        assertEquals("disk full", e.errorMessage);
        e.markAsCancelled("user request");
        assertEquals(ExportJobEntity.ExportStatus.CANCELLED, e.exportStatus);
        assertEquals("user request", e.statusMessage);

        // 描述与执行分钟
        e.exportStatus = ExportJobEntity.ExportStatus.COMPLETED;
        e.fileName = null; // 描述回退到 no-filename
        assertNotNull(e.getStatusDescription());
        assertTrue(e.getStatusDescription().contains("no-filename"));
        e.executionTimeMs = 120000L;
        assertEquals(2, e.getExecutionTimeMinutes());
        e.executionTimeMs = null;
        assertEquals(0, e.getExecutionTimeMinutes());
    }

    @Test
    @DisplayName("HealthCheckEntity：健康/降级/离线判定/响应阈值/统计与标记方法")
    void healthCheckEntity() {
        HealthCheckEntity h = new HealthCheckEntity();
        h.checkType = HealthCheckEntity.CheckType.DATABASE;
        h.checkName = "primary-db";
        h.warningThresholdMs = 1000;
        h.criticalThresholdMs = 5000;

        assertFalse(h.isHealthy());
        assertFalse(h.isDegraded());
        assertFalse(h.isUnhealthy());

        // 响应时间阈值分支
        h.responseTimeMs = 500L;
        assertFalse(h.isSlowResponse());
        assertFalse(h.isWarningResponse());
        h.responseTimeMs = 2000L;
        assertTrue(h.isWarningResponse());
        assertFalse(h.isSlowResponse());
        h.responseTimeMs = 5000L; // 等于严重阈值：不算慢响应，但仍落在警告区间 (warning, critical]
        assertFalse(h.isSlowResponse());
        assertTrue(h.isWarningResponse());
        h.responseTimeMs = 6000L;
        assertTrue(h.isSlowResponse());
        assertFalse(h.isWarningResponse());
        h.responseTimeMs = null;
        assertFalse(h.isSlowResponse());
        assertFalse(h.isWarningResponse());

        // 成功率
        assertEquals(0.0, h.getSuccessRate(), 0.001); // totalChecks=0
        h.totalChecks = 10;
        h.failedChecks = 2;
        assertEquals(80.0, h.getSuccessRate(), 0.001);

        // 标记健康：失败计数清零
        h.consecutiveFailures = 3;
        h.markAsHealthy("ok");
        assertTrue(h.isHealthy());
        assertEquals("ok", h.statusMessage);
        assertNotNull(h.lastHealthyAt);
        assertEquals(0, h.consecutiveFailures);

        // 降级态判定
        h.healthStatus = HealthCheckEntity.HealthStatus.DEGRADED;
        assertTrue(h.isDegraded());
        assertFalse(h.isHealthy());

        // 标记不健康/离线：连续失败递增
        h.markAsUnhealthy("timeout");
        assertTrue(h.isUnhealthy());
        assertEquals(1, h.consecutiveFailures);
        assertNotNull(h.lastUnhealthyAt);
        h.healthStatus = HealthCheckEntity.HealthStatus.DOWN;
        h.consecutiveFailures = 2;
        assertTrue(h.isUnhealthy()); // DOWN 同样计入不健康

        // 计数方法
        int beforeChecks = h.totalChecks;
        int beforeFailures = h.failedChecks;
        h.incrementChecks();
        h.incrementFailures();
        assertEquals(beforeChecks + 1, h.totalChecks);
        assertEquals(beforeFailures + 1, h.failedChecks);
    }

    @Test
    @DisplayName("SDKKeyEntity：密钥状态迁移/过期分支/交付模式与记录方法")
    void sdkKeyEntity() {
        SDKKeyEntity k = new SDKKeyEntity();
        k.gameId = "g1";
        k.keyName = "android-key";
        k.publicKey = "pk";
        k.platform = SDKKeyEntity.SDKPlatform.ANDROID;

        // 活跃 + 未过期
        assertTrue(k.isActive());
        assertFalse(k.isExpired());
        assertFalse(k.isSuspended());
        assertFalse(k.isRevoked());

        // 时间过期分支
        k.expiresAt = LocalDateTime.now().plusDays(1);
        assertTrue(k.isActive());
        k.expiresAt = LocalDateTime.now().minusDays(1);
        assertFalse(k.isActive());
        assertTrue(k.isExpired());
        k.expiresAt = LocalDateTime.now().plusDays(1);

        // 状态迁移：暂停/恢复/吊销/过期
        k.suspend();
        assertTrue(k.isSuspended());
        assertFalse(k.isActive());
        k.activate();
        assertTrue(k.isActive());
        k.revoke();
        assertTrue(k.isRevoked());
        k.activate();
        k.expire();
        assertTrue(k.isExpired()); // 状态过期分支
        k.activate();

        // 交付模式判定
        k.deliveryMode = SDKKeyEntity.DeliveryMode.REALTIME;
        assertTrue(k.isRealtime());
        assertFalse(k.isBatch());
        assertFalse(k.isHybrid());
        k.deliveryMode = SDKKeyEntity.DeliveryMode.BATCH;
        assertTrue(k.isBatch());
        k.deliveryMode = SDKKeyEntity.DeliveryMode.HYBRID;
        assertTrue(k.isHybrid());
    }

    @Test
    @DisplayName("TelemetryConfigEntity：配置状态迁移/默认与全局配置判定分支")
    void telemetryConfigEntity() {
        TelemetryConfigEntity c = new TelemetryConfigEntity();
        c.configName = "delivery-default";
        c.configType = TelemetryConfigEntity.ConfigType.EVENT_DELIVERY;

        // 初始草稿
        assertTrue(c.isDraft());
        assertFalse(c.isActive());
        assertFalse(c.isInactive());
        assertFalse(c.isArchived());

        // 状态迁移
        c.activate();
        assertTrue(c.isActive());
        c.deactivate();
        assertTrue(c.isInactive());
        c.archive();
        assertTrue(c.isArchived());

        // 默认配置空值防御
        c.isDefault = null;
        assertFalse(c.isDefault());
        c.isDefault = true;
        assertTrue(c.isDefault());

        // 全局与游戏级配置互斥分支
        c.gameId = null;
        assertTrue(c.isGlobal());
        assertFalse(c.isGameSpecific());
        c.gameId = "g1";
        assertFalse(c.isGlobal());
        assertTrue(c.isGameSpecific());
    }

    @Test
    @DisplayName("WebhookConfigEntity：发送匹配规则/成功率统计/连续失败熔断分支")
    void webhookConfigEntity() {
        WebhookConfigEntity w = new WebhookConfigEntity();
        w.gameId = "g1";
        w.name = "risk-hook";
        w.webhookUrl = "https://example.com/hook";

        assertTrue(w.isActive());
        assertTrue(w.shouldRetry()); // maxRetries=3
        w.maxRetries = 0;
        assertFalse(w.shouldRetry());
        w.maxRetries = null;
        assertFalse(w.shouldRetry());
        w.maxRetries = 3;

        // 成功率：无记录为 0
        assertEquals(0.0, w.getSuccessRate(), 0.001);
        w.totalSent = 5L;
        w.totalSuccess = 4L;
        assertEquals(0.8, w.getSuccessRate(), 0.001);

        // 记录成功
        w.recordSuccess();
        assertEquals(6L, w.totalSent);
        assertEquals(5L, w.totalSuccess);
        assertNotNull(w.lastSentAt);
        assertNotNull(w.lastSuccessAt);

        // 描述：显示名与鉴权回退
        w.displayName = "风控告警";
        w.authType = "bearer";
        assertEquals("[active] 风控告警 -> https://example.com/hook (bearer)", w.getWebhookDescription());
        w.displayName = null;
        w.authType = null;
        assertEquals("[active] risk-hook -> https://example.com/hook (none)", w.getWebhookDescription());

        // 事件/风险等级匹配（大小写不敏感）
        w.eventTypes = "risk_alert, block_alert";
        w.riskLevels = "HIGH,CRITICAL";
        assertTrue(w.shouldSendForEvent("RISK_ALERT", "high"));
        assertFalse(w.shouldSendForEvent("unknown_event", "high")); // 事件不匹配
        assertFalse(w.shouldSendForEvent("risk_alert", "low")); // 等级不匹配
        w.eventTypes = null;
        w.riskLevels = null;
        assertTrue(w.shouldSendForEvent("anything", "low")); // 未配置默认发送

        // 连续失败 5 次触发熔断
        WebhookConfigEntity failing = new WebhookConfigEntity();
        failing.name = "f";
        failing.webhookUrl = "https://x";
        for (int i = 0; i < 4; i++) {
            failing.recordFailure("e" + i);
            assertTrue(failing.isActive());
        }
        failing.recordFailure("e4");
        assertEquals(WebhookConfigEntity.WebhookStatus.FAILED, failing.status);
        assertFalse(failing.isActive());
        assertFalse(failing.shouldSendForEvent("risk_alert", "HIGH")); // 非 ACTIVE 不发送
        assertEquals(5L, failing.totalFailed);
        assertEquals("e4", failing.lastError);
        // 成功一次后恢复 ACTIVE
        failing.recordSuccess();
        assertTrue(failing.isActive());
    }

    @Test
    @DisplayName("QuotaEntity：配额用量百分比/阈值告警/递增与重置分支")
    void quotaEntity() {
        QuotaEntity q = new QuotaEntity();
        q.gameId = "g1";
        q.resourceType = QuotaEntity.ResourceType.EVENTS_PER_DAY;
        q.quotaLimit = 100L;
        q.currentUsage = 50L;

        // 用量百分比与阈值
        assertEquals(50.0, q.getUsagePercent(), 0.001);
        assertFalse(q.isOverLimit());
        assertFalse(q.isNearWarningThreshold());
        assertFalse(q.isNearAlertThreshold());
        assertFalse(q.shouldSendWarning());
        assertFalse(q.shouldSendAlert());

        // 超限与零限额
        q.currentUsage = 100L;
        assertTrue(q.isOverLimit());
        q.quotaLimit = 0L;
        assertEquals(0.0, q.getUsagePercent(), 0.001);
        assertTrue(q.isOverLimit());  // currentUsage(100) >= quotaLimit(0)
        q.quotaLimit = 100L;

        // 递增触发警告/告警阈值并一次性发送
        q.currentUsage = 50L;
        q.usagePercent = null;
        q.incrementUsage(35); // 85%
        assertEquals(85L, q.currentUsage);
        assertEquals(85.0, q.usagePercent, 0.001);
        assertTrue(q.isNearWarningThreshold());
        assertFalse(q.isNearAlertThreshold());
        assertTrue(q.shouldSendWarning());
        q.markWarningSent();
        assertFalse(q.shouldSendWarning());

        q.incrementUsage(10); // 95%
        assertEquals(95.0, q.getUsagePercent(), 0.001);
        assertTrue(q.isNearAlertThreshold());
        assertTrue(q.shouldSendAlert());
        q.markAlertSent();
        assertFalse(q.shouldSendAlert());

        // 重置用量
        q.resetUsage();
        assertEquals(0L, q.currentUsage);
        assertEquals(0.0, q.usagePercent, 0.001);
        assertFalse(q.warningSent);
        assertFalse(q.alertSent);
        assertNotNull(q.lastCalculatedAt);

        // 可空 currentUsage 拆箱兜底：不抛 NPE，按 0 处理
        q.currentUsage = null;
        assertEquals(0L, q.usage());
        assertEquals(0.0, q.getUsagePercent(), 0.001);
        assertFalse(q.isOverLimit());
        q.incrementUsage(25);
        assertEquals(25L, q.currentUsage);
        assertEquals(25.0, q.usagePercent, 0.001);
    }

}
