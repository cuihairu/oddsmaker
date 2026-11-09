package io.oddsmaker.control.jpa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LAY-017 自 EntitiesBranchTopUpTest 拆分：QuotaNotify/SystemConfig/MaintenanceWindow/ReviewQueue/RateLimit/DataQualityRule/HealthMetric：配额通知侧、配置侧、维护窗口侧、审核队列侧、限流侧、数据质量规则侧、健康指标 normal 侧。
 * BRANCH 收口补测：按 jacoco miss 分支逐行补对侧。
 * 与 EntitiesDeep* 拆分类/EntitiesSweep2 系列拆分类互补——本类只打已miss的分支侧
 * （三元 null 侧、装箱 Boolean null 侧、枚举 == 对侧、@PrePersist false 侧、
 * 多值 || 的未测值、名单/灰度/条件求值的短路侧）。
 * 字段全 public：null 侧直接赋 null（Hibernate 加载 NULL 列在生产可达）。
 * onCreate 为 protected：同包直调覆盖 false 侧。
 */
@DisplayName("JPA 实体分支对侧补测（BRANCH 收口）")
class EntitiesBranchOpsTest {

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

}
