package io.oddsmaker.control.api;

import io.oddsmaker.control.dto.EnvironmentDTO;
import io.oddsmaker.control.dto.ExperimentConfigDTO;
import io.oddsmaker.control.dto.ExperimentDTO;
import io.oddsmaker.control.dto.GameDTO;
import io.oddsmaker.control.dto.StorageProfileDTO;
import io.oddsmaker.control.experiment.ExperimentEntity;
import io.oddsmaker.control.experiment.ExperimentMetricSnapshotEntity;
import io.oddsmaker.control.experiment.ExperimentMetricSnapshotRepo;
import io.oddsmaker.control.experiment.ExperimentRepo;
import io.oddsmaker.control.experiment.ExperimentStatsService;
import io.oddsmaker.control.jpa.AnnouncementEntity;
import io.oddsmaker.control.jpa.AnnouncementRepo;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.service.AnnouncementService;
import io.oddsmaker.control.service.ExperimentService;
import io.oddsmaker.control.service.GameService;
import io.oddsmaker.control.service.StorageProfileService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * API 层深覆盖测试（纯 Mockito，无 Spring 上下文）：
 * - ApiController：Game/Environment/Experiment/StorageProfile CRUD 委托与 legacy api-keys 路由；
 * - ExperimentResultsController：ingestMetrics 快照回填的校验分支与幂等覆盖；
 * - AnnouncementService：update/schedule/offline/delete 的校验与状态机分支。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("API 层深覆盖测试")
class ApiControllersDeepTest {

    // ---------- ApiController ----------

    @Mock
    private ControlService svc;

    @Mock
    private GameService gameService;

    @Mock
    private ExperimentService experimentService;

    @Mock
    private StorageProfileService storageProfileService;

    @InjectMocks
    private ApiController apiController;

    // ---------- ExperimentResultsController ----------

    @Mock
    private ExperimentRepo experimentRepo;

    @Mock
    private ExperimentMetricSnapshotRepo snapshotRepo;

    @Spy
    private ExperimentStatsService statsService = new ExperimentStatsService();

    @InjectMocks
    private ExperimentResultsController resultsController;

    // ---------- AnnouncementService ----------

    @Mock
    private AnnouncementRepo announcementRepo;

    @Mock
    private GameRepo gameRepo;

    @Mock
    private GameEnvironmentRepo environmentRepo;

    @Mock
    private io.oddsmaker.control.service.AuditLogService auditLog;

    @InjectMocks
    private AnnouncementService announcementService;

    // ---------- 辅助构造 ----------

    private static GameDTO gameDto(String id, String name) {
        GameDTO dto = new GameDTO();
        dto.id = id;
        dto.name = name;
        return dto;
    }

    private static EnvironmentDTO envDto(String name) {
        EnvironmentDTO dto = new EnvironmentDTO();
        dto.name = name;
        dto.gameId = "game_1";
        return dto;
    }

    private static ExperimentDTO expDto(String id) {
        ExperimentDTO dto = new ExperimentDTO();
        dto.id = id;
        dto.gameId = "game_1";
        dto.name = "实验-" + id;
        dto.status = "draft";
        return dto;
    }

    private static StorageProfileDTO spDto(String id) {
        StorageProfileDTO dto = new StorageProfileDTO();
        dto.id = id;
        dto.name = "profile-" + id;
        return dto;
    }

    private static AnnouncementEntity announcement(String id, AnnouncementEntity.Status status) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.id = id;
        a.gameId = "game_1";
        a.title = "标题";
        a.content = "内容";
        a.status = status;
        return a;
    }

    // =====================================================================
    // ApiController：Game CRUD
    // =====================================================================

    @Test
    @DisplayName("listGames：无关键词走 getGames，默认分页 0/50 + name 排序")
    void listGamesDefaultsToGetGames() {
        GameDTO dto = gameDto("game_1", "Demo");
        lenient().when(gameService.getGames(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(dto)));

        ControlService.Paged<?> out = (ControlService.Paged<?>) apiController.listGames(null, null, null, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(gameService).getGames(captor.capture());
        assertEquals(0, captor.getValue().getPageNumber());
        assertEquals(50, captor.getValue().getPageSize());
        assertNotNull(captor.getValue().getSort().getOrderFor("name"));
        assertEquals(List.of(dto), out.items);
        assertEquals(1L, out.total);
    }

    @Test
    @DisplayName("listGames：带关键词与 sort 走 searchGames")
    void listGamesWithQuerySearches() {
        GameDTO dto = gameDto("game_1", "Demo");
        lenient().when(gameService.searchGames(eq("demo"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(dto)));

        ControlService.Paged<?> out = (ControlService.Paged<?>) apiController.listGames("demo", 2, 10, "createdAt");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(gameService).searchGames(eq("demo"), captor.capture());
        assertEquals(2, captor.getValue().getPageNumber());
        assertEquals(10, captor.getValue().getPageSize());
        assertNotNull(captor.getValue().getSort().getOrderFor("createdAt"));
        assertEquals(1L, out.total);
    }

    @Test
    @DisplayName("createGame：委托 GameService")
    void createGameDelegates() {
        GameDTO in = gameDto(null, "New");
        GameDTO out = gameDto("game_9", "New");
        lenient().when(gameService.createGame(in)).thenReturn(out);

        assertSame(out, apiController.createGame(in));
    }

    @Test
    @DisplayName("getGame：存在返回 200，不存在返回 404")
    void getGameFoundAndNotFound() {
        GameDTO dto = gameDto("game_1", "Demo");
        lenient().when(gameService.getGame("game_1")).thenReturn(Optional.of(dto));
        lenient().when(gameService.getGame("game_x")).thenReturn(Optional.empty());

        assertEquals(200, apiController.getGame("game_1").getStatusCode().value());
        assertSame(dto, apiController.getGame("game_1").getBody());
        assertEquals(404, apiController.getGame("game_x").getStatusCode().value());
        assertNull(apiController.getGame("game_x").getBody());
    }

    @Test
    @DisplayName("updateGame：委托并返回 200")
    void updateGameDelegates() {
        GameDTO in = gameDto("game_1", "Renamed");
        GameDTO out = gameDto("game_1", "Renamed");
        lenient().when(gameService.updateGame("game_1", in)).thenReturn(out);

        var resp = apiController.updateGame("game_1", in);
        assertEquals(200, resp.getStatusCode().value());
        assertSame(out, resp.getBody());
    }

    @Test
    @DisplayName("deleteGame：委托删除并返回 deleted 载荷")
    void deleteGameReturnsPayload() {
        var resp = apiController.deleteGame("game_1");

        verify(gameService).deleteGame("game_1");
        assertEquals(200, resp.getStatusCode().value());
        assertEquals(true, resp.getBody().get("deleted"));
        assertEquals("game_1", resp.getBody().get("gameId"));
    }

    @Test
    @DisplayName("publishGame/unpublishGame：委托并回包")
    void publishAndUnpublishGame() {
        GameDTO published = gameDto("game_1", "Demo");
        GameDTO unpublished = gameDto("game_1", "Demo");
        lenient().when(gameService.publishGame("game_1")).thenReturn(published);
        lenient().when(gameService.unpublishGame("game_1")).thenReturn(unpublished);

        assertSame(published, apiController.publishGame("game_1").getBody());
        assertSame(unpublished, apiController.unpublishGame("game_1").getBody());
    }

    // =====================================================================
    // ApiController：Environment CRUD
    // =====================================================================

    @Test
    @DisplayName("listEnvironments/createEnvironment：委托 GameService")
    void environmentListAndCreate() {
        List<EnvironmentDTO> envs = List.of(envDto("dev"), envDto("prod"));
        lenient().when(gameService.listEnvironments("game_1")).thenReturn(envs);

        assertEquals(envs, apiController.listEnvironments("game_1"));

        EnvironmentDTO in = envDto("qa");
        EnvironmentDTO created = envDto("qa");
        lenient().when(gameService.createEnvironment("game_1", in)).thenReturn(created);
        assertSame(created, apiController.createEnvironment("game_1", in));
    }

    @Test
    @DisplayName("getEnvironment：存在 200，不存在 404")
    void getEnvironmentFoundAndNotFound() {
        EnvironmentDTO dto = envDto("prod");
        lenient().when(gameService.getEnvironment("game_1", "prod")).thenReturn(Optional.of(dto));
        lenient().when(gameService.getEnvironment("game_1", "gone")).thenReturn(Optional.empty());

        assertSame(dto, apiController.getEnvironment("game_1", "prod").getBody());
        assertEquals(404, apiController.getEnvironment("game_1", "gone").getStatusCode().value());
    }

    @Test
    @DisplayName("updateEnvironment：委托并返回 200")
    void updateEnvironmentDelegates() {
        EnvironmentDTO in = envDto("prod");
        EnvironmentDTO out = envDto("prod");
        lenient().when(gameService.updateEnvironment("game_1", "prod", in)).thenReturn(out);

        var resp = apiController.updateEnvironment("game_1", "prod", in);
        assertEquals(200, resp.getStatusCode().value());
        assertSame(out, resp.getBody());
    }

    @Test
    @DisplayName("deleteEnvironment：成功 200 / 不存在 IAE 转 404 / 占用 ISE 转 409")
    void deleteEnvironmentMapsExceptions() {
        lenient().doNothing().when(gameService).deleteEnvironment("game_1", "prod");
        lenient().doThrow(new IllegalArgumentException("Environment not found: gone"))
            .when(gameService).deleteEnvironment("game_1", "gone");
        lenient().doThrow(new IllegalStateException("It is currently in use by 2 API key(s)"))
            .when(gameService).deleteEnvironment("game_1", "busy");

        assertEquals(200, apiController.deleteEnvironment("game_1", "prod").getStatusCode().value());
        assertEquals(true, apiController.deleteEnvironment("game_1", "prod").getBody().get("deleted"));

        assertEquals(404, apiController.deleteEnvironment("game_1", "gone").getStatusCode().value());

        var conflict = apiController.deleteEnvironment("game_1", "busy");
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals(false, conflict.getBody().get("deleted"));
        assertEquals("game_1", conflict.getBody().get("gameId"));
        assertEquals("busy", conflict.getBody().get("environment"));
        assertNotNull(conflict.getBody().get("error"));
    }

    // =====================================================================
    // ApiController：Experiment CRUD
    // =====================================================================

    @Test
    @DisplayName("listExperiments：默认分页 0/50，显式分页透传")
    void listExperimentsPagingDefaults() {
        ControlService.Paged<ExperimentDTO> paged = new ControlService.Paged<>(List.of(expDto("exp_1")), 1L);
        lenient().when(experimentService.listExperiments("game_1", null, "prod", "running", 0, 50)).thenReturn(paged);
        lenient().when(experimentService.listExperiments(null, null, null, null, 2, 10)).thenReturn(paged);

        assertSame(paged, apiController.listExperiments("game_1", null, "prod", "running", null, null));
        assertSame(paged, apiController.listExperiments(null, null, null, null, 2, 10));
    }

    @Test
    @DisplayName("createExperiment/getExperiment：委托与 200/404")
    void experimentCreateAndGet() {
        ExperimentDTO in = expDto(null);
        ExperimentDTO out = expDto("exp_1");
        lenient().when(experimentService.createExperiment(in)).thenReturn(out);
        lenient().when(experimentService.getExperiment("exp_1")).thenReturn(Optional.of(out));
        lenient().when(experimentService.getExperiment("exp_x")).thenReturn(Optional.empty());

        assertSame(out, apiController.createExperiment(in));
        assertSame(out, apiController.getExperiment("exp_1").getBody());
        assertEquals(404, apiController.getExperiment("exp_x").getStatusCode().value());
    }

    @Test
    @DisplayName("updateExperiment/publishExperiment/pauseExperiment：委托并回包 200")
    void experimentUpdatePublishPause() {
        ExperimentDTO in = expDto("exp_1");
        ExperimentDTO updated = expDto("exp_1");
        ExperimentDTO published = expDto("exp_1");
        published.status = "running";
        ExperimentDTO paused = expDto("exp_1");
        paused.status = "paused";
        lenient().when(experimentService.updateExperiment("exp_1", in)).thenReturn(updated);
        lenient().when(experimentService.publishExperiment("exp_1")).thenReturn(published);
        lenient().when(experimentService.pauseExperiment("exp_1")).thenReturn(paused);

        assertSame(updated, apiController.updateExperiment("exp_1", in).getBody());
        assertEquals("running", apiController.publishExperiment("exp_1").getBody().status);
        assertEquals("paused", apiController.pauseExperiment("exp_1").getBody().status);
    }

    @Test
    @DisplayName("assignExperiment：分配成功 assigned=true，非 running 兜底 control")
    void assignExperimentVariants() {
        lenient().when(experimentService.assign("exp_1", "user_1")).thenReturn("treatment");
        lenient().when(experimentService.assign("exp_1", "user_2")).thenReturn(null);

        Map<String, Object> hit = apiController.assignExperiment("exp_1", "user_1").getBody();
        assertEquals("treatment", hit.get("variant"));
        assertEquals(true, hit.get("assigned"));
        assertEquals("exp_1", hit.get("experimentId"));
        assertEquals("user_1", hit.get("subjectId"));

        Map<String, Object> fallback = apiController.assignExperiment("exp_1", "user_2").getBody();
        assertEquals("control", fallback.get("variant"));
        assertEquals(false, fallback.get("assigned"));
    }

    @Test
    @DisplayName("deleteExperiment：成功 200 / 不存在 404")
    void deleteExperimentResultMapping() {
        lenient().when(experimentService.deleteExperiment("exp_1")).thenReturn(true);
        lenient().when(experimentService.deleteExperiment("exp_x")).thenReturn(false);

        var ok = apiController.deleteExperiment("exp_1");
        assertEquals(200, ok.getStatusCode().value());
        assertEquals(true, ok.getBody().get("deleted"));
        assertEquals(404, apiController.deleteExperiment("exp_x").getStatusCode().value());
    }

    @Test
    @DisplayName("getExperimentConfig：委托 getRunningConfig")
    void getExperimentConfigDelegates() {
        ExperimentConfigDTO cfg = new ExperimentConfigDTO();
        cfg.id = "exp_1";
        cfg.salt = "exp_1";
        List<ExperimentConfigDTO> configs = List.of(cfg);
        lenient().when(experimentService.getRunningConfig("game_1", "prod")).thenReturn(configs);

        assertEquals(configs, apiController.getExperimentConfig("game_1", "prod"));
    }

    // =====================================================================
    // ApiController：StorageProfile CRUD
    // =====================================================================

    @Test
    @DisplayName("listStorageProfiles/getStorageProfile：委托与 200/404")
    void storageProfileListAndGet() {
        List<StorageProfileDTO> list = List.of(spDto("sp_1"));
        lenient().when(storageProfileService.getStorageProfiles()).thenReturn(list);
        lenient().when(storageProfileService.getStorageProfile("sp_1")).thenReturn(Optional.of(spDto("sp_1")));
        lenient().when(storageProfileService.getStorageProfile("sp_x")).thenReturn(Optional.empty());

        assertEquals(list, apiController.listStorageProfiles());
        assertEquals("sp_1", apiController.getStorageProfile("sp_1").getBody().id);
        assertEquals(404, apiController.getStorageProfile("sp_x").getStatusCode().value());
    }

    @Test
    @DisplayName("createStorageProfile：委托")
    void createStorageProfileDelegates() {
        StorageProfileDTO in = spDto(null);
        StorageProfileDTO out = spDto("sp_1");
        lenient().when(storageProfileService.createStorageProfile(in)).thenReturn(out);

        assertSame(out, apiController.createStorageProfile(in));
    }

    @Test
    @DisplayName("updateStorageProfile：成功 200 / not found 前缀 IAE 转 404 / 其他 IAE 透传")
    void updateStorageProfileExceptionMapping() {
        StorageProfileDTO in = spDto("sp_1");
        lenient().when(storageProfileService.updateStorageProfile("sp_1", in)).thenReturn(spDto("sp_1"));
        lenient().when(storageProfileService.updateStorageProfile(eq("sp_x"), any(StorageProfileDTO.class)))
            .thenThrow(new IllegalArgumentException("Storage profile not found: sp_x"));
        lenient().when(storageProfileService.updateStorageProfile(eq("sp_dup"), any(StorageProfileDTO.class)))
            .thenThrow(new IllegalArgumentException("Storage profile name already exists: other"));

        assertEquals(200, apiController.updateStorageProfile("sp_1", in).getStatusCode().value());
        assertEquals(404, apiController.updateStorageProfile("sp_x", in).getStatusCode().value());
        assertThrows(IllegalArgumentException.class,
            () -> apiController.updateStorageProfile("sp_dup", in));
    }

    @Test
    @DisplayName("deleteStorageProfile：成功 200 / IAE 转 404 / 占用 ISE 转 409")
    void deleteStorageProfileExceptionMapping() {
        lenient().doThrow(new IllegalArgumentException("Storage profile not found: sp_x"))
            .when(storageProfileService).deleteStorageProfile("sp_x");
        lenient().doThrow(new IllegalStateException("It is currently in use by 1 environment(s)"))
            .when(storageProfileService).deleteStorageProfile("sp_busy");

        assertEquals(200, apiController.deleteStorageProfile("sp_1").getStatusCode().value());
        assertEquals(true, apiController.deleteStorageProfile("sp_1").getBody().get("deleted"));

        assertEquals(404, apiController.deleteStorageProfile("sp_x").getStatusCode().value());

        var conflict = apiController.deleteStorageProfile("sp_busy");
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals("sp_busy", conflict.getBody().get("profileId"));
        assertNotNull(conflict.getBody().get("error"));
    }

    // =====================================================================
    // ApiController：Key 主路由与 legacy api-keys 路由
    // =====================================================================

    @Test
    @DisplayName("createKey/getKey：委托与 200/404")
    void keyCreateAndGet() {
        Models.CreateKeyReq req = new Models.CreateKeyReq();
        req.gameId = "game_1";
        req.environmentId = "env_1";
        req.name = "client-key";
        req.keyRole = "client";
        Models.ApiKeyResp resp = new Models.ApiKeyResp();
        resp.apiKey = "pk_1";
        lenient().when(svc.createKey("game_1", "env_1", "client-key", "client")).thenReturn(resp);
        assertSame(resp, apiController.createKey(req));

        // legacy /api-keys 创建路由复用同一委托
        assertSame(resp, apiController.createApiKey(req));

        Models.KeyDetailResp detail = new Models.KeyDetailResp();
        detail.apiKey = "pk_1";
        lenient().when(svc.getKey("pk_1")).thenReturn(detail);
        lenient().when(svc.getKey("pk_x")).thenReturn(null);

        assertSame(detail, apiController.getKey("pk_1").getBody());
        assertEquals(404, apiController.getKey("pk_x").getStatusCode().value());
    }

    @Test
    @DisplayName("updatePolicy：请求字段组装后委托，null 转 404")
    void updatePolicyDelegates() {
        ApiController.UpdatePolicyReq req = new ApiController.UpdatePolicyReq();
        req.rpm = 100;
        req.ipRpm = 10;
        req.propsAllowlist = List.of("level", "gold");
        req.piiEmail = "mask";
        req.piiPhone = "drop";
        req.piiIp = "coarse";
        req.denyKeys = List.of("secret");
        req.maskKeys = List.of("email");
        Models.KeyDetailResp out = new Models.KeyDetailResp();
        out.apiKey = "pk_1";
        lenient().when(svc.updatePolicy(eq("pk_1"), any(Models.KeyDetailResp.class))).thenReturn(out);
        lenient().when(svc.updatePolicy(eq("pk_x"), any(Models.KeyDetailResp.class))).thenReturn(null);

        var resp = apiController.updatePolicy("pk_1", req);
        assertEquals(200, resp.getStatusCode().value());

        ArgumentCaptor<Models.KeyDetailResp> captor = ArgumentCaptor.forClass(Models.KeyDetailResp.class);
        verify(svc).updatePolicy(eq("pk_1"), captor.capture());
        Models.KeyDetailResp passed = captor.getValue();
        assertEquals(100, passed.rpm);
        assertEquals(10, passed.ipRpm);
        assertEquals(List.of("level", "gold"), passed.propsAllowlist);
        assertEquals("mask", passed.piiEmail);
        assertEquals("drop", passed.piiPhone);
        assertEquals("coarse", passed.piiIp);
        assertEquals(List.of("secret"), passed.denyKeys);
        assertEquals(List.of("email"), passed.maskKeys);

        assertEquals(404, apiController.updatePolicy("pk_x", req).getStatusCode().value());
    }

    @Test
    @DisplayName("legacy updateApiKey：复用 updatePolicy 委托")
    void legacyUpdateApiKeyDelegates() {
        ApiController.UpdatePolicyReq req = new ApiController.UpdatePolicyReq();
        req.rpm = 60;
        Models.KeyDetailResp out = new Models.KeyDetailResp();
        out.apiKey = "pk_1";
        lenient().when(svc.updatePolicy(eq("pk_1"), any(Models.KeyDetailResp.class))).thenReturn(out);

        assertEquals(200, apiController.updateApiKey("pk_1", req).getStatusCode().value());
        verify(svc).updatePolicy(eq("pk_1"), any(Models.KeyDetailResp.class));
    }

    @Test
    @DisplayName("listKeys/listApiKeys：无过滤全量，带参分页搜索")
    void listKeysRoutes() {
        List<Models.KeyDetailResp> all = List.of(new Models.KeyDetailResp());
        ControlService.Paged<Models.KeyDetailResp> paged = new ControlService.Paged<>(all, 1L);
        lenient().when(svc.listKeys()).thenReturn(all);
        lenient().when(svc.searchKeys(null, null, "demo", 1, 10)).thenReturn(paged);

        assertSame(all, apiController.listKeys(null, null, null, null, null));
        assertSame(all, apiController.listApiKeys(null, null, null, null, null));
        assertSame(paged, apiController.listApiKeys("demo", null, null, 1, 10));
    }

    @Test
    @DisplayName("deleteKey/deleteApiKey：true 转 200 / false 转 404")
    void deleteKeyRoutes() {
        lenient().when(svc.deleteKey("pk_1")).thenReturn(true);
        lenient().when(svc.deleteKey("pk_x")).thenReturn(false);

        var ok = apiController.deleteKey("pk_1");
        assertEquals(200, ok.getStatusCode().value());
        assertEquals(true, ok.getBody().get("deleted"));

        assertEquals(404, apiController.deleteApiKey("pk_x").getStatusCode().value());
        assertEquals(200, apiController.deleteApiKey("pk_1").getStatusCode().value());
    }

    @Test
    @DisplayName("deleteKeys：批量删除返回计数")
    void batchDeleteKeysReturnsCount() {
        List<String> keys = List.of("pk_1", "pk_2");
        lenient().when(svc.deleteKeys(keys)).thenReturn(2L);

        ApiController.BatchDeleteReq req = new ApiController.BatchDeleteReq();
        req.apiKeys = keys;

        var resp = apiController.deleteKeys(req);
        assertEquals(200, resp.getStatusCode().value());
        assertEquals(2L, resp.getBody().get("deleted"));
        verify(svc).deleteKeys(keys);
    }

    // =====================================================================
    // ExperimentResultsController：ingestMetrics
    // =====================================================================

    private static ExperimentEntity experiment(String id) {
        ExperimentEntity e = new ExperimentEntity();
        e.id = id;
        e.status = "running";
        return e;
    }

    private static ExperimentResultsController.MetricSnapshotReq snapshot(String metricName,
                                                                          String variant,
                                                                          Long windowStart,
                                                                          Long count,
                                                                          Double sum,
                                                                          Long successes) {
        ExperimentResultsController.MetricSnapshotReq s = new ExperimentResultsController.MetricSnapshotReq();
        s.metricName = metricName;
        s.variant = variant;
        s.windowStart = windowStart;
        s.count = count;
        s.sum = sum;
        s.sumSquares = null;
        s.successes = successes;
        return s;
    }

    private static ExperimentResultsController.MetricsBatchReq batch(
        ExperimentResultsController.MetricSnapshotReq... snapshots) {
        ExperimentResultsController.MetricsBatchReq req = new ExperimentResultsController.MetricsBatchReq();
        req.snapshots = List.of(snapshots);
        return req;
    }

    @Test
    @DisplayName("ingestMetrics：新窗口生成 ems_ 前缀 ID，字段回填并钳制 successes")
    void ingestMetricsBackfillsNewSnapshots() {
        lenient().when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(experiment("exp_1")));
        lenient().when(snapshotRepo.findByExperimentIdAndMetricNameAndVariantAndWindowStart(
            "exp_1", "purchase", "control", 1000L)).thenReturn(Optional.empty());
        lenient().when(snapshotRepo.findByExperimentIdAndMetricNameAndVariantAndWindowStart(
            "exp_1", "purchase", "treatment", 1000L)).thenReturn(Optional.empty());
        lenient().when(snapshotRepo.save(any(ExperimentMetricSnapshotEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        // count=100, successes=200 越界应钳制为 100；sum 缺省回填为 count
        var resp = resultsController.ingestMetrics("exp_1", batch(
            snapshot("purchase", "control", 1000L, 100L, null, 200L),
            snapshot("purchase", "treatment", 1000L, 80L, 120.0, 40L)));

        assertEquals(200, resp.getStatusCode().value());
        assertEquals("exp_1", resp.getBody().get("experimentId"));
        assertEquals(2, resp.getBody().get("ingested"));

        ArgumentCaptor<ExperimentMetricSnapshotEntity> captor =
            ArgumentCaptor.forClass(ExperimentMetricSnapshotEntity.class);
        verify(snapshotRepo, org.mockito.Mockito.times(2)).save(captor.capture());

        ExperimentMetricSnapshotEntity control = captor.getAllValues().get(0);
        assertTrue(control.id.startsWith("ems_"));
        assertEquals("exp_1", control.experimentId);
        assertEquals("purchase", control.metricName);
        assertEquals("control", control.variant);
        assertEquals(1000L, control.windowStart);
        assertEquals(100L, control.count);
        assertEquals(100.0, control.sum, 1e-9);   // sum 为 null 时回填 count
        assertEquals(0.0, control.sumSquares, 1e-9);
        assertEquals(100L, control.successes);     // successes > count 被钳制

        ExperimentMetricSnapshotEntity treatment = captor.getAllValues().get(1);
        assertEquals(80L, treatment.count);
        assertEquals(120.0, treatment.sum, 1e-9);
        assertEquals(40L, treatment.successes);
    }

    @Test
    @DisplayName("ingestMetrics：同 (metric,variant,window) 已存在时幂等覆盖，不重建 ID")
    void ingestMetricsOverwritesExistingWindowIdempotently() {
        lenient().when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(experiment("exp_1")));
        ExperimentMetricSnapshotEntity existing = new ExperimentMetricSnapshotEntity();
        existing.id = "ems_existing";
        existing.experimentId = "exp_1";
        existing.metricName = "purchase";
        existing.variant = "control";
        existing.windowStart = 1000L;
        existing.count = 50L;
        existing.sum = 50.0;
        lenient().when(snapshotRepo.findByExperimentIdAndMetricNameAndVariantAndWindowStart(
            "exp_1", "purchase", "control", 1000L)).thenReturn(Optional.of(existing));

        // windowStart 为 null 时按 0 处理 → 与既有窗口(1000)不同，这里用同窗口显式传值
        var resp = resultsController.ingestMetrics("exp_1",
            batch(snapshot("purchase", "control", 1000L, 90L, 90.0, 30L)));

        assertEquals(1, resp.getBody().get("ingested"));
        verify(snapshotRepo).save(existing);   // 复用同一实体覆盖
        assertEquals("ems_existing", existing.id);
        assertEquals(90L, existing.count);
        assertEquals(90.0, existing.sum, 1e-9);
        assertEquals(30L, existing.successes);
    }

    @Test
    @DisplayName("ingestMetrics：windowStart 缺省按 0 查询并回填")
    void ingestMetricsDefaultsWindowStartToZero() {
        lenient().when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(experiment("exp_1")));
        lenient().when(snapshotRepo.findByExperimentIdAndMetricNameAndVariantAndWindowStart(
            "exp_1", "purchase", "control", 0L)).thenReturn(Optional.empty());

        resultsController.ingestMetrics("exp_1",
            batch(snapshot("purchase", "control", null, 10L, null, null)));

        verify(snapshotRepo).findByExperimentIdAndMetricNameAndVariantAndWindowStart(
            "exp_1", "purchase", "control", 0L);
        ArgumentCaptor<ExperimentMetricSnapshotEntity> captor =
            ArgumentCaptor.forClass(ExperimentMetricSnapshotEntity.class);
        verify(snapshotRepo).save(captor.capture());
        assertEquals(0L, captor.getValue().windowStart);
        assertEquals(10L, captor.getValue().count);
        assertEquals(10.0, captor.getValue().sum, 1e-9);
        assertEquals(0L, captor.getValue().successes);
    }

    @Test
    @DisplayName("ingestMetrics：metricName 空/空白被拒绝")
    void ingestMetricsRejectsBlankMetricName() {
        lenient().when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(experiment("exp_1")));

        assertThrows(IllegalArgumentException.class, () -> resultsController.ingestMetrics("exp_1",
            batch(snapshot(null, "control", 1000L, 10L, null, null))));
        assertThrows(IllegalArgumentException.class, () -> resultsController.ingestMetrics("exp_1",
            batch(snapshot("  ", "control", 1000L, 10L, null, null))));
        verify(snapshotRepo, never()).save(any(ExperimentMetricSnapshotEntity.class));
    }

    @Test
    @DisplayName("ingestMetrics：variant 空被拒绝")
    void ingestMetricsRejectsBlankVariant() {
        lenient().when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(experiment("exp_1")));

        assertThrows(IllegalArgumentException.class, () -> resultsController.ingestMetrics("exp_1",
            batch(snapshot("purchase", null, 1000L, 10L, null, null))));
        assertThrows(IllegalArgumentException.class, () -> resultsController.ingestMetrics("exp_1",
            batch(snapshot("purchase", "", 1000L, 10L, null, null))));
        verify(snapshotRepo, never()).save(any(ExperimentMetricSnapshotEntity.class));
    }

    @Test
    @DisplayName("ingestMetrics：snapshots 为 null 或空列表被拒绝")
    void ingestMetricsRejectsEmptySnapshots() {
        lenient().when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(experiment("exp_1")));

        ExperimentResultsController.MetricsBatchReq nullReq = new ExperimentResultsController.MetricsBatchReq();
        nullReq.snapshots = null;
        assertThrows(IllegalArgumentException.class, () -> resultsController.ingestMetrics("exp_1", nullReq));

        assertThrows(IllegalArgumentException.class,
            () -> resultsController.ingestMetrics("exp_1", batch()));
        verify(snapshotRepo, never()).save(any(ExperimentMetricSnapshotEntity.class));
    }

    @Test
    @DisplayName("ingestMetrics：实验不存在抛 IAE")
    void ingestMetricsRejectsUnknownExperiment() {
        lenient().when(experimentRepo.findById("nope")).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> resultsController.ingestMetrics("nope", batch(
                snapshot("purchase", "control", 1000L, 10L, null, null))));
        assertTrue(ex.getMessage().contains("Experiment not found"));
        verifyNoInteractions(snapshotRepo);
    }

    // =====================================================================
    // AnnouncementService：update / schedule / offline / delete
    // =====================================================================

    @Test
    @DisplayName("update：DRAFT 应用字段并可转为 SCHEDULED")
    void updateAppliesFieldsAndSchedules() {
        AnnouncementEntity existing = announcement("ann_u1", AnnouncementEntity.Status.DRAFT);
        lenient().when(announcementRepo.findById("ann_u1")).thenReturn(Optional.of(existing));
        lenient().when(announcementRepo.save(any(AnnouncementEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        AnnouncementEntity req = new AnnouncementEntity();
        req.title = "  新标题  ";
        req.content = "新内容";
        req.channel = AnnouncementEntity.Channel.MARQUEE;
        req.priority = 7;
        req.scheduledAt = LocalDateTime.now().plusDays(1);
        req.autoOfflineAt = LocalDateTime.now().plusDays(2);

        AnnouncementEntity saved = announcementService.update("ann_u1", req, "op_1");

        assertEquals("新标题", saved.title);   // trim
        assertEquals("新内容", saved.content);
        assertEquals(AnnouncementEntity.Channel.MARQUEE, saved.channel);
        assertEquals(7, saved.priority);
        assertEquals(AnnouncementEntity.Status.SCHEDULED, saved.status);
        assertEquals(req.scheduledAt, saved.scheduledAt);
        assertEquals(req.autoOfflineAt, saved.autoOfflineAt);
        verify(announcementRepo).save(existing);
    }

    @Test
    @DisplayName("update：空白 title/content 被忽略，其余字段保留")
    void updateIgnoresBlankFields() {
        AnnouncementEntity existing = announcement("ann_u2", AnnouncementEntity.Status.DRAFT);
        lenient().when(announcementRepo.findById("ann_u2")).thenReturn(Optional.of(existing));
        lenient().when(announcementRepo.save(any(AnnouncementEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        AnnouncementEntity req = new AnnouncementEntity();
        req.title = "   ";
        req.content = null;
        req.priority = 3;

        AnnouncementEntity saved = announcementService.update("ann_u2", req, "op_1");

        assertEquals("标题", saved.title);
        assertEquals("内容", saved.content);
        assertEquals(3, saved.priority);
        assertEquals(AnnouncementEntity.Status.DRAFT, saved.status);
    }

    @Test
    @DisplayName("update：PUBLISHED/OFFLINE 不可编辑")
    void updateRejectsPublishedAndOffline() {
        AnnouncementEntity published = announcement("ann_p", AnnouncementEntity.Status.PUBLISHED);
        AnnouncementEntity offline = announcement("ann_o", AnnouncementEntity.Status.OFFLINE);
        lenient().when(announcementRepo.findById("ann_p")).thenReturn(Optional.of(published));
        lenient().when(announcementRepo.findById("ann_o")).thenReturn(Optional.of(offline));

        assertThrows(IllegalStateException.class,
            () -> announcementService.update("ann_p", new AnnouncementEntity(), "op_1"));
        assertThrows(IllegalStateException.class,
            () -> announcementService.update("ann_o", new AnnouncementEntity(), "op_1"));
        verify(announcementRepo, never()).save(any(AnnouncementEntity.class));
    }

    @Test
    @DisplayName("update：排期时间为过去被拒绝")
    void updateRejectsPastScheduledAt() {
        AnnouncementEntity existing = announcement("ann_u3", AnnouncementEntity.Status.DRAFT);
        lenient().when(announcementRepo.findById("ann_u3")).thenReturn(Optional.of(existing));

        AnnouncementEntity req = new AnnouncementEntity();
        req.scheduledAt = LocalDateTime.now().minusMinutes(1);

        assertThrows(IllegalArgumentException.class,
            () -> announcementService.update("ann_u3", req, "op_1"));
        verify(announcementRepo, never()).save(any(AnnouncementEntity.class));
    }

    @Test
    @DisplayName("update：公告不存在抛 IAE")
    void updateRejectsUnknownAnnouncement() {
        lenient().when(announcementRepo.findById("ann_none")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
            () -> announcementService.update("ann_none", new AnnouncementEntity(), "op_1"));
    }

    @Test
    @DisplayName("schedule：null/过去时间被拒绝")
    void scheduleRejectsInvalidTime() {
        assertThrows(IllegalArgumentException.class,
            () -> announcementService.schedule("ann_s1", null, "op_1"));
        assertThrows(IllegalArgumentException.class,
            () -> announcementService.schedule("ann_s1", LocalDateTime.now().minusMinutes(1), "op_1"));
        verifyNoInteractions(announcementRepo);
    }

    @Test
    @DisplayName("schedule：PUBLISHED/OFFLINE 不可排期")
    void scheduleRejectsPublishedAndOffline() {
        AnnouncementEntity published = announcement("ann_sp", AnnouncementEntity.Status.PUBLISHED);
        AnnouncementEntity offline = announcement("ann_so", AnnouncementEntity.Status.OFFLINE);
        lenient().when(announcementRepo.findById("ann_sp")).thenReturn(Optional.of(published));
        lenient().when(announcementRepo.findById("ann_so")).thenReturn(Optional.of(offline));
        LocalDateTime at = LocalDateTime.now().plusHours(1);

        assertThrows(IllegalStateException.class,
            () -> announcementService.schedule("ann_sp", at, "op_1"));
        assertThrows(IllegalStateException.class,
            () -> announcementService.schedule("ann_so", at, "op_1"));
        verify(announcementRepo, never()).save(any(AnnouncementEntity.class));
    }

    @Test
    @DisplayName("schedule：DRAFT 排期转 SCHEDULED，已排期可改期")
    void scheduleDraftAndReschedule() {
        AnnouncementEntity existing = announcement("ann_s2", AnnouncementEntity.Status.DRAFT);
        lenient().when(announcementRepo.findById("ann_s2")).thenReturn(Optional.of(existing));
        lenient().when(announcementRepo.save(any(AnnouncementEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        LocalDateTime first = LocalDateTime.now().plusHours(1);
        AnnouncementEntity scheduled = announcementService.schedule("ann_s2", first, "op_1");
        assertEquals(AnnouncementEntity.Status.SCHEDULED, scheduled.status);
        assertEquals(first, scheduled.scheduledAt);

        LocalDateTime second = LocalDateTime.now().plusHours(3);
        AnnouncementEntity rescheduled = announcementService.schedule("ann_s2", second, "op_1");
        assertEquals(AnnouncementEntity.Status.SCHEDULED, rescheduled.status);
        assertEquals(second, rescheduled.scheduledAt);
    }

    @Test
    @DisplayName("offline：PUBLISHED 转 OFFLINE；不存在抛 IAE")
    void offlineTransitionsAndRejectsUnknown() {
        AnnouncementEntity published = announcement("ann_off", AnnouncementEntity.Status.PUBLISHED);
        lenient().when(announcementRepo.findById("ann_off")).thenReturn(Optional.of(published));
        lenient().when(announcementRepo.save(any(AnnouncementEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        AnnouncementEntity offlined = announcementService.offline("ann_off", "op_1");
        assertEquals(AnnouncementEntity.Status.OFFLINE, offlined.status);
        assertNotNull(offlined.offlineAt);
        verify(announcementRepo).save(published);

        lenient().when(announcementRepo.findById("ann_none")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> announcementService.offline("ann_none", "op_1"));
    }

    @Test
    @DisplayName("delete：不存在或已软删返回 false")
    void deleteUnknownOrSoftDeletedReturnsFalse() {
        lenient().when(announcementRepo.findById("ann_none")).thenReturn(Optional.empty());
        AnnouncementEntity deleted = announcement("ann_gone", AnnouncementEntity.Status.DRAFT);
        deleted.deletedAt = LocalDateTime.now();
        lenient().when(announcementRepo.findById("ann_gone")).thenReturn(Optional.of(deleted));

        assertFalse(announcementService.delete("ann_none", "op_1"));
        assertFalse(announcementService.delete("ann_gone", "op_1"));
        verify(announcementRepo, never()).save(any(AnnouncementEntity.class));
    }

    @Test
    @DisplayName("delete：SCHEDULED 必须先下线")
    void deleteRejectsScheduled() {
        AnnouncementEntity scheduled = announcement("ann_sd", AnnouncementEntity.Status.SCHEDULED);
        lenient().when(announcementRepo.findById("ann_sd")).thenReturn(Optional.of(scheduled));

        assertThrows(IllegalStateException.class, () -> announcementService.delete("ann_sd", "op_1"));
        verify(announcementRepo, never()).save(any(AnnouncementEntity.class));
    }

    @Test
    @DisplayName("delete：DRAFT/OFFLINE 软删除成功并审计")
    void deleteDraftAndOfflineSucceed() {
        AnnouncementEntity draft = announcement("ann_d1", AnnouncementEntity.Status.DRAFT);
        AnnouncementEntity offline = announcement("ann_d2", AnnouncementEntity.Status.OFFLINE);
        lenient().when(announcementRepo.findById("ann_d1")).thenReturn(Optional.of(draft));
        lenient().when(announcementRepo.findById("ann_d2")).thenReturn(Optional.of(offline));

        assertTrue(announcementService.delete("ann_d1", "op_1"));
        assertNotNull(draft.deletedAt);
        assertTrue(announcementService.delete("ann_d2", "op_1"));
        assertNotNull(offline.deletedAt);
        verify(announcementRepo).save(draft);
        verify(announcementRepo).save(offline);
        verify(auditLog, org.mockito.Mockito.times(2))
            .logDelete(eq("announcement"), any(), any(), eq("op_1"), eq("op_1"), any());
    }
}
