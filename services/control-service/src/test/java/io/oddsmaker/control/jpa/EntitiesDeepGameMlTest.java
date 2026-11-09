package io.oddsmaker.control.jpa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JPA 实体业务方法深度覆盖（LAY-010 拆分）：游戏/环境/ML 模型生命周期/群组实体的状态判定与计数分支。
 */
@DisplayName("JPA 实体填充字段后的业务方法分支覆盖")
class EntitiesDeepGameMlTest {

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

}
