package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.AnnouncementEntity;
import io.oddsmaker.control.jpa.MailEntity;
import io.oddsmaker.control.jpa.PlayerExportJobEntity;
import io.oddsmaker.control.jpa.RedeemCodeBatchEntity;
import io.oddsmaker.control.jpa.SymbolMappingEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.AnnouncementService;
import io.oddsmaker.control.service.MailService;
import io.oddsmaker.control.service.PlayerDataQueryService;
import io.oddsmaker.control.service.PlayerExportService;
import io.oddsmaker.control.service.RedeemCodeService;
import io.oddsmaker.control.jpa.SymbolMappingRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运营工具 Controller 测试：公告/邮件/兑换码/玩家数据查询/玩家导出/符号表。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("运营工具 Controller 测试")
class OpsControllersTest {

    @Mock
    private AccessGuard accessGuard;

    // ===== 公告 =====

    @Mock
    private AnnouncementService announcementService;

    @InjectMocks
    private AnnouncementController announcementController;

    @Test
    @DisplayName("公告：全端点委托与跨游戏 404")
    void announcementEndpoints() {
        AnnouncementEntity entity = new AnnouncementEntity();
        entity.id = "a1";
        entity.gameId = "g";
        lenient().when(announcementService.get("a1")).thenReturn(entity);
        lenient().when(announcementService.get("other")).thenReturn(null);
        lenient().when(announcementService.create(any(), anyString())).thenReturn(entity);
        lenient().when(announcementService.update(eq("a1"), any(), anyString())).thenReturn(entity);
        lenient().when(announcementService.publish(eq("a1"), anyString())).thenReturn(entity);
        lenient().when(announcementService.schedule(eq("a1"), any(), anyString())).thenReturn(entity);
        lenient().when(announcementService.offline(eq("a1"), anyString())).thenReturn(entity);
        lenient().when(announcementService.delete(eq("a1"), anyString())).thenReturn(true);

        assertEquals(200, announcementController.list("g").getStatusCode().value());
        assertEquals(200, announcementController.get("g", "a1").getStatusCode().value());
        assertEquals(404, announcementController.get("g", "other").getStatusCode().value());
        assertEquals(200, announcementController.create("g", new AnnouncementEntity()).getStatusCode().value());
        assertEquals(200, announcementController.update("g", "a1", new AnnouncementEntity()).getStatusCode().value());
        assertEquals(200, announcementController.publish("g", "a1").getStatusCode().value());
        AnnouncementController.ScheduleReq req = new AnnouncementController.ScheduleReq();
        req.scheduledAt = "2026-09-10T10:00:00";
        assertEquals(200, announcementController.schedule("g", "a1", req).getStatusCode().value());
        assertEquals(200, announcementController.offline("g", "a1").getStatusCode().value());
        assertEquals(200, announcementController.delete("g", "a1").getStatusCode().value());
        assertEquals(200, announcementController.listActive("g", null).getStatusCode().value());
        verify(announcementService).list("g");
    }

    // ===== 邮件 =====

    @Mock
    private MailService mailService;

    @InjectMocks
    private MailController mailController;

    @Test
    @DisplayName("邮件：全端点委托与跨游戏 404")
    void mailEndpoints() {
        MailEntity mail = new MailEntity();
        mail.id = "m1";
        mail.gameId = "g";
        lenient().when(mailService.get("m1")).thenReturn(mail);
        lenient().when(mailService.get("other")).thenReturn(null);
        lenient().when(mailService.create(any(), anyString())).thenReturn(mail);
        lenient().when(mailService.send(eq("m1"), anyString())).thenReturn(mail);
        lenient().when(mailService.delete(eq("m1"), anyString())).thenReturn(true);
        io.oddsmaker.control.jpa.MailClaimEntity claimT = new io.oddsmaker.control.jpa.MailClaimEntity();
        claimT.id = "mc_1";
        claimT.mailId = "m1";
        claimT.playerKey = "p1";
        claimT.claimedAt = java.time.LocalDateTime.now();
        lenient().when(mailService.claim(eq("m1"), eq("p1"))).thenReturn(claimT);

        assertEquals(200, mailController.list("g").getStatusCode().value());
        assertEquals(200, mailController.get("g", "m1").getStatusCode().value());
        assertEquals(404, mailController.get("g", "other").getStatusCode().value());
        assertEquals(200, mailController.create("g", new MailEntity()).getStatusCode().value());
        assertEquals(200, mailController.send("g", "m1").getStatusCode().value());
        assertEquals(200, mailController.delete("g", "m1").getStatusCode().value());
        assertEquals(200, mailController.inbox("g", "p1", null).getStatusCode().value());
        assertEquals(200, mailController.claim("m1", "g", "p1").getStatusCode().value());
    }

    // ===== 兑换码 =====

    @Mock
    private RedeemCodeService redeemCodeService;

    @InjectMocks
    private RedeemCodeController redeemCodeController;

    @Test
    @DisplayName("兑换码：运营端与游戏服端点委托")
    void redeemEndpoints() {
        RedeemCodeBatchEntity batch = new RedeemCodeBatchEntity();
        batch.id = "b1";
        batch.gameId = "g";
        lenient().when(redeemCodeService.getBatch("b1")).thenReturn(batch);
        lenient().when(redeemCodeService.getBatch("other")).thenReturn(null);
        lenient().when(redeemCodeService.createBatch(any(), any(), anyInt(), any(), anyString())).thenReturn(batch);
        lenient().when(redeemCodeService.disable(eq("b1"), anyString())).thenReturn(batch);
        io.oddsmaker.control.jpa.RedeemRecordEntity record = new io.oddsmaker.control.jpa.RedeemRecordEntity();
        record.id = "rr_1";
        record.batchId = "b1";
        record.gameId = "g";
        record.playerKey = "p1";
        record.code = "CODE";
        record.reward = "{}";
        record.redeemedAt = java.time.LocalDateTime.now();
        lenient().when(redeemCodeService.redeem(eq("g"), eq("CODE"), eq("p1"))).thenReturn(record);

        assertEquals(200, redeemCodeController.list("g").getStatusCode().value());
        assertEquals(200, redeemCodeController.get("g", "b1").getStatusCode().value());
        assertEquals(404, redeemCodeController.get("g", "other").getStatusCode().value());
        assertEquals(200, redeemCodeController.create("g", new RedeemCodeBatchEntity(), null, 12, null).getStatusCode().value());
        assertEquals(200, redeemCodeController.disable("g", "b1").getStatusCode().value());
        assertEquals(200, redeemCodeController.codes("g", "b1").getStatusCode().value());
        assertEquals(200, redeemCodeController.redeem("g", "p1", "CODE").getStatusCode().value());
        assertEquals(200, redeemCodeController.history("g", "p1").getStatusCode().value());
    }

    // ===== 玩家数据查询 =====

    @Mock
    private PlayerDataQueryService playerDataQueryService;

    @InjectMocks
    private PlayerDataQueryController playerDataQueryController;

    @Test
    @DisplayName("玩家数据查询：档案权限过滤与查询/上报委托")
    void playerDataEndpoints() {
        when(accessGuard.canAccessGame("g", "game:read")).thenReturn(true);
        when(accessGuard.canAccessGame("other", "game:read")).thenReturn(false);
        when(playerDataQueryService.profile("p1")).thenReturn(List.of(
            java.util.Map.of("gameId", "g"), java.util.Map.of("gameId", "other")));

        assertEquals(1, playerDataQueryController.profile("p1").getBody().size());  // 行级过滤
        assertEquals(200, playerDataQueryController.payments("p1", "g", 10).getStatusCode().value());
        assertEquals(200, playerDataQueryController.loginLogs("p1", "g", 10).getStatusCode().value());
        assertEquals(200, playerDataQueryController.ingestPayment(new io.oddsmaker.control.jpa.PlayerPaymentEntity()).getStatusCode().value());
        assertEquals(200, playerDataQueryController.ingestLogin(new io.oddsmaker.control.jpa.PlayerLoginLogEntity()).getStatusCode().value());
    }

    // ===== 玩家数据导出 =====

    @Mock
    private PlayerExportService playerExportService;

    @InjectMocks
    private PlayerExportController playerExportController;

    @Test
    @DisplayName("玩家导出：创建/列表/详情/下载与 404/409 分支")
    void playerExportEndpoints() {
        PlayerExportJobEntity job = new PlayerExportJobEntity();
        job.id = "pex_1";
        job.gameId = "g";
        lenient().when(playerExportService.create(eq("g"), eq("p1"), any(), any(), any(), anyString())).thenReturn(job);
        lenient().when(playerExportService.get("pex_1")).thenReturn(job);
        lenient().when(playerExportService.get("nope")).thenThrow(new IllegalArgumentException("nf"));
        PlayerExportJobEntity pending = new PlayerExportJobEntity();
        pending.id = "pend";
        pending.gameId = "g";
        lenient().when(playerExportService.get("pend")).thenReturn(pending);
        lenient().when(playerExportService.download("pex_1"))
            .thenReturn(new PlayerExportService.ExportedFile("f.json", "application/json", new byte[]{1}));
        lenient().when(playerExportService.download("pend")).thenThrow(new IllegalStateException("not ready"));

        PlayerExportController.CreateRequest req = new PlayerExportController.CreateRequest();
        req.gameId = "g";
        req.playerId = "p1";
        assertEquals(200, playerExportController.create(req).getStatusCode().value());
        assertEquals(200, playerExportController.list("g", "p1").getStatusCode().value());
        assertEquals(200, playerExportController.get("pex_1").getStatusCode().value());
        assertEquals(404, playerExportController.get("nope").getStatusCode().value());
        assertEquals(200, playerExportController.download("pex_1").getStatusCode().value());
        assertEquals(409, playerExportController.download("pend").getStatusCode().value());
    }

    // ===== 符号表 =====

    @Mock
    private SymbolMappingRepo symbolMappingRepo;

    @InjectMocks
    private SymbolMappingController symbolMappingController;

    @Test
    @DisplayName("符号表：注册补默认值/查询/软废弃")
    void symbolMappingEndpoints() {
        SymbolMappingEntity saved = new SymbolMappingEntity();
        saved.id = "sym_1";
        when(symbolMappingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(symbolMappingRepo.findById("sym_1")).thenReturn(java.util.Optional.of(saved));

        SymbolMappingEntity body = new SymbolMappingEntity();
        body.gameId = "g";
        body.platform = "ios";
        body.appVersion = "1.0";
        body.fileType = "DSYM";
        body.filePath = "/tmp/x";
        SymbolMappingEntity registered = symbolMappingController.register(body).getBody();
        org.junit.jupiter.api.Assertions.assertTrue(registered.id.startsWith("sym_"));
        assertEquals(SymbolMappingEntity.MappingStatus.ACTIVE, registered.status);

        assertEquals(200, symbolMappingController.listByGame("g").getStatusCode().value());
        assertEquals(200, symbolMappingController.listByVersion("g", "ios", "1.0").getStatusCode().value());
        assertEquals(200, symbolMappingController.deprecate("sym_1").getStatusCode().value());
        assertEquals(404, symbolMappingController.deprecate("nope").getStatusCode().value());
    }
}
