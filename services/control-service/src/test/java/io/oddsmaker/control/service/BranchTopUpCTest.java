package io.oddsmaker.control.service;

import io.oddsmaker.control.exception.BusinessException;
import io.oddsmaker.control.jpa.ApiKeyEntity;
import io.oddsmaker.control.jpa.ApiKeyRepo;
import io.oddsmaker.control.jpa.DashboardEntity;
import io.oddsmaker.control.jpa.DashboardRepo;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.MlArtifactRepo;
import io.oddsmaker.control.jpa.SegmentEntity;
import io.oddsmaker.control.jpa.SegmentRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 片C BRANCH 收口（覆盖率巡检第四刀 control-service 专项）：DashboardService /
 * SegmentService / InspectorProxyService / EventsExportService / MlArtifactRegistry
 * 剩余 48 个未覆盖分支（82 → 34）的对侧补充，全部 mock/内存库/临时目录离线可跑。
 *
 * 记账（不可达分支）：EventsExportService.exportDay L111 `rows > MAX_ROWS`
 * 需单分区 500 万行以上（每行 46 列 JSONL 序列化 + gzip 写盘），离线单测需
 * GB 级磁盘与分钟级时长，不可离线承受——上限路径由常量与分页逻辑保证，按
 * 项目既定做法记账不凑覆盖。
 *
 * 注：既有定义校验用例中 event_name/within_days 等 snake_case 键会被
 * FAIL_ON_UNKNOWN_PROPERTIES 在 JSON 解析期拒绝，实际未抵达目标校验臂；
 * 本文件相关用例一律用 camelCase（eventName/withinDays）直击目标分支。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("片C服务分支对侧测试")
class BranchTopUpCTest {

    @Mock
    private DashboardRepo dashRepo;

    @Mock
    private SegmentRepo segRepo;

    @Mock
    private ClickHouseClient ch;

    @Mock
    private ApiKeyRepo keyRepo;

    @Mock
    private GameEnvironmentRepo envRepo;

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private MlArtifactRepo mlRepo;

    @Mock
    private AuditLogService auditLogService;

    @TempDir
    Path tempDir;

    private DashboardService dashService;
    private SegmentService segService;
    private InspectorProxyService proxyService;
    private MlArtifactRegistry mlRegistry;
    private EventsExportService exportService;

    private static final String VALID_SEG_JSON =
            "{\"conditions\":[{\"kind\":\"attribute\",\"field\":\"platform\",\"op\":\"eq\",\"value\":\"ios\"}]}";
    private static final String VALID_DASH_LAYOUT =
            "{\"widgets\":[{\"type\":\"kpi\",\"source\":\"online-overview\"}]}";

    @BeforeEach
    void setUp() {
        dashService = new DashboardService(dashRepo);
        segService = new SegmentService(segRepo, ch);
        proxyService = new InspectorProxyService(keyRepo, envRepo, restTemplate, "http://gateway:8080");
        mlRegistry = new MlArtifactRegistry(mlRepo, auditLogService);
        exportService = new EventsExportService(ch, tempDir.toString());
        when(segRepo.findByGameIdAndNameAndDeletedAtIsNull(anyString(), anyString())).thenReturn(Optional.empty());
        when(segRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(dashRepo.findByGameIdAndNameAndDeletedAtIsNull(anyString(), anyString())).thenReturn(Optional.empty());
        when(dashRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private SegmentEntity segEntity() {
        SegmentEntity e = new SegmentEntity();
        e.id = "seg123";
        e.gameId = "game_a";
        e.name = "whales";
        e.environment = "prod";
        e.subject = SegmentEntity.SegmentSubject.PLAYER;
        e.definition = VALID_SEG_JSON;
        return e;
    }

    private DashboardEntity dashEntity() {
        DashboardEntity e = new DashboardEntity();
        e.id = "dash123";
        e.gameId = "game_a";
        e.name = "ops_daily";
        e.layout = VALID_DASH_LAYOUT;
        return e;
    }

    private Map<String, Object> eventRow(String eventId) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("game_id", "game_a");
        r.put("environment", "prod");
        r.put("event_date", java.sql.Date.valueOf("2026-09-20"));
        r.put("ts_server", Timestamp.from(Instant.parse("2026-09-20T08:00:00Z")));
        r.put("event_id", eventId);
        r.put("event_name", "purchase");
        return r;
    }

    // ================= DashboardService：L89/L96/L114/L128/L141/L155/L158/L175/L181/L187/L192/L198/L221 =================

    @Test
    @DisplayName("update：全空参数 → status/layoutJson 两重 null 臂，仅回填时间戳")
    void dashUpdateAllNullArgs() {
        DashboardEntity entity = dashEntity();
        when(dashRepo.findByIdAndDeletedAtIsNull("dash123")).thenReturn(Optional.of(entity));

        DashboardEntity updated = dashService.update("dash123", null, null, null);

        assertNotNull(updated.updatedAt);
        verify(dashRepo).save(entity);
    }

    @Test
    @DisplayName("delete：已软删实体 → 直接 false 不重复保存")
    void dashDeleteAlreadyDeleted() {
        DashboardEntity entity = dashEntity();
        entity.deletedAt = LocalDateTime.now();
        when(dashRepo.findById("dash123")).thenReturn(Optional.of(entity));

        assertEquals(false, dashService.delete("dash123"));
        verify(dashRepo, never()).save(any());
    }

    @Test
    @DisplayName("create：名称 null / 空白 / 超 100 字符 均被拒")
    void dashNameNullBlankTooLong() {
        assertThrows(BusinessException.class, () -> dashService.create("game_a", null, null, VALID_DASH_LAYOUT));
        assertThrows(BusinessException.class, () -> dashService.create("game_a", " ", null, VALID_DASH_LAYOUT));
        assertThrows(BusinessException.class,
                () -> dashService.create("game_a", "x".repeat(101), null, VALID_DASH_LAYOUT));
    }

    @Test
    @DisplayName("布局：JSON 字面量 null → layout 空；{} → widgets 为空 均拒")
    void dashLayoutNullAndWidgetsNull() {
        assertThrows(BusinessException.class, () -> dashService.parseAndValidate("null"));
        assertThrows(BusinessException.class, () -> dashService.parseAndValidate("{}"));
    }

    @Test
    @DisplayName("widget：缺 type / 缺 source 均被拒")
    void dashWidgetMissingTypeOrSource() {
        assertThrows(BusinessException.class, () -> dashService.create("game_a", "w1", null,
                "{\"widgets\":[{\"source\":\"online-overview\"}]}"));
        assertThrows(BusinessException.class, () -> dashService.create("game_a", "w2", null,
                "{\"widgets\":[{\"type\":\"kpi\"}]}"));
    }

    @Test
    @DisplayName("params：environment 为 null 放行（白名单校验短路）")
    void dashParamsEnvironmentNullAllowed() {
        DashboardEntity ok = dashService.create("game_a", "env_null", null,
                "{\"widgets\":[{\"type\":\"kpi\",\"source\":\"online-overview\",\"params\":{\"environment\":null}}]}");
        assertTrue(ok.layout.contains("\"environment\":null"));
    }

    @Test
    @DisplayName("params：minutes 0 下界被拒")
    void dashParamsMinutesBelowMin() {
        assertThrows(BusinessException.class, () -> dashService.create("game_a", "m0", null,
                "{\"widgets\":[{\"type\":\"kpi\",\"source\":\"online-overview\",\"params\":{\"minutes\":0}}]}"));
    }

    @Test
    @DisplayName("params：days 366 上界被拒")
    void dashParamsDaysAboveMax() {
        assertThrows(BusinessException.class, () -> dashService.create("game_a", "d366", null,
                "{\"widgets\":[{\"type\":\"kpi\",\"source\":\"online-overview\",\"params\":{\"days\":366}}]}"));
    }

    @Test
    @DisplayName("params：granularity 为 null / 合法 day 均放行")
    void dashParamsGranularityNullAndValid() {
        DashboardEntity okNull = dashService.create("game_a", "gn", null,
                "{\"widgets\":[{\"type\":\"kpi\",\"source\":\"online-overview\",\"params\":{\"granularity\":null}}]}");
        assertTrue(okNull.layout.contains("\"granularity\":null"));
        DashboardEntity okDay = dashService.create("game_a", "gd", null,
                "{\"widgets\":[{\"type\":\"kpi\",\"source\":\"online-overview\",\"params\":{\"granularity\":\"day\"}}]}");
        assertTrue(okDay.layout.contains("\"granularity\":\"day\""));
    }

    @Test
    @DisplayName("params：limit 0 下界被拒、10 合法放行")
    void dashParamsLimitBelowMinAndValid() {
        assertThrows(BusinessException.class, () -> dashService.create("game_a", "l0", null,
                "{\"widgets\":[{\"type\":\"kpi\",\"source\":\"online-overview\",\"params\":{\"limit\":0}}]}"));
        DashboardEntity ok = dashService.create("game_a", "l10", null,
                "{\"widgets\":[{\"type\":\"kpi\",\"source\":\"online-overview\",\"params\":{\"limit\":10}}]}");
        assertTrue(ok.layout.contains("\"limit\":10"));
    }

    @Test
    @DisplayName("规范化：title 空白 → 按 source 补齐")
    void dashBlankTitleUsesSource() {
        DashboardEntity ok = dashService.create("game_a", "bt", null,
                "{\"widgets\":[{\"type\":\"kpi\",\"source\":\"online-overview\",\"title\":\" \"}]}");
        assertTrue(ok.layout.contains("\"title\":\"online-overview\""));
    }

    // ================= SegmentService：L114/L124/L221/L230/L236/L251/L254/L259/L270/L282/L288/L305/L308/L377 =================

    @Test
    @DisplayName("update：全空参数 → definitionJson null 臂，定义原样保留仅回填时间戳")
    void segUpdateAllNullArgs() {
        when(segRepo.findByIdAndDeletedAtIsNull("seg123")).thenReturn(Optional.of(segEntity()));

        SegmentEntity updated = segService.update("seg123", null, null, null, null);

        assertNotNull(updated.updatedAt);
        assertTrue(updated.definition.contains("platform"));
        verify(segRepo).save(any());
    }

    @Test
    @DisplayName("delete：已软删实体 → 直接 false 不重复保存")
    void segDeleteAlreadyDeleted() {
        SegmentEntity entity = segEntity();
        entity.deletedAt = LocalDateTime.now();
        when(segRepo.findById("seg123")).thenReturn(Optional.of(entity));

        assertEquals(false, segService.delete("seg123"));
        verify(segRepo, never()).save(any());
    }

    @Test
    @DisplayName("定义：空白字符串 → 不能为空")
    void segDefinitionBlankString() {
        BusinessException ex = assertThrows(BusinessException.class, () -> segService.parseAndValidate(" "));
        assertTrue(ex.getMessage().contains("不能为空"));
    }

    @Test
    @DisplayName("定义：conditions 缺省为 null → 至少需要一条条件")
    void segConditionsNullRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> segService.parseAndValidate("{\"match\":\"all\"}"));
        assertTrue(ex.getMessage().contains("至少需要一条条件"));
    }

    @Test
    @DisplayName("定义：match 显式 null → 规范化为 all 仍通过")
    void segMatchNullNormalizesToAll() {
        SegmentService.Definition def = segService.parseAndValidate(
                "{\"match\":null,\"conditions\":[{\"kind\":\"attribute\",\"field\":\"platform\",\"op\":\"eq\",\"value\":\"ios\"}]}");
        assertEquals("all", def.match);
    }

    @Test
    @DisplayName("attribute：field 缺失 → 白名单校验拒绝")
    void segAttributeNullField() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> segService.parseAndValidate("{\"conditions\":[{\"kind\":\"attribute\",\"op\":\"eq\",\"value\":\"x\"}]}"));
        assertTrue(ex.getMessage().contains("属性条件字段不在白名单"));
    }

    @Test
    @DisplayName("attribute：op 缺失 → 仅支持 eq/neq/in 拒绝")
    void segAttributeNullOp() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> segService.parseAndValidate("{\"conditions\":[{\"kind\":\"attribute\",\"field\":\"platform\",\"value\":\"ios\"}]}"));
        assertTrue(ex.getMessage().contains("属性条件 op 仅支持"));
    }

    @Test
    @DisplayName("attribute：in 全字符串数组 → 校验通过（anyMatch 短路整体为假）")
    void segInWithStringListPasses() {
        SegmentService.Definition def = segService.parseAndValidate(
                "{\"conditions\":[{\"kind\":\"attribute\",\"field\":\"country\",\"op\":\"in\",\"value\":[\"US\",\"CA\"]}]}");
        assertEquals(1, def.conditions.size());
        assertEquals("in", def.conditions.get(0).op);
    }

    @Test
    @DisplayName("event：count 0（camelCase）→ 阈值下界拒绝")
    void segEventCountZeroRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> segService.parseAndValidate(
                        "{\"conditions\":[{\"kind\":\"event\",\"eventName\":\"purchase\",\"count\":0}]}"));
        assertTrue(ex.getMessage().contains("count 需 >= 1"));
    }

    @Test
    @DisplayName("event_absent：within_days 0 拒绝；7 通过（含通用窗口钳制合法臂）")
    void segEventAbsentWindowArms() {
        BusinessException zero = assertThrows(BusinessException.class,
                () -> segService.parseAndValidate(
                        "{\"conditions\":[{\"kind\":\"event_absent\",\"eventName\":\"session_start\",\"withinDays\":0}]}"));
        assertTrue(zero.getMessage().contains("within_days"));

        SegmentService.Definition ok = segService.parseAndValidate(
                "{\"conditions\":[{\"kind\":\"event_absent\",\"eventName\":\"session_start\",\"withinDays\":7}]}");
        assertEquals(1, ok.conditions.size());
    }

    @Test
    @DisplayName("compile：未知 kind / attribute 未知 op → unreachable 兜底抛 IllegalStateException")
    void segCompileUnreachableDefaults() {
        SegmentService.Definition badKind = new SegmentService.Definition();
        SegmentService.Condition c1 = new SegmentService.Condition();
        c1.kind = "magic";
        badKind.conditions = List.of(c1);
        assertThrows(IllegalStateException.class,
                () -> segService.compile(badKind, SegmentEntity.SegmentSubject.PLAYER));

        SegmentService.Definition badOp = new SegmentService.Definition();
        SegmentService.Condition c2 = new SegmentService.Condition();
        c2.kind = "attribute";
        c2.field = "platform";
        c2.op = "regex";
        c2.value = "x";
        badOp.conditions = List.of(c2);
        assertThrows(IllegalStateException.class,
                () -> segService.compile(badOp, SegmentEntity.SegmentSubject.PLAYER));
    }

    @Test
    @DisplayName("subject：空白 / 显式 player → 均归一为 PLAYER")
    void segSubjectBlankAndExplicitPlayer() {
        SegmentEntity blank = segService.create("game_a", "seg_blank", null, null, "prod", " ", VALID_SEG_JSON);
        assertEquals(SegmentEntity.SegmentSubject.PLAYER, blank.subject);
        SegmentEntity player = segService.create("game_a", "seg_player", null, null, "prod", "player", VALID_SEG_JSON);
        assertEquals(SegmentEntity.SegmentSubject.PLAYER, player.subject);
    }

    // ================= InspectorProxyService：L64/L76/L110 =================

    private ApiKeyEntity key(String apiKey, String envId, ApiKeyEntity.ApiKeyType type) {
        ApiKeyEntity k = new ApiKeyEntity();
        k.apiKey = apiKey;
        k.environmentId = envId;
        k.gameId = "game_a";
        k.keyType = type;
        k.status = ApiKeyEntity.ApiKeyStatus.ACTIVE;
        return k;
    }

    private GameEnvironmentEntity env(String envId, String name, String gameId) {
        GameEnvironmentEntity e = new GameEnvironmentEntity();
        e.id = envId;
        e.name = name;
        e.gameId = gameId;
        return e;
    }

    private void stubScopedKey() {
        when(keyRepo.findByGameIdAndStatus("game_a", ApiKeyEntity.ApiKeyStatus.ACTIVE))
                .thenReturn(List.of(key("pk_server", "env2", ApiKeyEntity.ApiKeyType.SERVER)));
        when(envRepo.findById("env2")).thenReturn(Optional.of(env("env2", "prod", "game_a")));
    }

    @Test
    @DisplayName("recent：outcome 空串 → 不追加 outcome 查询参数")
    void inspBlankOutcomeOmitted() {
        stubScopedKey();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("count", 0)));

        proxyService.recent("game_a", "prod", "", 25);

        verify(restTemplate).exchange(
                eq("http://gateway:8080/v1/inspector/recent?game_id=game_a&environment=prod&limit=25"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("recent：2xx 但响应体为空 → gateway_rejected 并透传状态码")
    void inspTwoxxNullBodyRejected() {
        stubScopedKey();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<Map>(null, HttpStatus.OK));

        Map<String, Object> resp = proxyService.recent("game_a", "prod", null, null);

        assertEquals(false, resp.get("available"));
        assertEquals("gateway_rejected", resp.get("reason"));
        assertEquals(200, resp.get("status"));
    }

    @Test
    @DisplayName("recent：envRepo 查无 / env.gameId 不匹配 → 两种降级跳过臂")
    void inspMissingEnvAndGameMismatch() {
        when(keyRepo.findByGameIdAndStatus("game_a", ApiKeyEntity.ApiKeyStatus.ACTIVE))
                .thenReturn(List.of(
                        key("pk_missing", "envX", ApiKeyEntity.ApiKeyType.SERVER),
                        key("pk_mismatch", "envY", ApiKeyEntity.ApiKeyType.SERVER)));
        when(envRepo.findById("envX")).thenReturn(Optional.empty());
        when(envRepo.findById("envY")).thenReturn(Optional.of(env("envY", "prod", "game_b")));

        Map<String, Object> resp = proxyService.recent("game_a", "prod", null, null);

        assertEquals(false, resp.get("available"));
        assertEquals("no_scoped_server_key", resp.get("reason"));
        verify(restTemplate, never()).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(Map.class));
    }

    // ================= MlArtifactRegistry：L90/L110/L156/L200 =================

    private static Map<String, Object> artifact(String type) {
        Map<String, Object> a = new HashMap<>();
        a.put("schema_version", 1);
        a.put("model_type", type);
        a.put("model_version", "v0.1.0");
        return a;
    }

    @Test
    @DisplayName("listVersions：modelType 为 null → 按游戏全量查询")
    void mlListVersionsNullType() {
        when(mlRepo.findByGameIdOrderByCreatedAtDescIdDesc("g")).thenReturn(List.of());
        assertTrue(mlRegistry.listVersions("g", null).isEmpty());
    }

    @Test
    @DisplayName("校验：schema_version 非数值（字符串 \"1\"）→ 拒绝")
    void mlSchemaVersionNonNumber() {
        Map<String, Object> a = artifact("churn");
        a.put("schema_version", "1");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> mlRegistry.register("g", a, "op"));
        assertTrue(ex.getMessage().contains("schema_version"));
    }

    @Test
    @DisplayName("校验：feature_names 含数值元素 → 拒绝")
    void mlFeatureNamesNonStringElement() {
        Map<String, Object> a = artifact("churn");
        a.put("feature_names", List.of(0.8, -0.3));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> mlRegistry.register("g", a, "op"));
        assertTrue(ex.getMessage().contains("feature_names"));
    }

    @Test
    @DisplayName("校验：pltv multiplier 为 NaN / 无穷 → 拒绝")
    void mlMultiplierNaNAndInfinite() {
        Map<String, Object> nan = artifact("pltv");
        nan.put("multiplier", Double.NaN);
        IllegalArgumentException exNaN = assertThrows(IllegalArgumentException.class,
                () -> mlRegistry.register("g", nan, "op"));
        assertTrue(exNaN.getMessage().contains("multiplier"));

        Map<String, Object> inf = artifact("pltv");
        inf.put("multiplier", Double.POSITIVE_INFINITY);
        IllegalArgumentException exInf = assertThrows(IllegalArgumentException.class,
                () -> mlRegistry.register("g", inf, "op"));
        assertTrue(exInf.getMessage().contains("multiplier"));
    }

    // ================= EventsExportService：L78/L202（L111 记账不可达） =================

    @Test
    @DisplayName("exportDay：环境为 null / 超 100 字符 → INVALID_ENV")
    void evExportRejectsNullAndOverlongEnv() {
        BusinessException e1 = assertThrows(BusinessException.class,
                () -> exportService.exportDay("game_a", null, "2026-09-20", false));
        assertTrue(e1.getMessage().contains("INVALID_ENV"));

        BusinessException e2 = assertThrows(BusinessException.class,
                () -> exportService.exportDay("game_a", "x".repeat(101), "2026-09-20", false));
        assertTrue(e2.getMessage().contains("INVALID_ENV"));
    }

    @Test
    @DisplayName("listDays：环境为 null / 空串 → 不做环境过滤均可见；不匹配仍过滤")
    void evListDaysNullAndBlankEnvironment() throws Exception {
        when(ch.isAvailable()).thenReturn(true);
        when(ch.query(anyString(), any(Object[].class))).thenReturn(List.of(eventRow("e1")));
        exportService.exportDay("game_a", "prod", "2026-09-20", false);

        assertEquals(1, exportService.listDays("game_a", null).size());
        assertEquals(1, exportService.listDays("game_a", "").size());
        assertTrue(exportService.listDays("game_a", "dev").isEmpty());
    }
}