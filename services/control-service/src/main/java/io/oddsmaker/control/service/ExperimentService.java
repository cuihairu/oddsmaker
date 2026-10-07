package io.oddsmaker.control.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.api.ControlService;
import io.oddsmaker.control.dto.ExperimentConfigDTO;
import io.oddsmaker.control.dto.ExperimentDTO;
import io.oddsmaker.control.experiment.ExperimentEntity;
import io.oddsmaker.control.experiment.ExperimentRepo;
import io.oddsmaker.control.experiment.ExperimentSplitter;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.SegmentEntity;
import io.oddsmaker.control.jpa.SegmentRepo;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A/B 实验控制面服务。
 */
@Service
@Transactional
public class ExperimentService {

    /** 枚举化后的实验生命周期（B8）：与 ExperimentEntity.ExperimentStatus 对齐 */
    private static final Set<String> STATUSES = Set.of("DRAFT", "LIVE", "PAUSED", "ENDED");

    private final ExperimentRepo experimentRepo;
    private final GameRepo gameRepo;
    private final GameEnvironmentRepo environmentRepo;
    private final SegmentRepo segmentRepo;
    private final ObjectMapper objectMapper;

    /** 审计日志（构造器注入保持类风格一致；@InjectMocks 场景字段注入会被构造器注入短路） */
    private final AuditLogService auditLog;

    public ExperimentService(ExperimentRepo experimentRepo,
                             GameRepo gameRepo,
                             GameEnvironmentRepo environmentRepo,
                             SegmentRepo segmentRepo,
                             ObjectMapper objectMapper,
                             AuditLogService auditLog) {
        this.experimentRepo = experimentRepo;
        this.gameRepo = gameRepo;
        this.environmentRepo = environmentRepo;
        this.segmentRepo = segmentRepo;
        this.objectMapper = objectMapper;
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    public ControlService.Paged<ExperimentDTO> listExperiments(String gameId,
                                                               String environmentId,
                                                               String environmentName,
                                                               String status,
                                                               int page,
                                                               int size) {
        String resolvedGameId = blankToNull(gameId);
        String resolvedEnvironmentId = blankToNull(environmentId);
        if (resolvedEnvironmentId == null && environmentName != null && !environmentName.isBlank()) {
            if (resolvedGameId == null) {
                throw new IllegalArgumentException("gameId is required when filtering by environment name");
            }
            resolvedEnvironmentId = requireEnvironment(resolvedGameId, environmentName).id;
        }

        var pageable = PageRequest.of(
            Math.max(0, page),
            Math.max(1, size),
            Sort.by("updatedAt").descending()
        );
        var result = experimentRepo.search(
            resolvedGameId,
            resolvedEnvironmentId,
            parseStatusFilter(status),
            pageable
        );
        return new ControlService.Paged<>(
            result.getContent().stream().map(this::toDto).collect(Collectors.toList()),
            result.getTotalElements()
        );
    }

    @Transactional(readOnly = true)
    public Optional<ExperimentDTO> getExperiment(String id) {
        return experimentRepo.findById(id).map(this::toDto);
    }

    public ExperimentDTO createExperiment(ExperimentDTO dto) {
        requireGame(dto.gameId);
        GameEnvironmentEntity environment = resolveEnvironment(dto.gameId, dto.environmentId, dto.environment);

        String id = dto.id == null || dto.id.isBlank()
            ? "exp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            : dto.id.trim();
        if (experimentRepo.existsById(id)) {
            throw new IllegalArgumentException("Experiment already exists: " + id);
        }

        String status = normalizeStatus(dto.status, "DRAFT");
        JsonNode config = normalizeConfig(dto.config);
        validateConfig(config, "LIVE".equals(status));

        LocalDateTime now = LocalDateTime.now();
        ExperimentEntity entity = new ExperimentEntity();
        entity.id = id;
        entity.gameId = dto.gameId;
        entity.environmentId = environment.id;
        entity.name = requireName(dto.name);
        entity.status = ExperimentEntity.ExperimentStatus.valueOf(status);
        entity.salt = dto.salt == null || dto.salt.isBlank() ? id : dto.salt.trim();
        entity.configJson = writeConfig(config);
        applyFormalizedFields(entity, dto, config);
        entity.createdAt = now;
        entity.updatedAt = now;
        return toDto(experimentRepo.save(entity));
    }

    public ExperimentDTO updateExperiment(String id, ExperimentDTO dto) {
        ExperimentEntity entity = experimentRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Experiment not found: " + id));

        if (dto.gameId != null && !dto.gameId.equals(entity.gameId)) {
            throw new IllegalArgumentException("Experiment game cannot be changed");
        }
        if ((dto.environmentId != null && !dto.environmentId.equals(entity.environmentId)) || dto.environment != null) {
            GameEnvironmentEntity environment = resolveEnvironment(entity.gameId, dto.environmentId, dto.environment);
            if (!environment.id.equals(entity.environmentId)) {
                throw new IllegalArgumentException("Experiment environment cannot be changed");
            }
        }

        if (dto.name != null) {
            entity.name = requireName(dto.name);
        }
        if (dto.salt != null) {
            entity.salt = dto.salt.isBlank() ? entity.id : dto.salt.trim();
        }
        JsonNode config = null;
        if (dto.config != null) {
            config = normalizeConfig(dto.config);
            validateConfig(config, entity.status == ExperimentEntity.ExperimentStatus.LIVE);
            entity.configJson = writeConfig(config);
        }
        if (dto.status != null) {
            String status = normalizeStatus(dto.status, entity.status != null ? entity.status.name() : "DRAFT");
            if ("LIVE".equals(status)) {
                validateConfig(readConfig(entity.configJson), true);
            }
            entity.status = ExperimentEntity.ExperimentStatus.valueOf(status);
        }
        applyFormalizedFields(entity, dto, config);
        entity.updatedAt = LocalDateTime.now();
        return toDto(experimentRepo.save(entity));
    }

    public ExperimentDTO publishExperiment(String id) {
        ExperimentEntity entity = experimentRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Experiment not found: " + id));
        validateConfig(readConfig(entity.configJson), true);
        entity.status = ExperimentEntity.ExperimentStatus.LIVE;
        entity.updatedAt = LocalDateTime.now();
        return toDto(experimentRepo.save(entity));
    }

    public ExperimentDTO pauseExperiment(String id) {
        ExperimentEntity entity = experimentRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Experiment not found: " + id));
        entity.status = ExperimentEntity.ExperimentStatus.PAUSED;
        entity.updatedAt = LocalDateTime.now();
        return toDto(experimentRepo.save(entity));
    }

    public boolean deleteExperiment(String id) {
        if (!experimentRepo.existsById(id)) {
            return false;
        }
        experimentRepo.deleteById(id);
        auditLog.logDelete("experiment", id, id, "api", "api", null);
        return true;
    }

    @Transactional(readOnly = true)
    public List<ExperimentConfigDTO> getRunningConfig(String gameId, String environmentName) {
        requireGame(gameId);
        GameEnvironmentEntity environment = requireEnvironment(gameId, environmentName);
        if (!environment.isActive()) {
            throw new IllegalStateException("Environment is not active: " + environmentName);
        }
        return experimentRepo.findRunningConfigs(gameId, environment.id).stream()
            .map(this::toConfigDto)
            .collect(Collectors.toList());
    }

    /**
     * 服务端分流：为主体分配变体（仅 LIVE 实验分流，DRAFT/PAUSED 返回 null 由调用方兜底）。
     * 与 SDK 端使用相同的确定性哈希算法（ExperimentSplitter）。
     */
    @Transactional(readOnly = true)
    public String assign(String experimentId, String subjectId) {
        if (subjectId == null || subjectId.isBlank()) {
            throw new IllegalArgumentException("subjectId is required");
        }
        ExperimentEntity entity = experimentRepo.findById(experimentId)
            .orElseThrow(() -> new IllegalArgumentException("Experiment not found: " + experimentId));
        if (entity.status != ExperimentEntity.ExperimentStatus.LIVE) {
            return null;
        }
        JsonNode config = readConfig(entity.configJson);
        String controlVariant = config.path("control_variant").asText(null);
        List<ExperimentSplitter.Variant> variants = ExperimentSplitter.parseVariants(config);
        String assigned = ExperimentSplitter.assign(experimentId, entity.salt, subjectId.trim(), variants);
        if (assigned == null && controlVariant != null) {
            return controlVariant;
        }
        return assigned;
    }

    /**
     * B8 形式化字段写入：audience 引用校验 + variants/allocation/guardrails/decision 落列。
     * 全部字段「提供才写、缺席保留现值」（部分更新不清空既有列）；variants 与 configJson
     * 保持同步——dto.variants 直写优先，否则从本次随带的 config 提取，使旧 configJson
     * 路径写入的 variants 同样落列、新列缺失时读路径回退 config。
     */
    private void applyFormalizedFields(ExperimentEntity entity, ExperimentDTO dto, JsonNode config) {
        String audienceSegmentId = blankToNull(dto.audienceSegmentId);
        if (audienceSegmentId != null) {
            requireSegment(entity.gameId, audienceSegmentId);
            entity.audienceSegmentId = audienceSegmentId;
        } else if (dto.audienceSegmentId != null) {
            entity.audienceSegmentId = null;  // 显式传空串 = 清除引用
        }

        JsonNode variants = dto.variants != null && !dto.variants.isNull() && !dto.variants.isMissingNode()
            ? dto.variants
            : (config != null ? config.get("variants") : null);
        if (variants != null) {
            entity.variantsJson = writeConfig(variants);
        }
        if (dto.allocationInfo != null) {
            entity.allocationInfo = writeConfig(dto.allocationInfo);
        }
        if (dto.guardrails != null) {
            entity.guardrailsJson = writeConfig(dto.guardrails);
        }
        if (dto.decision != null) {
            entity.decisionJson = writeConfig(dto.decision);
        }
    }

    private GameEntity requireGame(String gameId) {
        if (gameId == null || gameId.isBlank()) {
            throw new IllegalArgumentException("gameId is required");
        }
        return gameRepo.findById(gameId)
            .filter(game -> game.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameId));
    }

    private void requireSegment(String gameId, String segmentId) {
        SegmentEntity segment = segmentRepo.findById(segmentId)
            .filter(s -> s.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Segment not found: " + segmentId));
        if (!segment.gameId.equals(gameId)) {
            throw new IllegalArgumentException("Segment does not belong to game: " + segmentId);
        }
    }

    private GameEnvironmentEntity resolveEnvironment(String gameId, String environmentId, String environmentName) {
        String normalizedEnvironmentId = blankToNull(environmentId);
        if (normalizedEnvironmentId != null) {
            Optional<GameEnvironmentEntity> byId = environmentRepo.findById(normalizedEnvironmentId)
                .filter(environment -> environment.deletedAt == null);
            if (byId.isPresent()) {
                GameEnvironmentEntity environment = byId.get();
                if (!environment.gameId.equals(gameId)) {
                    throw new IllegalArgumentException("Environment does not belong to game: " + normalizedEnvironmentId);
                }
                return environment;
            }
            return requireEnvironment(gameId, normalizedEnvironmentId);
        }
        return requireEnvironment(gameId, environmentName);
    }

    private GameEnvironmentEntity requireEnvironment(String gameId, String environmentName) {
        if (environmentName == null || environmentName.isBlank()) {
            throw new IllegalArgumentException("environmentId or environment is required");
        }
        String normalizedName = environmentName.trim().toLowerCase(Locale.ROOT);
        return environmentRepo.findByGameIdAndNameAndDeletedAtIsNull(gameId, normalizedName).stream()
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Environment not found: " + environmentName));
    }

    private ExperimentDTO toDto(ExperimentEntity entity) {
        ExperimentDTO dto = new ExperimentDTO();
        dto.id = entity.id;
        dto.gameId = entity.gameId;
        dto.environmentId = entity.environmentId;
        dto.environment = environmentRepo.findById(entity.environmentId)
            .filter(environment -> environment.deletedAt == null)
            .map(environment -> environment.name)
            .orElse(null);
        dto.name = entity.name;
        dto.status = entity.status.name();
        dto.salt = entity.salt;
        dto.config = readConfig(entity.configJson);
        dto.createdAt = entity.createdAt;
        dto.updatedAt = entity.updatedAt;
        // B8 形式化字段：新列优先，variants 回退旧 configJson（向前兼容）
        dto.audienceSegmentId = entity.audienceSegmentId;
        dto.variants = entity.variantsJson != null
            ? readConfig(entity.variantsJson)
            : dto.config.path("variants");
        dto.allocationInfo = entity.allocationInfo != null ? readConfig(entity.allocationInfo) : null;
        dto.guardrails = entity.guardrailsJson != null ? readConfig(entity.guardrailsJson) : null;
        dto.decision = entity.decisionJson != null ? readConfig(entity.decisionJson) : null;
        return dto;
    }

    private ExperimentConfigDTO toConfigDto(ExperimentEntity entity) {
        ExperimentConfigDTO dto = new ExperimentConfigDTO();
        dto.id = entity.id;
        dto.salt = entity.salt;
        dto.config = readConfig(entity.configJson);
        return dto;
    }

    private JsonNode normalizeConfig(JsonNode config) {
        return config != null ? config : objectMapper.createObjectNode();
    }

    private void validateConfig(JsonNode config, boolean requireRunnable) {
        // 两个调用点（normalizeConfig/readConfig）对 null 入参均兜底 createObjectNode，config 恒非 null
        if (!config.isObject()) {
            throw new IllegalArgumentException("Experiment config must be a JSON object");
        }
        if (config.has("targeting") && !config.get("targeting").isObject()) {
            throw new IllegalArgumentException("Experiment targeting must be a JSON object");
        }
        if (config.has("metrics") && !config.get("metrics").isObject()) {
            throw new IllegalArgumentException("Experiment metrics must be a JSON object");
        }

        JsonNode variants = config.get("variants");
        if (variants == null) {
            if (requireRunnable) {
                throw new IllegalArgumentException("Running experiment requires variants");
            }
            return;
        }
        if (!variants.isArray() || variants.size() < 2) {
            throw new IllegalArgumentException("Experiment variants must contain at least two variants");
        }

        int totalWeight = 0;
        Set<String> names = new HashSet<>();
        for (JsonNode variant : variants) {
            if (!variant.isObject()) {
                throw new IllegalArgumentException("Experiment variant must be a JSON object");
            }
            JsonNode name = variant.get("name");
            if (name == null || !name.isTextual() || name.asText().isBlank()) {
                throw new IllegalArgumentException("Experiment variant name is required");
            }
            String normalizedName = name.asText().trim();
            if (!names.add(normalizedName)) {
                throw new IllegalArgumentException("Experiment variant name must be unique: " + normalizedName);
            }
            JsonNode weight = variant.get("weight");
            if (weight == null || !weight.isIntegralNumber() || weight.asInt() <= 0) {
                throw new IllegalArgumentException("Experiment variant weight must be a positive integer");
            }
            totalWeight += weight.asInt();
        }
        if (totalWeight <= 0) {
            throw new IllegalArgumentException("Experiment variants total weight must be greater than zero");
        }
    }

    private JsonNode readConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(configJson);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Experiment config is not valid JSON: " + ex.getMessage());
        }
    }

    private String writeConfig(JsonNode config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Experiment config is not valid JSON: " + ex.getMessage());
        }
    }

    private ExperimentEntity.ExperimentStatus parseStatusFilter(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return ExperimentEntity.ExperimentStatus.valueOf(normalizeStatus(status, null));
    }

    private String normalizeStatus(String status, String defaultStatus) {
        // 调用点的 defaultStatus 均非 null（filter 入口已提前拦截 blank），normalized 不会为 null
        String normalized = status == null || status.isBlank()
            ? defaultStatus
            : status.trim().toUpperCase(Locale.ROOT);
        // 向前兼容：旧 API 值 "running" 映射枚举名 "LIVE"（存储与输出一律枚举名）
        if ("RUNNING".equals(normalized)) {
            normalized = "LIVE";
        }
        if (!STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported experiment status: " + status);
        }
        return normalized;
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Experiment name is required");
        }
        return name.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * B8 形式化：实验结束（LIVE → ENDED，单向不可逆）。
     * 与状态机对齐：ENDED 为终态，不能再次状态变更。
     */
    public ExperimentDTO endExperiment(String id) {
        ExperimentEntity entity = experimentRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Experiment not found: " + id));
        if (entity.status != ExperimentEntity.ExperimentStatus.LIVE) {
            throw new IllegalStateException("Only LIVE experiments can be ended");
        }
        entity.status = ExperimentEntity.ExperimentStatus.ENDED;
        entity.updatedAt = LocalDateTime.now();
        return toDto(experimentRepo.save(entity));
    }
}

