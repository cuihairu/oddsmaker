package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.DimensionSyncStatusEntity;
import io.oddsmaker.control.jpa.DimensionSyncStatusRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 维度同步状态服务测试：Agent 心跳 upsert（新建/合并）、必填校验、游戏存在性校验、
 * lastEventTs 毫秒→本地时间换算、两口径延迟投影。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("维度同步状态服务测试")
class DimensionSyncStatusServiceTest {

    @Mock
    private DimensionSyncStatusRepo statusRepo;

    @Mock
    private GameRepo gameRepo;

    private DimensionSyncStatusService service;

    @BeforeEach
    void setUp() {
        service = new DimensionSyncStatusService(statusRepo, gameRepo);
    }

    private GameEntity game() {
        GameEntity g = new GameEntity();
        g.id = "g1";
        return g;
    }

    private DimensionSyncStatusService.StatusUpsert upsert() {
        DimensionSyncStatusService.StatusUpsert req = new DimensionSyncStatusService.StatusUpsert();
        req.gameId = "g1";
        req.environment = "prod";
        req.sourceKey = "agent-mysql-main";
        return req;
    }

    private DimensionSyncStatusEntity existing() {
        DimensionSyncStatusEntity e = new DimensionSyncStatusEntity();
        e.id = "dss_exists";
        e.gameId = "g1";
        e.environment = "prod";
        e.sourceKey = "agent-mysql-main";
        e.sourceType = "mysql";
        e.cursor = "n:100";
        e.pushedCount = 10L;
        e.errorCount = 0L;
        e.createdAt = LocalDateTime.now().minusHours(1);
        return e;
    }

    @Test
    @DisplayName("upsert：不存在则新建（dss_ 前缀 id、sourceType 落库、心跳 lastPushAt 刷新）")
    void upsertCreatesNewStatus() {
        when(gameRepo.findById("g1")).thenReturn(Optional.of(game()));
        when(statusRepo.findByGameIdAndEnvironmentAndSourceKey("g1", "prod", "agent-mysql-main"))
                .thenReturn(Optional.empty());
        when(statusRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DimensionSyncStatusService.StatusUpsert req = upsert();
        req.sourceType = "mysql";
        req.cursor = "n:123";
        req.lastEventTs = System.currentTimeMillis();
        req.pushedCount = 5L;
        req.errorCount = 0L;

        DimensionSyncStatusEntity saved = service.upsert(req);

        assertTrue(saved.id.startsWith("dss_"));
        assertEquals("g1", saved.gameId);
        assertEquals("prod", saved.environment);
        assertEquals("agent-mysql-main", saved.sourceKey);
        assertEquals("mysql", saved.sourceType);
        assertEquals("n:123", saved.cursor);
        assertEquals(5L, saved.pushedCount);
        assertNotNull(saved.lastPushAt);
        assertNotNull(saved.createdAt);
    }

    @Test
    @DisplayName("upsert：已存在则合并（最后写入胜出），心跳刷新 lastPushAt")
    void upsertMergesIntoExisting() {
        when(gameRepo.findById("g1")).thenReturn(Optional.of(game()));
        when(statusRepo.findByGameIdAndEnvironmentAndSourceKey("g1", "prod", "agent-mysql-main"))
                .thenReturn(Optional.of(existing()));
        when(statusRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DimensionSyncStatusService.StatusUpsert req = upsert();
        req.cursor = "n:200";
        req.pushedCount = 42L;
        req.errorCount = 1L;
        req.lastError = "once failed";
        req.lastEventTs = System.currentTimeMillis();

        DimensionSyncStatusEntity saved = service.upsert(req);

        assertEquals("dss_exists", saved.id);          // 复用既有行
        assertEquals("n:200", saved.cursor);
        assertEquals(42L, saved.pushedCount);
        assertEquals(1L, saved.errorCount);
        assertEquals("once failed", saved.lastError);
        assertNotNull(saved.lastPushAt);
    }

    @Test
    @DisplayName("upsert：成功心跳清除 lastError；非法负计数忽略")
    void upsertClearsLastErrorAndIgnoresNegativeCounts() {
        when(gameRepo.findById("g1")).thenReturn(Optional.of(game()));
        when(statusRepo.findByGameIdAndEnvironmentAndSourceKey("g1", "prod", "agent-mysql-main"))
                .thenReturn(Optional.of(existing()));
        when(statusRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DimensionSyncStatusService.StatusUpsert req = upsert();
        req.pushedCount = -5L;   // 非法：忽略
        req.errorCount = -1L;    // 非法：忽略

        DimensionSyncStatusEntity saved = service.upsert(req);
        assertNull(saved.lastError);
        assertEquals(10L, saved.pushedCount);   // 保持既有
        assertEquals(0L, saved.errorCount);
    }

    @Test
    @DisplayName("upsert：lastEventTs（epoch millis）换算为本地时间，误差秒级")
    void upsertConvertsLastEventTs() {
        when(gameRepo.findById("g1")).thenReturn(Optional.of(game()));
        when(statusRepo.findByGameIdAndEnvironmentAndSourceKey("g1", "prod", "agent-mysql-main"))
                .thenReturn(Optional.empty());
        when(statusRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        long fiveMinutesAgo = System.currentTimeMillis() - 300_000L;
        DimensionSyncStatusService.StatusUpsert req = upsert();
        req.lastEventTs = fiveMinutesAgo;

        DimensionSyncStatusEntity saved = service.upsert(req);
        long lagSeconds = Duration.between(saved.lastEventTs, LocalDateTime.now()).getSeconds();
        assertTrue(lagSeconds >= 295 && lagSeconds <= 310, "lag=" + lagSeconds);
    }

    @Test
    @DisplayName("upsert：lastEventTs<=0 与 null 不更新打点")
    void upsertIgnoresNonPositiveLastEventTs() {
        when(gameRepo.findById("g1")).thenReturn(Optional.of(game()));
        when(statusRepo.findByGameIdAndEnvironmentAndSourceKey("g1", "prod", "agent-mysql-main"))
                .thenReturn(Optional.of(existing()));
        when(statusRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DimensionSyncStatusService.StatusUpsert req = upsert();
        req.lastEventTs = 0L;
        assertNull(service.upsert(req).lastEventTs);

        req.lastEventTs = null;
        assertNull(service.upsert(req).lastEventTs);
    }

    @Test
    @DisplayName("upsert：必填校验（gameId/environment/sourceKey）")
    void upsertRequiresFields() {
        assertThrows(IllegalArgumentException.class, () -> service.upsert(new DimensionSyncStatusService.StatusUpsert()));

        DimensionSyncStatusService.StatusUpsert noEnv = upsert();
        noEnv.environment = " ";
        assertThrows(IllegalArgumentException.class, () -> service.upsert(noEnv));

        DimensionSyncStatusService.StatusUpsert noKey = upsert();
        noKey.sourceKey = null;
        assertThrows(IllegalArgumentException.class, () -> service.upsert(noKey));
    }

    @Test
    @DisplayName("upsert：游戏不存在或已删除拒绝")
    void upsertRejectsUnknownOrDeletedGame() {
        when(gameRepo.findById("ghost")).thenReturn(Optional.empty());
        when(gameRepo.findById("g1")).thenReturn(Optional.of(game()));

        DimensionSyncStatusService.StatusUpsert missing = upsert();
        missing.gameId = "ghost";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.upsert(missing));
        assertTrue(ex.getMessage().contains("ghost"));

        DimensionSyncStatusService.StatusUpsert deleted = upsert();
        GameEntity removed = game();
        removed.deletedAt = LocalDateTime.now().minusDays(1);
        when(gameRepo.findById("g1")).thenReturn(Optional.of(removed));
        assertThrows(IllegalArgumentException.class, () -> service.upsert(deleted));
    }

    @Test
    @DisplayName("list：env 非空走组合查询，否则全量按游戏")
    void listFiltersByEnvironment() {
        when(statusRepo.findByGameIdAndEnvironment("g1", "prod")).thenReturn(List.of(existing()));
        when(statusRepo.findByGameId("g1")).thenReturn(List.of(existing(), existing()));

        assertEquals(1, service.list("g1", "prod").size());
        assertEquals(2, service.list("g1", null).size());
        assertEquals(2, service.list("g1", " ").size());
    }

    @Test
    @DisplayName("toResp：两口径延迟（距上次推送/距源头最新变更），未打点为 null")
    void toRespExposesLagMetrics() {
        DimensionSyncStatusEntity e = existing();
        e.lastPushAt = LocalDateTime.now().minusSeconds(30);
        e.lastEventTs = LocalDateTime.now().minusMinutes(5);
        e.updatedAt = LocalDateTime.now().minusSeconds(1);

        var resp = service.toResp(e);
        assertEquals("dss_exists", resp.get("id"));
        long pushLag = (Long) resp.get("sinceLastPushSeconds");
        assertTrue(pushLag >= 25 && pushLag <= 60, "pushLag=" + pushLag);
        long eventLag = (Long) resp.get("sinceLastEventSeconds");
        assertTrue(eventLag >= 290 && eventLag <= 330, "eventLag=" + eventLag);

        DimensionSyncStatusEntity bare = new DimensionSyncStatusEntity();
        var bareResp = service.toResp(bare);
        assertNull(bareResp.get("sinceLastPushSeconds"));
        assertNull(bareResp.get("sinceLastEventSeconds"));
    }

    @Test
    @DisplayName("secondsSince：null 安全，未来时间钳到 0")
    void secondsSinceEdgeCases() {
        assertNull(DimensionSyncStatusService.secondsSince(null, LocalDateTime.now()));
        assertEquals(0L, DimensionSyncStatusService.secondsSince(
                LocalDateTime.now().plusSeconds(30), LocalDateTime.now()));
        assertEquals(10L, DimensionSyncStatusService.secondsSince(
                LocalDateTime.now().minusSeconds(10), LocalDateTime.now()));
    }
}
