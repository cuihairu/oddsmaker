package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.BlockListEntity;
import io.oddsmaker.control.jpa.BlockListRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.IdentityEntity;
import io.oddsmaker.control.jpa.IdentityLinkEntity;
import io.oddsmaker.control.jpa.IdentityLinkRepo;
import io.oddsmaker.control.jpa.IdentityRepo;
import io.oddsmaker.control.jpa.MailClaimRepo;
import io.oddsmaker.control.jpa.MailEntity;
import io.oddsmaker.control.jpa.MailRepo;
import io.oddsmaker.control.jpa.PlayerErasureRequestEntity;
import io.oddsmaker.control.jpa.PlayerErasureRequestRepo;
import io.oddsmaker.control.jpa.PlayerExportJobEntity;
import io.oddsmaker.control.jpa.PlayerExportJobRepo;
import io.oddsmaker.control.jpa.PlayerLoginLogRepo;
import io.oddsmaker.control.jpa.PlayerPaymentRepo;
import io.oddsmaker.control.jpa.RedeemRecordRepo;
import io.oddsmaker.control.jpa.ReviewQueueEntity;
import io.oddsmaker.control.jpa.ReviewQueueRepo;
import io.oddsmaker.control.jpa.RiskCaseEntity;
import io.oddsmaker.control.jpa.RiskCaseRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家数据删除请求服务（GDPR erasure）：
 * 输入任一标识（player_id/user_id/device_id）经身份图谱展开全集合后异步清洗——
 * PG 行级硬删 + 导出文件清理 + 风控记录匿名化占位（erased:&lt;reqId&gt; 保留防欺诈审计线索），
 * ClickHouse 行级明细走 ALTER ... DELETE mutation（异步持久，轮询 system.mutations 确认）。
 *
 * 流程：create（展开固化）→ sweep 到期处理 → PG 段短事务提交（pgDone 中间落库，
 * PARTIAL 重试不重复）→ CH 段（幂等可重试）→ COMPLETED / PARTIAL（CH 未确认，自动重试）。
 * process 刻意不加类级事务：PG 删除必须尽早持久化，CH 失败不能回滚 PG。
 *
 * 不覆盖（见 CHANGELOG 边界声明）：Kafka 残留与 Flink checkpoint 自然过期、
 * CH 聚合草图表（uniqState 不可逆）、audit_logs 等内部表正当保留。
 */
@Service
public class PlayerErasureService {

    private static final Logger logger = LoggerFactory.getLogger(PlayerErasureService.class);

    /** 完成后派发的 webhook 事件类型 */
    public static final String WEBHOOK_EVENT = "player_data_erasure";

    /** 展开防全表误扫：标识集合超过此规模时跳过 evidence/context 的 LIKE 扫描（只做精确列匹配） */
    private static final int LIKE_SCAN_LIMIT = 50;

    @Autowired
    private PlayerErasureRequestRepo requestRepo;

    @Autowired
    private GameRepo gameRepo;

    @Autowired
    private IdentityRepo identityRepo;

    @Autowired
    private IdentityLinkRepo identityLinkRepo;

    @Autowired
    private PlayerLoginLogRepo loginLogRepo;

    @Autowired
    private PlayerPaymentRepo paymentRepo;

    @Autowired
    private RedeemRecordRepo redeemRecordRepo;

    @Autowired
    private MailRepo mailRepo;

    @Autowired
    private MailClaimRepo mailClaimRepo;

    @Autowired
    private PlayerExportJobRepo exportJobRepo;

    @Autowired
    private RiskCaseRepo riskCaseRepo;

    @Autowired
    private ReviewQueueRepo reviewQueueRepo;

    @Autowired
    private BlockListRepo blockListRepo;

    @Autowired
    private AuditLogService auditLog;

    @Autowired
    private WebhookService webhookService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ClickHouseClient clickHouse;

    @Value("${oddsmaker.erasure.identity-expansion-limit:500}")
    private int identityExpansionLimit;

    @Value("${oddsmaker.erasure.processing-timeout-minutes:60}")
    private int processingTimeoutMinutes;

    @Value("${oddsmaker.erasure.mutation-timeout-seconds:300}")
    private int mutationTimeoutSeconds;

    @Value("${oddsmaker.erasure.mutation-poll-interval-seconds:5}")
    private int mutationPollIntervalSeconds;

    @Value("${oddsmaker.erasure.max-ch-retries:8}")
    private int maxChRetries;

    /** 身份图谱展开结果（创建时固化存 resolved_identities） */
    public record ResolvedIdentities(String inputType, String inputValue, String gameId,
                                     List<String> identityIds, List<String> userIds,
                                     List<String> playerIds, List<String> deviceIds,
                                     List<String> characterIds, boolean truncated) {}

    // ========== 创建 / 查询 / 取消 ==========

    /** 创建删除请求：校验游戏 → 图谱展开固化 → PENDING（scheduledFor null = 立即）+ 审计 */
    @Transactional
    public PlayerErasureRequestEntity create(String gameId, String requestType, String requestValue,
                                             LocalDateTime scheduledFor, String requestedBy) {
        requireGame(gameId);
        if (requestValue == null || requestValue.isBlank()) {
            throw new IllegalArgumentException("requestValue is required");
        }
        PlayerErasureRequestEntity.RequestType type = parseType(requestType);

        ResolvedIdentities resolved = resolve(gameId, type, requestValue, identityExpansionLimit);

        PlayerErasureRequestEntity req = new PlayerErasureRequestEntity();
        req.id = "per_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        req.gameId = gameId;
        req.status = PlayerErasureRequestEntity.Status.PENDING;
        req.requestType = type;
        req.requestValue = requestValue;
        req.resolvedIdentities = toJson(resolved);
        req.scheduledFor = scheduledFor;
        req.requestedBy = requestedBy;
        req = requestRepo.save(req);

        auditLog.log(AuditLogEntity.AuditAction.CREATE, "player_erasure_request", req.id,
            requestValue, "Erasure requested for " + type + "=" + requestValue
                + " (resolved " + resolved.identityIds().size() + " identities)"
                + (resolved.truncated() ? " [truncated]" : ""),
            AuditLogEntity.AuditResult.SUCCESS, requestedBy, null, null, null, null,
            Map.of("gameId", gameId));
        logger.info("Created player erasure request: {} game: {} {}={} resolved identities: {}",
            req.id, gameId, type, requestValue, resolved.identityIds().size());
        return req;
    }

    @Transactional(readOnly = true)
    public PlayerErasureRequestEntity get(String requestId) {
        return requestRepo.findById(requestId)
            .orElseThrow(() -> new IllegalArgumentException("Erasure request not found: " + requestId));
    }

    @Transactional(readOnly = true)
    public List<PlayerErasureRequestEntity> list(String gameId) {
        requireGame(gameId);
        return requestRepo.findByGameIdOrderByCreatedAtDesc(gameId);
    }

    /** 取消：仅 PENDING 可取消（scheduled_for 任意值）；PROCESSING 及之后一律 409 */
    @Transactional
    public PlayerErasureRequestEntity cancel(String requestId, String cancelledBy) {
        PlayerErasureRequestEntity req = get(requestId);
        if (req.status != PlayerErasureRequestEntity.Status.PENDING) {
            throw new IllegalStateException("Erasure request is not PENDING: " + req.status);
        }
        req.status = PlayerErasureRequestEntity.Status.CANCELLED;
        req.cancelledBy = cancelledBy;
        req.cancelledAt = LocalDateTime.now();
        req = requestRepo.save(req);

        auditLog.log(AuditLogEntity.AuditAction.UPDATE, "player_erasure_request", req.id,
            req.requestValue, "Erasure request cancelled", AuditLogEntity.AuditResult.SUCCESS,
            cancelledBy, req.status.name(), null, null, null, Map.of("gameId", req.gameId));
        logger.info("Cancelled player erasure request: {} by {}", req.id, cancelledBy);
        return req;
    }

    // ========== 处理（sweep 驱动） ==========

    /**
     * 单请求处理：PENDING（或 pgDone 的 PARTIAL 重试）→ PG 段 → CH 段 → 终态。
     * 刻意不加类级 @Transactional：PG 段在 purgePostgres 内部短事务提交并中间落库，
     * CH 失败不能回滚 PG（清洗不可逆是设计意图，PARTIAL 重试只走 CH 段）。
     */
    public void process(String requestId) {
        PlayerErasureRequestEntity req = get(requestId);
        if (req.status != PlayerErasureRequestEntity.Status.PENDING
                && req.status != PlayerErasureRequestEntity.Status.PARTIAL) {
            throw new IllegalStateException("Erasure request is not PENDING/PARTIAL: " + req.status);
        }
        Map<String, Object> summary = parseSummary(req.executionSummary);
        boolean pgDone = Boolean.TRUE.equals(summary.get("pgDone"));
        if (req.status == PlayerErasureRequestEntity.Status.PARTIAL && !pgDone) {
            throw new IllegalStateException("PARTIAL request without pgDone cannot retry: " + requestId);
        }

        req.status = PlayerErasureRequestEntity.Status.PROCESSING;
        req.startedAt = LocalDateTime.now();
        req.errorMessage = null;
        requestRepo.save(req);

        try {
            // ① PG 段（幂等：重跑命中 0 行）
            if (!pgDone) {
                summary.put("pg", purgePostgres(req.gameId, readResolved(req), req.id));
                summary.put("pgDone", true);
                summary.put("retries", intOf(summary.get("retries")));
                req.executionSummary = toJson(summary);
                requestRepo.save(req);   // 中间落库：此后任何失败都走 PARTIAL 而非 FAILED
            }

            // ② CH 段（幂等：mutation 谓词重跑命中 0 行）
            Map<String, Object> ch = purgeClickHouse(req.gameId, readResolved(req), req.id);
            summary.put("ch", ch);

            boolean chOk = !"PENDING_RETRY".equals(ch.get("status"));
            req.status = chOk ? PlayerErasureRequestEntity.Status.COMPLETED
                               : PlayerErasureRequestEntity.Status.PARTIAL;
            if (!chOk) {
                summary.put("retries", intOf(summary.get("retries")) + 1);
                req.errorMessage = "ClickHouse purge not confirmed yet, will retry";
            }
            req.executionSummary = toJson(summary);
            req.completedAt = LocalDateTime.now();
            requestRepo.save(req);

            sendWebhook(req, chOk);
            auditLog.log(AuditLogEntity.AuditAction.DELETE, "player_erasure_request", req.id,
                req.requestValue, "Player data erased (pgDone=true, ch=" + ch.get("status") + ")",
                AuditLogEntity.AuditResult.SUCCESS, req.requestedBy, req.resolvedIdentities,
                req.executionSummary, null, null, Map.of("gameId", req.gameId));
            logger.info("Processed player erasure request: {} status: {}", req.id, req.status);
        } catch (Exception e) {
            // PG 段异常 → FAILED（无图谱无法重试）；仅 CH 段异常且 pgDone → PARTIAL（可自动重试）
            boolean canRetry = Boolean.TRUE.equals(summary.get("pgDone"));
            req.status = canRetry ? PlayerErasureRequestEntity.Status.PARTIAL
                                  : PlayerErasureRequestEntity.Status.FAILED;
            if (canRetry) {
                summary.put("retries", intOf(summary.get("retries")) + 1);
                req.executionSummary = toJson(summary);
            }
            req.errorMessage = e.getMessage();
            requestRepo.save(req);
            sendWebhook(req, false);
            auditLog.log(AuditLogEntity.AuditAction.DELETE, "player_erasure_request", req.id,
                req.requestValue, "Erasure processing failed: " + e.getMessage(),
                AuditLogEntity.AuditResult.FAILURE, req.requestedBy, null, req.executionSummary,
                null, null, Map.of("gameId", req.gameId));
            logger.error("Failed player erasure request: {} - {}", req.id, e.getMessage(), e);
        }
    }

    /**
     * 每 30 秒调度：① 僵尸 PROCESSING 重置（started_at 超时回 PENDING，全流程幂等安全）
     * ② 到期 PENDING 逐个处理（scheduledFor null 或已到）③ PARTIAL 自动重试 CH 段（上限保护）。
     */
    @Scheduled(fixedDelay = 30_000)
    public void sweep() {
        try {
            LocalDateTime now = LocalDateTime.now();
            // ① 僵尸重置：进程崩溃在 process 中途的 PROCESSING（宽超时 > CH 轮询上限 + PG 裕量）
            for (PlayerErasureRequestEntity stale : requestRepo.findStaleProcessing(
                    now.minusMinutes(processingTimeoutMinutes))) {
                stale.status = PlayerErasureRequestEntity.Status.PENDING;
                stale.startedAt = null;
                stale.errorMessage = "requeued after suspected crash";
                requestRepo.save(stale);
                logger.warn("Requeued stale processing erasure request: {}", stale.id);
            }
            // ② 到期 PENDING
            for (PlayerErasureRequestEntity req : requestRepo.findByStatusOrderByCreatedAtAsc(
                    PlayerErasureRequestEntity.Status.PENDING)) {
                if (req.scheduledFor != null && req.scheduledFor.isAfter(now)) {
                    continue;
                }
                try {
                    process(req.id);
                } catch (Exception e) {
                    logger.error("Sweep failed erasure request {}: {}", req.id, e.getMessage());
                }
            }
            // ③ PARTIAL 重试（只走 CH 段）
            for (PlayerErasureRequestEntity req : requestRepo.findByStatus(
                    PlayerErasureRequestEntity.Status.PARTIAL)) {
                Map<String, Object> summary = parseSummary(req.executionSummary);
                if (intOf(summary.get("retries")) >= maxChRetries) {
                    continue;   // 重试耗尽：停在 PARTIAL，等人工介入
                }
                try {
                    process(req.id);
                } catch (Exception e) {
                    logger.error("Sweep retry failed erasure request {}: {}", req.id, e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("Player erasure sweep failed", e);
        }
    }

    // ========== 标识展开（身份图谱不动点） ==========

    /**
     * 从输入标识出发沿 identities + identity_links 展开到不动点：
     * seen 集合按 (type,id) 去重防环；超 limit 置 truncated（仍执行已解析子集）。
     * identities 反查始终带 gameId（防跨 game 脏 link 误扩）。
     */
    ResolvedIdentities resolve(String gameId, PlayerErasureRequestEntity.RequestType inputType,
                               String inputValue, int limit) {
        Set<String> identityIds = new LinkedHashSet<>();
        Set<String> userIds = new LinkedHashSet<>();
        Set<String> playerIds = new LinkedHashSet<>();
        Set<String> deviceIds = new LinkedHashSet<>();
        Set<String> characterIds = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String[]> work = new ArrayList<>();
        boolean truncated = false;

        work.add(new String[]{linkTypeOf(inputType), inputValue});

        while (!work.isEmpty()) {
            String[] item = work.remove(work.size() - 1);
            String type = item[0];
            String value = item[1];
            // 去重与截断都在处理端判定：入队端只收集待查项（否则 seen 先占位，
            // 处理端 seen.add 恒失败，truncated 分支不可达）
            if (value == null || value.isBlank() || !seen.add(type + "=" + value)) {
                continue;
            }
            if (seen.size() > limit) {
                truncated = true;
                break;
            }
            switch (type) {
                case "player_id" -> playerIds.add(value);
                case "user_id" -> userIds.add(value);
                case "device_id" -> deviceIds.add(value);
                case "character_id" -> characterIds.add(value);
                default -> { /* 不会发生：type 来自固定取值空间 */ }
            }

            // ① 经 identity_links 反查 identityId（AnyStatus：非活跃 link 仍暴露关联）
            for (IdentityLinkEntity link : identityLinkRepo.findByTypeAndIdAnyStatus(type, value)) {
                if (identityIds.add(link.identityId)) {
                    expandIdentity(gameId, link.identityId, userIds, playerIds, deviceIds,
                        characterIds, work);
                }
            }
            // ② identities 直匹配（兼容无 link 的历史行）
            List<IdentityEntity> direct = switch (type) {
                case "player_id" -> identityRepo.findByGameIdAndPlayerIdAnyStatus(gameId, value);
                case "user_id" -> identityRepo.findByGameIdAndUserIdAnyStatus(gameId, value);
                case "device_id" -> identityRepo.findByGameIdAndDeviceIdAnyStatus(gameId, value);
                default -> List.of();
            };
            for (IdentityEntity identity : direct) {
                if (identityIds.add(identity.id)) {
                    expandIdentity(gameId, identity.id, userIds, playerIds, deviceIds,
                        characterIds, work);
                }
            }
        }

        return new ResolvedIdentities(linkTypeOf(inputType), inputValue, gameId,
            List.copyOf(identityIds), List.copyOf(userIds), List.copyOf(playerIds),
            List.copyOf(deviceIds), List.copyOf(characterIds), truncated);
    }

    /** 收集身份行的全部标识与新 (type,id) 待查项（identity 行 + 全量 links；去重在处理端统一判定） */
    private void expandIdentity(String gameId, String identityId, Set<String> userIds,
                                Set<String> playerIds, Set<String> deviceIds,
                                Set<String> characterIds, List<String[]> work) {
        identityRepo.findById(identityId).ifPresent(identity -> {
            addId("user_id", identity.userId, work);
            addId("player_id", identity.playerId, work);
            addId("device_id", identity.deviceId, work);
            addId("character_id", identity.characterId, work);
        });
        for (IdentityLinkEntity link : identityLinkRepo.findByIdentityIdAnyStatus(identityId)) {
            String linkedId = link.linkedId;
            if (linkedId == null || linkedId.isBlank()) {
                continue;
            }
            boolean known = switch (link.linkedIdentityType == null ? "" : link.linkedIdentityType) {
                case "player_id", "user_id", "device_id", "character_id" -> true;
                default -> false;
            };
            if (known) {
                work.add(new String[]{link.linkedIdentityType, linkedId});
            }
        }
    }

    /** 单值标识入待查队列（identities 行的单值字段；桶收录与去重在处理端统一） */
    private static void addId(String type, String value, List<String[]> work) {
        if (value == null || value.isBlank()) {
            return;
        }
        work.add(new String[]{type, value});
    }

    // ========== PG 清洗（单事务短提交） ==========

    /**
     * PG 清洗：导出文件/行删除 → 硬删（identity 图谱、登录日志、支付、兑换、邮件领取）
     * → 邮件收件人摘除 → 风控记录匿名化占位。幂等：重跑全部命中 0 行。
     * 返回各表影响行数（计入 execution_summary.pg）。
     */
    @Transactional
    Map<String, Long> purgePostgres(String gameId, ResolvedIdentities ids, String reqId) {
        Map<String, Long> pg = new LinkedHashMap<>();
        List<String> playerIds = ids.playerIds();
        List<String> userIds = ids.userIds();
        List<String> deviceIds = ids.deviceIds();
        List<String> playerKeys = new ArrayList<>(playerIds);
        playerKeys.addAll(userIds);   // player_key 语义：player_id 优先退化 user_id（兑换/邮件领取）
        List<String> allIds = new ArrayList<>(playerKeys);
        allIds.addAll(deviceIds);
        allIds.addAll(ids.characterIds());
        String placeholder = "erased:" + reqId;

        // ① 导出文件先行（磁盘文件不属于事务，行删前逐个清理）
        long filesDeleted = 0;
        for (PlayerExportJobEntity job : exportJobRepo.findByGameIdAndPlayerIdIn(gameId, playerIds)) {
            if (job.filePath != null) {
                try {
                    filesDeleted += Files.deleteIfExists(Paths.get(job.filePath)) ? 1 : 0;
                } catch (Exception e) {
                    logger.warn("Failed to delete export file {}: {}", job.filePath, e.getMessage());
                }
            }
        }
        pg.put("export_files_deleted", filesDeleted);

        // ② 硬删（IN 列表空集防御：空列表换不可能匹配的占位值，避免 IN () 语法错）
        pg.put("identities", (long) identityRepo.deleteAllByIdIn(safeIn(ids.identityIds())));
        pg.put("identity_links", (long) identityLinkRepo.deleteByIdentityIdIn(safeIn(ids.identityIds())));
        pg.put("player_login_logs", (long) loginLogRepo.deleteByGameIdAndPlayerIdInOrDeviceIdIn(
            gameId, safeIn(playerIds), safeIn(deviceIds)));
        pg.put("player_payments", (long) paymentRepo.deleteByGameIdAndPlayerIdIn(gameId, safeIn(playerIds)));
        pg.put("redeem_records", (long) redeemRecordRepo.deleteByGameIdAndPlayerKeyIn(gameId, safeIn(playerKeys)));
        pg.put("op_mail_claims", (long) mailClaimRepo.deleteByGameIdAndPlayerKeyIn(gameId, safeIn(playerKeys)));
        pg.put("player_export_jobs", (long) exportJobRepo.deleteByGameIdAndPlayerIdIn(gameId, safeIn(playerIds)));

        // ③ 个人邮件收件人摘除（正文是运营资产且列表可能含其他玩家——摘 token 不删行）
        long mailsScrubbed = 0;
        for (String key : playerKeys) {
            for (MailEntity mail : mailRepo.findIndividualByGameIdAndRecipientsContaining(gameId, key)) {
                String remaining = removeRecipient(mail.recipients, playerKeys);
                if (remaining != null && !remaining.equals(mail.recipients)) {
                    mail.recipients = remaining;
                    mailRepo.save(mail);
                    mailsScrubbed++;
                }
            }
        }
        pg.put("op_mails_recipients_scrubbed", mailsScrubbed);

        // ④ 风控记录匿名化占位（保留案件记录，标识替换为 erased:<reqId>）
        pg.put("risk_cases_anonymized", anonymizeRiskCases(gameId, allIds, placeholder));
        pg.put("review_queues_anonymized", anonymizeReviewQueues(gameId, allIds, placeholder));
        pg.put("block_lists_anonymized", anonymizeBlockLists(gameId, allIds, placeholder));
        return pg;
    }

    /** 风控案件：target 精确命中 + evidence/context LIKE 粗筛（大集合跳过 LIKE 防全表扫），JSON 内逐标识替换 */
    private long anonymizeRiskCases(String gameId, List<String> allIds, String placeholder) {
        Set<RiskCaseEntity> hit = new LinkedHashSet<>(riskCaseRepo.findByGameIdAndTargetIdIn(gameId, allIds));
        if (allIds.size() <= LIKE_SCAN_LIMIT) {
            for (String id : allIds) {
                hit.addAll(riskCaseRepo.findByGameIdAndEvidenceDataContaining(gameId, id));
                hit.addAll(riskCaseRepo.findByGameIdAndContextDataContaining(gameId, id));
            }
        }
        long count = 0;
        for (RiskCaseEntity rc : hit) {
            String newTargetId = replaceAll(rc.targetId, allIds, placeholder);
            String newTargetName = replaceAll(rc.targetName, allIds, placeholder);
            String newEvidence = replaceAll(rc.evidenceData, allIds, placeholder);
            String newContext = replaceAll(rc.contextData, allIds, placeholder);
            if (!Objects.equals(newTargetId, rc.targetId) || !Objects.equals(newTargetName, rc.targetName)
                    || !Objects.equals(newEvidence, rc.evidenceData)
                    || !Objects.equals(newContext, rc.contextData)) {
                rc.targetId = newTargetId;
                rc.targetName = newTargetName;
                rc.evidenceData = newEvidence;
                rc.contextData = newContext;
                riskCaseRepo.save(rc);
                count++;
            }
        }
        return count;
    }

    /** 审核队列：target 替换为占位（保留审核记录） */
    private long anonymizeReviewQueues(String gameId, List<String> allIds, String placeholder) {
        long count = 0;
        for (ReviewQueueEntity rq : reviewQueueRepo.findByGameIdAndTargetIdIn(gameId, allIds)) {
            rq.targetId = replaceAll(rq.targetId, allIds, placeholder);
            if (rq.targetName != null) {
                rq.targetName = replaceAll(rq.targetName, allIds, placeholder);
            }
            reviewQueueRepo.save(rq);
            count++;
        }
        return count;
    }

    /** 封禁列表：仅玩家标识类（ip/ip_range/account_id 不动），gateway 15s TTL 缓存自然过期 */
    private long anonymizeBlockLists(String gameId, List<String> allIds, String placeholder) {
        long count = 0;
        for (BlockListEntity bl : blockListRepo.findByGameIdAndTargetValueInAndTargetTypeIn(
                gameId, allIds, List.of("player_id", "user_id", "device_id"))) {
            bl.targetValue = placeholder;
            if (bl.targetName != null) {
                bl.targetName = replaceAll(bl.targetName, allIds, placeholder);
            }
            blockListRepo.save(bl);
            count++;
        }
        return count;
    }

    // ========== ClickHouse 清洗（mutation 异步持久 + system.mutations 轮询确认） ==========

    /**
     * CH 行级明细 mutation：逐表提交 ALTER ... DELETE（谓词尾带 per:&lt;reqId&gt; 标记），
     * 轮询 system.mutations 未完成数回落到提交前基线即确认。
     * 未配置 CH → SKIPPED_UNCONFIGURED（整体 COMPLETED）；提交/确认失败 → PENDING_RETRY（整体 PARTIAL 自动重试）。
     * 超时不判失败（mutation 已持久化必然最终执行）→ COMPLETED 但 confirmed=false。
     */
    Map<String, Object> purgeClickHouse(String gameId, ResolvedIdentities ids, String reqId) {
        Map<String, Object> ch = new LinkedHashMap<>();
        if (!clickHouse.isAvailable()) {
            ch.put("status", "SKIPPED_UNCONFIGURED");
            ch.put("confirmed", true);
            ch.put("mutations", List.of());
            return ch;
        }
        List<String> playerIds = ids.playerIds();
        List<String> userIds = ids.userIds();
        List<String> deviceIds = ids.deviceIds();
        List<String> playerKeys = new ArrayList<>(playerIds);
        playerKeys.addAll(userIds);
        List<String> allIds = new ArrayList<>(playerKeys);
        allIds.addAll(deviceIds);
        allIds.addAll(ids.characterIds());

        String g = quote(gameId);
        List<String[]> mutations = List.of(
            new String[]{"events", "DELETE WHERE game_id=" + g
                + " AND (player_id IN " + inList(playerIds)
                + " OR user_id IN " + inList(userIds)
                + " OR device_id IN " + inList(deviceIds) + ")"},
            new String[]{"sessions", "DELETE WHERE game_id=" + g
                + " AND (player_id IN " + inList(playerIds)
                + " OR user_id IN " + inList(userIds)
                + " OR device_id IN " + inList(deviceIds) + ")"},
            new String[]{"identities", "DELETE WHERE game_id=" + g
                + " AND (identity_id IN " + inList(ids.identityIds())
                + " OR user_id IN " + inList(userIds)
                + " OR player_id IN " + inList(playerIds)
                + " OR hasAny(device_ids, " + arrayLiteral(deviceIds) + ")"
                + " OR hasAny(character_ids, " + arrayLiteral(ids.characterIds()) + ")"},
            new String[]{"risk_events", "DELETE WHERE game_id=" + g + " AND subject_id IN " + inList(allIds)},
            new String[]{"risk_scores", "DELETE WHERE game_id=" + g + " AND subject_id IN " + inList(allIds)},
            new String[]{"risk_actions", "DELETE WHERE game_id=" + g + " AND subject_id IN " + inList(allIds)},
            new String[]{"exp_exposure_users", "DELETE WHERE game_id=" + g + " AND uid IN " + inList(allIds)},
            new String[]{"exp_first_level_complete", "DELETE WHERE game_id=" + g + " AND uid IN " + inList(allIds)},
            new String[]{"predictions", "DELETE WHERE game_id=" + g + " AND user_id IN " + inList(userIds)});

        List<String> tables = mutations.stream().map(m -> m[0]).toList();
        Map<String, Object> baseline = pendingMutations(tables);

        List<Map<String, Object>> results = new ArrayList<>();
        try {
            for (String[] m : mutations) {
                String sql = "ALTER TABLE " + m[0] + " " + m[1] + " /* per:" + reqId + " */";
                long estimate = estimateRows(gameId, m[0], m[1]);
                clickHouse.execute(conn -> conn.createStatement().execute(sql));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("table", m[0]);
                row.put("estimate", estimate);
                results.add(row);
            }
        } catch (Exception e) {
            ch.put("status", "PENDING_RETRY");
            ch.put("confirmed", false);
            ch.put("error", e.getMessage());
            ch.put("mutations", results);
            return ch;
        }

        // 轮询确认：未完成 mutation 数回落到提交前基线（不依赖 command 文本，注释被剥离也能判定）
        boolean confirmed = awaitMutations(tables, baseline);
        ch.put("status", "EXECUTED");
        ch.put("confirmed", confirmed);
        ch.put("mutations", results);
        return ch;
    }

    /** mutation 前预估命中行数（mutation 本身不返回影响行数；estimate 仅运营参考） */
    private long estimateRows(String gameId, String table, String deleteWhere) {
        try {
            List<Map<String, Object>> rows = clickHouse.query(
                "SELECT count() AS c FROM " + table + " WHERE game_id='" + escape(gameId) + "' AND "
                    + deleteWhere.substring("DELETE ".length()));
            Object c = rows.isEmpty() ? 0 : rows.get(0).get("c");
            return c instanceof Number n ? n.longValue() : 0;
        } catch (Exception e) {
            return -1;   // 预估失败不影响主流程
        }
    }

    /** 各表未完成 mutation 数；查询失败返回 null（调用方按无法确认处理，不得误判完成） */
    private Map<String, Object> pendingMutations(List<String> tables) {
        Map<String, Object> baseline = new LinkedHashMap<>();
        try {
            for (String table : tables) {
                List<Map<String, Object>> rows = clickHouse.query(
                    "SELECT count() AS c FROM system.mutations "
                        + "WHERE database = currentDatabase() AND table = ? AND NOT is_done", table);
                Object c = rows.isEmpty() ? 0 : rows.get(0).get("c");
                baseline.put(table, c instanceof Number n ? n.longValue() : 0);
            }
        } catch (Exception e) {
            logger.warn("system.mutations query failed: {}", e.getMessage());
            return null;
        }
        return baseline;
    }

    /** 轮询至各表未完成数回落到基线；超时或查询失败返回 false（confirmed=false，不失败） */
    private boolean awaitMutations(List<String> tables, Map<String, Object> baseline) {
        if (baseline == null) {
            return false;
        }
        long deadline = System.currentTimeMillis() + mutationTimeoutSeconds * 1000L;
        try {
            while (System.currentTimeMillis() < deadline) {
                Map<String, Object> pending = pendingMutations(tables);
                if (pending == null) {
                    return false;
                }
                boolean done = true;
                for (String table : tables) {
                    if (longOf(pending.get(table)) > longOf(baseline.get(table))) {
                        done = false;
                        break;
                    }
                }
                if (done) {
                    return true;
                }
                Thread.sleep(mutationPollIntervalSeconds * 1000L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    // ========== 辅助 ==========

    private void sendWebhook(PlayerErasureRequestEntity req, boolean ok) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", WEBHOOK_EVENT);
            payload.put("requestId", req.id);
            payload.put("gameId", req.gameId);
            payload.put("status", req.status.name());
            payload.put("ok", ok);
            payload.put("summary", parseSummary(req.executionSummary));
            webhookService.sendCustomWebhook(req.gameId, WEBHOOK_EVENT, payload);
        } catch (Exception e) {
            logger.warn("erasure webhook dispatch failed for {}: {}", req.id, e.getMessage());
        }
    }

    private ResolvedIdentities readResolved(PlayerErasureRequestEntity req) {
        try {
            return objectMapper.readValue(req.resolvedIdentities, ResolvedIdentities.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse resolvedIdentities: " + req.id, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSummary(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static PlayerErasureRequestEntity.RequestType parseType(String requestType) {
        if (requestType == null || requestType.isBlank()) {
            throw new IllegalArgumentException("requestType is required");
        }
        try {
            return PlayerErasureRequestEntity.RequestType.valueOf(requestType.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "requestType must be one of PLAYER_ID, USER_ID, DEVICE_ID: " + requestType);
        }
    }

    /** RequestType → identity_links.linkedIdentityType 取值 */
    private static String linkTypeOf(PlayerErasureRequestEntity.RequestType type) {
        return switch (type) {
            case PLAYER_ID -> "player_id";
            case USER_ID -> "user_id";
            case DEVICE_ID -> "device_id";
        };
    }

    /** 从逗号收件人列表精确摘除 playerKeys 集合中的每个 token（参照 MailEntity.containsRecipient 口径） */
    static String removeRecipient(String recipients, Collection<String> playerKeys) {
        if (recipients == null || recipients.isBlank()) {
            return recipients;
        }
        StringBuilder out = new StringBuilder();
        for (String r : recipients.split(",")) {
            String token = r.trim();
            if (token.isEmpty() || playerKeys.contains(token)) {
                continue;
            }
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(token);
        }
        return out.toString();
    }

    /** 对文本中的每个标识做字面量替换（标识是唯一长 token，不会误替换） */
    static String replaceAll(String text, Collection<String> ids, String placeholder) {
        if (text == null) {
            return null;
        }
        String out = text;
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                out = out.replace(id, placeholder);
            }
        }
        return out;
    }

    /** 剔除 null 后空列表换不可能匹配的占位值，避免 IN () 语法错 */
    private static List<String> safeIn(List<String> values) {
        List<String> nonNull = values.stream().filter(Objects::nonNull).toList();
        return nonNull.isEmpty() ? List.of("") : nonNull;
    }

    /** IN 字面量：('a','b')；值来自 PG 内部图谱数据，仍做单引号转义防注入 */
    static String inList(Collection<String> values) {
        StringBuilder sb = new StringBuilder("(");
        boolean first = true;
        for (String v : safeIn(values == null ? List.of() : values.stream().toList())) {
            if (!first) {
                sb.append(',');
            }
            sb.append('\'').append(escape(v)).append('\'');
            first = false;
        }
        return sb.append(')').toString();
    }

    private static String arrayLiteral(Collection<String> values) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String v : values) {
            if (!first) {
                sb.append(',');
            }
            sb.append('\'').append(escape(v)).append('\'');
            first = false;
        }
        return sb.append(']').toString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    private static String quote(String value) {
        return "'" + escape(value) + "'";
    }

    private static int intOf(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static long longOf(Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize erasure JSON", e);
        }
    }

    private void requireGame(String gameId) {
        gameRepo.findById(gameId)
            .filter(g -> g.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameId));
    }
}
