package io.oddsmaker.control.jpa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LAY-017 自 EntitiesBranchTopUpTest 拆分：PipelineJob/FlinkJob/ExportJob/MlPrediction/ModelTrainingProgress：限流与生命周期侧、进度侧、预测与训练进度侧。
 * BRANCH 收口补测：按 jacoco miss 分支逐行补对侧。
 * 与 EntitiesDeep* 拆分类/EntitiesSweep2 系列拆分类互补——本类只打已miss的分支侧
 * （三元 null 侧、装箱 Boolean null 侧、枚举 == 对侧、@PrePersist false 侧、
 * 多值 || 的未测值、名单/灰度/条件求值的短路侧）。
 * 字段全 public：null 侧直接赋 null（Hibernate 加载 NULL 列在生产可达）。
 * onCreate 为 protected：同包直调覆盖 false 侧。
 */
@DisplayName("JPA 实体分支对侧补测（BRANCH 收口）")
class EntitiesBranchJobsMlTest {

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

}
