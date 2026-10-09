package io.oddsmaker.control.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.IdentityEntity;
import io.oddsmaker.control.jpa.IdentityLinkEntity;
import io.oddsmaker.control.jpa.IdentityLinkRepo;
import io.oddsmaker.control.jpa.IdentityRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 显式身份合并服务（操作员人工合并，补齐 IdentityConsumer 自动链路之外的人工通道）。
 *
 * 语义：secondary 并入 primary——secondary 的活跃 links 重挂到 primary（三元组冲突的吊销）、
 * primary 落 identity_id 反查链路（IdentityService.findByIdentifier 按 "identity_id" 类型可解）、
 * 缺失标量列从 secondary 回填、计数与首末见时间归并；secondary 墓碑化（status=MERGED +
 * primaryIdentityType=MERGED，标识列保留——GDPR erasure 的 AnyStatus 反查仍能命中）。
 *
 * 与自动链路的关系：IdentityConsumer 对 status=MERGED 的身份跳过重建（人工合并决定不被
 * Flink 重放复活）；不做反向拆分（unmerge），误合并时以人工数据修正兜底。
 */
@Service
public class IdentityMergeService {

    private static final Logger logger = LoggerFactory.getLogger(IdentityMergeService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final IdentityRepo identityRepo;
    private final IdentityLinkRepo identityLinkRepo;
    private final AuditLogService auditLogService;

    public IdentityMergeService(IdentityRepo identityRepo,
                                IdentityLinkRepo identityLinkRepo,
                                AuditLogService auditLogService) {
        this.identityRepo = identityRepo;
        this.identityLinkRepo = identityLinkRepo;
        this.auditLogService = auditLogService;
    }

    /**
     * 显式合并：secondary 并入 primary。
     *
     * @param gameId      校验两个身份同属该游戏
     * @param primaryId   保留的幸存身份
     * @param secondaryId 被并入的身份（墓碑化）
     * @param reason      合并原因（审计必填）
     * @param by          操作人
     * @return 合并摘要（primaryId/mergedFrom/movedLinks/dedupedLinks）
     */
    @Transactional
    public Map<String, Object> merge(String gameId, String primaryId, String secondaryId,
                                     String reason, String by) {
        if (primaryId == null || primaryId.isBlank() || secondaryId == null || secondaryId.isBlank()) {
            throw new IllegalArgumentException("primaryId and secondaryId are required");
        }
        if (primaryId.equals(secondaryId)) {
            throw new IllegalArgumentException("Cannot merge an identity into itself: " + primaryId);
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        IdentityEntity primary = loadMergable(gameId, primaryId, "primary");
        IdentityEntity secondary = loadMergable(gameId, secondaryId, "secondary");

        LocalDateTime now = LocalDateTime.now();

        // 1) secondary 活跃 links 重挂到 primary；primary 已有同三元组时吊销 secondary 侧（不重复）
        List<IdentityLinkEntity> secondaryLinks = identityLinkRepo.findByIdentityId(secondaryId);
        int moved = 0;
        int deduped = 0;
        for (IdentityLinkEntity link : secondaryLinks) {
            if (!link.isActive()) {
                continue;
            }
            Optional<IdentityLinkEntity> onPrimary = identityLinkRepo
                    .findActiveByIdentityIdAndTypeAndLinkedId(primaryId, link.linkedIdentityType, link.linkedId);
            if (onPrimary.isPresent()) {
                link.revoke();
                link.deletedAt = now;
                identityLinkRepo.save(link);
                deduped++;
            } else {
                link.identityId = primaryId;
                link.linkType = IdentityLinkEntity.LinkType.MERGED;
                link.verificationStatus = IdentityLinkEntity.VerificationStatus.CONFIRMED;
                link.verifiedAt = now;
                link.lastConfirmedAt = now;
                link.verificationMethod = "manual_merge";
                link.linkSource = "manual-merge";
                identityLinkRepo.save(link);
                moved++;
            }
        }

        // 2) primary 落 identity_id 反查链路：按 "identity_id" 类型查 secondaryId 可解回 primary
        IdentityLinkEntity tombstoneLink = identityLinkRepo
                .findActiveByIdentityIdAndTypeAndLinkedId(primaryId, "identity_id", secondaryId)
                .orElseGet(() -> {
                    IdentityLinkEntity l = new IdentityLinkEntity();
                    l.id = "ilk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
                    l.identityId = primaryId;
                    l.linkedIdentityType = "identity_id";
                    l.linkedId = secondaryId;
                    l.firstLinkedAt = now;
                    l.usageCount = 0L;
                    return l;
                });
        tombstoneLink.linkType = IdentityLinkEntity.LinkType.MERGED;
        tombstoneLink.verificationStatus = IdentityLinkEntity.VerificationStatus.CONFIRMED;
        tombstoneLink.verifiedAt = now;
        tombstoneLink.lastConfirmedAt = now;
        tombstoneLink.verificationMethod = "manual_merge";
        tombstoneLink.linkSource = "manual-merge";
        tombstoneLink.status = IdentityLinkEntity.LinkStatus.ACTIVE;
        tombstoneLink.deletedAt = null;
        tombstoneLink.usageCount = (tombstoneLink.usageCount == null ? 0L : tombstoneLink.usageCount) + 1;
        identityLinkRepo.save(tombstoneLink);

        // 3) primary 缺失标量列从 secondary 回填；首末见与计数归并
        if (isBlank(primary.userId) && !isBlank(secondary.userId)) primary.userId = secondary.userId;
        if (isBlank(primary.playerId) && !isBlank(secondary.playerId)) primary.playerId = secondary.playerId;
        if (isBlank(primary.characterId) && !isBlank(secondary.characterId)) primary.characterId = secondary.characterId;
        if (isBlank(primary.deviceId) && !isBlank(secondary.deviceId)) primary.deviceId = secondary.deviceId;
        if (isBlank(primary.deviceType) && !isBlank(secondary.deviceType)) primary.deviceType = secondary.deviceType;
        if (secondary.firstSeenAt != null
                && (primary.firstSeenAt == null || secondary.firstSeenAt.isBefore(primary.firstSeenAt))) {
            primary.firstSeenAt = secondary.firstSeenAt;
        }
        if (secondary.lastSeenAt != null
                && (primary.lastSeenAt == null || secondary.lastSeenAt.isAfter(primary.lastSeenAt))) {
            primary.lastSeenAt = secondary.lastSeenAt;
        }
        primary.eventCount = nvl(primary.eventCount) + nvl(secondary.eventCount);
        primary.sessionCount = nvlInt(primary.sessionCount) + nvlInt(secondary.sessionCount);

        // 4) mergedFrom 追加 + 原因落列
        List<String> mergedFrom = parseMergedFrom(primary.mergedFrom);
        if (!mergedFrom.contains(secondaryId)) {
            mergedFrom.add(secondaryId);
        }
        primary.mergedFrom = writeJson(mergedFrom);
        primary.mergeReason = reason.trim();

        // 5) secondary 墓碑化（标识列保留：erasure AnyStatus 反查仍可命中）
        secondary.status = IdentityEntity.IdentityStatus.MERGED;
        secondary.primaryIdentityType = IdentityEntity.IdentityType.MERGED;

        identityRepo.save(primary);
        identityRepo.save(secondary);

        // 6) 审计（12 参重载）
        auditLogService.log(AuditLogEntity.AuditAction.UPDATE, "identity", primaryId, primary.getDisplayId(),
                "merge identity " + secondaryId + " into " + primaryId,
                AuditLogEntity.AuditResult.SUCCESS, by, null, primary.mergedFrom, null, null,
                Map.of("gameId", gameId, "secondaryIdentityId", secondaryId,
                        "movedLinks", moved, "dedupedLinks", deduped, "reason", primary.mergeReason));

        logger.info("Identity merged: game={} {} <- {} (moved={} deduped={}) by={}",
                gameId, primaryId, secondaryId, moved, deduped, by);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("identityId", primaryId);
        out.put("mergedSecondaryId", secondaryId);
        out.put("mergedFrom", mergedFrom);
        out.put("movedLinks", moved);
        out.put("dedupedLinks", deduped);
        out.put("status", secondary.status.name());
        return out;
    }

    /** 加载可参与合并的身份：存在、属于该游戏、未软删；已 MERGED 拒绝（幂等护栏）。 */
    private IdentityEntity loadMergable(String gameId, String id, String label) {
        IdentityEntity entity = identityRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(label + " identity not found: " + id));
        if (!gameId.equals(entity.gameId)) {
            throw new IllegalArgumentException(label + " identity does not belong to game " + gameId + ": " + id);
        }
        if (entity.deletedAt != null) {
            throw new IllegalArgumentException(label + " identity is deleted: " + id);
        }
        if (entity.status == IdentityEntity.IdentityStatus.MERGED) {
            throw new IllegalStateException(label + " identity is already merged: " + id);
        }
        return entity;
    }

    private static List<String> parseMergedFrom(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> list = JSON.readValue(json, new TypeReference<List<String>>() {});
            return list != null ? new ArrayList<>(list) : new ArrayList<>();
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }

    private static String writeJson(List<String> list) {
        try {
            return JSON.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize mergedFrom", e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static long nvl(Long v) {
        return v == null ? 0L : v;
    }

    private static int nvlInt(Integer v) {
        return v == null ? 0 : v;
    }
}
