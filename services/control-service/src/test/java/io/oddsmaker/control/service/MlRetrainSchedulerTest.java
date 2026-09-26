package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.MlArtifactRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ml 产物自动重训调度测试：开关、数据源决策、成功/失败/幂等路径、审计与降级。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ml 产物自动重训调度测试")
class MlRetrainSchedulerTest {

    private static final String CHURN_ARTIFACT_JSON =
            "{\"schema_version\":1,\"model_type\":\"churn\",\"model_version\":\"v0.1.0\","
            + "\"feature_names\":[\"a\"],\"coefficients\":[1.0],\"intercept\":0.0}";

    @Mock
    private MlArtifactRepo repo;
    @Mock
    private MlArtifactRegistry registry;
    @Mock
    private PredictionMetricsService metrics;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private ClickHouseClient clickHouseClient;
    @Mock
    private MlTrainingRunner runner;

    @BeforeEach
    void setUp() {
        when(clickHouseClient.isAvailable()).thenReturn(true);
    }

    private MlRetrainScheduler scheduler(boolean enabled, boolean allowSynthetic) {
        return scheduler(enabled, allowSynthetic, "prod", "", "jdbc:clickhouse://ch:8123/oddsmaker");
    }

    private MlRetrainScheduler scheduler(boolean enabled, boolean allowSynthetic,
                                         String environment, String explicitUrl, String jdbcUrl) {
        return new MlRetrainScheduler(repo, registry, metrics, auditLogService,
                clickHouseClient, runner,
                /* enabled */ enabled,
                /* python-bin */ "python3",
                /* ml-dir */ "ml",
                /* model-version */ "v0.1.0",
                /* environment */ environment,
                /* clickhouse-url 显式 */ explicitUrl,
                /* jdbc url（供推导） */ jdbcUrl,
                /* timeout */ 900,
                /* allow-synthetic */ allowSynthetic);
    }

    /** mock 训练进程：按 --out 参数写出全部产物文件后正常退出。 */
    private void trainWritesArtifacts() throws Exception {
        when(runner.run(anyList(), any(Path.class), anyLong())).thenAnswer(inv -> {
            List<String> cmd = inv.getArgument(0);
            Path out = Path.of(cmd.get(cmd.indexOf("--out") + 1));
            writeArtifacts(out, "churn", "pltv", "risk", "propensity");
            return new MlTrainingRunner.Result(0, "训练完成");
        });
    }

    private void writeArtifacts(Path out, String... types) throws Exception {
        for (String type : types) {
            Files.writeString(out.resolve(type + ".json"),
                    CHURN_ARTIFACT_JSON.replace("\"churn\"", "\"" + type + "\""));
        }
    }

    @Test
    @DisplayName("开关关闭：静默返回，零交互")
    void disabledIsNoop() {
        scheduler(false, true).retrainAll();
        verifyNoInteractions(repo, runner, registry, metrics, auditLogService);
    }

    @Test
    @DisplayName("无已注册产物的游戏：本轮无事可做")
    void noGamesIsNoop() {
        when(repo.findDistinctGameIds()).thenReturn(List.of());
        scheduler(true, true).retrainAll();
        verifyNoInteractions(runner, registry, metrics, auditLogService);
    }

    @Test
    @DisplayName("成功路径：ClickHouse 源训练 → 全部产物注册 → 四类打分；成功不写调度审计")
    void successPath() throws Exception {
        when(repo.findDistinctGameIds()).thenReturn(List.of("g"));
        trainWritesArtifacts();
        when(metrics.refreshChurn(eq("g"), eq("prod"))).thenReturn(Map.of("available", true));
        when(metrics.refreshRiskScore(eq("g"), eq("prod"))).thenReturn(Map.of("available", true));
        when(metrics.refreshPltv(eq("g"), eq("prod"))).thenReturn(Map.of("available", true));
        when(metrics.refreshPropensity(eq("g"), eq("prod"))).thenReturn(Map.of("available", true));

        scheduler(true, true).retrainAll();

        // 训练命令：ClickHouse 源 + 推导出的 HTTP url
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> cmd =
                (ArgumentCaptor<List<String>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(runner).run(cmd.capture(), eq(Path.of("ml")), eq(900L));
        List<String> args = cmd.getValue();
        assertEquals("clickhouse", args.get(args.indexOf("--source") + 1));
        assertEquals("http://ch:8123/?database=oddsmaker", args.get(args.indexOf("--clickhouse-url") + 1));

        verify(registry, times(4)).register(eq("g"), anyMap(), eq("ml-retrain"));
        verify(metrics).refreshChurn("g", "prod");
        verify(metrics).refreshRiskScore("g", "prod");
        verify(metrics).refreshPltv("g", "prod");
        verify(metrics).refreshPropensity("g", "prod");
        verify(auditLogService, never()).log(any(), anyString(), anyString(), any(),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("ClickHouse 不可用：回落合成数据，命令不带 --clickhouse-url")
    void syntheticFallback() throws Exception {
        when(clickHouseClient.isAvailable()).thenReturn(false);
        when(repo.findDistinctGameIds()).thenReturn(List.of("g"));
        trainWritesArtifacts();
        when(metrics.refreshChurn(anyString(), any())).thenReturn(Map.of("available", false));
        when(metrics.refreshRiskScore(anyString(), any())).thenReturn(Map.of("available", false));
        when(metrics.refreshPltv(anyString(), any())).thenReturn(Map.of("available", false));

        Map<String, Object> summary = scheduler(true, true).retrainForGame("g");

        assertEquals("synthetic", summary.get("source"));
        assertEquals(4, summary.get("registered"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> cmd =
                (ArgumentCaptor<List<String>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(runner).run(cmd.capture(), any(Path.class), anyLong());
        assertEquals("synthetic", cmd.getValue().get(cmd.getValue().indexOf("--source") + 1));
        assertFalse(cmd.getValue().contains("--clickhouse-url"));
        // 打分 available=false：如实不计入已完成打分
        assertTrue(((List<?>) summary.get("scored")).isEmpty());
    }

    @Test
    @DisplayName("禁用合成数据且 CH 不可用：SKIPPED 审计，不训练不注册")
    void syntheticDisallowedSkips() throws Exception {
        when(clickHouseClient.isAvailable()).thenReturn(false);

        Map<String, Object> summary = scheduler(true, false).retrainForGame("g");

        assertNotNull(summary.get("skipped"));
        verifyNoInteractions(runner, registry, metrics);
        verify(auditLogService).log(eq(AuditLogEntity.AuditAction.UPDATE), eq("ml_retrain"),
                eq("g"), eq("g"), anyString(), eq(AuditLogEntity.AuditResult.SKIPPED),
                eq("ml-retrain"), isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("训练进程失败：FAILURE 审计，不阻塞其他游戏")
    void trainingFailureAuditsAndContinues() throws Exception {
        when(repo.findDistinctGameIds()).thenReturn(List.of("g1", "g2"));
        when(runner.run(anyList(), any(Path.class), anyLong()))
                .thenReturn(new MlTrainingRunner.Result(1, "模型 x 训练失败"));

        scheduler(true, true).retrainAll();

        verify(runner, times(2)).run(anyList(), any(Path.class), anyLong());
        verify(registry, never()).register(anyString(), anyMap(), anyString());
        verify(auditLogService, times(2)).log(eq(AuditLogEntity.AuditAction.UPDATE),
                eq("ml_retrain"), anyString(), anyString(), contains("训练进程失败"),
                eq(AuditLogEntity.AuditResult.FAILURE), eq("ml-retrain"),
                isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("全部产物注册失败：retrainForGame 抛出（retrainAll 记 FAILURE）")
    void allRegistrationsFailedThrows() throws Exception {
        when(runner.run(anyList(), any(Path.class), anyLong())).thenAnswer(inv -> {
            List<String> cmd = inv.getArgument(0);
            Path out = Path.of(cmd.get(cmd.indexOf("--out") + 1));
            for (String type : List.of("churn", "pltv", "risk", "propensity")) {
                Files.writeString(out.resolve(type + ".json"), "{not-json");
            }
            return new MlTrainingRunner.Result(0, "");
        });

        assertThrows(IllegalStateException.class, () -> scheduler(true, true).retrainForGame("g"));
        verify(registry, never()).register(anyString(), anyMap(), anyString());
    }

    @Test
    @DisplayName("部分产物非法：合法的照常注册，PARTIAL 审计")
    void partialFailuresRegisterRestAndAudit() throws Exception {
        when(runner.run(anyList(), any(Path.class), anyLong())).thenAnswer(inv -> {
            List<String> cmd = inv.getArgument(0);
            Path out = Path.of(cmd.get(cmd.indexOf("--out") + 1));
            Files.writeString(out.resolve("churn.json"), "{not-json");
            Files.writeString(out.resolve("pltv.json"),
                    "{\"schema_version\":1,\"model_type\":\"pltv\",\"model_version\":\"v0.1.0\",\"multiplier\":2.0}");
            Files.writeString(out.resolve("risk.json"), CHURN_ARTIFACT_JSON.replace("\"churn\"", "\"risk\""));
            Files.writeString(out.resolve("propensity.json"),
                    CHURN_ARTIFACT_JSON.replace("\"churn\"", "\"propensity\""));
            return new MlTrainingRunner.Result(0, "");
        });
        when(metrics.refreshChurn(anyString(), any())).thenReturn(Map.of("available", true));
        when(metrics.refreshRiskScore(anyString(), any())).thenReturn(Map.of("available", true));
        when(metrics.refreshPltv(anyString(), any())).thenReturn(Map.of("available", true));
        when(metrics.refreshPropensity(anyString(), any())).thenReturn(Map.of("available", true));

        Map<String, Object> summary = scheduler(true, true).retrainForGame("g");

        assertEquals(3, summary.get("registered"));
        assertEquals(1, ((List<?>) summary.get("errors")).size());
        verify(auditLogService).log(eq(AuditLogEntity.AuditAction.UPDATE), eq("ml_retrain"),
                eq("g"), eq("g"), contains("churn: 注册失败"),
                eq(AuditLogEntity.AuditResult.PARTIAL), eq("ml-retrain"),
                isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("幂等：同游戏重复重训以相同参数注册（同版本覆盖）")
    void idempotentReregistration() throws Exception {
        when(repo.findDistinctGameIds()).thenReturn(List.of("g"));
        trainWritesArtifacts();
        when(metrics.refreshChurn(anyString(), any())).thenReturn(Map.of("available", true));
        when(metrics.refreshRiskScore(anyString(), any())).thenReturn(Map.of("available", true));
        when(metrics.refreshPltv(anyString(), any())).thenReturn(Map.of("available", true));

        MlRetrainScheduler s = scheduler(true, true);
        s.retrainAll();
        s.retrainAll();

        verify(registry, times(8)).register(eq("g"), anyMap(), eq("ml-retrain"));
    }

    @Test
    @DisplayName("打分异常不阻塞：注册保留，错误入审计（PARTIAL）")
    void scoringFailureTolerated() throws Exception {
        when(repo.findDistinctGameIds()).thenReturn(List.of("g"));
        trainWritesArtifacts();
        when(metrics.refreshChurn(anyString(), any())).thenThrow(new RuntimeException("ch down"));
        when(metrics.refreshRiskScore(anyString(), any())).thenReturn(Map.of("available", true));
        when(metrics.refreshPltv(anyString(), any())).thenReturn(Map.of("available", true));
        when(metrics.refreshPropensity(anyString(), any())).thenReturn(Map.of("available", true));

        Map<String, Object> summary = scheduler(true, true).retrainForGame("g");

        assertEquals(4, summary.get("registered"));
        assertEquals(1, ((List<?>) summary.get("errors")).size());
        assertTrue(String.valueOf(((List<?>) summary.get("errors")).get(0)).contains("打分 churn"));
        assertEquals(List.of("risk_model", "pltv", "propensity"), summary.get("scored"));
    }

    @Test
    @DisplayName("游戏发现失败：本轮跳过，不训练不审计")
    void gameDiscoveryFailureSkipsRound() {
        when(repo.findDistinctGameIds()).thenThrow(new RuntimeException("db down"));
        scheduler(true, true).retrainAll();
        verifyNoInteractions(runner, registry, metrics, auditLogService);
    }

    @Test
    @DisplayName("产物文件缺失：该类型计错误并 PARTIAL 审计，其余照常注册")
    void missingArtifactFileCountedAsError() throws Exception {
        when(repo.findDistinctGameIds()).thenReturn(List.of("g"));
        when(runner.run(anyList(), any(Path.class), anyLong())).thenAnswer(inv -> {
            List<String> cmd = inv.getArgument(0);
            Path out = Path.of(cmd.get(cmd.indexOf("--out") + 1));
            writeArtifacts(out, "churn", "pltv", "risk");  // propensity.json 缺失
            return new MlTrainingRunner.Result(0, "");
        });
        when(metrics.refreshChurn(anyString(), any())).thenReturn(Map.of("available", true));
        when(metrics.refreshRiskScore(anyString(), any())).thenReturn(Map.of("available", true));
        when(metrics.refreshPltv(anyString(), any())).thenReturn(Map.of("available", true));
        when(metrics.refreshPropensity(anyString(), any())).thenReturn(Map.of("available", true));

        Map<String, Object> summary = scheduler(true, true).retrainForGame("g");

        assertEquals(3, summary.get("registered"));
        assertEquals(1, ((List<?>) summary.get("errors")).size());
        assertTrue(String.valueOf(((List<?>) summary.get("errors")).get(0)).contains("产物文件缺失"));
        verify(auditLogService).log(eq(AuditLogEntity.AuditAction.UPDATE), eq("ml_retrain"),
                eq("g"), eq("g"), contains("propensity: 产物文件缺失"),
                eq(AuditLogEntity.AuditResult.PARTIAL), eq("ml-retrain"),
                isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("CH 可用但 url 不可推导：回落 synthetic")
    void chAvailableWithoutUrlFallsToSynthetic() throws Exception {
        when(repo.findDistinctGameIds()).thenReturn(List.of("g"));
        trainWritesArtifacts();
        when(metrics.refreshChurn(anyString(), any())).thenReturn(Map.of("available", true));
        when(metrics.refreshRiskScore(anyString(), any())).thenReturn(Map.of("available", true));
        when(metrics.refreshPltv(anyString(), any())).thenReturn(Map.of("available", true));
        when(metrics.refreshPropensity(anyString(), any())).thenReturn(Map.of("available", true));

        Map<String, Object> summary = scheduler(true, true, "prod", "", "jdbc:mysql://localhost/db")
                .retrainForGame("g");

        assertEquals("synthetic", summary.get("source"));
    }

    @Test
    @DisplayName("显式 clickhouse-url 优先于 JDBC 推导；空白环境归一化 null 传打分")
    void explicitUrlPreferredAndBlankEnvPassesNull() throws Exception {
        when(repo.findDistinctGameIds()).thenReturn(List.of("g"));
        trainWritesArtifacts();
        when(metrics.refreshChurn(anyString(), any())).thenReturn(Map.of("available", true));
        when(metrics.refreshRiskScore(anyString(), any())).thenReturn(Map.of("available", true));
        when(metrics.refreshPltv(anyString(), any())).thenReturn(Map.of("available", true));
        when(metrics.refreshPropensity(anyString(), any())).thenReturn(Map.of("available", true));

        scheduler(true, true, " ", "http://explicit:8123/?database=oddsmaker",
                "jdbc:clickhouse://ch:8123/oddsmaker").retrainForGame("g");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> cmd =
                (ArgumentCaptor<List<String>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(runner).run(cmd.capture(), any(Path.class), anyLong());
        assertEquals("http://explicit:8123/?database=oddsmaker",
                cmd.getValue().get(cmd.getValue().indexOf("--clickhouse-url") + 1));
        verify(metrics).refreshChurn("g", null);
    }

    @Test
    @DisplayName("审计写失败兜底：不向 retrainAll 传播")
    void auditWriteFailureTolerated() throws Exception {
        when(repo.findDistinctGameIds()).thenReturn(List.of("g"));
        when(runner.run(anyList(), any(Path.class), anyLong()))
                .thenReturn(new MlTrainingRunner.Result(1, "boom"));
        doThrow(new RuntimeException("audit down")).when(auditLogService).log(
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());

        assertDoesNotThrow(() -> scheduler(true, true).retrainAll());
        verify(auditLogService).log(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("失败审计：超长异常信息截断 500 字符，null message 截断为空串")
    void truncatesLongAndNullMessages() throws Exception {
        when(repo.findDistinctGameIds()).thenReturn(List.of("g1", "g2"));
        when(runner.run(anyList(), any(Path.class), anyLong()))
                .thenReturn(new MlTrainingRunner.Result(1, "x".repeat(600)))
                .thenThrow(new RuntimeException());

        scheduler(true, true).retrainAll();

        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(auditLogService, times(2)).log(eq(AuditLogEntity.AuditAction.UPDATE), eq("ml_retrain"),
                anyString(), anyString(), details.capture(), eq(AuditLogEntity.AuditResult.FAILURE),
                eq("ml-retrain"), isNull(), isNull(), isNull(), isNull(), isNull());
        assertEquals(500, details.getAllValues().get(0).length());
        assertEquals("", details.getAllValues().get(1));
    }

    @Test
    @DisplayName("httpFromJdbc：空 host 与仅 query 返回 null")
    void httpFromJdbcEdgeCases() {
        assertNull(MlRetrainScheduler.httpFromJdbc("jdbc:clickhouse://"));
        assertNull(MlRetrainScheduler.httpFromJdbc("jdbc:clickhouse://?x=1"));
    }

    @Test
    @DisplayName("JDBC url 推导 HTTP url：显式配置优先，无法推导返回 null")
    void jdbcUrlDerivation() {
        assertEquals("http://ch:8123/?database=oddsmaker",
                MlRetrainScheduler.httpFromJdbc("jdbc:clickhouse://ch:8123/oddsmaker"));
        assertEquals("http://ch:8123", MlRetrainScheduler.httpFromJdbc("jdbc:clickhouse://ch:8123"));
        assertEquals("http://ch:8123/?database=db&wait_end_of_query=1",
                MlRetrainScheduler.httpFromJdbc("jdbc:clickhouse://ch:8123/db?wait_end_of_query=1"));
        assertEquals("http://ch:8123/?database=db",
                MlRetrainScheduler.httpFromJdbc("jdbc:clickhouse://ch:8123/db/"));
        assertEquals(null, MlRetrainScheduler.httpFromJdbc("jdbc:mysql://localhost/db"));
        assertEquals(null, MlRetrainScheduler.httpFromJdbc(null));
    }

    @Test
    @DisplayName("数据源决策：CH 可用且 url 可推导 → clickhouse；否则 synthetic")
    void sourceResolution() {
        assertTrue(scheduler(true, true).resolveSource().equals("clickhouse"));

        when(clickHouseClient.isAvailable()).thenReturn(false);
        assertTrue(scheduler(true, true).resolveSource().equals("synthetic"));
    }
}
