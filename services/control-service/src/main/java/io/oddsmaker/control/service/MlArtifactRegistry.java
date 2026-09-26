package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.MlArtifactEntity;
import io.oddsmaker.control.jpa.MlArtifactRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * ml 训练产物注册表（P4.4 收尾）：
 * - 注册：接收 ml/（oddsmaker-ml）版本化 JSON 产物，校验（语义对齐 Python 侧
 *   validate_artifact）后落 ml_model_artifacts；同 (game, model_type, model_version)
 *   重训覆盖。注册动作写审计。
 * - 查询：按 game（+ 可选 model_type）列出已注册版本；resolveActive 取最新版本。
 * - 打分侧消费：PredictionMetricsService 优先用校验通过的产物打分，缺失或不匹配
 *   回落启发式——两条路径以 predictions.model_id/model_version 与返回体 path 区分。
 */
@Service
@Transactional
public class MlArtifactRegistry {

    private static final Logger logger = LoggerFactory.getLogger(MlArtifactRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 支持的产物类型（对齐 ml 侧 MODEL_TYPES；propensity 训练管线落地后再加） */
    public static final Set<String> SUPPORTED_TYPES = Set.of("churn", "pltv", "risk");

    private final MlArtifactRepo repo;
    private final AuditLogService auditLogService;

    public MlArtifactRegistry(MlArtifactRepo repo, AuditLogService auditLogService) {
        this.repo = repo;
        this.auditLogService = auditLogService;
    }

    /** 校验并注册产物；同 (game, type, version) 覆盖更新。 */
    public MlArtifactEntity register(String gameId, Map<String, Object> artifact, String createdBy) {
        Parsed parsed = validate(artifact);
        MlArtifactEntity entity = repo
                .findByGameIdAndModelTypeAndModelVersion(gameId, parsed.type(), parsed.version())
                .orElseGet(MlArtifactEntity::new);
        if (entity.id == null) {
            entity.id = "mla_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            entity.gameId = gameId;
            entity.modelType = parsed.type();
            entity.modelVersion = parsed.version();
        }
        entity.source = str(artifact.get("source"));
        entity.trainedAt = str(artifact.get("trained_at"));
        entity.featureNames = parsed.featureNames() == null ? null : toJson(parsed.featureNames());
        entity.coefficients = parsed.coefficients() == null ? null : toJson(parsed.coefficients());
        entity.intercept = parsed.intercept();
        entity.multiplier = parsed.multiplier();
        entity.metrics = artifact.get("metrics") == null ? null : toJson(artifact.get("metrics"));
        entity.heuristicBaseline = artifact.get("heuristic_baseline") == null
                ? null : toJson(artifact.get("heuristic_baseline"));
        entity.artifactJson = toJson(artifact);
        entity.status = "ACTIVE";
        entity.createdBy = createdBy;

        entity = repo.save(entity);

        auditLogService.logCreate("ml_model_artifact", entity.id,
                parsed.type() + ":" + parsed.version(), createdBy, createdBy, null,
                Map.of("gameId", gameId, "modelType", parsed.type(),
                        "modelVersion", parsed.version(), "source", str(entity.source)));

        logger.info("Registered ml artifact: game={} type={} version={} id={}",
                gameId, parsed.type(), parsed.version(), entity.id);
        return entity;
    }

    /** 打分消费入口：该游戏该类型的最新已注册产物（无则 Optional.empty → 回落启发式）。 */
    public Optional<MlArtifactEntity> resolveActive(String gameId, String modelType) {
        return repo.findFirstByGameIdAndModelTypeOrderByCreatedAtDescIdDesc(gameId, modelType);
    }

    /** 已注册版本列表；modelType 空白时返回该游戏全部类型。 */
    public List<MlArtifactEntity> listVersions(String gameId, String modelType) {
        if (modelType == null || modelType.isBlank()) {
            return repo.findByGameIdOrderByCreatedAtDescIdDesc(gameId);
        }
        return repo.findByGameIdAndModelTypeOrderByCreatedAtDescIdDesc(gameId, modelType);
    }

    /**
     * 产物校验（语义对齐 ml/oddsmaker_ml/artifact.py validate_artifact）：
     * schema_version=1；model_type ∈ {churn, pltv, risk}；model_version 非空；
     * churn/risk 需 feature_names（非空字符串数组）+ 等长数值 coefficients + intercept；
     * pltv 需正数 multiplier。不满足抛 IllegalArgumentException。
     */
    Parsed validate(Map<String, Object> artifact) {
        if (artifact == null) {
            throw new IllegalArgumentException("产物校验失败: 产物为空");
        }
        String type = str(artifact.get("model_type"));
        if (!SUPPORTED_TYPES.contains(type)) {
            throw new IllegalArgumentException("产物校验失败: 不支持的 model_type: " + type);
        }
        if (!(artifact.get("schema_version") instanceof Number schema) || schema.intValue() != 1) {
            throw new IllegalArgumentException("产物校验失败: schema_version 必须为 1");
        }
        String version = str(artifact.get("model_version"));
        if (version.isEmpty()) {
            throw new IllegalArgumentException("产物校验失败: model_version 缺失");
        }

        List<String> featureNames = null;
        double[] coefficients = null;
        Double intercept = null;
        Double multiplier = null;
        if ("pltv".equals(type)) {
            multiplier = readPositiveDouble(artifact.get("multiplier"), "multiplier");
        } else {
            featureNames = readStringArray(artifact.get("feature_names"));
            coefficients = readDoubleArray(artifact.get("coefficients"));
            if (featureNames.isEmpty()) {
                throw new IllegalArgumentException("产物校验失败: feature_names 为空");
            }
            if (coefficients.length != featureNames.size()) {
                throw new IllegalArgumentException("产物校验失败: coefficients 与 feature_names 长度不一致 ("
                        + coefficients.length + " vs " + featureNames.size() + ")");
            }
            intercept = readPositiveOrAnyDouble(artifact.get("intercept"), "intercept");
        }
        return new Parsed(type, version, featureNames, coefficients, intercept, multiplier);
    }

    /**
     * 校验结果：type/version + churn|risk 的 featureNames/coefficients/intercept
     * 或 pltv 的 multiplier。
     */
    record Parsed(String type, String version, List<String> featureNames,
                  double[] coefficients, Double intercept, Double multiplier) {}

    // ===== 解析辅助（非法输入一律 IllegalArgumentException，字段名指位） =====

    private static List<String> readStringArray(Object value) {
        try {
            JsonNode node = MAPPER.readTree(MAPPER.writeValueAsString(value));
            if (!node.isArray()) {
                throw new IllegalArgumentException("产物校验失败: feature_names 需为数组");
            }
            List<String> out = new ArrayList<>();
            node.forEach(n -> {
                if (!n.isTextual() || n.asText().isBlank()) {
                    throw new IllegalArgumentException("产物校验失败: feature_names 含空白/非字符串元素");
                }
                out.add(n.asText());
            });
            return out;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("产物校验失败: feature_names 解析失败: " + e.getMessage(), e);
        }
    }

    private static double[] readDoubleArray(Object value) {
        try {
            JsonNode node = MAPPER.readTree(MAPPER.writeValueAsString(value));
            if (!node.isArray()) {
                throw new IllegalArgumentException("产物校验失败: coefficients 需为数组");
            }
            double[] out = new double[node.size()];
            for (int i = 0; i < node.size(); i++) {
                JsonNode n = node.get(i);
                if (!n.isNumber()) {
                    throw new IllegalArgumentException("产物校验失败: coefficients 含非数值元素: " + n);
                }
                out[i] = n.asDouble();
            }
            return out;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("产物校验失败: coefficients 解析失败: " + e.getMessage(), e);
        }
    }

    private static Double readPositiveOrAnyDouble(Object value, String field) {
        if (!(value instanceof Number n)) {
            throw new IllegalArgumentException("产物校验失败: " + field + " 缺失或非数值");
        }
        return n.doubleValue();
    }

    private static Double readPositiveDouble(Object value, String field) {
        Double v = readPositiveOrAnyDouble(value, field);
        if (v <= 0 || v.isNaN() || v.isInfinite()) {
            throw new IllegalArgumentException("产物校验失败: " + field + " 必须为正数");
        }
        return v;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("产物序列化失败: " + e.getMessage(), e);
        }
    }
}
