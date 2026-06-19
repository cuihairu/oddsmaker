package io.oddsmaker.control.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.api.ControlService;
import io.oddsmaker.control.dto.ExperimentConfigDTO;
import io.oddsmaker.control.dto.ExperimentDTO;
import io.oddsmaker.control.experiment.ExperimentEntity;
import io.oddsmaker.control.experiment.ExperimentRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.GameRepo;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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

    private static final Set<String> STATUSES = Set.of("draft", "running", "paused");

    private final ExperimentRepo experimentRepo;
    private final GameRepo gameRepo;
    private final GameEnvironmentRepo environmentRepo;
    private final ObjectMapper objectMapper;

    public ExperimentService(ExperimentRepo experimentRepo,
                             GameRepo gameRepo,
                             GameEnvironmentRepo environmentRepo,
                             ObjectMapper objectMapper) {
        this.experimentRepo = experimentRepo;
        this.gameRepo = gameRepo;
        this.environmentRepo = environmentRepo;
        this.objectMapper = objectMapper;
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
            normalizeStatusFilter(status),
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

        String status = normalizeStatus(dto.status, "draft");
        JsonNode config = normalizeConfig(dto.config);
        validateConfig(config, "running".equals(status));

        Instant now = Instant.now();
        ExperimentEntity entity = new ExperimentEntity();
        entity.id = id;
        entity.gameId = dto.gameId;
        entity.environmentId = environment.id;
        entity.name = requireName(dto.name);
        entity.status = status;
        entity.salt = dto.salt == null || dto.salt.isBlank() ? id : dto.salt.trim();
        entity.configJson = writeConfig(config);
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
        if (dto.config != null) {
            JsonNode config = normalizeConfig(dto.config);
            validateConfig(config, "running".equals(entity.status));
            entity.configJson = writeConfig(config);
        }
        if (dto.status != null) {
            String status = normalizeStatus(dto.status, entity.status);
            if ("running".equals(status)) {
                validateConfig(readConfig(entity.configJson), true);
            }
            entity.status = status;
        }
        entity.updatedAt = Instant.now();
        return toDto(experimentRepo.save(entity));
    }

    public ExperimentDTO publishExperiment(String id) {
        ExperimentEntity entity = experimentRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Experiment not found: " + id));
        validateConfig(readConfig(entity.configJson), true);
        entity.status = "running";
        entity.updatedAt = Instant.now();
        return toDto(experimentRepo.save(entity));
    }

    public ExperimentDTO pauseExperiment(String id) {
        ExperimentEntity entity = experimentRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Experiment not found: " + id));
        entity.status = "paused";
        entity.updatedAt = Instant.now();
        return toDto(experimentRepo.save(entity));
    }

    public boolean deleteExperiment(String id) {
        if (!experimentRepo.existsById(id)) {
            return false;
        }
        experimentRepo.deleteById(id);
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

    private GameEntity requireGame(String gameId) {
        if (gameId == null || gameId.isBlank()) {
            throw new IllegalArgumentException("gameId is required");
        }
        return gameRepo.findById(gameId)
            .filter(game -> game.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameId));
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
        dto.status = entity.status;
        dto.salt = entity.salt;
        dto.config = readConfig(entity.configJson);
        dto.createdAt = entity.createdAt;
        dto.updatedAt = entity.updatedAt;
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
        if (config == null || !config.isObject()) {
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

    private String normalizeStatusFilter(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return normalizeStatus(status, null);
    }

    private String normalizeStatus(String status, String defaultStatus) {
        String normalized = status == null || status.isBlank()
            ? defaultStatus
            : status.trim().toLowerCase(Locale.ROOT);
        if (normalized == null) {
            return null;
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
}
