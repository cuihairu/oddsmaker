package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.RemoteConfigEntity;
import io.oddsmaker.control.jpa.RemoteConfigRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Remote Config / LiveOps 联动：游戏级键值配置管理。
 * 运营端 CRUD（环境特定 key 覆盖全环境同名 key）；游戏服/SDK 按 (gameId, environment)
 * 拉取生效配置与聚合版本（生效配置 version 之和，任一变更即变化）。
 */
@Service
public class RemoteConfigService {

    private final RemoteConfigRepo repo;
    private final GameRepo gameRepo;
    private final ObjectMapper objectMapper;

    public RemoteConfigService(RemoteConfigRepo repo, GameRepo gameRepo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.gameRepo = gameRepo;
        this.objectMapper = objectMapper;
    }

    /** 运营端列表（含 INACTIVE；environment 过滤可选） */
    @Transactional(readOnly = true)
    public java.util.List<RemoteConfigEntity> list(String gameId, String environment) {
        requireGame(gameId);
        java.util.List<RemoteConfigEntity> all = repo.findByGameId(gameId);
        if (environment == null || environment.isBlank()) {
            return all;
        }
        return all.stream().filter(c -> environment.equals(c.environmentId)).toList();
    }

    @Transactional(readOnly = true)
    public RemoteConfigEntity get(String id) {
        return repo.findById(id)
            .filter(c -> c.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Remote config not found: " + id));
    }

    /** 创建：key 格式校验 + JSON 值校验 + (game, env, key) 唯一 */
    @Transactional
    public RemoteConfigEntity create(RemoteConfigEntity config, String operator) {
        requireGame(config.gameId);
        validateKey(config.configKey);
        validateJson(config.configValue);
        if (config.environmentId == null) {
            config.environmentId = "";
        }
        repo.findByGameIdAndEnvironmentIdAndConfigKeyAndDeletedAtIsNull(
                config.gameId, config.environmentId, config.configKey)
            .ifPresent(c -> {
                throw new IllegalStateException("Config key already exists: "
                    + config.configKey + "@" + (config.environmentId.isEmpty() ? "*" : config.environmentId));
            });
        config.id = "rc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        config.version = 1;
        config.updatedBy = operator;
        return repo.save(config);
    }

    /** 更新值/状态：version 自增 */
    @Transactional
    public RemoteConfigEntity update(String id, String configValue, RemoteConfigEntity.Status status,
                                     String description, String operator) {
        RemoteConfigEntity config = get(id);
        if (configValue != null) {
            validateJson(configValue);
            config.configValue = configValue;
            config.version++;
        }
        if (status != null) {
            config.status = status;
            config.version++;
        }
        if (description != null) {
            config.description = description;
        }
        config.updatedBy = operator;
        return repo.save(config);
    }

    /** 软删除 */
    @Transactional
    public void delete(String id) {
        RemoteConfigEntity config = get(id);
        config.deletedAt = java.time.LocalDateTime.now();
        repo.save(config);
    }

    /**
     * 生效配置解析（SDK/游戏服拉取）：全环境 + 环境特定（同 key 覆盖），
     * 仅 ACTIVE。返回 {version: 生效配置 version 之和, configs: {key: 解析后的 JSON 值}}。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> resolve(String gameId, String environment) {
        requireGame(gameId);
        String env = environment == null || environment.isBlank() ? null : environment;
        Map<String, Object> configs = new LinkedHashMap<>();
        long version = 0;
        // 全环境先入，环境特定覆盖（列表已按 environmentId 排序，'' 在前）
        for (RemoteConfigEntity config : repo.findEffective(gameId, env)) {
            if (config.status != RemoteConfigEntity.Status.ACTIVE) {
                continue;
            }
            configs.put(config.configKey, parse(config.configValue));
            version += config.version;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gameId", gameId);
        out.put("environment", env);
        out.put("version", version);
        out.put("configs", configs);
        return out;
    }

    private void validateKey(String key) {
        if (key == null || !key.matches("[a-zA-Z][a-zA-Z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(
                "configKey must start with a letter and contain only [a-zA-Z0-9._-]: " + key);
        }
    }

    private void validateJson(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("configValue (JSON) is required");
        }
        try {
            objectMapper.readTree(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("configValue is not valid JSON: " + e.getMessage());
        }
    }

    private Object parse(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node.isBoolean()) {
                return node.booleanValue();
            }
            if (node.isIntegralNumber()) {
                return node.longValue();
            }
            if (node.isFloatingPointNumber()) {
                return node.doubleValue();
            }
            if (node.isTextual()) {
                return node.textValue();
            }
            return objectMapper.convertValue(node, Object.class);
        } catch (Exception e) {
            return value;  // 原样返回，不阻断拉取
        }
    }

    private void requireGame(String gameId) {
        GameEntity game = gameRepo.findById(gameId)
            .filter(g -> g.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameId));
    }
}
