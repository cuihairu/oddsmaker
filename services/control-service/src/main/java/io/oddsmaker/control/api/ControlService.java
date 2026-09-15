package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Control Service - 单公司多游戏模型
 * ApiKey 绑定到 Game + Environment。
 */
@Service
@Transactional
public class ControlService {
    private final ApiKeyRepo keyRepo;
    private final GameRepo gameRepo;
    private final GameEnvironmentRepo envRepo;
    private final StorageProfileRepo storageProfileRepo;
    private final io.oddsmaker.control.service.AuditLogService auditLog;

    public ControlService(ApiKeyRepo keyRepo,
                          GameRepo gameRepo,
                          GameEnvironmentRepo envRepo,
                          StorageProfileRepo storageProfileRepo,
                          io.oddsmaker.control.service.AuditLogService auditLog) {
        this.keyRepo = keyRepo;
        this.gameRepo = gameRepo;
        this.envRepo = envRepo;
        this.storageProfileRepo = storageProfileRepo;
        this.auditLog = auditLog;
    }

    public Models.ApiKeyResp createKey(String gameId, String environmentId, String name) {
        return createKey(gameId, environmentId, name, null);
    }

    public Models.ApiKeyResp createKey(String gameId, String environmentId, String name, String keyRole) {
        GameEntity game = gameRepo.findById(gameId)
            .filter(entity -> entity.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameId));

        GameEnvironmentEntity environment = envRepo.findById(environmentId)
            .filter(entity -> entity.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Environment not found: " + environmentId));

        if (!Objects.equals(environment.gameId, game.id)) {
            throw new IllegalArgumentException("Environment does not belong to game: " + environmentId);
        }

        ApiKeyEntity.ApiKeyType role = parseKeyRole(keyRole);

        ApiKeyEntity e = new ApiKeyEntity();
        e.apiKey = gen("pk_"); e.secret = gen("sk_");
        e.gameId = gameId;
        e.environmentId = environmentId;
        e.name = name; e.rpm = 600; e.ipRpm = 300;
        e.keyType = role;
        switch (role) {
            case SERVER -> {
                // Server SDK 持有 secret，必须强制签名
                e.requireHmac = true;
                e.canWrite = true;
            }
            case ADMIN -> {
                // 管理查询用途：只读
                e.canWrite = false;
                e.canRead = true;
                e.canExport = true;
                e.requireHmac = true;
            }
            default -> {
                // 客户端 key 不持有签名密钥
                e.requireHmac = false;
                e.canWrite = true;
            }
        }
        keyRepo.save(e);
        auditLog.logCreate("api_key", e.apiKey, e.name, "api", "api", null,
            Map.of("gameId", gameId, "environmentId", environmentId,
                "keyRole", String.valueOf(role),
                "requireHmac", String.valueOf(e.requireHmac)));
        return toResp(e);
    }

    private ApiKeyEntity.ApiKeyType parseKeyRole(String keyRole) {
        if (keyRole == null || keyRole.isBlank()) {
            return ApiKeyEntity.ApiKeyType.CLIENT;
        }
        try {
            return ApiKeyEntity.ApiKeyType.valueOf(keyRole.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid keyRole: " + keyRole + " (expected client|server|admin)");
        }
    }

    public Models.KeyDetailResp getKey(String apiKey) {
        return keyRepo.findById(apiKey).map(this::toDetail).orElse(null);
    }

    /**
     * 返回 Gateway 校验事件所需的最小凭据视图。
     * 密钥仅能由受服务间认证保护的内部端点读取。
     * 注意：这里不能用 readOnly 事务——只读模式下 findById 会返回未初始化的
     * HibernateProxy，直接读公共字段得到 null，导致 gameId 校验误判。
     */
    @Transactional
    public Models.InternalApiKeyResp getActiveKeyForGateway(String apiKey) {
        ApiKeyEntity key = keyRepo.findById(apiKey)
            .filter(ApiKeyEntity::isActive)
            .orElse(null);
        if (key == null) return null;

        GameEnvironmentEntity env = envRepo.findById(key.environmentId)
            .map(e -> (GameEnvironmentEntity) org.hibernate.Hibernate.unproxy(e))
            .filter(e -> e.deletedAt == null)
            .filter(e -> Objects.equals(e.gameId, key.gameId))
            .orElse(null);
        return env == null ? null : toInternalDetail(key, env);
    }

    public List<Models.KeyDetailResp> listKeys() {
        return keyRepo.findAll().stream().map(this::toDetail).collect(Collectors.toList());
    }

    public Paged<Models.KeyDetailResp> searchKeys(String gameId, String environmentId, String q, int page, int size) {
        var pageable = org.springframework.data.domain.PageRequest.of(page, size,
                org.springframework.data.domain.Sort.by("apiKey").ascending());
        var pg = keyRepo.searchApiKeysByScope(
                gameId == null ? "" : gameId,
                environmentId == null ? "" : environmentId,
                q == null ? "" : q,
                pageable
        );
        var items = pg.getContent().stream().map(this::toDetail).collect(Collectors.toList());
        return new Paged<>(items, pg.getTotalElements());
    }

    public boolean deleteKey(String apiKey) {
        if (!keyRepo.existsById(apiKey)) return false;
        keyRepo.deleteById(apiKey);
        auditLog.logDelete("api_key", apiKey, null, "api", "api", null);
        return true;
    }

    public long deleteKeys(java.util.List<String> apiKeys) {
        if (apiKeys == null || apiKeys.isEmpty()) return 0L;
        keyRepo.deleteAllById(apiKeys);
        for (String apiKey : apiKeys) {
            auditLog.logDelete("api_key", apiKey, null, "api", "api", null);
        }
        return apiKeys.size();
    }

    /**
     * 分页响应。同时输出 items/total 与 content/totalElements 两套字段：
     * content 是前端列表页（Spring Page 事实标准，risk-rules/users/audit-logs 同款）
     * 的读取字段；items 保留给既有消费方（e2e 脚本等）。
     */
    public static class Paged<T> {
        public java.util.List<T> items; public long total;
        public Paged(java.util.List<T> items, long total){ this.items=items; this.total=total; }
        public java.util.List<T> getContent() { return items; }
        public long getTotalElements() { return total; }
    }

    public Models.KeyDetailResp updatePolicy(String apiKey, Models.KeyDetailResp req) {
        return keyRepo.findById(apiKey).map(e -> {
            Integer rpmBefore = e.rpm;
            Integer ipRpmBefore = e.ipRpm;
            if (req.rpm != null) e.rpm = req.rpm;
            if (req.ipRpm != null) e.ipRpm = req.ipRpm;
            if (req.propsAllowlist != null) e.propsAllowlist = String.join(",", req.propsAllowlist);
            if (req.piiEmail != null) e.piiEmail = req.piiEmail;
            if (req.piiPhone != null) e.piiPhone = req.piiPhone;
            if (req.piiIp != null) e.piiIp = req.piiIp;
            if (req.denyKeys != null) e.denyKeys = String.join(",", req.denyKeys);
            if (req.maskKeys != null) e.maskKeys = String.join(",", req.maskKeys);
            keyRepo.save(e);
            auditLog.logUpdate("api_key", e.apiKey, e.name, "api", "api", null,
                Map.of("gameId", String.valueOf(e.gameId),
                    "rpmBefore", String.valueOf(rpmBefore), "rpmAfter", String.valueOf(e.rpm),
                    "ipRpmBefore", String.valueOf(ipRpmBefore), "ipRpmAfter", String.valueOf(e.ipRpm)));
            return toDetail(e);
        }).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<Models.StorageProfileResp> listStorageProfiles() {
        return storageProfileRepo.findAll().stream()
            .filter(profile -> profile.deletedAt == null)
            .sorted(Comparator.comparing(profile -> profile.name))
            .map(this::toStorageProfile)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Models.StorageProfileResp getStorageProfile(String profileId) {
        return storageProfileRepo.findById(profileId)
            .filter(profile -> profile.deletedAt == null)
            .map(this::toStorageProfile)
            .orElse(null);
    }

    public Models.StorageProfileResp createStorageProfile(Models.CreateStorageProfileReq req) {
        String id = req.id != null && !req.id.isBlank() ? req.id : slug(req.name);
        if (storageProfileRepo.existsById(id)) {
            throw new IllegalArgumentException("Storage profile already exists: " + id);
        }
        if (storageProfileRepo.existsByNameAndDeletedAtIsNull(req.name)) {
            throw new IllegalArgumentException("Storage profile name already exists: " + req.name);
        }
        StorageProfileEntity entity = new StorageProfileEntity();
        entity.id = id;
        applyStorageProfile(req, entity);
        storageProfileRepo.save(entity);
        return toStorageProfile(entity);
    }

    public Models.StorageProfileResp updateStorageProfile(String profileId, Models.CreateStorageProfileReq req) {
        StorageProfileEntity entity = storageProfileRepo.findById(profileId)
            .filter(profile -> profile.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Storage profile not found: " + profileId));
        if (req.name != null && !Objects.equals(req.name, entity.name)
            && storageProfileRepo.existsByNameAndDeletedAtIsNull(req.name)) {
            throw new IllegalArgumentException("Storage profile name already exists: " + req.name);
        }
        applyStorageProfile(req, entity);
        storageProfileRepo.save(entity);
        return toStorageProfile(entity);
    }

    public boolean deleteStorageProfile(String profileId) {
        StorageProfileEntity entity = storageProfileRepo.findById(profileId)
            .filter(profile -> profile.deletedAt == null)
            .orElse(null);
        if (entity == null) {
            return false;
        }

        // 检查是否有环境在使用该 profile
        List<GameEnvironmentEntity> environments = envRepo.findByStorageProfileIdAndDeletedAtIsNull(profileId);
        if (!environments.isEmpty()) {
            throw new IllegalStateException(
                "Cannot delete storage profile: " + profileId + ". It is currently in use by " +
                environments.size() + " environment(s). Please reassign or delete the environments first."
            );
        }

        // 软删除
        entity.deletedAt = java.time.LocalDateTime.now();
        entity.active = false;
        storageProfileRepo.save(entity);
        return true;
    }

    private Models.ApiKeyResp toResp(ApiKeyEntity e) {
        Models.ApiKeyResp out = new Models.ApiKeyResp();
        out.apiKey = e.apiKey; out.secret = e.secret;
        out.gameId = e.gameId; out.environmentId = e.environmentId;
        out.storageProfileId = storageProfileIdFor(e.environmentId);
        out.name = e.name;
        out.keyRole = e.keyType == null ? null : e.keyType.name().toLowerCase(Locale.ROOT);
        return out;
    }

    private Models.KeyDetailResp toDetail(ApiKeyEntity e) {
        Models.KeyDetailResp r = new Models.KeyDetailResp();
        r.apiKey = e.apiKey;
        r.gameId = e.gameId; r.environmentId = e.environmentId;
        r.storageProfileId = storageProfileIdFor(e.environmentId);
        r.keyRole = e.keyType == null ? null : e.keyType.name().toLowerCase(Locale.ROOT);
        r.rpm = e.rpm; r.ipRpm = e.ipRpm;
        r.propsAllowlist = split(e.propsAllowlist);
        r.piiEmail = e.piiEmail; r.piiPhone = e.piiPhone; r.piiIp = e.piiIp;
        r.denyKeys = split(e.denyKeys); r.maskKeys = split(e.maskKeys);
        return r;
    }

    private Models.InternalApiKeyResp toInternalDetail(ApiKeyEntity key, GameEnvironmentEntity environment) {
        Models.InternalApiKeyResp out = new Models.InternalApiKeyResp();
        out.apiKey = key.apiKey;
        out.secret = key.secret;
        out.gameId = key.gameId;
        out.environment = environment.name;
        out.keyRole = key.keyType == null ? null : key.keyType.name().toLowerCase(Locale.ROOT);
        // 环境级策略下发：状态门禁 + 采样配置由 Gateway 统一执行
        out.envStatus = environment.status == null ? null : environment.status.name().toLowerCase(Locale.ROOT);
        out.envEnableSampling = environment.enableSampling;
        out.envSampleRate = environment.sampleRate;
        out.canWrite = key.canWrite;
        out.requireHmac = key.requireHmac;
        out.rpm = key.rpm;
        out.ipRpm = key.ipRpm;
        out.propsAllowlist = split(key.propsAllowlist);
        out.piiEmail = key.piiEmail;
        out.piiPhone = key.piiPhone;
        out.piiIp = key.piiIp;
        out.denyKeys = split(key.denyKeys);
        out.maskKeys = split(key.maskKeys);
        return out;
    }

    private Models.StorageProfileResp toStorageProfile(StorageProfileEntity entity) {
        Models.StorageProfileResp out = new Models.StorageProfileResp();
        out.id = entity.id;
        out.name = entity.name;
        out.displayName = entity.displayName;
        out.description = entity.description;
        out.isolationStrategy = entity.isolationStrategy != null ? entity.isolationStrategy.name() : null;
        out.kafkaCluster = entity.kafkaCluster;
        out.clickhouseCluster = entity.clickhouseCluster;
        out.redisCluster = entity.redisCluster;
        out.archiveBucket = entity.archiveBucket;
        out.active = entity.active;
        return out;
    }

    private void applyStorageProfile(Models.CreateStorageProfileReq req, StorageProfileEntity entity) {
        if (req.name == null || req.name.isBlank()) {
            throw new IllegalArgumentException("Storage profile name is required");
        }
        entity.name = req.name;
        entity.displayName = req.displayName != null ? req.displayName : req.name;
        entity.description = req.description;
        entity.kafkaCluster = req.kafkaCluster;
        entity.clickhouseCluster = req.clickhouseCluster;
        entity.redisCluster = req.redisCluster;
        entity.archiveBucket = req.archiveBucket;
        entity.active = req.active != null ? req.active : Boolean.TRUE;
        entity.isolationStrategy = req.isolationStrategy != null
            ? StorageProfileEntity.IsolationStrategy.valueOf(req.isolationStrategy.trim().toUpperCase(Locale.ROOT))
            : StorageProfileEntity.IsolationStrategy.SHARED;
    }

    private String storageProfileIdFor(String environmentId) {
        return envRepo.findById(environmentId)
            .filter(environment -> environment.deletedAt == null)
            .map(environment -> environment.storageProfileId)
            .orElse(null);
    }

    private static List<String> split(String s) {
        if (s == null || s.isBlank()) return null;
        String[] parts = s.split(",");
        List<String> out = new ArrayList<>();
        for (String p : parts) { String t = p.trim(); if (!t.isEmpty()) out.add(t); }
        return out;
    }

    private static String gen(String prefix) {
        byte[] b = new byte[12]; new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(prefix);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String slug(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Storage profile name is required");
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }
}
