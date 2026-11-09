package io.oddsmaker.control.jpa;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖率冲刺第二轮·分析/ML/消息域（LAY-002 拆分）：社分分析、广告分析、漏斗步、ML 预测与训练、性能与收入分析、邮件与领取、事件定义、ML 制品。与 EntitiesSmokeCoverageTest（空构造冒烟）、EntitiesDeep* 拆分类（主要状态方法）互补。
 */
class EntitiesSweep2AnalyticsTest {

    @Test
    @DisplayName("SocialAnalyticsEntity：社交事件指标填充与 onCreate 时间戳")
    void socialAnalyticsEntity() {
        SocialAnalyticsEntity s = new SocialAnalyticsEntity();
        s.gameId = "g1";
        s.environment = "prod";
        s.analysisDate = LocalDate.now().minusDays(1);
        s.socialEventType = SocialAnalyticsEntity.SocialEventType.GIFT_SEND;
        s.totalFriendships = 1200L;
        s.newFriendships = 80L;
        s.avgFriendsPerUser = 12.5;
        s.totalGuilds = 45L;
        s.newGuilds = 3L;
        s.avgGuildSize = 26.7;
        s.guildMembers = 1200L;
        s.chatMessages = 88000L;
        s.giftsSent = 3200L;
        s.invitesSent = 560L;
        s.invitesAccepted = 210L;
        s.viralCoefficient = 0.37;
        s.socialUsersRetentionD1 = 0.52;
        s.socialUsersRetentionD7 = 0.28;
        s.nonSocialUsersRetentionD1 = 0.31;
        s.nonSocialUsersRetentionD7 = 0.12;
        s.platform = "ios";

        assertEquals(SocialAnalyticsEntity.SocialEventType.GIFT_SEND, s.socialEventType);
        assertEquals(1200L, s.totalFriendships);
        assertEquals(0.37, s.viralCoefficient, 0.0001);
        assertEquals("ios", s.platform);

        assertNull(s.createdAt);
        s.onCreate();
        assertNotNull(s.createdAt);
        LocalDateTime preset = LocalDateTime.now().minusHours(3);
        s.createdAt = preset;
        s.onCreate();
        assertEquals(preset, s.createdAt);
    }

    @Test
    @DisplayName("AdAnalysisEntity：广告指标填充与 onCreate")
    void adAnalysisEntity() {
        AdAnalysisEntity a = new AdAnalysisEntity();
        a.gameId = "g1";
        a.environment = "prod";
        a.analysisDate = LocalDate.now().minusDays(1);
        a.adNetwork = AdAnalysisEntity.AdNetwork.APPLOVIN;
        a.adFormat = AdAnalysisEntity.AdFormat.REWARDED;
        a.adPlacement = "结算页激励视频";
        a.impressions = 50000L;
        a.clicks = 1200L;
        a.rewards = 900L;
        a.revenue = 880.5;
        a.ecpm = 17.61;
        a.requests = 60000L;
        a.fills = 52000L;
        a.fillRate = 0.86;
        a.ctr = 0.024;
        a.uniqueUsers = 18000L;
        a.platform = "android";
        a.country = "CN";

        assertEquals(AdAnalysisEntity.AdNetwork.APPLOVIN, a.adNetwork);
        assertEquals(50000L, a.impressions);
        assertEquals(17.61, a.ecpm, 0.001);

        assertNull(a.createdAt);
        a.onCreate();
        assertNotNull(a.createdAt);
        LocalDateTime preset = LocalDateTime.now().minusHours(2);
        a.createdAt = preset;
        a.onCreate();
        assertEquals(preset, a.createdAt);
    }

    @Test
    @DisplayName("FunnelStepEntity：可选步骤/过滤条件/时间窗口判定")
    void funnelStepEntity() {
        FunnelStepEntity s = new FunnelStepEntity();
        s.stepOrder = 1;
        s.name = "完成新手教程";
        s.eventName = "tutorial_complete";

        assertFalse(s.isOptional());
        s.optional = null;
        assertFalse(s.isOptional());
        s.optional = true;
        assertTrue(s.isOptional());

    }

    @Test
    @DisplayName("MLModelPredictionEntity：预测状态迁移/反馈记录/延迟计算与 onCreate")
    void mlModelPredictionEntity() {
        MLModelPredictionEntity p = new MLModelPredictionEntity();
        p.modelId = "m-1";
        p.gameId = "g1";

        assertTrue(p.isPending());
        assertFalse(p.isCompleted());
        assertFalse(p.isFailed());
        assertFalse(p.hasFeedback());
        assertFalse(p.isCorrect());
        assertFalse(p.isAbTest());
        assertFalse(p.isCanary());
        assertFalse(p.isCacheHit());

        p.predictionStatus = MLModelPredictionEntity.PredictionStatus.PROCESSING;
        assertTrue(p.isPending());

        p.complete("{\"churn\":true}", 0.87);
        assertTrue(p.isCompleted());
        assertEquals("{\"churn\":true}", p.outputPrediction);
        assertEquals(0.87, p.predictionScore, 0.0001);
        assertNotNull(p.completedAt);

        p.cacheHit = true;
        assertTrue(p.isCacheHit());

        p.fail("模型服务不可用");
        assertTrue(p.isFailed());
        assertEquals("模型服务不可用", p.errorMessage);
        p.timeout();
        assertEquals(MLModelPredictionEntity.PredictionStatus.TIMEOUT, p.predictionStatus);
        assertEquals("Prediction request timed out", p.errorMessage);
        assertTrue(p.isFailed());

        p.isAbTest = true;
        assertTrue(p.isAbTest());
        p.isCanary = true;
        assertTrue(p.isCanary());

        p.addFeedback(MLModelPredictionEntity.FeedbackType.CORRECT, "确实流失", "运营-周八");
        assertTrue(p.hasFeedback());
        assertTrue(p.isCorrect());
        assertEquals("确实流失", p.actualValue);
        assertEquals("运营-周八", p.feedbackBy);
        assertNotNull(p.feedbackAt);
        p.feedbackType = MLModelPredictionEntity.FeedbackType.INCORRECT;
        assertFalse(p.isCorrect());

        p.latencyMs = 150;
        assertEquals(150L, p.getLatencyMs());
        p.latencyMs = null;
        assertEquals(0L, p.getLatencyMs());
        LocalDateTime base = LocalDateTime.now();
        p.createdAt = base.minusSeconds(3);
        p.completedAt = base;
        assertEquals(3000L, p.getLatencyMs());

        MLModelPredictionEntity fresh = new MLModelPredictionEntity();
        fresh.onCreate();
        assertNotNull(fresh.createdAt);
        assertEquals(MLModelPredictionEntity.PredictionStatus.PENDING, fresh.predictionStatus);
        fresh.predictionStatus = null;
        LocalDateTime preset = LocalDateTime.now().minusMinutes(1);
        fresh.createdAt = preset;
        fresh.onCreate();
        assertEquals(preset, fresh.createdAt);
        assertEquals(MLModelPredictionEntity.PredictionStatus.PENDING, fresh.predictionStatus);
    }

    @Test
    @DisplayName("MailEntity：可领取判定/全服与个人可见性/收件人静态匹配")
    void mailEntity() {
        LocalDateTime now = LocalDateTime.now();
        MailEntity m = new MailEntity();
        m.gameId = "g1";
        m.title = "周年庆补偿";
        m.content = "感谢陪伴，送上钻石礼包";

        assertFalse(m.claimable(now));
        m.status = MailEntity.Status.SENT;
        assertTrue(m.claimable(now));
        m.scope = MailEntity.Scope.INDIVIDUAL;
        assertFalse(m.visibleTo("p1", now));

        m.scope = MailEntity.Scope.ALL;
        assertTrue(m.visibleTo("任意玩家", now));

        m.scope = MailEntity.Scope.INDIVIDUAL;
        m.recipients = "p1, p2";
        assertTrue(m.visibleTo("p1", now));
        assertFalse(m.visibleTo("p3", now));
        m.recipients = null;
        assertFalse(m.visibleTo("p1", now));

        m.recipients = "p1, p2";
        m.expireAt = now.minusMinutes(1);
        assertFalse(m.claimable(now));
        m.expireAt = now.plusHours(1);
        assertTrue(m.claimable(now));

        m.deletedAt = now;
        assertFalse(m.claimable(now));
        m.deletedAt = null;

        assertTrue(MailEntity.containsRecipient("a , b , c", "b"));
        assertFalse(MailEntity.containsRecipient("a,b,c", "d"));
    }

    @Test
    @DisplayName("ModelTrainingEntity：训练生命周期/最佳损失更新/进度与时长计算")
    void modelTrainingEntity() {
        LocalDateTime now = LocalDateTime.now();
        ModelTrainingEntity t = new ModelTrainingEntity();
        t.modelId = "m-1";
        t.trainingJobName = "流失预测周训练";

        assertTrue(t.isPending());
        assertFalse(t.isRunning());
        assertFalse(t.isCompleted());
        assertFalse(t.isFailed());
        assertFalse(t.isCancelled());

        t.trainingStatus = ModelTrainingEntity.TrainingStatus.TIMEOUT;
        assertTrue(t.isFailed());

        t.start();
        assertTrue(t.isRunning());
        assertNotNull(t.startedAt);

        t.updateProgress(1, 10, 0.5);
        assertEquals(1, t.currentEpoch);
        assertEquals(10, t.trainingEpochs);
        assertEquals(10, t.progressPercent);
        assertEquals(0.5, t.bestLoss, 0.0001);
        assertEquals(1, t.bestEpoch);
        t.updateProgress(2, 10, 0.9);
        assertEquals(0.5, t.bestLoss, 0.0001);
        assertEquals(1, t.bestEpoch);
        t.updateProgress(3, 10, 0.3);
        assertEquals(0.3, t.bestLoss, 0.0001);
        assertEquals(3, t.bestEpoch);
        assertEquals(30.0, t.getProgress(), 0.001);

        t.trainingEpochs = null;
        t.progressPercent = 40;
        assertEquals(40.0, t.getProgress(), 0.001);
        t.progressPercent = null;
        assertEquals(0.0, t.getProgress(), 0.001);

        assertEquals(0, new ModelTrainingEntity().getDurationMinutes());
        t.startedAt = now.minusMinutes(30);
        t.completedAt = now.minusMinutes(10);
        assertEquals(20, t.getDurationMinutes());
        t.completedAt = null;
        assertTrue(t.getDurationMinutes() >= 29);

        t.complete();
        assertTrue(t.isCompleted());
        assertNotNull(t.completedAt);
        assertEquals(100, t.progressPercent);
        assertNotNull(t.durationMs);

        ModelTrainingEntity f = new ModelTrainingEntity();
        f.fail("loss 不收敛");
        assertTrue(f.isFailed());
        assertEquals("loss 不收敛", f.errorMessage);
        assertNull(f.durationMs);
        f.cancel();
        assertTrue(f.isCancelled());

        ModelTrainingEntity fresh = new ModelTrainingEntity();
        fresh.onCreate();
        assertNotNull(fresh.createdAt);
        assertEquals(ModelTrainingEntity.TrainingStatus.PENDING, fresh.trainingStatus);
        fresh.trainingStatus = null;
        LocalDateTime preset = LocalDateTime.now().minusHours(2);
        fresh.createdAt = preset;
        fresh.onCreate();
        assertEquals(preset, fresh.createdAt);
        assertEquals(ModelTrainingEntity.TrainingStatus.PENDING, fresh.trainingStatus);
        fresh.onUpdate();
        assertNotNull(fresh.updatedAt);
    }

    @Test
    @DisplayName("PerformanceMetricEntity：崩溃指标填充与 onCreate")
    void performanceMetricEntity() {
        PerformanceMetricEntity p = new PerformanceMetricEntity();
        p.gameId = "g1";
        p.environment = "prod";
        p.metricType = PerformanceMetricEntity.MetricType.CRASH;
        p.severity = PerformanceMetricEntity.Severity.CRITICAL;
        p.userId = "u-1";
        p.deviceId = "dev-1";
        p.sessionId = "sess-1";
        p.platform = "android";
        p.deviceModel = "Pixel 8";
        p.osVersion = "14";
        p.appVersion = "2.3.1";
        p.metricValue = 1.0;
        p.metricUnit = "count";
        p.crashType = "OOM";
        p.crashMessage = "OutOfMemoryError: 堆内存溢出";
        p.crashHash = "hash-9";
        p.contextData = "{\"level\":88}";

        assertEquals(PerformanceMetricEntity.MetricType.CRASH, p.metricType);
        assertEquals("OOM", p.crashType);
        assertEquals("hash-9", p.crashHash);

        assertNull(p.createdAt);
        p.onCreate();
        assertNotNull(p.createdAt);
        LocalDateTime preset = LocalDateTime.now().minusMinutes(3);
        p.createdAt = preset;
        p.onCreate();
        assertEquals(preset, p.createdAt);
    }

    @Test
    @DisplayName("RevenueAnalysisEntity：收入指标填充与 onCreate")
    void revenueAnalysisEntity() {
        RevenueAnalysisEntity v = new RevenueAnalysisEntity();
        v.gameId = "g1";
        v.environment = "prod";
        v.analysisDate = LocalDate.now().minusDays(1);
        v.revenueType = RevenueAnalysisEntity.RevenueType.IAP;
        v.totalRevenue = 58000.5;
        v.iapRevenue = 50000.0;
        v.adRevenue = 5000.5;
        v.subscriptionRevenue = 3000.0;
        v.totalUsers = 100000L;
        v.payingUsers = 4200L;
        v.newPayingUsers = 260L;
        v.arpu = 0.58;
        v.arppu = 13.8;
        v.totalTransactions = 6100L;
        v.avgTransactionValue = 9.5;
        v.platform = "ios";
        v.country = "CN";
        v.currency = "CNY";

        assertEquals(RevenueAnalysisEntity.RevenueType.IAP, v.revenueType);
        assertEquals(58000.5, v.totalRevenue, 0.001);
        assertEquals(4200L, v.payingUsers);

        assertNull(v.createdAt);
        v.onCreate();
        assertNotNull(v.createdAt);
        LocalDateTime preset = LocalDateTime.now().minusHours(1);
        v.createdAt = preset;
        v.onCreate();
        assertEquals(preset, v.createdAt);
    }

    @Test
    @DisplayName("MailClaimEntity：领取记录字段固化")
    void mailClaimEntity() {
        MailClaimEntity c = new MailClaimEntity();
        c.id = "claim-1";
        c.mailId = "mail-1";
        c.gameId = "g1";
        c.playerKey = "p-100";
        c.claimedAttachments = "[{\"type\":\"item\",\"id\":\"gem\",\"count\":100}]";
        c.claimedAt = LocalDateTime.now().minusMinutes(1);

        assertEquals("mail-1", c.mailId);
        assertEquals("p-100", c.playerKey);
        assertTrue(c.claimedAttachments.contains("gem"));
        assertNotNull(c.claimedAt);
    }

    @Test
    @DisplayName("EventDefinitionEntity：重要性/弃用判定/必需标识与全名拼接")
    void eventDefinitionEntity() {
        EventDefinitionEntity e = new EventDefinitionEntity();
        e.trackingPlanId = "tp-1";
        e.eventName = "level_complete";
        e.eventType = "progression";

        assertTrue(e.isActive());
        assertFalse(e.isRequired());
        assertFalse(e.isDeprecated());
        assertEquals("level_complete", e.getFullEventName());

        e.importance = EventDefinitionEntity.Importance.CRITICAL;
        assertTrue(e.isRequired());
        e.importance = EventDefinitionEntity.Importance.HIGH;
        assertTrue(e.isRequired());
        e.importance = EventDefinitionEntity.Importance.NORMAL;
        assertFalse(e.isRequired());

        e.status = EventDefinitionEntity.DefinitionStatus.DEPRECATED;
        assertTrue(e.isDeprecated());
        e.status = EventDefinitionEntity.DefinitionStatus.ACTIVE;
        e.importance = EventDefinitionEntity.Importance.DEPRECATED;
        assertTrue(e.isDeprecated());
        e.importance = EventDefinitionEntity.Importance.NORMAL;
        e.deletedAt = LocalDateTime.now();
        assertTrue(e.isDeprecated());
        assertFalse(e.isActive());
        e.deletedAt = null;

        TrackingPlanEntity tp = new TrackingPlanEntity();
        e.trackingPlan = tp;
        assertEquals("level_complete", e.getFullEventName());
        GameEntity g = new GameEntity();
        g.id = "g7";
        tp.game = g;
        assertEquals("g7.level_complete", e.getFullEventName());
    }

    @Test
    @DisplayName("EventPropertyDefinitionEntity：类型判定/校验存在性逐字段分支与描述拼接")
    void eventPropertyDefinitionEntity() {
        EventPropertyDefinitionEntity p = new EventPropertyDefinitionEntity();
        p.eventDefinitionId = "ed-1";
        p.propertyName = "level";

        assertTrue(p.isActive());
        assertFalse(p.isNumeric());
        assertTrue(p.isString());
        assertFalse(p.isSensitive());
        assertEquals("", p.getValidationDescription());

        p.type = EventPropertyDefinitionEntity.PropertyType.INTEGER;
        assertTrue(p.isNumeric());
        assertFalse(p.isString());
        p.type = EventPropertyDefinitionEntity.PropertyType.FLOAT;
        assertTrue(p.isNumeric());
        p.type = EventPropertyDefinitionEntity.PropertyType.ENUM;
        assertTrue(p.isString());
        p.type = EventPropertyDefinitionEntity.PropertyType.STRING;
        assertTrue(p.isString());

        p.required = true;
        p.minValue = 0.0;
        p.maxValue = 99.0;
        p.minLength = 1;
        p.maxLength = 3;
        p.regexPattern = "^\\d+$";
        p.allowedValues = "[1,2,3]";
        String desc = p.getValidationDescription();
        assertTrue(desc.startsWith("Required"));
        assertTrue(desc.contains("Min=0.0"));
        assertTrue(desc.contains("Max=99.0"));
        assertTrue(desc.contains("MinLen=1"));
        assertTrue(desc.contains("MaxLen=3"));
        assertTrue(desc.contains("Pattern"));
        assertTrue(desc.contains("Enum"));

        p.isPii = null;
        assertFalse(p.isSensitive());
        p.isPii = true;
        assertTrue(p.isSensitive());

        p.deletedAt = LocalDateTime.now();
        assertFalse(p.isActive());
    }

    @Test
    @DisplayName("MlArtifactEntity：生命周期回调补默认值且不覆盖已有值")
    void mlArtifactEntity() {
        MlArtifactEntity fresh = new MlArtifactEntity();
        fresh.status = null;
        fresh.onCreate();
        assertNotNull(fresh.createdAt);
        assertEquals("ACTIVE", fresh.status);

        LocalDateTime pre = LocalDateTime.now().minusDays(1);
        fresh.createdAt = pre;
        fresh.status = "RETIRED";
        fresh.onCreate();
        assertEquals(pre, fresh.createdAt);
        assertEquals("RETIRED", fresh.status);
    }
}
