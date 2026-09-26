package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.MlArtifactEntity;
import io.oddsmaker.control.jpa.MlArtifactRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ml 产物注册表测试：校验、upsert、审计、查询。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ml 产物注册表测试")
class MlArtifactRegistryTest {

    @Mock
    private MlArtifactRepo repo;

    @Mock
    private AuditLogService auditLogService;

    private MlArtifactRegistry registry() {
        return new MlArtifactRegistry(repo, auditLogService);
    }

    private static Map<String, Object> churnArtifact() {
        Map<String, Object> a = new HashMap<>();
        a.put("schema_version", 1);
        a.put("model_type", "churn");
        a.put("model_version", "v0.1.0");
        a.put("source", "synthetic");
        a.put("trained_at", "2026-09-26T00:00:00+00:00");
        a.put("feature_names", List.of("days_inactive_30d", "session_count_30d"));
        a.put("coefficients", List.of(0.8, -0.3));
        a.put("intercept", -1.5);
        a.put("metrics", Map.of("auc", 0.98));
        a.put("heuristic_baseline", Map.of("auc", 0.98));
        return a;
    }

    @Test
    @DisplayName("注册合法 churn 产物：字段落库 + 审计")
    void registerValidChurnArtifact() {
        when(repo.findByGameIdAndModelTypeAndModelVersion("g", "churn", "v0.1.0"))
                .thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MlArtifactEntity saved = registry().register("g", churnArtifact(), "op");

        assertEquals("churn", saved.modelType);
        assertEquals("v0.1.0", saved.modelVersion);
        assertEquals("synthetic", saved.source);
        assertEquals("[\"days_inactive_30d\",\"session_count_30d\"]", saved.featureNames);
        assertEquals("[0.8,-0.3]", saved.coefficients);
        assertEquals(-1.5, saved.intercept);
        assertNull(saved.multiplier);
        assertEquals("ACTIVE", saved.status);
        assertTrue(saved.id.startsWith("mla_"));
        assertNotNull(saved.artifactJson);
        verify(auditLogService).logCreate(eq("ml_model_artifact"), eq(saved.id),
                eq("churn:v0.1.0"), eq("op"), eq("op"), eq((String) null),
                ArgumentMatchers.<Map<String, ?>>any());
    }

    @Test
    @DisplayName("注册合法 pltv 产物：multiplier 必须为正")
    void registerValidPltvArtifact() {
        when(repo.findByGameIdAndModelTypeAndModelVersion("g", "pltv", "v0.1.0"))
                .thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> a = new HashMap<>();
        a.put("schema_version", 1);
        a.put("model_type", "pltv");
        a.put("model_version", "v0.1.0");
        a.put("multiplier", 3.2);
        a.put("metrics", Map.of("holdout_mape_trained", 0.045));

        MlArtifactEntity saved = registry().register("g", a, "op");

        assertNull(saved.featureNames);
        assertNull(saved.intercept);
        assertEquals(3.2, saved.multiplier);
    }

    @Test
    @DisplayName("同 (game, type, version) 重训覆盖：复用既有行 id")
    void reregisterSameVersionReusesRow() {
        MlArtifactEntity existing = new MlArtifactEntity();
        existing.id = "mla_existing";
        existing.gameId = "g";
        existing.modelType = "churn";
        existing.modelVersion = "v0.1.0";
        when(repo.findByGameIdAndModelTypeAndModelVersion("g", "churn", "v0.1.0"))
                .thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MlArtifactEntity saved = registry().register("g", churnArtifact(), "op");

        assertSame(existing, saved);
        assertEquals("mla_existing", saved.id);
    }

    @Test
    @DisplayName("校验拒绝：schema_version / model_type / model_version 缺失")
    void validationRejectsHeaderProblems() {
        MlArtifactRegistry r = registry();

        Map<String, Object> badSchema = churnArtifact();
        badSchema.put("schema_version", 99);
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> r.register("g", badSchema, "op"));
        assertTrue(e1.getMessage().contains("schema_version"));

        Map<String, Object> badType = churnArtifact();
        badType.put("model_type", "propensity");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> r.register("g", badType, "op")).getMessage().contains("model_type"));

        Map<String, Object> noVersion = churnArtifact();
        noVersion.remove("model_version");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> r.register("g", noVersion, "op")).getMessage().contains("model_version"));

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> r.register("g", null, "op")).getMessage().contains("为空"));
    }

    @Test
    @DisplayName("校验拒绝：churn 线性字段（feature_names / coefficients / intercept）")
    void validationRejectsBadLinearFields() {
        MlArtifactRegistry r = registry();

        Map<String, Object> noNames = churnArtifact();
        noNames.remove("feature_names");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> r.register("g", noNames, "op")).getMessage().contains("feature_names"));

        Map<String, Object> emptyNames = churnArtifact();
        emptyNames.put("feature_names", List.of());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> r.register("g", emptyNames, "op")).getMessage().contains("feature_names"));

        Map<String, Object> lengthMismatch = churnArtifact();
        lengthMismatch.put("coefficients", List.of(1.0));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> r.register("g", lengthMismatch, "op")).getMessage().contains("长度不一致"));

        Map<String, Object> noIntercept = churnArtifact();
        noIntercept.remove("intercept");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> r.register("g", noIntercept, "op")).getMessage().contains("intercept"));

        Map<String, Object> nonNumberCoef = churnArtifact();
        nonNumberCoef.put("coefficients", List.of(0.8, "x"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> r.register("g", nonNumberCoef, "op")).getMessage().contains("coefficients"));

        Map<String, Object> namesNotArray = churnArtifact();
        namesNotArray.put("feature_names", "days_inactive_30d");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> r.register("g", namesNotArray, "op")).getMessage().contains("feature_names"));
    }

    @Test
    @DisplayName("校验拒绝：pltv multiplier 非正")
    void validationRejectsNonPositiveMultiplier() {
        MlArtifactRegistry r = registry();

        Map<String, Object> zero = new HashMap<>();
        zero.put("schema_version", 1);
        zero.put("model_type", "pltv");
        zero.put("model_version", "v0.1.0");
        zero.put("multiplier", 0);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> r.register("g", zero, "op")).getMessage().contains("multiplier"));

        Map<String, Object> negative = new HashMap<>(zero);
        negative.put("multiplier", -1.0);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> r.register("g", negative, "op")).getMessage().contains("multiplier"));
    }

    @Test
    @DisplayName("校验拒绝：feature_names 含空白元素")
    void validationRejectsBlankFeatureName() {
        MlArtifactRegistry r = registry();

        Map<String, Object> blank = churnArtifact();
        blank.put("feature_names", List.of("days_inactive_30d", " "));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> r.register("g", blank, "op")).getMessage().contains("feature_names"));
    }

    @Test
    @DisplayName("校验拒绝：不可序列化的字段值（自引用结构）按解析失败处理")
    void validationRejectsUnserializableValues() {
        MlArtifactRegistry r = registry();
        Map<String, Object> selfRef = new HashMap<>();
        selfRef.put("self", selfRef);

        Map<String, Object> badNames = churnArtifact();
        badNames.put("feature_names", selfRef);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> r.register("g", badNames, "op")).getMessage().contains("feature_names"));

        Map<String, Object> badCoefs = churnArtifact();
        badCoefs.put("coefficients", selfRef);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> r.register("g", badCoefs, "op")).getMessage().contains("coefficients"));
    }

    @Test
    @DisplayName("校验拒绝：coefficients 非数组")
    void validationRejectsNonArrayCoefficients() {
        MlArtifactRegistry r = registry();

        Map<String, Object> scalar = churnArtifact();
        scalar.put("coefficients", 0.8);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> r.register("g", scalar, "op")).getMessage().contains("coefficients"));
    }

    @Test
    @DisplayName("可空字段落 null：churn 产物无 metrics 时不写 \"null\" 字符串")
    void nullableFieldsStoredAsNull() {
        when(repo.findByGameIdAndModelTypeAndModelVersion("g", "churn", "v0.1.0"))
                .thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> noMetrics = churnArtifact();
        noMetrics.remove("metrics");

        MlArtifactEntity saved = registry().register("g", noMetrics, "op");
        assertNull(saved.metrics);
        assertNotNull(saved.heuristicBaseline);
    }

    @Test
    @DisplayName("注册失败：metrics 不可序列化 → 产物不入库")
    void registerFailsWhenMetricsUnserializable() {
        Map<String, Object> selfRef = new HashMap<>();
        selfRef.put("self", selfRef);
        Map<String, Object> a = churnArtifact();
        a.put("metrics", selfRef);

        assertThrows(IllegalArgumentException.class, () -> registry().register("g", a, "op"));
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("resolveActive / listVersions：委托与空白类型分支")
    void queries() {
        MlArtifactEntity active = new MlArtifactEntity();
        when(repo.findFirstByGameIdAndModelTypeOrderByCreatedAtDescIdDesc("g", "churn"))
                .thenReturn(Optional.of(active));
        when(repo.findByGameIdAndModelTypeOrderByCreatedAtDescIdDesc("g", "churn"))
                .thenReturn(List.of(active));
        when(repo.findByGameIdOrderByCreatedAtDescIdDesc("g")).thenReturn(List.of());

        MlArtifactRegistry r = registry();
        assertTrue(r.resolveActive("g", "churn").isPresent());
        assertEquals(1, r.listVersions("g", "churn").size());
        assertTrue(r.listVersions("g", " ").isEmpty());
    }
}
