package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.dto.IdentityEventDto;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.IdentityEntity;
import io.oddsmaker.control.jpa.IdentityLinkEntity;
import io.oddsmaker.control.jpa.IdentityLinkRepo;
import io.oddsmaker.control.jpa.IdentityRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 身份事件消费者。
 *
 * 消费 Flink IdentityMergeJob 双写到 oddsmaker.identity_events 的 JSON 消息，
 * 把身份快照落到 PG identities 主表 + 展平到 identity_links（device/player/character/user 各一行）。
 *
 * 幂等（适配 Flink at-least-once）：主表按 id upsert，links 按 (identityId, linkedIdentityType, linkedId) 三元组 upsert。
 * 字段冲突处理见 Phase 0.2 计划：identity_id 已在源头缩到 32 字符；environment 名→id 消费侧解析（miss 则 null）；数组扇出到 links。
 */
@Component
public class IdentityConsumer {

    private static final Logger logger = LoggerFactory.getLogger(IdentityConsumer.class);

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IdentityRepo identityRepo;

    @Autowired
    private IdentityLinkRepo identityLinkRepo;

    @Autowired
    private GameEnvironmentRepo gameEnvironmentRepo;

    /** 环境名→ID 缓存（key = gameId|envName）。用 Optional 包装，允许缓存「未找到(null)」语义。 */
    private final Map<String, Optional<String>> envCache = new ConcurrentHashMap<>();

    @KafkaListener(topics = "oddsmaker.identity_events", groupId = "control-service-identity")
    @Transactional
    public void onIdentityEvent(String message) {
        IdentityEventDto dto;
        try {
            dto = objectMapper.readValue(message, IdentityEventDto.class);
        } catch (Exception e) {
            logger.error("Failed to deserialize identity event: {}", e.getMessage());
            return;
        }

        if (dto.identityId == null || dto.identityId.isBlank()) {
            logger.warn("Identity event has no identity_id, skipping");
            return;
        }

        try {
            boolean mergedTombstone = upsertIdentity(dto);
            if (mergedTombstone) {
                return;  // 合并墓碑：主表与 links 均不重建
            }
            upsertLinks(dto);
            logger.info("Identity upserted: id={} game={} devices={} players={}",
                    dto.identityId, dto.gameId,
                    dto.deviceIds != null ? dto.deviceIds.size() : 0,
                    dto.playerIds != null ? dto.playerIds.size() : 0);
        } catch (Exception e) {
            logger.error("Failed to handle identity event {}: {}", dto.identityId, e.getMessage(), e);
        }
    }

    // ========== 主表 upsert ==========

    /** @return true 表示该身份是合并墓碑（已跳过重建，links 也应跳过） */
    private boolean upsertIdentity(IdentityEventDto dto) {
        String primaryDevice = firstNonEmpty(dto.deviceIds);
        String primaryId = primaryDevice != null ? primaryDevice
                : (nonEmpty(dto.userId) ? dto.userId : dto.identityId);

        IdentityEntity entity = identityRepo.findById(dto.identityId).orElse(null);

        // 人工合并墓碑（status=MERGED）：保留合并决定，不被 Flink 重放复活为 ACTIVE。
        // links 亦不重建——合并时已把活跃链重挂到幸存身份。
        if (entity != null && entity.status == IdentityEntity.IdentityStatus.MERGED) {
            logger.debug("Identity {} is merged, skipping auto upsert", dto.identityId);
            return true;
        }

        LocalDateTime firstSeen = toDateTime(dto.firstSeen);
        LocalDateTime lastSeen = toDateTime(dto.lastSeen);

        if (entity == null) {
            entity = new IdentityEntity();
            entity.id = dto.identityId;
            entity.gameId = dto.gameId;
            entity.environmentId = resolveEnvironmentId(dto.gameId, dto.environment);
            entity.primaryIdentityType = IdentityEntity.IdentityType.DEVICE;
            entity.primaryId = primaryId;
            entity.userId = dto.userId;
            entity.playerId = dto.playerId;
            entity.deviceId = primaryDevice;
            entity.status = IdentityEntity.IdentityStatus.ACTIVE;
            entity.firstSeenAt = firstSeen;
            entity.lastSeenAt = lastSeen != null ? lastSeen : LocalDateTime.now();
            entity.eventCount = 1L;
            entity.sessionCount = 0;
            entity.confidenceScore = 1.0;
        } else {
            entity.lastSeenAt = lastSeen != null ? lastSeen : LocalDateTime.now();
            if (entity.firstSeenAt == null) entity.firstSeenAt = firstSeen;
            if (nonEmpty(dto.userId)) entity.userId = dto.userId;
            if (nonEmpty(dto.playerId)) entity.playerId = dto.playerId;
            if (primaryDevice != null) entity.deviceId = primaryDevice;
            entity.eventCount = (entity.eventCount != null ? entity.eventCount : 0L) + 1;
        }
        identityRepo.save(entity);
        return false;
    }

    // ========== links 扇出 upsert ==========

    private void upsertLinks(IdentityEventDto dto) {
        LocalDateTime lastSeen = toDateTime(dto.lastSeen);
        if (dto.deviceIds != null) {
            for (String d : dto.deviceIds) {
                if (nonEmpty(d)) upsertLink(dto.identityId, "device_id", d, lastSeen);
            }
        }
        if (dto.playerIds != null) {
            for (String p : dto.playerIds) {
                if (nonEmpty(p)) upsertLink(dto.identityId, "player_id", p, lastSeen);
            }
        }
        if (dto.characterIds != null) {
            for (String c : dto.characterIds) {
                if (nonEmpty(c)) upsertLink(dto.identityId, "character_id", c, lastSeen);
            }
        }
        if (nonEmpty(dto.userId)) {
            upsertLink(dto.identityId, "user_id", dto.userId, lastSeen);
        }
    }

    private void upsertLink(String identityId, String type, String value, LocalDateTime lastSeen) {
        IdentityLinkEntity link = identityLinkRepo
                .findActiveByIdentityIdAndTypeAndLinkedId(identityId, type, value)
                .orElse(null);
        if (link == null) {
            link = new IdentityLinkEntity();
            link.id = "ilk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);  // 4+24=28 字符，对齐 VARCHAR(32)
            link.identityId = identityId;
            link.linkedIdentityType = type;
            link.linkedId = value;
            link.linkType = IdentityLinkEntity.LinkType.ASSOCIATED;
            link.linkStrength = 1.0;
            link.verificationStatus = IdentityLinkEntity.VerificationStatus.PENDING;
            link.linkSource = "identity-merge-job";
            link.status = IdentityLinkEntity.LinkStatus.ACTIVE;
            link.firstLinkedAt = lastSeen;
            link.usageCount = 1L;
        } else {
            link.usageCount = (link.usageCount != null ? link.usageCount : 0L) + 1;
        }
        link.lastSeenAt = lastSeen != null ? lastSeen : LocalDateTime.now();
        identityLinkRepo.save(link);
    }

    // ========== 辅助 ==========

    private String resolveEnvironmentId(String gameId, String envName) {
        if (gameId == null || envName == null || envName.isBlank()) return null;
        String cacheKey = gameId + "|" + envName;
        Optional<String> cached = envCache.get(cacheKey);
        if (cached != null) return cached.orElse(null);

        String resolved = null;
        try {
            List<GameEnvironmentEntity> envs = gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull(gameId, envName);
            if (envs != null && !envs.isEmpty()) {
                resolved = envs.get(0).id;
            } else {
                logger.warn("Environment not found: game={}, name={}, environmentId left null", gameId, envName);
            }
        } catch (Exception e) {
            logger.warn("Failed to resolve environment game={} name={}: {}", gameId, envName, e.getMessage());
        }
        envCache.put(cacheKey, Optional.ofNullable(resolved));  // null 也缓存，避免反复打 DB
        return resolved;
    }

    private static String firstNonEmpty(List<String> list) {
        if (list == null) return null;
        for (String s : list) {
            if (nonEmpty(s)) return s;
        }
        return null;
    }

    private static boolean nonEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    private static LocalDateTime toDateTime(Long epochMillis) {
        if (epochMillis == null) return null;
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
    }
}
