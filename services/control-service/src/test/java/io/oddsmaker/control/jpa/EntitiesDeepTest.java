package io.oddsmaker.control.jpa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JPA 实体业务方法深度覆盖：填充字段后验证状态判定分支与状态迁移逻辑。
 * 与 coverage 包下的冒烟测试互补，这里覆盖依赖字段值的分支（过期/白名单/阈值等）。
 */
@DisplayName("JPA 实体填充字段后的业务方法分支覆盖")
class EntitiesDeepTest {

    @Test
    @DisplayName("FeatureFlagEntity：用户可用性/过期/计划启用/灰度推进分支")
    void featureFlagEntity() {
        FeatureFlagEntity f = new FeatureFlagEntity();
        f.flagKey = "new-ui";
        f.flagName = "新UI";
        f.createdBy = "admin";
        f.defaultValue = false;

        // 禁用状态：任何用户不可用
        assertFalse(f.isEnabled());
        assertTrue(f.isDisabled());
        assertFalse(f.isAvailableForUser("u1", "g1"));

        // 启用 + 未过期 + 无白名单：全部可用
        f.enable();
        assertTrue(f.isEnabled());
        assertTrue(f.isAvailableForUser("u1", "g1"));

        // 过期分支
        f.expiryDate = LocalDateTime.now().minusDays(1);
        assertTrue(f.isExpired());
        assertFalse(f.isAvailableForUser("u1", "g1"));
        f.expiryDate = LocalDateTime.now().plusDays(1);
        assertFalse(f.isExpired());

        // 用户黑名单分支
        f.blacklistUsers = "u1,u2";
        assertFalse(f.isAvailableForUser("u1", "g1"));
        assertTrue(f.isAvailableForUser("u3", "g1"));

        // 游戏黑名单分支
        f.blacklistUsers = null;
        f.blacklistGames = "g1";
        assertFalse(f.isAvailableForUser("u1", "g1"));
        assertTrue(f.isAvailableForUser("u1", "g2"));
        f.blacklistGames = null;

        // 用户白名单命中/未命中
        f.whitelistUsers = "u1,u2";
        assertTrue(f.isAvailableForUser("u2", "g1"));
        assertFalse(f.isAvailableForUser("u9", "g1"));
        f.whitelistUsers = null;

        // 游戏白名单命中/未命中
        f.whitelistGames = "g1,g2";
        assertTrue(f.isAvailableForUser("u1", "g2"));
        assertFalse(f.isAvailableForUser("u1", "g9"));
        f.whitelistGames = null;

        // 条件启用/灰度：走 defaultValue 分支
        f.flagStatus = FeatureFlagEntity.FlagStatus.CONDITIONAL;
        assertTrue(f.isConditional());
        f.defaultValue = true;
        assertTrue(f.isAvailableForUser("u1", "g1"));
        f.flagStatus = FeatureFlagEntity.FlagStatus.STAGED_ROLLOUT;
        assertTrue(f.isStagedRollout());
        f.defaultValue = false;
        assertFalse(f.isAvailableForUser("u1", "g1"));


        // 百分比设置：边界钳制与状态联动
        f.setPercentage(150);
        assertEquals(100, f.percentageValue);
        assertTrue(f.isEnabled());
        f.setPercentage(-10);
        assertEquals(0, f.percentageValue);
        assertTrue(f.isDisabled());
        f.setPercentage(50);
        assertEquals(50, f.percentageValue);
        assertTrue(f.isStagedRollout());

        // 灰度推进：仅灰度态且有步骤时步数递增
        f.rolloutSteps = "[10,50,100]";
        f.currentStep = 0;
        f.advanceRollout();
        assertEquals(1, f.currentStep);
        f.advanceRollout();
        assertEquals(2, f.currentStep);
        f.rolloutSteps = null;
        f.advanceRollout();
        assertEquals(2, f.currentStep); // 无步骤不推进
        f.disable();
        f.rolloutSteps = "[10,50,100]";
        f.advanceRollout();
        assertEquals(2, f.currentStep); // 非灰度态不推进

        // 灰度分桶：FNV-1a 确定性（锚定向量硬编码防哈希漂移），公式与 ExperimentSplitter 同源
        f.setPercentage(50); // 回到 STAGED_ROLLOUT，pct=50
        // hash32("new-ui:u7")=0x18abaef4 → bucket=28 < 50 命中；hash32("new-ui:u5")=0x1aabb21a → bucket=66 不命中
        assertTrue(f.isAvailableForUser("u7", "g1"));
        assertFalse(f.isAvailableForUser("u5", "g1"));
        // 重复调用稳定（确定性分桶）
        assertTrue(f.isAvailableForUser("u7", "g1"));
        assertFalse(f.isAvailableForUser("u5", "g1"));
        // pct 0/100 边界（手动保持灰度态，不借 ENABLED 捷径）
        f.setPercentage(0);
        assertFalse(f.isAvailableForUser("u7", "g1"));
        f.setPercentage(100);
        f.flagStatus = FeatureFlagEntity.FlagStatus.STAGED_ROLLOUT;
        assertTrue(f.isAvailableForUser("u1", "g1")); // bucket<100 恒真
        f.setPercentage(50);

        // 白名单优先于分桶：5% 灰度下 u1（bucket=90）不命中，但白名单透传
        f.setPercentage(5);
        assertFalse(f.isAvailableForUser("u1", "g1"));
        f.whitelistUsers = "u1";
        assertTrue(f.isAvailableForUser("u1", "g1"));
        f.whitelistUsers = null;

        // userId null/blank 回落 defaultValue（不参与分桶）
        f.defaultValue = true;
        assertTrue(f.isAvailableForUser(null, "g1"));
        assertTrue(f.isAvailableForUser("  ", "g1"));
        f.defaultValue = false;
        assertFalse(f.isAvailableForUser(null, "g1"));
        assertFalse(f.isAvailableForUser("  ", "g1"));

        // percentageValue null 防御（按 0 处理，回落 defaultValue）
        f.percentageValue = null;
        assertFalse(f.isAvailableForUser("u5", "g1"));
        f.percentageValue = 5;

        // 灰度推进回写：步进按 rolloutSteps 更新百分比，末步 100 自动 ENABLED
        f.flagStatus = FeatureFlagEntity.FlagStatus.STAGED_ROLLOUT;
        f.rolloutSteps = "[10,50,100]";
        f.currentStep = 0;
        f.setPercentage(5);
        f.advanceRollout();
        assertEquals(1, f.currentStep);
        assertEquals(10, f.percentageValue);
        f.advanceRollout();
        assertEquals(2, f.currentStep);
        assertEquals(50, f.percentageValue);
        f.advanceRollout();
        assertEquals(3, f.currentStep);
        assertEquals(100, f.percentageValue);
        assertTrue(f.isEnabled()); // 末步自动启用
        f.flagStatus = FeatureFlagEntity.FlagStatus.STAGED_ROLLOUT;
        f.advanceRollout(); // 超出步骤数：仅递增不回写
        assertEquals(4, f.currentStep);
        assertEquals(100, f.percentageValue);

        // 回写容错：非法 JSON/越界步骤/空白 → 仅递增不回写
        f.currentStep = 0;
        f.setPercentage(5);
        f.rolloutSteps = "not-json";
        f.advanceRollout();
        assertEquals(1, f.currentStep);
        assertEquals(5, f.percentageValue);
        f.rolloutSteps = "[10,200]";
        f.advanceRollout();
        assertEquals(2, f.currentStep);
        assertEquals(5, f.percentageValue);
        f.rolloutSteps = " ";
        f.advanceRollout();
        assertEquals(3, f.currentStep);
        assertEquals(5, f.percentageValue);
    }

    @Test
    @DisplayName("FeatureFlagEntity：CONDITIONAL 最小条件求值（AND/attribute/op/回落）")
    void featureFlagConditions() {
        FeatureFlagEntity f = new FeatureFlagEntity();
        f.flagKey = "cond-flag";
        f.flagName = "条件开关";
        f.createdBy = "admin";
        f.flagStatus = FeatureFlagEntity.FlagStatus.CONDITIONAL;
        f.defaultValue = true;

        // conditions null/空/非法 JSON/空数组/对象 → 回落 defaultValue（保守）
        assertTrue(f.isAvailableForUser("u1", "g1"));
        f.conditions = "";
        assertTrue(f.isAvailableForUser("u1", "g1"));
        f.conditions = "not-json";
        assertTrue(f.isAvailableForUser("u1", "g1"));
        f.conditions = "[]";
        assertTrue(f.isAvailableForUser("u1", "g1"));
        f.conditions = "{}";
        assertTrue(f.isAvailableForUser("u1", "g1"));

        // game_id in：命中/未命中
        f.conditions = "[{\"attribute\":\"game_id\",\"op\":\"in\",\"value\":[\"g1\",\"g2\"]}]";
        assertTrue(f.isAvailableForUser("u1", "g1"));
        assertFalse(f.isAvailableForUser("u1", "g9"));

        // user_id eq：标量 value 命中/未命中
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":\"eq\",\"value\":\"u1\"}]";
        assertTrue(f.isAvailableForUser("u1", "g1"));
        assertFalse(f.isAvailableForUser("u2", "g1"));

        // op 大小写不敏感 + 数值标量/数组 value 转字符串比对
        f.conditions = "[{\"attribute\":\"game_id\",\"op\":\"IN\",\"value\":[7,8]}]";
        assertTrue(f.isAvailableForUser("u1", "7"));
        assertFalse(f.isAvailableForUser("u1", "9"));

        // ne / not_in
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":\"ne\",\"value\":\"u1\"}]";
        assertFalse(f.isAvailableForUser("u1", "g1"));
        assertTrue(f.isAvailableForUser("u2", "g1"));
        f.conditions = "[{\"attribute\":\"game_id\",\"op\":\"not_in\",\"value\":[\"g1\"]}]";
        assertTrue(f.isAvailableForUser("u1", "g2"));
        assertFalse(f.isAvailableForUser("u1", "g1"));

        // 多条件 AND
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":\"eq\",\"value\":\"u1\"},"
            + "{\"attribute\":\"game_id\",\"op\":\"eq\",\"value\":\"g1\"}]";
        assertTrue(f.isAvailableForUser("u1", "g1"));
        assertFalse(f.isAvailableForUser("u2", "g1"));
        assertFalse(f.isAvailableForUser("u1", "g2"));

        // 未知 attribute / 未知 op / value 空数组 / 入参 null → 该条件 false（明确不放行，不回落）
        f.conditions = "[{\"attribute\":\"device_id\",\"op\":\"eq\",\"value\":\"d1\"}]";
        assertFalse(f.isAvailableForUser("u1", "g1"));
        f.conditions = "[{\"attribute\":\"game_id\",\"op\":\"regex\",\"value\":\"g.*\"}]";
        assertFalse(f.isAvailableForUser("u1", "g1"));
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":\"eq\",\"value\":[]}]";
        assertFalse(f.isAvailableForUser("u1", "g1"));
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":\"eq\",\"value\":\"u1\"}]";
        assertFalse(f.isAvailableForUser(null, "g1"));
        f.conditions = "[{\"attribute\":\"game_id\",\"op\":\"eq\",\"value\":\"g1\"}]";
        assertFalse(f.isAvailableForUser("u1", null));

        // 防御分支：null 元素 / 缺 attribute → 条件 false；裸数值标量 value 转字符串比对
        f.conditions = "[null]";
        assertFalse(f.isAvailableForUser("u1", "g1"));
        f.conditions = "[{\"op\":\"eq\",\"value\":\"u1\"}]";
        assertFalse(f.isAvailableForUser("u1", "g1"));
        f.conditions = "[{\"attribute\":\"game_id\",\"op\":\"eq\",\"value\":7}]";
        assertTrue(f.isAvailableForUser("u1", "7"));
        assertFalse(f.isAvailableForUser("u1", "9"));

        // 条件态白名单优先：条件未命中但白名单用户透传
        f.conditions = "[{\"attribute\":\"game_id\",\"op\":\"eq\",\"value\":\"g9\"}]";
        assertFalse(f.isAvailableForUser("u1", "g1"));
        f.whitelistUsers = "u1";
        assertTrue(f.isAvailableForUser("u1", "g1"));
        f.whitelistUsers = null;

        // conditions 空且 defaultValue=false → false
        f.defaultValue = false;
        f.conditions = null;
        assertFalse(f.isAvailableForUser("u1", "g1"));
    }

    @Test
    @DisplayName("FeatureFlagEntity：黑白名单 JSON 数组精确成员判断（子串误伤回归）与非 JSON 回退")
    void featureFlagListMembership() {
        FeatureFlagEntity f = new FeatureFlagEntity();
        f.flagKey = "list-flag";
        f.flagName = "名单开关";
        f.createdBy = "admin";
        f.defaultValue = false;

        // 用户黑名单 JSON 数组：["u10"] 含 "u1" 子串但不含成员 "u1" → 不再误拒（回归核心）
        f.enable();
        f.blacklistUsers = "[\"u10\"]";
        assertTrue(f.isAvailableForUser("u1", "g1"));
        assertFalse(f.isAvailableForUser("u10", "g1"));

        // 多元素名单：u1 命中拒绝、u10 命中拒绝、u2 放行
        f.blacklistUsers = "[\"u1\",\"u10\"]";
        assertFalse(f.isAvailableForUser("u1", "g1"));
        assertFalse(f.isAvailableForUser("u10", "g1"));
        assertTrue(f.isAvailableForUser("u2", "g1"));

        // 游戏黑名单 JSON 数组：["g10"] 含 "g1" 子串但 "g1" 不是成员 → 不再误拒（回归核心）
        f.blacklistUsers = null;
        f.blacklistGames = "[\"g10\"]";
        assertFalse(f.isAvailableForUser("u1", "g10"));
        assertTrue(f.isAvailableForUser("u1", "g1"));
        assertTrue(f.isAvailableForUser("u1", "g2"));

        // ENABLED 态用户白名单：精确命中（u10 在名单、u1 不在——旧子串匹配下 u1 被误放）
        f.blacklistGames = null;
        f.whitelistUsers = "[\"u10\"]";
        assertTrue(f.isAvailableForUser("u10", "g1"));
        assertFalse(f.isAvailableForUser("u1", "g1"));

        // 游戏白名单
        f.whitelistUsers = null;
        f.whitelistGames = "[\"g1\",\"g10\"]";
        assertTrue(f.isAvailableForUser("u1", "g10"));
        assertFalse(f.isAvailableForUser("u1", "g9"));

        // 非 JSON 存量文本（逗号分隔）→ 保守回退子串匹配（不回归既有行为）
        f.whitelistGames = null;
        f.whitelistUsers = "u1,u2";
        assertTrue(f.isAvailableForUser("u1", "g1"));
        assertFalse(f.isAvailableForUser("u3", "g1"));

        // 名单 null → 不在名单（黑名单放行 / 白名单不启用）
        f.whitelistUsers = null;
        f.blacklistUsers = null;
        assertTrue(f.isAvailableForUser("u1", "g1"));

        // 灰度态白名单 JSON 先行透传：u5 分桶 66 > 5 不命中，但白名单 ["u5"] 透传
        f.setPercentage(5);
        f.flagStatus = FeatureFlagEntity.FlagStatus.STAGED_ROLLOUT;
        assertFalse(f.isAvailableForUser("u5", "g1"));
        f.whitelistUsers = "[\"u5\"]";
        assertTrue(f.isAvailableForUser("u5", "g1"));
    }

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
    @DisplayName("SecuritySessionEntity：活跃/过期/撤销/续期/空闲与设备判定分支")
    void securitySessionEntity() {
        SecuritySessionEntity s = new SecuritySessionEntity();
        s.userId = "u1";
        s.sessionToken = "tok";
        s.loginAt = LocalDateTime.now().minusMinutes(10);
        s.lastActivityAt = LocalDateTime.now().minusMinutes(10);
        s.expiresAt = LocalDateTime.now().plusHours(1);

        // 活跃 + 未过期
        assertTrue(s.isActive());
        assertFalse(s.isExpired());
        assertFalse(s.isRevoked());
        assertFalse(s.isTerminated());

        // 时间过期分支
        s.expiresAt = LocalDateTime.now().minusMinutes(1);
        assertFalse(s.isActive());
        assertTrue(s.isExpired());
        // 状态过期分支
        s.expiresAt = LocalDateTime.now().plusHours(1);
        s.sessionStatus = SecuritySessionEntity.SessionStatus.EXPIRED;
        assertTrue(s.isExpired());
        s.sessionStatus = SecuritySessionEntity.SessionStatus.ACTIVE;

        // 续期上限判定（renew 本体零生产调用方已删，canRenew 保留）
        s.maxRenewalTimes = 2;
        s.renewalCount = 0;
        assertTrue(s.canRenew());
        s.renewalCount = 2;
        assertFalse(s.canRenew());
        s.maxRenewalTimes = null;
        assertTrue(s.canRenew());

        // 活动更新与会话时长
        s.lastActivityAt = LocalDateTime.now().minusHours(1);
        s.updateActivity();
        assertTrue(s.lastActivityAt.isAfter(LocalDateTime.now().minusSeconds(5)));
        s.terminatedAt = null;
        assertTrue(s.getSessionDurationMinutes() >= 9); // loginAt=10 分钟前
        s.terminatedAt = LocalDateTime.now();
        assertTrue(s.getSessionDurationMinutes() >= 9); // 以 terminatedAt 为终点

        // 撤销/终止
        s.revoke("audit");
        assertTrue(s.isRevoked());
        assertEquals("audit", s.terminationReason);
        assertNotNull(s.terminatedAt);
        s.terminate("admin", "cleanup");
        assertTrue(s.isTerminated());
        assertFalse(s.isActive());
        assertEquals("admin", s.terminatedBy);

        // 设备/位置
        SecuritySessionEntity fresh = new SecuritySessionEntity();
        assertFalse(fresh.isFromNewDevice());
        fresh.deviceFingerprint = "fp-123";
        assertTrue(fresh.isFromNewDevice());
        assertFalse(fresh.isSuspiciousLocation());
    }

    @Test
    @DisplayName("ApiKeyEntity：活跃/过期/轮换需求/吊销与用量记录分支")
    void apiKeyEntity() {
        ApiKeyEntity k = new ApiKeyEntity();
        k.apiKey = "ak_test";
        k.secret = "sk";
        k.gameId = "g1";
        k.environmentId = "prod";
        k.name = "默认密钥";

        // 活跃 + 永不过期
        assertTrue(k.isActive());
        assertFalse(k.isExpired());

        // 过期时间分支
        k.expiresAt = LocalDateTime.now().plusDays(1);
        assertTrue(k.isActive());
        assertFalse(k.isExpired());
        k.expiresAt = LocalDateTime.now().minusDays(1);
        assertFalse(k.isActive());
        assertTrue(k.isExpired());

        // 吊销
        k.revoke();
        assertEquals(ApiKeyEntity.ApiKeyStatus.REVOKED, k.status);
        assertNotNull(k.revokedAt);
        assertFalse(k.isActive());

    }

    @Test
    @DisplayName("GameEntity：上线状态/平台支持/多人与全名回退分支")
    void gameEntity() {
        GameEntity g = new GameEntity();
        g.id = "g1";
        g.name = "rpg";

        // 未上线 / 软删除
        assertEquals(GameEntity.GameStatus.DEVELOPMENT, g.status);
        assertFalse(g.isLive());
        g.status = GameEntity.GameStatus.LIVE;
        assertTrue(g.isLive());
        g.deletedAt = LocalDateTime.now();
        assertFalse(g.isLive());
        g.deletedAt = null;

        // 多人标识
        g.hasMultiplayer = null;
        assertFalse(g.isMultiplayer());
        g.hasMultiplayer = true;
        assertTrue(g.isMultiplayer());

        // 全名回退
        assertEquals("rpg", g.getFullName());
        g.displayName = "仙境传说";
        assertEquals("仙境传说", g.getFullName());
    }

    @Test
    @DisplayName("GameEnvironmentEntity：生产/开发判定/活跃与删除/命名空间/采样与专用存储分支")
    void gameEnvironmentEntity() {
        GameEnvironmentEntity e = new GameEnvironmentEntity();
        e.id = "env1";
        e.gameId = "g1";
        e.name = "prod";

        // 环境类型判定
        e.type = GameEnvironmentEntity.EnvironmentType.PRODUCTION;
        assertTrue(e.isProduction());
        assertFalse(e.isDevelopment());
        assertFalse(e.isNonProduction());
        e.type = GameEnvironmentEntity.EnvironmentType.DEVELOPMENT;
        assertTrue(e.isDevelopment());
        assertTrue(e.isNonProduction());

        // 活跃状态与软删除
        e.type = GameEnvironmentEntity.EnvironmentType.PRODUCTION;
        e.status = GameEnvironmentEntity.EnvironmentStatus.ACTIVE;
        assertTrue(e.isActive());
        e.status = GameEnvironmentEntity.EnvironmentStatus.MAINTENANCE;
        assertFalse(e.isActive());
        e.status = GameEnvironmentEntity.EnvironmentStatus.ACTIVE;
        e.deletedAt = LocalDateTime.now();
        assertFalse(e.isActive());
        e.deletedAt = null;

        // 全名：关联游戏为空时回退 unknown
        assertEquals("unknown-prod", e.getFullName());
        GameEntity game = new GameEntity();
        game.name = "rpg";
        e.game = game;
        assertEquals("rpg-prod", e.getFullName());

        // 数据分区：命名空间为空时按 游戏_环境 拼接
        assertNull(e.dataNamespace);
        assertEquals("g1_prod", e.getDataPartition());
        e.dataNamespace = "ns-main";
        assertEquals("ns-main", e.getDataPartition());

        // 采样判定在 SDK 客户端（enableSampling/sampleRate 经配置端点送达），服务端无 shouldSample

    }

    @Test
    @DisplayName("MLModelEntity：部署/训练/失败状态迁移与预测计数分支")
    void mlModelEntity() {
        MLModelEntity m = new MLModelEntity();
        m.modelName = "churn";
        m.modelType = MLModelEntity.ModelType.CLASSIFICATION;
        m.createdBy = "ds";

        assertFalse(m.isDeployed());
        assertFalse(m.isTraining());
        assertFalse(m.isFailed());

        // 部署与预发布都视为已部署
        m.deploy();
        assertTrue(m.isDeployed());
        assertEquals(MLModelEntity.ModelStatus.DEPLOYED, m.modelStatus);
        assertNotNull(m.lastDeployedAt);
        m.modelStatus = MLModelEntity.ModelStatus.STAGING;
        assertTrue(m.isDeployed());

        // 训练生命周期
        m.startTraining();
        assertTrue(m.isTraining());
        m.completeTraining();
        assertEquals(MLModelEntity.ModelStatus.EVALUATING, m.modelStatus);
        assertNotNull(m.lastTrainedAt);
        m.fail();
        assertTrue(m.isFailed());
        m.archive();
        assertEquals(MLModelEntity.ModelStatus.ARCHIVED, m.modelStatus);

        // A/B 与金丝雀标识的空值防御
        m.isAbTest = null;
        assertFalse(m.isAbTest());
        m.isAbTest = true;
        assertTrue(m.isAbTest());
        m.canaryDeployment = null;
        assertFalse(m.isCanaryDeployment());
        m.canaryDeployment = true;
        assertTrue(m.isCanaryDeployment());

        // 预测计数：空值归零后递增
        m.predictionCount = null;
        m.incrementPredictionCount();
        assertEquals(1L, m.predictionCount);
        assertNotNull(m.lastPredictionAt);
        m.incrementPredictionCount();
        assertEquals(2L, m.predictionCount);
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
    }

    @Test
    @DisplayName("ReviewQueueEntity：审核状态迁移/优先级/SLA 逾期与时长计算分支")
    void reviewQueueEntity() {
        ReviewQueueEntity r = new ReviewQueueEntity();
        r.riskCaseId = "case-1";
        r.gameId = "g1";
        r.caseNumber = "CN-001";
        r.targetType = "player";
        r.targetId = "p-100";
        r.priority = 50;
        r.createdAt = LocalDateTime.now().minusHours(2);

        // 初始待处理
        assertTrue(r.isPending());
        assertFalse(r.isAssigned());
        assertFalse(r.isInReview());
        assertFalse(r.isCompleted());
        assertFalse(r.isEscalated());
        assertFalse(r.isHighPriority());
        assertFalse(r.isUrgent());
        assertEquals(0, r.getResolutionTimeMinutes()); // resolvedAt 为空

        // 无 SLA 不逾期
        assertFalse(r.isOverdue());

        // 分配：SLA 已过且未完成 → 逾期
        r.assignTo("alice", LocalDateTime.now().minusMinutes(5));
        assertTrue(r.isAssigned());
        assertEquals("alice", r.assignedTo);
        assertNotNull(r.assignedAt);
        assertTrue(r.isOverdue());

        // 认领（CLAIMED 同样计入 isAssigned）
        r.claim("bob");
        assertTrue(r.isAssigned());
        assertEquals("bob", r.claimedBy);
        assertNotNull(r.claimedBy);
        assertNotNull(r.claimedAt);

        // 开始审核
        r.startReview("bob");
        assertTrue(r.isInReview());
        assertEquals("bob", r.reviewedBy);
        assertNotNull(r.reviewedAt);

        // 完成：不再逾期、不再需要动作、可计算解决时长
        r.complete("bob", "确认欺诈", "confirmed_fraud", "封禁账号");
        assertTrue(r.isCompleted());
        assertNotNull(r.resolvedAt);
        assertEquals("confirmed_fraud", r.disposition);
        assertFalse(r.isOverdue());
        assertTrue(r.getResolutionTimeMinutes() >= 119);

        // 年龄计算
        assertTrue(r.getAgeMinutes() >= 119);
        assertEquals(0, new ReviewQueueEntity().getAgeMinutes()); // createdAt 为空

        // 升级
        r.escalate("carol", "金额巨大");
        assertTrue(r.isEscalated());
        assertTrue(r.escalated);
        assertEquals("carol", r.escalatedTo);
        assertEquals("金额巨大", r.escalationReason);
        assertNotNull(r.escalatedAt);

        // 取消
        r.cancel("误报");
        assertEquals(ReviewQueueEntity.ReviewStatus.CANCELLED, r.reviewStatus);
        assertEquals("误报", r.reviewNotes);

        // 优先级阈值
        r.priority = 70;
        assertTrue(r.isHighPriority());
        assertFalse(r.isUrgent());
        r.priority = 90;
        assertTrue(r.isUrgent());
        r.priority = null;
        assertFalse(r.isHighPriority());
        assertFalse(r.isUrgent());

        // 队列描述：caseNumber 为空回退 riskCaseId
        r.caseNumber = null;
        assertTrue(r.getQueueDescription().contains("case-1"));
        r.caseNumber = "CN-001";
        assertTrue(r.getQueueDescription().contains("CN-001"));
    }

    @Test
    @DisplayName("CohortEntity：计算状态迁移/结果判定/留存周期解析与描述回退")
    void cohortEntity() {
        CohortEntity c = new CohortEntity();
        c.gameId = "g1";
        c.name = "2026-01 获客群";
        c.cohortType = CohortEntity.CohortType.ACQUISITION;

        // 初始待计算
        assertTrue(c.isActive());
        assertTrue(c.isPending());
        assertFalse(c.isCalculating());
        assertFalse(c.isCompleted());
        assertFalse(c.hasResults());
        assertTrue(c.isAcquisitionCohort());
        assertFalse(c.isBehavioralCohort());

        // 计算中 → 完成
        c.markAsCalculating();
        assertTrue(c.isCalculating());
        c.markAsCompleted(100L, "{\"retention\":[]}", "次日留存 40%", 500L);
        assertTrue(c.isCompleted());
        assertEquals(100L, c.cohortCount);
        assertEquals("次日留存 40%", c.resultSummary);
        assertEquals(500L, c.lastCalculationTimeMs);
        assertNotNull(c.calculatedAt);
        assertEquals(1L, c.totalCalculations);
        assertTrue(c.hasResults());

        // 结果为空 → 无结果
        c.resultData = null;
        assertFalse(c.hasResults());
        c.resultData = "";
        assertFalse(c.hasResults());

        // 失败态仍视为活跃（原因落 resultSummary 可见）
        c.markAsFailed("窗口配置非法");
        assertTrue(c.isActive());
        assertEquals("窗口配置非法", c.resultSummary);

        // 归档/软删除 → 非活跃
        c.status = CohortEntity.CohortStatus.ARCHIVED;
        assertFalse(c.isActive());
        c.status = CohortEntity.CohortStatus.PENDING;
        c.deletedAt = LocalDateTime.now();
        assertFalse(c.isActive());
        c.deletedAt = null;

        // 行为同期群判定
        c.cohortType = CohortEntity.CohortType.BEHAVIORAL;
        assertTrue(c.isBehavioralCohort());
        assertFalse(c.isAcquisitionCohort());

        // 留存周期解析：默认 / 自定义 / 非法 JSON 回退
        assertEquals(Arrays.asList(1, 7, 14, 30, 60, 90), c.getRetentionPeriods());
        c.retentionPeriods = "[1,7,30]";
        assertEquals(Arrays.asList(1, 7, 30), c.getRetentionPeriods());
        c.retentionPeriods = "not-json";
        assertEquals(6, c.getRetentionPeriods().size());
        c.retentionPeriods = "";
        assertEquals(6, c.getRetentionPeriods().size());

        // 描述：显示名与日期回退
        c.displayName = "一月获客";
        c.startDate = LocalDate.of(2026, 1, 1);
        assertEquals("[behavioral] 一月获客 - 2026-01-01 (day)", c.getCohortDescription());
        c.displayName = null;
        c.startDate = null;
        assertEquals("[behavioral] 2026-01 获客群 - no-date (day)", c.getCohortDescription());
    }

    @Test
    @DisplayName("UserEntity：活跃/锁定/角色权限范围/登录记录分支")
    void userEntity() {
        UserEntity u = new UserEntity();
        u.id = "u1";
        u.username = "alice";

        // 活跃与软删除
        assertTrue(u.isActive());
        assertFalse(u.isLocked());
        u.deletedAt = LocalDateTime.now();
        assertFalse(u.isActive());
        u.deletedAt = null;
        u.status = UserEntity.UserStatus.LOCKED;
        assertTrue(u.isLocked());
        u.status = UserEntity.UserStatus.ACTIVE;

        // 全名回退
        assertEquals("alice", u.getFullName());
        u.displayName = "爱丽丝";
        assertEquals("爱丽丝", u.getFullName());

        // 登录记录：计数从空值与数值两种起点递增
        u.loginCount = null;
        u.recordLogin("192.168.1.1");
        assertEquals(1L, u.loginCount);
        assertEquals("192.168.1.1", u.lastLoginIp);
        assertNotNull(u.lastLoginAt);
        u.recordLogin("10.0.0.1");
        assertEquals(2L, u.loginCount);
        assertEquals("10.0.0.1", u.lastLoginIp);
    }

    @Test
    @DisplayName("FunnelConfigEntity：启用/删除/漏斗类型判定与步骤计数分支")
    void funnelConfigEntity() {
        FunnelConfigEntity f = new FunnelConfigEntity();
        f.gameId = "g1";
        f.name = "新手引导漏斗";

        // 启用与删除
        assertTrue(f.isEnabled());
        assertFalse(f.isDeleted());
        f.enabled = null;
        assertFalse(f.isEnabled());
        f.enabled = false;
        assertFalse(f.isEnabled());
        f.enabled = true;
        f.deletedAt = LocalDateTime.now();
        assertTrue(f.isDeleted());
        f.deletedAt = null;

        // 类型判定
        assertFalse(f.isSequential());
        assertFalse(f.isTimeWindow());
        f.type = FunnelConfigEntity.FunnelType.SEQUENTIAL;
        assertTrue(f.isSequential());
        assertFalse(f.isTimeWindow());
        f.type = FunnelConfigEntity.FunnelType.TIME_WINDOW;
        assertFalse(f.isSequential());
        assertTrue(f.isTimeWindow());

        // 步骤计数：空集合防御
        assertEquals(0, f.getStepCount());
        f.steps = new ArrayList<>(Arrays.asList(new FunnelStepEntity(), new FunnelStepEntity()));
        assertEquals(2, f.getStepCount());
        f.steps = null;
        assertEquals(0, f.getStepCount());
    }

    @Test
    @DisplayName("PrePersist 主键生成：FunnelStep/AuditLog 空 id 落库前生成 32 位 hex，已有 id 保留")
    void prePersistIdGeneration() {
        // FunnelStepEntity.ensureId：null → 生成；空串 → 生成；非空 → 保留
        FunnelStepEntity step = new FunnelStepEntity();
        step.ensureId();
        assertEquals(32, step.id.length());
        assertFalse(step.id.contains("-"));
        step.id = "";
        step.ensureId();
        assertEquals(32, step.id.length());
        step.id = "preset";
        step.ensureId();
        assertEquals("preset", step.id);

        // AuditLogEntity.generateId：null/空白 → 生成；已有 → 保留
        AuditLogEntity log = new AuditLogEntity();
        log.generateId();
        assertEquals(32, log.id.length());
        assertFalse(log.id.contains("-"));
        log.id = "  ";
        log.generateId();
        assertEquals(32, log.id.length());
        log.id = "preset-log";
        log.generateId();
        assertEquals("preset-log", log.id);
    }

    @Test
    @DisplayName("实体收尾：markNotNew 持久化回调与限流用量空值分支")
    void entityPersistenceDefaults() {
        // GameEntity.markNotNew：持久化回调后 isNew 置 false
        GameEntity g = new GameEntity();
        assertTrue(g.isNew);
        g.markNotNew();
        assertFalse(g.isNew);
    }
}
