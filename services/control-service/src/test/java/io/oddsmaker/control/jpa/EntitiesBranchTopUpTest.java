package io.oddsmaker.control.jpa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BRANCH 收口补测：按 jacoco miss 分支逐行补对侧。
 * 与 EntitiesDeepTest/EntitiesSweep2Test 互补——本类只打已miss的分支侧
 * （三元 null 侧、装箱 Boolean null 侧、枚举 == 对侧、@PrePersist false 侧、
 * 多值 || 的未测值、名单/灰度/条件求值的短路侧）。
 * 字段全 public：null 侧直接赋 null（Hibernate 加载 NULL 列在生产可达）。
 * onCreate 为 protected：同包直调覆盖 false 侧。
 */
@DisplayName("JPA 实体分支对侧补测（BRANCH 收口）")
class EntitiesBranchTopUpTest {

    // ===== FeatureFlagEntity =====

    @Test
    @DisplayName("FeatureFlag：ENABLED 态白名单全短路侧——userId null/名单 null/名单空/命中/未命中")
    void featureFlagEnabledWhitelistSides() {
        FeatureFlagEntity f = new FeatureFlagEntity();
        f.flagStatus = FeatureFlagEntity.FlagStatus.ENABLED;

        // userId null 侧：跳过用户白名单，无游戏白名单 → 放行
        f.whitelistUsers = "[\"u1\"]";
        assertTrue(f.isAvailableForUser(null, null));

        // whitelistUsers null 侧 → 放行
        f.whitelistUsers = null;
        assertTrue(f.isAvailableForUser("u1", null));

        // whitelistUsers 空串侧（isEmpty）→ 放行
        f.whitelistUsers = "";
        assertTrue(f.isAvailableForUser("u1", null));

        // 全真侧：名单命中 → true；未命中 → false
        f.whitelistUsers = "[\"u1\"]";
        assertTrue(f.isAvailableForUser("u1", null));
        f.whitelistUsers = "[\"u2\"]";
        assertFalse(f.isAvailableForUser("u1", null));

        // 游戏白名单同四侧（userId 不在用户白名单、名单为 null 时进入游戏判定）
        f.whitelistUsers = null;
        f.whitelistGames = "[\"g1\"]";
        assertTrue(f.isAvailableForUser(null, "g1"));   // 命中
        assertFalse(f.isAvailableForUser(null, "g2"));  // 未命中
        f.whitelistGames = "[\"g1\"]";
        assertTrue(f.isAvailableForUser(null, null));   // gameId null 侧 → 放行
        f.whitelistGames = null;
        assertTrue(f.isAvailableForUser(null, "g1"));   // 名单 null 侧 → 放行
        f.whitelistGames = "";
        assertTrue(f.isAvailableForUser(null, "g1"));   // 名单空侧 → 放行
    }

    @Test
    @DisplayName("FeatureFlag：CONDITIONAL 态白名单先行四侧——命中放行/未命中回落/名单 null/空/userId null")
    void featureFlagConditionalWhitelistSides() {
        FeatureFlagEntity f = new FeatureFlagEntity();
        f.flagStatus = FeatureFlagEntity.FlagStatus.CONDITIONAL;
        f.defaultValue = false;
        f.conditions = null; // parseConditions null → 回落 defaultValue

        // 命中 → 恒 true（白名单先行）
        f.whitelistUsers = "[\"u1\"]";
        assertTrue(f.isAvailableForUser("u1", null));
        // 未命中 → 回落条件求值 → conditions null → defaultValue false
        f.whitelistUsers = "[\"u2\"]";
        assertFalse(f.isAvailableForUser("u1", null));
        // userId null 侧：白名单短路跳过 → defaultValue
        assertFalse(f.isAvailableForUser(null, null));
        // whitelistUsers null 侧 / 空串侧
        f.whitelistUsers = null;
        assertFalse(f.isAvailableForUser("u1", null));
        f.whitelistUsers = "";
        assertFalse(f.isAvailableForUser("u1", null));
    }

    @Test
    @DisplayName("FeatureFlag：条件求值——op 非 String、attribute 缺失、value 标量/列表的 Number/Boolean 元素")
    void featureFlagConditionEvalSides() {
        FeatureFlagEntity f = new FeatureFlagEntity();
        f.flagStatus = FeatureFlagEntity.FlagStatus.CONDITIONAL;
        f.defaultValue = false;

        // op 为数字 → instanceof String false → op null → 条件 false
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":5,\"value\":[\"u1\"]}]";
        assertFalse(f.isAvailableForUser("u1", null));
        // attribute 缺失 → attribute null → 条件 false
        f.conditions = "[{\"op\":\"eq\",\"value\":[\"u1\"]}]";
        assertFalse(f.isAvailableForUser("u1", null));
        // 未知 op → false
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":\"gt\",\"value\":[\"u1\"]}]";
        assertFalse(f.isAvailableForUser("u1", null));
        // value 空数组 → expected 空 → false
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":\"eq\",\"value\":[]}]";
        assertFalse(f.isAvailableForUser("u1", null));

        // toValueList 列表元素 Number/Boolean 侧（ne 语义：不含即放行）
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":\"ne\",\"value\":[5, true, \"x\"]}]";
        assertTrue(f.isAvailableForUser("u9", null));  // Number+Boolean 元素入列但不命中
        // value 标量 Number / Boolean 侧
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":\"eq\",\"value\":5}]";
        assertTrue(f.isAvailableForUser("5", null));
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":\"eq\",\"value\":true}]";
        assertTrue(f.isAvailableForUser("true", null));
        // op 大小写不敏感（EQ 大写）
        f.conditions = "[{\"attribute\":\"game_id\",\"op\":\"EQ\",\"value\":[\"g1\"]}]";
        assertTrue(f.isAvailableForUser("u1", "g1"));
        // ne 语义：命中列表则不放行
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":\"ne\",\"value\":[\"u1\"]}]";
        assertFalse(f.isAvailableForUser("u1", null));
    }

    @Test
    @DisplayName("FeatureFlag：advanceRollout 步骤合法性——null/空白/越界元素/null 元素/越步/负步")
    void featureFlagRolloutStepSides() {
        FeatureFlagEntity f = new FeatureFlagEntity();
        f.flagStatus = FeatureFlagEntity.FlagStatus.STAGED_ROLLOUT;

        // rolloutSteps null → 外层守卫直接跳过（连 currentStep 都不递增）
        f.rolloutSteps = null;
        f.currentStep = 0;
        f.advanceRollout();
        assertEquals(0, f.currentStep);
        assertEquals(0, f.percentageValue);

        // rolloutSteps 空白 → 同上（isBlank 侧）
        f.rolloutSteps = "   ";
        f.currentStep = 0;
        f.advanceRollout();
        assertEquals(1, f.currentStep);

        // 步骤含 null 元素 → 非法不回写
        f.rolloutSteps = "[null]";
        f.currentStep = 0;
        f.advanceRollout();
        assertEquals(1, f.currentStep);
        assertEquals(0, f.percentageValue);

        // 步骤负值 / 超 100 → 非法不回写
        f.rolloutSteps = "[-1]";
        f.currentStep = 0;
        f.advanceRollout();
        assertEquals(1, f.currentStep);
        f.rolloutSteps = "[101]";
        f.currentStep = 0;
        f.advanceRollout();
        assertEquals(1, f.currentStep);

        // 合法范围内 → 回写百分比
        f.rolloutSteps = "[50,100]";
        f.currentStep = 0;
        f.advanceRollout();
        assertEquals(1, f.currentStep);
        assertEquals(50, f.percentageValue);

        // 超出步骤数上限 → 仅递增不回写（currentStep <= size false 侧）
        f.currentStep = 2;
        f.advanceRollout();
        assertEquals(3, f.currentStep);
        assertEquals(50, f.percentageValue);

        // currentStep 负值递增后仍 < 1 → 不回写（currentStep >= 1 false 侧）
        f.currentStep = -2;
        f.advanceRollout();
        assertEquals(-1, f.currentStep);
        assertEquals(50, f.percentageValue);
    }

    @Test
    @DisplayName("FeatureFlag：onCreate false 侧——createdAt 已设不覆盖")
    void featureFlagOnCreatePresets() {
        FeatureFlagEntity f = new FeatureFlagEntity();
        LocalDateTime preset = LocalDateTime.of(2025, 1, 1, 0, 0);
        f.createdAt = preset;
        f.onCreate();
        assertEquals(preset, f.createdAt);
    }

    @Test
    @DisplayName("FeatureFlag：parseRolloutSteps null 侧（调用方已守卫，反射直调）与不可识别 value 元素侧")
    void featureFlagParseAndValueSides() {
        FeatureFlagEntity f = new FeatureFlagEntity();
        // advanceRollout 的 rolloutSteps != null 守卫使 parseRolloutSteps 的 null 侧
        // 在当前调用图不可达，反射直调覆盖该防御侧
        f.rolloutSteps = null;
        assertNull(org.springframework.test.util.ReflectionTestUtils.invokeMethod(f, "parseRolloutSteps"));

        // toValueList 列表元素为嵌套结构（非 String/Number/Boolean）→ 忽略
        f.flagStatus = FeatureFlagEntity.FlagStatus.CONDITIONAL;
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":\"ne\",\"value\":[[1], 5]}]";
        assertTrue(f.isAvailableForUser("u9", null));   // 嵌套元素忽略、5 入列、u9 不在列 → ne 放行
        // value 本身为对象（非 List/String/Number/Boolean）→ 空列表 → 条件 false
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":\"eq\",\"value\":{\"k\":1}}]";
        assertFalse(f.isAvailableForUser("u9", null));
    }

    // ===== WebhookConfigEntity =====

    @Test
    @DisplayName("WebhookConfig：计数三元 null 侧与事件/风险等级过滤侧")
    void webhookConfigCounterAndFilterSides() {
        WebhookConfigEntity w = new WebhookConfigEntity();
        w.status = WebhookConfigEntity.WebhookStatus.ACTIVE;

        // getSuccessRate：totalSent null → 0；totalSuccess null → 0
        w.totalSent = null;
        w.totalSuccess = null;
        assertEquals(0.0, w.getSuccessRate());
        w.totalSent = 10L;
        assertEquals(0.0, w.getSuccessRate());   // totalSuccess null 侧
        w.totalSuccess = 4L;
        assertEquals(0.4, w.getSuccessRate());

        // recordSuccess：null 起点递增
        WebhookConfigEntity s = new WebhookConfigEntity();
        s.totalSent = null;
        s.totalSuccess = null;
        s.recordSuccess();
        assertEquals(1L, s.totalSent);
        assertEquals(1L, s.totalSuccess);

        // recordFailure：null 起点递增 + totalSuccess null 时连续失败判 FAILED
        WebhookConfigEntity f = new WebhookConfigEntity();
        f.totalSent = null;
        f.totalFailed = null;
        f.totalSuccess = null;
        f.recordFailure("err");   // totalFailed=1, consecutive=1-0
        assertEquals(1L, f.totalSent);
        assertEquals(1L, f.totalFailed);
        f.totalFailed = 5L;       // consecutive = 5 - 0(totalSuccess null) → FAILED
        f.recordFailure("err");
        assertEquals(WebhookConfigEntity.WebhookStatus.FAILED, f.status);

        // shouldSendForEvent：eventTypes null/空直通、非空匹配/不匹配
        WebhookConfigEntity e = new WebhookConfigEntity();
        e.status = WebhookConfigEntity.WebhookStatus.ACTIVE;
        e.eventTypes = null;
        e.riskLevels = null;
        assertTrue(e.shouldSendForEvent("any", "any"));   // null 名单直通
        e.eventTypes = "";
        assertTrue(e.shouldSendForEvent("any", "any"));   // 空名单直通
        e.eventTypes = "risk_high, risk_low";
        assertTrue(e.shouldSendForEvent("RISK_HIGH", null));  // trim+ignoreCase 命中
        assertFalse(e.shouldSendForEvent("other", null));     // 不匹配拒绝
        e.riskLevels = "";
        assertTrue(e.shouldSendForEvent("RISK_HIGH", "any")); // riskLevels 空直通
        e.riskLevels = "high";
        assertTrue(e.shouldSendForEvent("RISK_HIGH", "HIGH"));
        assertFalse(e.shouldSendForEvent("RISK_HIGH", "low"));
    }

    // ===== PipelineJobEntity =====

    @Test
    @DisplayName("PipelineJob：速率三元 null 侧、无 start 的 complete/fail、onCreate false 侧")
    void pipelineJobRateAndLifecycleSides() {
        PipelineJobEntity j = new PipelineJobEntity();
        // getProcessingRate：durationMs null / =0 / processedRows null
        j.durationMs = null;
        assertEquals(0.0, j.getProcessingRate());
        j.durationMs = 0L;
        assertEquals(0.0, j.getProcessingRate());
        j.durationMs = 100L;
        j.processedRows = null;
        assertEquals(0.0, j.getProcessingRate());
        j.processedRows = 50L;
        assertEquals(500.0, j.getProcessingRate());

        // getErrorRate：processedRows/errorRows null 侧
        j.processedRows = null;
        j.errorRows = null;
        assertEquals(0.0, j.getErrorRate());
        j.processedRows = 10L;
        assertEquals(0.0, j.getErrorRate());   // errorRows null 侧
        j.errorRows = 2L;
        assertEquals(20.0, j.getErrorRate());

        // complete/fail 无 startedAt → 不计算时长
        PipelineJobEntity c = new PipelineJobEntity();
        c.complete(10L, 0L);
        assertNull(c.durationMs);
        PipelineJobEntity fl = new PipelineJobEntity();
        fl.fail("err");
        assertNull(fl.durationMs);
        // start 后 complete → 时长写入（startedAt != null true 侧）
        c.start();
        c.complete(10L, 0L);
        assertNotNull(c.durationMs);

        // onCreate false 侧
        PipelineJobEntity p = new PipelineJobEntity();
        LocalDateTime preset = LocalDateTime.of(2025, 1, 1, 0, 0);
        p.createdAt = preset;
        p.jobStatus = PipelineJobEntity.JobStatus.RUNNING;
        p.onCreate();
        assertEquals(preset, p.createdAt);
        assertEquals(PipelineJobEntity.JobStatus.RUNNING, p.jobStatus);
    }

    // ===== SDKKeyEntity =====

    @Test
    @DisplayName("SDKKey：过期判定 expiresAt null/过去/将来、投递模式、onCreate false 侧")
    void sdkKeyExpiryAndModeSides() {
        SDKKeyEntity k = new SDKKeyEntity();
        k.keyStatus = SDKKeyEntity.KeyStatus.ACTIVE;
        // isExpired：expiresAt null → false（&& 左真右假侧）
        k.expiresAt = null;
        assertFalse(k.isExpired());
        // 过去时间 → true；将来 → false
        k.expiresAt = LocalDateTime.now().minusDays(1);
        assertTrue(k.isExpired());
        k.expiresAt = LocalDateTime.now().plusDays(1);
        assertFalse(k.isExpired());

        // 投递模式三态
        k.deliveryMode = null;
        assertFalse(k.isRealtime());
        k.deliveryMode = SDKKeyEntity.DeliveryMode.REALTIME;
        assertTrue(k.isRealtime());
        k.deliveryMode = SDKKeyEntity.DeliveryMode.BATCH;
        assertFalse(k.isRealtime());
        assertTrue(k.isBatch());
        k.deliveryMode = SDKKeyEntity.DeliveryMode.HYBRID;
        assertTrue(k.isHybrid());

        // onCreate false 侧：三字段全预设
        SDKKeyEntity p = new SDKKeyEntity();
        LocalDateTime preset = LocalDateTime.of(2025, 1, 1, 0, 0);
        p.createdAt = preset;
        p.keyStatus = SDKKeyEntity.KeyStatus.SUSPENDED;
        p.deliveryMode = SDKKeyEntity.DeliveryMode.BATCH;
        p.onCreate();
        assertEquals(preset, p.createdAt);
        assertEquals(SDKKeyEntity.KeyStatus.SUSPENDED, p.keyStatus);
        assertEquals(SDKKeyEntity.DeliveryMode.BATCH, p.deliveryMode);
    }

    // ===== MLModelPredictionEntity =====

    @Test
    @DisplayName("MLModelPrediction：CACHED 完成态、装箱 Boolean null 侧、延迟回退侧")
    void mlPredictionSides() {
        MLModelPredictionEntity p = new MLModelPredictionEntity();
        p.predictionStatus = MLModelPredictionEntity.PredictionStatus.CACHED;
        assertTrue(p.isCompleted());   // CACHED 侧
        p.predictionStatus = MLModelPredictionEntity.PredictionStatus.COMPLETED;
        assertTrue(p.isCompleted());
        p.predictionStatus = MLModelPredictionEntity.PredictionStatus.FAILED;
        assertFalse(p.isCompleted());

        // 装箱 Boolean null/false/true 三态
        p.isAbTest = null;
        assertFalse(p.isAbTest());
        p.isAbTest = false;
        assertFalse(p.isAbTest());
        p.isAbTest = true;
        assertTrue(p.isAbTest());
        p.isCanary = null;
        assertFalse(p.isCanary());
        p.isCanary = true;
        assertTrue(p.isCanary());
        p.cacheHit = null;
        assertFalse(p.isCacheHit());
        p.cacheHit = true;
        assertTrue(p.isCacheHit());

        // getLatencyMs：latencyMs null 时 createdAt/completedAt 任一 null → 0
        p.latencyMs = null;
        p.createdAt = null;
        p.completedAt = LocalDateTime.now();
        assertEquals(0, p.getLatencyMs());          // createdAt null 侧
        p.createdAt = LocalDateTime.now().minusMinutes(2);
        p.completedAt = null;
        assertEquals(0, p.getLatencyMs());          // completedAt null 侧
        p.completedAt = LocalDateTime.now();
        assertTrue(p.getLatencyMs() >= 0);          // 双非 null 回退计算
    }

    // ===== DataQualityRuleEntity =====

    @Test
    @DisplayName("DataQualityRule：INFO 严重级、违规率 null 侧、onCreate false 侧")
    void dataQualityRuleSides() {
        DataQualityRuleEntity r = new DataQualityRuleEntity();
        r.severity = DataQualityRuleEntity.Severity.INFO;
        assertTrue(r.isWarning());   // INFO 侧
        r.severity = DataQualityRuleEntity.Severity.WARNING;
        assertTrue(r.isWarning());
        r.severity = DataQualityRuleEntity.Severity.CRITICAL;
        assertFalse(r.isWarning());

        // getViolationRate：totalViolations null 侧
        r.totalEvaluations = 10;
        r.totalViolations = null;
        assertEquals(0.0, r.getViolationRate());
        r.totalViolations = 5;
        assertEquals(50.0, r.getViolationRate());

        // onCreate false 侧
        DataQualityRuleEntity p = new DataQualityRuleEntity();
        LocalDateTime preset = LocalDateTime.of(2025, 1, 1, 0, 0);
        p.createdAt = preset;
        p.ruleStatus = DataQualityRuleEntity.RuleStatus.INACTIVE;
        p.onCreate();
        assertEquals(preset, p.createdAt);
        assertEquals(DataQualityRuleEntity.RuleStatus.INACTIVE, p.ruleStatus);
    }

    // ===== RiskCaseEntity =====

    @Test
    @DisplayName("RiskCase：isPending 对侧、isBlocked 合取侧、isResolved 或侧、解决时长 null 侧")
    void riskCaseSides() {
        RiskCaseEntity c = new RiskCaseEntity();
        c.executionStatus = RiskCaseEntity.ExecutionStatus.PENDING;
        assertTrue(c.isPending());
        c.executionStatus = RiskCaseEntity.ExecutionStatus.EXECUTED;
        assertFalse(c.isPending());

        // isBlocked：BLOCK+EXECUTED 全真；BLOCK+PENDING 半真；REVIEW+EXECUTED 半真
        c.actionTaken = RiskCaseEntity.ActionType.BLOCK;
        c.executionStatus = RiskCaseEntity.ExecutionStatus.EXECUTED;
        assertTrue(c.isBlocked());
        c.executionStatus = RiskCaseEntity.ExecutionStatus.PENDING;
        assertFalse(c.isBlocked());
        c.actionTaken = RiskCaseEntity.ActionType.REVIEW;
        c.executionStatus = RiskCaseEntity.ExecutionStatus.EXECUTED;
        assertFalse(c.isBlocked());

        // isResolved：resolvedAt 非空（第一侧）与 confirmed_benign（第二侧）
        c.resolvedAt = LocalDateTime.now();
        assertTrue(c.isResolved());
        c.resolvedAt = null;
        c.disposition = "confirmed_benign";
        assertTrue(c.isResolved());
        c.disposition = "confirmed_fraud";
        assertFalse(c.isResolved());

        // getResolutionTimeMinutes：resolvedAt/createdAt null 侧
        assertEquals(0, c.getResolutionTimeMinutes());   // resolvedAt null
        LocalDateTime base = LocalDateTime.of(2025, 1, 1, 0, 0);
        c.resolvedAt = base.plusMinutes(5);
        c.createdAt = null;
        assertEquals(0, c.getResolutionTimeMinutes());   // createdAt null
        c.createdAt = base;
        assertEquals(5, c.getResolutionTimeMinutes());
    }

    // ===== SecuritySessionEntity =====

    @Test
    @DisplayName("SecuritySession：onCreate 四字段全预设不覆盖")
    void securitySessionOnCreatePresets() {
        SecuritySessionEntity s = new SecuritySessionEntity();
        LocalDateTime preset = LocalDateTime.of(2025, 1, 1, 0, 0);
        s.createdAt = preset;
        s.loginAt = preset;
        s.lastActivityAt = preset;
        s.sessionStatus = SecuritySessionEntity.SessionStatus.EXPIRED;
        s.onCreate();
        assertEquals(preset, s.createdAt);
        assertEquals(preset, s.loginAt);
        assertEquals(preset, s.lastActivityAt);
        assertEquals(SecuritySessionEntity.SessionStatus.EXPIRED, s.sessionStatus);
    }

    // ===== QuotaEntity =====

    @Test
    @DisplayName("Quota：预警/告警通知的 warningSent/alertSent null 与已发侧、onCreate false 侧")
    void quotaNotifySides() {
        QuotaEntity q = new QuotaEntity();
        q.quotaLimit = 100L;
        q.currentUsage = 90L;   // 90% >= 80 warning；90 < 95 alert
        q.usagePercent = 90.0;

        // warningSent null 侧（初始化器 false，null 需显式赋值）
        q.warningSent = null;
        assertTrue(q.shouldSendWarning());
        q.warningSent = true;
        assertFalse(q.shouldSendWarning());   // 已发不再发
        q.warningSent = false;
        assertTrue(q.shouldSendWarning());

        // alertSent：用量推到 96% >= 95
        q.currentUsage = 96L;
        q.usagePercent = 96.0;
        q.alertSent = null;
        assertTrue(q.shouldSendAlert());
        q.alertSent = true;
        assertFalse(q.shouldSendAlert());
        q.alertSent = false;
        assertTrue(q.shouldSendAlert());

        // onCreate false 侧
        QuotaEntity p = new QuotaEntity();
        LocalDateTime preset = LocalDateTime.of(2025, 1, 1, 0, 0);
        p.createdAt = preset;
        p.onCreate();
        assertEquals(preset, p.createdAt);
    }

    // ===== RoleEntity =====

    @Test
    @DisplayName("Role：isEnabled/isSystem 三态、hasPermission null 名单与命中/未命中")
    void roleSides() {
        RoleEntity r = new RoleEntity();
        r.enabled = null;
        assertFalse(r.isEnabled());
        r.enabled = false;
        assertFalse(r.isEnabled());
        r.enabled = true;
        assertTrue(r.isEnabled());
        r.system = null;
        assertFalse(r.isSystem());
        r.system = true;
        assertTrue(r.isSystem());

        // hasPermission(resourceType, action)：名单 null 侧
        r.permissions = null;
        assertFalse(r.hasPermission("game", PermissionEntity.PermissionAction.READ));
        // 命中/未命中（action 枚举取真实值）
        PermissionEntity.PermissionAction act = PermissionEntity.PermissionAction.values()[0];
        PermissionEntity pe = new PermissionEntity();
        pe.resourceType = "game";
        pe.action = act;
        pe.enabled = true;
        r.permissions = Set.of(pe);
        assertTrue(r.hasPermission("game", act));
        assertFalse(r.hasPermission("user", act));
        PermissionEntity.PermissionAction other = PermissionEntity.PermissionAction.values()[0] == act
            ? PermissionEntity.PermissionAction.values()[1] : PermissionEntity.PermissionAction.values()[0];
        assertFalse(r.hasPermission("game", other));
    }

    // ===== WebhookLogEntity =====

    @Test
    @DisplayName("WebhookLog：TIMEOUT/RETRYING 等 || 未测值")
    void webhookLogStatusSides() {
        WebhookLogEntity l = new WebhookLogEntity();
        l.deliveryStatus = WebhookLogEntity.DeliveryStatus.TIMEOUT;
        assertTrue(l.isFailed());
        assertTrue(l.shouldRetry());
        l.deliveryStatus = WebhookLogEntity.DeliveryStatus.FAILED;
        assertTrue(l.isFailed());
        assertTrue(l.shouldRetry());
        l.deliveryStatus = WebhookLogEntity.DeliveryStatus.RETRYING;
        assertTrue(l.isPending());
        l.deliveryStatus = WebhookLogEntity.DeliveryStatus.PENDING;
        assertTrue(l.isPending());
        l.deliveryStatus = WebhookLogEntity.DeliveryStatus.SENDING;
        assertFalse(l.isPending());
        l.deliveryStatus = WebhookLogEntity.DeliveryStatus.SUCCESS;
        assertFalse(l.isFailed());
        assertFalse(l.isPending());
        l.deliveryStatus = WebhookLogEntity.DeliveryStatus.CANCELLED;
        assertFalse(l.isFailed());
    }

    // ===== SystemConfigEntity =====

    @Test
    @DisplayName("SystemConfig：软删后不活跃、getIntegerValue 两条件对侧")
    void systemConfigSides() {
        SystemConfigEntity c = new SystemConfigEntity();
        assertTrue(c.isActive());
        c.deletedAt = LocalDateTime.now();
        assertFalse(c.isActive());

        // isInteger true + configValue null → null
        c.valueType = "integer";
        c.configValue = null;
        assertNull(c.getIntegerValue());
        // isInteger false + configValue 非空 → null
        c.valueType = "string";
        c.configValue = "42";
        assertNull(c.getIntegerValue());
        // 双真 → 解析
        c.valueType = "integer";
        c.configValue = "42";
        assertEquals(42, c.getIntegerValue());
    }

    // ===== MaintenanceWindowEntity =====

    @Test
    @DisplayName("MaintenanceWindow：未超期侧（isAfter false）、affectsGame 全侧")
    void maintenanceWindowSides() {
        MaintenanceWindowEntity m = new MaintenanceWindowEntity();
        m.maintenanceStatus = MaintenanceWindowEntity.MaintenanceStatus.IN_PROGRESS;
        m.scheduledEnd = LocalDateTime.now().plusHours(1);
        assertFalse(m.isOverdue());   // IN_PROGRESS+终点将来 → false
        m.scheduledEnd = LocalDateTime.now().minusMinutes(1);
        assertTrue(m.isOverdue());

        m.impactScope = MaintenanceWindowEntity.ImpactScope.GLOBAL;
        assertTrue(m.affectsGame("any"));           // GLOBAL 直通
        m.impactScope = MaintenanceWindowEntity.ImpactScope.ENVIRONMENT;
        assertFalse(m.affectsGame("g1"));           // 非 GLOBAL 非 GAME 范围 → 不影响
        m.impactScope = MaintenanceWindowEntity.ImpactScope.GAME;
        m.gameId = null;
        assertFalse(m.affectsGame("g1"));           // GAME + gameId null
        m.gameId = "g1";
        assertTrue(m.affectsGame("g1"));            // 命中
        assertFalse(m.affectsGame("g2"));           // 未命中
    }

    // ===== ReviewQueueEntity =====

    @Test
    @DisplayName("ReviewQueue：已完成的超期不再算超期、解决时长 null 侧")
    void reviewQueueSides() {
        ReviewQueueEntity r = new ReviewQueueEntity();
        r.slaDueAt = LocalDateTime.now().minusHours(1);
        r.reviewStatus = ReviewQueueEntity.ReviewStatus.COMPLETED;
        assertFalse(r.isOverdue());   // 超时但已完成 → false
        r.reviewStatus = ReviewQueueEntity.ReviewStatus.IN_REVIEW;
        assertTrue(r.isOverdue());
        r.slaDueAt = LocalDateTime.now().plusHours(1);
        assertFalse(r.isOverdue());

        // getResolutionTimeMinutes：resolvedAt null / createdAt null
        r.slaDueAt = null;
        assertEquals(0, r.getResolutionTimeMinutes());   // resolvedAt null
        LocalDateTime base = LocalDateTime.of(2025, 1, 1, 0, 0);
        r.resolvedAt = base.plusMinutes(3);
        r.createdAt = null;
        assertEquals(0, r.getResolutionTimeMinutes());   // createdAt null
        r.createdAt = base;
        assertEquals(3, r.getResolutionTimeMinutes());
    }

    // ===== SDKVersionEntity =====

    @Test
    @DisplayName("SDKVersion：isDraft/isPatch 对侧")
    void sdkVersionSides() {
        SDKVersionEntity v = new SDKVersionEntity();
        v.versionStatus = SDKVersionEntity.VersionStatus.DRAFT;
        assertTrue(v.isDraft());
        v.versionStatus = SDKVersionEntity.VersionStatus.RELEASED;
        assertFalse(v.isDraft());
        v.changeType = SDKVersionEntity.ChangeType.PATCH;
        assertTrue(v.isPatch());
        v.changeType = SDKVersionEntity.ChangeType.MAJOR;
        assertFalse(v.isPatch());
    }

    // ===== RateLimitEntity =====

    @Test
    @DisplayName("RateLimit：isEnabled 三条件全侧、isGlobal 对侧")
    void rateLimitSides() {
        RateLimitEntity r = new RateLimitEntity();
        r.enabled = null;
        assertFalse(r.isEnabled());
        r.enabled = false;
        assertFalse(r.isEnabled());
        r.enabled = true;
        assertTrue(r.isEnabled());
        r.deletedAt = LocalDateTime.now();
        assertFalse(r.isEnabled());   // 软删侧
        r.deletedAt = null;

        r.scope = RateLimitEntity.Scope.GLOBAL;
        assertTrue(r.isGlobal());
        r.scope = RateLimitEntity.Scope.GAME;
        assertFalse(r.isGlobal());
    }

    // ===== MLModelEntity / TelemetryConfigEntity / HealthCheckEntity =====

    @Test
    @DisplayName("MLModel/TelemetryConfig/HealthCheck：onCreate false 侧")
    void onCreatePresets() {
        LocalDateTime preset = LocalDateTime.of(2025, 1, 1, 0, 0);
        MLModelEntity m = new MLModelEntity();
        m.createdAt = preset;
        m.modelStatus = MLModelEntity.ModelStatus.DEPLOYED;
        m.onCreate();
        assertEquals(preset, m.createdAt);
        assertEquals(MLModelEntity.ModelStatus.DEPLOYED, m.modelStatus);

        TelemetryConfigEntity t = new TelemetryConfigEntity();
        t.createdAt = preset;
        t.configStatus = TelemetryConfigEntity.ConfigStatus.ACTIVE;
        t.onCreate();
        assertEquals(preset, t.createdAt);
        assertEquals(TelemetryConfigEntity.ConfigStatus.ACTIVE, t.configStatus);

        HealthCheckEntity h = new HealthCheckEntity();
        h.createdAt = preset;
        h.onCreate();
        assertEquals(preset, h.createdAt);
    }

    // ===== ExportJobEntity =====

    @Test
    @DisplayName("ExportJob：无 start 的完成不计算时长、状态描述 filename null 侧")
    void exportJobSides() {
        ExportJobEntity e = new ExportJobEntity();
        e.exportType = "csv";
        e.exportStatus = ExportJobEntity.ExportStatus.COMPLETED;
        e.markAsCompleted("/tmp/x.csv", 100L, 10L);   // startedAt null → 不算时长
        assertNull(e.executionTimeMs);
        e.fileName = null;
        assertTrue(e.getStatusDescription().contains("no-filename"));
        e.fileName = "x.csv";
        assertTrue(e.getStatusDescription().contains("x.csv"));
    }

    // ===== AuditLogEntity =====

    @Test
    @DisplayName("AuditLog：认证动作五枚举全真侧、数据动作全枚举与非数据侧")
    void auditLogActionSides() {
        AuditLogEntity a = new AuditLogEntity();
        for (AuditLogEntity.AuditAction act : new AuditLogEntity.AuditAction[]{
                AuditLogEntity.AuditAction.LOGIN, AuditLogEntity.AuditAction.LOGOUT,
                AuditLogEntity.AuditAction.LOGIN_FAILED, AuditLogEntity.AuditAction.PASSWORD_CHANGE,
                AuditLogEntity.AuditAction.PASSWORD_RESET}) {
            a.action = act;
            assertTrue(a.isAuthAction(), act + " 应为认证动作");
            assertFalse(a.isDataAction(), act + " 不应为数据动作");
        }
        for (AuditLogEntity.AuditAction act : new AuditLogEntity.AuditAction[]{
                AuditLogEntity.AuditAction.CREATE, AuditLogEntity.AuditAction.UPDATE,
                AuditLogEntity.AuditAction.DELETE, AuditLogEntity.AuditAction.EXPORT,
                AuditLogEntity.AuditAction.IMPORT}) {
            a.action = act;
            assertTrue(a.isDataAction(), act + " 应为数据动作");
            assertFalse(a.isAuthAction(), act + " 不应为认证动作");
        }
    }

    // ===== TrackingPlanEntity =====

    @Test
    @DisplayName("TrackingPlan：isDraft/canEdit 的非草稿与软删侧")
    void trackingPlanSides() {
        TrackingPlanEntity t = new TrackingPlanEntity();
        t.status = TrackingPlanEntity.PlanStatus.DRAFT;
        assertTrue(t.isDraft());
        assertTrue(t.canEdit());
        t.deletedAt = LocalDateTime.now();
        assertFalse(t.isDraft());    // 草稿但软删
        assertFalse(t.canEdit());
        t.deletedAt = null;
        t.status = TrackingPlanEntity.PlanStatus.ACTIVE;
        assertFalse(t.isDraft());
        assertFalse(t.canEdit());
    }

    // ===== StorageProfileDTO（实体包内不可达，放 dto 测试；此处仅 DTO 关联实体行为验证） =====

    // ===== HealthCheckEntity/IntegrationLogEntity/FlinkJobEntity/SSOConfigEntity =====

    @Test
    @DisplayName("IntegrationLog：TIMEOUT 失败侧；SSOConfig：DISABLED 对侧")
    void integrationAndSsoSides() {
        IntegrationLogEntity i = new IntegrationLogEntity();
        i.callStatus = IntegrationLogEntity.CallStatus.TIMEOUT;
        assertTrue(i.isFailed());
        i.callStatus = IntegrationLogEntity.CallStatus.FAILED;
        assertTrue(i.isFailed());
        i.callStatus = IntegrationLogEntity.CallStatus.SUCCESS;
        assertFalse(i.isFailed());
        i.callStatus = IntegrationLogEntity.CallStatus.RETRYING;
        assertFalse(i.isFailed());

        SSOConfigEntity s = new SSOConfigEntity();
        s.ssoStatus = SSOConfigEntity.SSOStatus.DISABLED;
        assertTrue(s.isDisabled());
        s.ssoStatus = SSOConfigEntity.SSOStatus.ACTIVE;
        assertFalse(s.isDisabled());
        assertTrue(s.isActive());
    }

    @Test
    @DisplayName("FlinkJob：风险案例率——总量 null/0 与分子 null")
    void flinkJobRateSides() {
        FlinkJobEntity f = new FlinkJobEntity();
        f.totalEventsProcessed = null;
        assertEquals(0.0, f.getRiskCaseRate());   // null 侧
        f.totalEventsProcessed = 0L;
        assertEquals(0.0, f.getRiskCaseRate());   // 0 侧
        f.totalEventsProcessed = 100L;
        f.totalRiskCasesCreated = null;
        assertEquals(0.0, f.getRiskCaseRate());   // 分子 null
        f.totalRiskCasesCreated = 3L;
        assertEquals(0.03, f.getRiskCaseRate());
    }

    // ===== MailEntity =====

    @Test
    @DisplayName("Mail：不可领取态（未发送）visibleTo 直接 false")
    void mailNotClaimableSide() {
        MailEntity m = new MailEntity();
        m.status = MailEntity.Status.DRAFT;
        assertFalse(m.visibleTo("p1", LocalDateTime.now()));   // !claimable true 侧
        m.status = MailEntity.Status.SENT;
        m.expireAt = LocalDateTime.now().minusMinutes(1);      // 已过期同样不可领取
        assertFalse(m.visibleTo("p1", LocalDateTime.now()));
    }

    // ===== BlockListEntity =====

    @Test
    @DisplayName("BlockList：硬封禁对侧")
    void blockListSides() {
        BlockListEntity b = new BlockListEntity();
        b.blockType = BlockListEntity.BlockType.HARD;
        assertTrue(b.isHardBlock());
        b.blockType = BlockListEntity.BlockType.SOFT;
        assertFalse(b.isHardBlock());
        b.blockType = null;
        assertFalse(b.isHardBlock());
    }

    // ===== CohortEntity =====

    @Test
    @DisplayName("Cohort：totalCalculations null 起点递增")
    void cohortNullCounterSide() {
        CohortEntity c = new CohortEntity();
        c.totalCalculations = null;
        c.markAsCompleted(10L, "{}", "sum", 5L);
        assertEquals(1L, c.totalCalculations);   // null → 1（三元 null 侧）
        c.markAsCompleted(11L, "{}", "sum", 5L);
        assertEquals(2L, c.totalCalculations);
    }

    // ===== ReportExecutionEntity / AnnouncementEntity =====

    @Test
    @DisplayName("ReportExecution：PENDING 对侧；Announcement：软删与非发布侧")
    void reportAndAnnouncementSides() {
        ReportExecutionEntity r = new ReportExecutionEntity();
        r.executionStatus = ReportExecutionEntity.ExecutionStatus.PENDING;
        assertTrue(r.isPending());
        r.executionStatus = ReportExecutionEntity.ExecutionStatus.RUNNING;
        assertFalse(r.isPending());

        AnnouncementEntity a = new AnnouncementEntity();
        a.status = AnnouncementEntity.Status.PUBLISHED;
        assertTrue(a.isActive());
        a.deletedAt = LocalDateTime.now();
        assertFalse(a.isActive());   // 已发布但软删
        a.deletedAt = null;
        a.status = AnnouncementEntity.Status.DRAFT;
        assertFalse(a.isActive());
    }

    // ===== ModelTrainingEntity =====

    @Test
    @DisplayName("ModelTraining：进度——总轮数 null/0、当前轮 null")
    void modelTrainingProgressSides() {
        ModelTrainingEntity t = new ModelTrainingEntity();
        t.trainingEpochs = null;
        assertEquals(0.0, t.getProgress());
        t.trainingEpochs = 0;
        assertEquals(0.0, t.getProgress());
        t.trainingEpochs = 10;
        t.currentEpoch = null;
        assertEquals(0.0, t.getProgress());
        t.currentEpoch = 5;
        assertEquals(50.0, t.getProgress());
    }

    // ===== HealthMetricEntity =====

    @Test
    @DisplayName("HealthMetric：isNormal 三态——正常/预警/严重")
    void healthMetricNormalSides() {
        HealthMetricEntity h = new HealthMetricEntity();
        h.warningThreshold = 50.0;
        h.criticalThreshold = 80.0;
        h.metricValue = 10.0;
        assertTrue(h.isNormal());    // 双否 → true
        h.metricValue = 60.0;
        assertFalse(h.isNormal());   // !isWarning false 侧
        h.metricValue = 90.0;
        assertFalse(h.isNormal());   // !isCritical false 侧
        // 交叉阈值：严重线低于警告线，值落在中间——isWarning false 且 isCritical true
        HealthMetricEntity cross = new HealthMetricEntity();
        cross.warningThreshold = 50.0;
        cross.criticalThreshold = 30.0;
        cross.metricValue = 40.0;
        assertFalse(cross.isNormal());   // 第二操作数 !isCritical false 侧
        assertTrue(cross.isCritical());
    }

    // ===== PermissionEntity / PipelineEntity / RiskRuleEntity / SystemAlertEntity =====

    @Test
    @DisplayName("Permission：isSystem 三态；Pipeline：runCount null/0；SystemAlert：OPEN 对侧")
    void miscEntitySides() {
        PermissionEntity p = new PermissionEntity();
        p.system = null;
        assertFalse(p.isSystem());
        p.system = true;
        assertTrue(p.isSystem());
        p.system = false;
        assertFalse(p.isSystem());

        PipelineEntity pl = new PipelineEntity();
        pl.runCount = null;
        assertEquals(0.0, pl.getSuccessRate());   // null 侧
        pl.runCount = 0;
        assertEquals(0.0, pl.getSuccessRate());
        pl.runCount = 4;
        pl.successCount = 3;
        assertEquals(75.0, pl.getSuccessRate());

        SystemAlertEntity sa = new SystemAlertEntity();
        sa.alertStatus = SystemAlertEntity.AlertStatus.OPEN;
        assertTrue(sa.isOpen());
        sa.alertStatus = SystemAlertEntity.AlertStatus.RESOLVED;
        assertFalse(sa.isOpen());
        sa.alertStatus = null;
        assertFalse(sa.isOpen());
    }

    @Test
    @DisplayName("RiskRule/SecurityPolicy/EventPropertyDefinition：isActive 三条件全侧")
    void activeWithSoftDeleteSides() {
        RiskRuleEntity rr = new RiskRuleEntity();
        rr.status = RiskRuleEntity.RuleStatus.ACTIVE;
        assertTrue(rr.isActive());
        rr.deletedAt = LocalDateTime.now();
        assertFalse(rr.isActive());
        rr.deletedAt = null;
        rr.status = RiskRuleEntity.RuleStatus.PAUSED;
        assertFalse(rr.isActive());

        SecurityPolicyEntity sp = new SecurityPolicyEntity();
        sp.enabled = null;
        assertFalse(sp.isEnabled());
        sp.enabled = false;
        assertFalse(sp.isEnabled());
        sp.enabled = true;
        assertTrue(sp.isEnabled());
        sp.deletedAt = LocalDateTime.now();
        assertFalse(sp.isEnabled());

        EventPropertyDefinitionEntity ep = new EventPropertyDefinitionEntity();
        ep.status = EventPropertyDefinitionEntity.PropertyStatus.ACTIVE;
        assertTrue(ep.isActive());
        ep.deletedAt = LocalDateTime.now();
        assertFalse(ep.isActive());
        ep.deletedAt = null;
        ep.status = EventPropertyDefinitionEntity.PropertyStatus.DISABLED;
        assertFalse(ep.isActive());
    }
}
