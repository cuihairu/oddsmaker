package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.BlockListEntity;
import io.oddsmaker.control.jpa.MailEntity;
import io.oddsmaker.control.jpa.PlayerExportJobEntity;
import io.oddsmaker.control.jpa.ReviewQueueEntity;
import io.oddsmaker.control.jpa.RiskCaseEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 玩家数据删除请求服务测试（LAY-014 拆分）：PG 清洗——硬删计数/导出文件删除/邮件摘除/风控匿名化、邮件摘空与 playerKey 并集、无路径与无变化安全跳过、导出文件路径非法告警跳过。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("玩家数据删除请求服务")
class PlayerErasurePurgePgTest extends PlayerErasureServiceTestBase {

    @Test
    @DisplayName("PG 清洗：硬删计数 + 导出文件删除 + 邮件摘除 + 风控匿名化占位")
    void purgePostgresCoversAllTables() throws Exception {
        PlayerErasureService.ResolvedIdentities ids = objectMapper.readValue(
            savedRequest().resolvedIdentities, PlayerErasureService.ResolvedIdentities.class);

        Path exportFile = tempDir.resolve("export.json");
        Files.writeString(exportFile, "{}");
        PlayerExportJobEntity job = new PlayerExportJobEntity();
        job.filePath = exportFile.toString();
        when(exportJobRepo.findByGameIdAndPlayerIdIn(eq("g1"), anyList())).thenReturn(List.of(job));
        when(identityRepo.deleteAllByIdIn(anyList())).thenReturn(2);
        when(identityLinkRepo.deleteByIdentityIdIn(anyList())).thenReturn(3);
        when(loginLogRepo.deleteByGameIdAndPlayerIdInOrDeviceIdIn(eq("g1"), anyList(), anyList())).thenReturn(4);
        when(paymentRepo.deleteByGameIdAndPlayerIdIn(eq("g1"), anyList())).thenReturn(5);
        when(redeemRecordRepo.deleteByGameIdAndPlayerKeyIn(eq("g1"), anyList())).thenReturn(6);
        when(mailClaimRepo.deleteByGameIdAndPlayerKeyIn(eq("g1"), anyList())).thenReturn(7);
        when(exportJobRepo.deleteByGameIdAndPlayerIdIn(eq("g1"), anyList())).thenReturn(1);

        MailEntity mail = new MailEntity();
        mail.recipients = "p1,other";
        when(mailRepo.findIndividualByGameIdAndRecipientsContaining("g1", "p1")).thenReturn(List.of(mail));
        when(mailRepo.findIndividualByGameIdAndRecipientsContaining("g1", "u1")).thenReturn(List.of());

        RiskCaseEntity rc = new RiskCaseEntity();
        rc.targetId = "p1";
        rc.targetName = "player p1";
        rc.evidenceData = "{\"subject\":\"p1\"}";
        when(riskCaseRepo.findByGameIdAndTargetIdIn(eq("g1"), anyList())).thenReturn(List.of(rc));
        when(riskCaseRepo.findByGameIdAndEvidenceDataContaining(anyString(), anyString())).thenReturn(List.of());
        when(riskCaseRepo.findByGameIdAndContextDataContaining(anyString(), anyString())).thenReturn(List.of());
        when(reviewQueueRepo.findByGameIdAndTargetIdIn(eq("g1"), anyList())).thenReturn(List.of());

        BlockListEntity bl = new BlockListEntity();
        bl.targetValue = "d1";
        bl.targetType = "device_id";
        when(blockListRepo.findByGameIdAndTargetValueInAndTargetTypeIn(eq("g1"), anyList(), anyList()))
            .thenReturn(List.of(bl));

        Map<String, Long> pg = service.purgePostgres("g1", ids, "per_test000000000000000000");

        assertThat(pg.get("identities")).isEqualTo(2L);
        assertThat(pg.get("identity_links")).isEqualTo(3L);
        assertThat(pg.get("player_login_logs")).isEqualTo(4L);
        assertThat(pg.get("player_payments")).isEqualTo(5L);
        assertThat(pg.get("redeem_records")).isEqualTo(6L);
        assertThat(pg.get("op_mail_claims")).isEqualTo(7L);
        assertThat(pg.get("player_export_jobs")).isEqualTo(1L);
        assertThat(pg.get("export_files_deleted")).isEqualTo(1L);
        assertThat(Files.exists(exportFile)).isFalse();
        assertThat(pg.get("op_mails_recipients_scrubbed")).isEqualTo(1L);
        assertThat(mail.recipients).isEqualTo("other");
        assertThat(pg.get("risk_cases_anonymized")).isEqualTo(1L);
        assertThat(rc.targetId).isEqualTo("erased:per_test000000000000000000");
        assertThat(rc.evidenceData).contains("erased:").doesNotContain("p1");
        assertThat(pg.get("block_lists_anonymized")).isEqualTo(1L);
        assertThat(bl.targetValue).isEqualTo("erased:per_test000000000000000000");
    }

    @Test
    @DisplayName("邮件摘空保留空串；playerKey 集合 = playerIds ∪ userIds")
    void purgePostgresScrubEmptyAndPlayerKeyUnion() {
        PlayerErasureService.ResolvedIdentities ids = new PlayerErasureService.ResolvedIdentities(
            "player_id", "p1", "g1", List.of(), List.of("u1"), List.of("p1"), List.of(), List.of(), false);
        stubPgDeletes();

        MailEntity mail = new MailEntity();
        mail.recipients = "u1";
        when(mailRepo.findIndividualByGameIdAndRecipientsContaining(eq("g1"), anyString()))
            .thenReturn(List.of(mail));

        service.purgePostgres("g1", ids, "per_x");

        assertThat(mail.recipients).isEmpty();
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redeemRecordRepo).deleteByGameIdAndPlayerKeyIn(eq("g1"), keys.capture());
        assertThat(keys.getValue()).containsExactlyInAnyOrder("p1", "u1");
    }

    @Test
    @DisplayName("PG 清洗：无路径/文件缺失的导出任务安全跳过；无变化的风控案件不落库；审核队列与封禁 targetName 一并匿名化")
    void purgePostgresSkipsNullPathAndNoChangeHits() {
        PlayerErasureService.ResolvedIdentities ids = new PlayerErasureService.ResolvedIdentities(
            "player_id", "p1", "g1", List.of(), List.of(), List.of("p1"), List.of(), List.of(), false);

        PlayerExportJobEntity nullPathJob = new PlayerExportJobEntity();
        PlayerExportJobEntity missingFileJob = new PlayerExportJobEntity();
        missingFileJob.filePath = tempDir.resolve("nope.json").toString();
        when(exportJobRepo.findByGameIdAndPlayerIdIn(eq("g1"), anyList()))
            .thenReturn(List.of(nullPathJob, missingFileJob));
        when(identityRepo.deleteAllByIdIn(anyList())).thenReturn(0);
        when(identityLinkRepo.deleteByIdentityIdIn(anyList())).thenReturn(0);
        when(loginLogRepo.deleteByGameIdAndPlayerIdInOrDeviceIdIn(eq("g1"), anyList(), anyList())).thenReturn(0);
        when(paymentRepo.deleteByGameIdAndPlayerIdIn(eq("g1"), anyList())).thenReturn(0);
        when(redeemRecordRepo.deleteByGameIdAndPlayerKeyIn(eq("g1"), anyList())).thenReturn(0);
        when(mailClaimRepo.deleteByGameIdAndPlayerKeyIn(eq("g1"), anyList())).thenReturn(0);
        when(exportJobRepo.deleteByGameIdAndPlayerIdIn(eq("g1"), anyList())).thenReturn(0);
        when(mailRepo.findIndividualByGameIdAndRecipientsContaining(anyString(), anyString()))
            .thenReturn(List.of());

        // LIKE 命中但文本不含任何标识 → 替换无变化 → 不 save、不计数
        RiskCaseEntity unchanged = new RiskCaseEntity();
        unchanged.targetId = "keep";
        unchanged.evidenceData = "nothing to replace";
        when(riskCaseRepo.findByGameIdAndTargetIdIn(eq("g1"), anyList())).thenReturn(List.of(unchanged));
        when(riskCaseRepo.findByGameIdAndEvidenceDataContaining(anyString(), anyString())).thenReturn(List.of());
        when(riskCaseRepo.findByGameIdAndContextDataContaining(anyString(), anyString())).thenReturn(List.of());

        ReviewQueueEntity rq = new ReviewQueueEntity();
        rq.targetId = "p1";
        rq.targetName = "name p1";
        when(reviewQueueRepo.findByGameIdAndTargetIdIn(eq("g1"), anyList())).thenReturn(List.of(rq));

        BlockListEntity bl = new BlockListEntity();
        bl.targetValue = "p1";
        bl.targetType = "player_id";
        bl.targetName = "nick p1";
        when(blockListRepo.findByGameIdAndTargetValueInAndTargetTypeIn(eq("g1"), anyList(), anyList()))
            .thenReturn(List.of(bl));

        Map<String, Long> pg = service.purgePostgres("g1", ids, "per_x");

        assertThat(pg.get("export_files_deleted")).isEqualTo(0L);
        assertThat(pg.get("risk_cases_anonymized")).isEqualTo(0L);
        verify(riskCaseRepo, never()).save(any());
        assertThat(rq.targetId).isEqualTo("erased:per_x");
        assertThat(rq.targetName).isEqualTo("name erased:per_x");
        assertThat(pg.get("review_queues_anonymized")).isEqualTo(1L);
        assertThat(bl.targetValue).isEqualTo("erased:per_x");
        assertThat(bl.targetName).isEqualTo("nick erased:per_x");
    }

    @Test
    @DisplayName("PG 清洗：导出文件路径非法时告警跳过不中断")
    void purgePostgresExportFileBadPath() {
        PlayerErasureService.ResolvedIdentities ids = new PlayerErasureService.ResolvedIdentities(
            "player_id", "p1", "g1", List.of(), List.of(), List.of("p1"), List.of(), List.of(), false);
        PlayerExportJobEntity badPathJob = new PlayerExportJobEntity();
        badPathJob.filePath = "bad\0path";
        when(exportJobRepo.findByGameIdAndPlayerIdIn(eq("g1"), anyList()))
            .thenReturn(List.of(badPathJob));
        when(identityRepo.deleteAllByIdIn(anyList())).thenReturn(0);
        when(identityLinkRepo.deleteByIdentityIdIn(anyList())).thenReturn(0);
        when(loginLogRepo.deleteByGameIdAndPlayerIdInOrDeviceIdIn(eq("g1"), anyList(), anyList())).thenReturn(0);
        when(paymentRepo.deleteByGameIdAndPlayerIdIn(eq("g1"), anyList())).thenReturn(0);
        when(redeemRecordRepo.deleteByGameIdAndPlayerKeyIn(eq("g1"), anyList())).thenReturn(0);
        when(mailClaimRepo.deleteByGameIdAndPlayerKeyIn(eq("g1"), anyList())).thenReturn(0);
        when(exportJobRepo.deleteByGameIdAndPlayerIdIn(eq("g1"), anyList())).thenReturn(0);
        when(mailRepo.findIndividualByGameIdAndRecipientsContaining(anyString(), anyString()))
            .thenReturn(List.of());
        when(riskCaseRepo.findByGameIdAndTargetIdIn(eq("g1"), anyList())).thenReturn(List.of());
        when(riskCaseRepo.findByGameIdAndEvidenceDataContaining(anyString(), anyString())).thenReturn(List.of());
        when(riskCaseRepo.findByGameIdAndContextDataContaining(anyString(), anyString())).thenReturn(List.of());
        when(reviewQueueRepo.findByGameIdAndTargetIdIn(eq("g1"), anyList())).thenReturn(List.of());
        when(blockListRepo.findByGameIdAndTargetValueInAndTargetTypeIn(eq("g1"), anyList(), anyList()))
            .thenReturn(List.of());

        Map<String, Long> pg = service.purgePostgres("g1", ids, "per_x");

        assertThat(pg.get("export_files_deleted")).isEqualTo(0L);   // 非法路径被 catch 后继续
    }

}
