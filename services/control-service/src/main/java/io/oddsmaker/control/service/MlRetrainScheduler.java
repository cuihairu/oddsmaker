package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.MlArtifactRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * ml 产物自动重训调度（P4.4 后续，训练调度衔接点）：
 * 发现已注册产物的游戏 → 触发 ml/（oddsmaker-ml）训练子进程
 * （优先 ClickHouse 真实数据，不可用时按开关回落合成数据跑通）
 * → 产物逐个校验注册（同版本重训覆盖即幂等）→ 触发批量打分回写 predictions。
 *
 * <p>诚实降级：训练进程失败/产物非法/打分异常只写审计与日志，不抛出、
 * 不阻塞既有链路；ClickHouse 未配置且禁用合成数据时跳过并写 SKIPPED 审计。
 * 调度入口在 {@link MLModelService#scheduledMlRetrain()}（cron 可配），
 * 开关 oddsmaker.ml.retrain.enabled 默认关闭。
 */
@Service
public class MlRetrainScheduler {

    private static final Logger logger = LoggerFactory.getLogger(MlRetrainScheduler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单轮训练的产物类型（对齐 ml CLI --model all） */
    static final List<String> ARTIFACT_TYPES = List.of("churn", "pltv", "risk", "propensity");

    static final String ACTOR = "ml-retrain";

    private final MlArtifactRepo repo;
    private final MlArtifactRegistry registry;
    private final PredictionMetricsService metrics;
    private final AuditLogService auditLogService;
    private final ClickHouseClient clickHouseClient;
    private final MlTrainingRunner runner;

    private final boolean enabled;
    private final String pythonBin;
    private final String mlDir;
    private final String modelVersion;
    private final String environment;
    private final String clickhouseUrl;
    private final String jdbcUrl;
    private final long timeoutSeconds;
    private final boolean allowSynthetic;

    public MlRetrainScheduler(
            MlArtifactRepo repo,
            MlArtifactRegistry registry,
            PredictionMetricsService metrics,
            AuditLogService auditLogService,
            ClickHouseClient clickHouseClient,
            MlTrainingRunner runner,
            @Value("${oddsmaker.ml.retrain.enabled:false}") boolean enabled,
            @Value("${oddsmaker.ml.retrain.python-bin:python3}") String pythonBin,
            @Value("${oddsmaker.ml.retrain.ml-dir:ml}") String mlDir,
            @Value("${oddsmaker.ml.retrain.model-version:v0.1.0}") String modelVersion,
            @Value("${oddsmaker.ml.retrain.environment:prod}") String environment,
            @Value("${oddsmaker.ml.retrain.clickhouse-url:}") String clickhouseUrl,
            @Value("${oddsmaker.clickhouse.url:}") String jdbcUrl,
            @Value("${oddsmaker.ml.retrain.timeout-seconds:900}") long timeoutSeconds,
            @Value("${oddsmaker.ml.retrain.allow-synthetic:true}") boolean allowSynthetic) {
        this.repo = repo;
        this.registry = registry;
        this.metrics = metrics;
        this.auditLogService = auditLogService;
        this.clickHouseClient = clickHouseClient;
        this.runner = runner;
        this.enabled = enabled;
        this.pythonBin = pythonBin;
        this.mlDir = mlDir;
        this.modelVersion = modelVersion;
        this.environment = environment;
        this.clickhouseUrl = clickhouseUrl;
        this.jdbcUrl = jdbcUrl;
        this.timeoutSeconds = timeoutSeconds;
        this.allowSynthetic = allowSynthetic;
    }

    /** 调度入口：开关关闭或无注册游戏时静默返回。单游戏失败不阻断其余游戏。 */
    public void retrainAll() {
        if (!enabled) {
            return;
        }
        List<String> gameIds;
        try {
            gameIds = repo.findDistinctGameIds();
        } catch (Exception e) {
            logger.error("ml retrain: 查询注册游戏失败，本轮跳过: {}", e.getMessage(), e);
            return;
        }
        if (gameIds.isEmpty()) {
            logger.info("ml retrain: 无已注册产物的游戏，本轮无事可做");
            return;
        }
        for (String gameId : gameIds) {
            try {
                Map<String, Object> summary = retrainForGame(gameId);
                logger.info("ml retrain: game={} 完成 {}", gameId, summary);
            } catch (Exception e) {
                logger.error("ml retrain: game={} 失败: {}", gameId, e.getMessage(), e);
                auditResult(gameId, AuditLogEntity.AuditResult.FAILURE, truncate(e.getMessage()));
            }
        }
    }

    /**
     * 单游戏重训：训练 → 逐产物注册 → 批量打分。
     * 返回摘要（source/registered/scored/errors）；部分失败不抛出，
     * 汇总在 errors 与审计（PARTIAL）里——全部失败才抛给 retrainAll 记 FAILURE。
     */
    public Map<String, Object> retrainForGame(String gameId) throws IOException, InterruptedException {
        List<String> errors = new ArrayList<>();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("gameId", gameId);

        String source = resolveSource();
        summary.put("source", source);
        if ("synthetic".equals(source) && !allowSynthetic) {
            String reason = "ClickHouse 不可用且 allow-synthetic=false，本轮跳过（不落合成产物）";
            summary.put("skipped", reason);
            auditResult(gameId, AuditLogEntity.AuditResult.SKIPPED, reason);
            return summary;
        }

        Path outDir = Files.createTempDirectory("ml-retrain-");
        MlTrainingRunner.Result result = runner.run(buildCommand(source, gameId, outDir),
                Path.of(mlDir), timeoutSeconds);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("训练进程失败（exit=" + result.exitCode() + "）: "
                    + result.outputTail());
        }

        int registered = 0;
        for (String type : ARTIFACT_TYPES) {
            Path artifactFile = outDir.resolve(type + ".json");
            if (!Files.exists(artifactFile)) {
                errors.add(type + ": 产物文件缺失（该模型训练可能失败，见训练输出）");
                continue;
            }
            try {
                Map<String, Object> artifact = MAPPER.readValue(artifactFile.toFile(), Map.class);
                registry.register(gameId, artifact, ACTOR);
                registered++;
            } catch (Exception e) {
                errors.add(type + ": 注册失败: " + e.getMessage());
            }
        }
        summary.put("registered", registered);

        // 注册成功即触发批量打分回写 predictions；打分失败不回滚注册
        List<String> scoreTypes = new ArrayList<>();
        safeScore(gameId, "churn", errors, scoreTypes,
                () -> metrics.refreshChurn(gameId, envOrNull()));
        safeScore(gameId, "risk_model", errors, scoreTypes,
                () -> metrics.refreshRiskScore(gameId, envOrNull()));
        safeScore(gameId, "pltv", errors, scoreTypes,
                () -> metrics.refreshPltv(gameId, envOrNull()));
        safeScore(gameId, "propensity", errors, scoreTypes,
                () -> metrics.refreshPropensity(gameId, envOrNull()));
        summary.put("scored", scoreTypes);
        summary.put("errors", errors);

        if (!errors.isEmpty() && registered == 0) {
            throw new IllegalStateException("全部产物注册失败: " + String.join("; ", errors));
        }
        if (!errors.isEmpty()) {
            auditResult(gameId, AuditLogEntity.AuditResult.PARTIAL,
                    "registered=" + registered + " errors: " + String.join("; ", errors));
        }
        return summary;
    }

    /** 训练数据源：ClickHouse 可用且有可访问的 HTTP url 优先，否则合成数据。 */
    String resolveSource() {
        if (!clickHouseClient.isAvailable()) {
            return "synthetic";
        }
        return resolveClickhouseHttpUrl() != null ? "clickhouse" : "synthetic";
    }

    /** 训练用 HTTP url：显式配置优先，否则从 JDBC url 推导（jdbc:clickhouse://host:port/db → http://host:port/?database=db）。 */
    String resolveClickhouseHttpUrl() {
        if (clickhouseUrl != null && !clickhouseUrl.isBlank()) {
            return clickhouseUrl.trim();
        }
        return httpFromJdbc(jdbcUrl);
    }

    static String httpFromJdbc(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:clickhouse://")) {
            return null;
        }
        String rest = jdbcUrl.substring("jdbc:clickhouse://".length());
        String query = "";
        int q = rest.indexOf('?');
        if (q >= 0) {
            query = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }
        String database = "";
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            database = rest.substring(slash + 1);
            rest = rest.substring(0, slash);
        }
        while (database.endsWith("/")) {  // 宽容尾斜杠：jdbc:clickhouse://host/db/
            database = database.substring(0, database.length() - 1);
        }
        if (rest.isEmpty()) {
            return null;
        }
        String url = "http://" + rest;
        List<String> params = new ArrayList<>();
        if (!database.isBlank()) {
            params.add("database=" + database);
        }
        if (!query.isBlank()) {
            params.add(query);
        }
        return params.isEmpty() ? url : url + "/?" + String.join("&", params);
    }

    private List<String> buildCommand(String source, String gameId, Path outDir) {
        List<String> cmd = new ArrayList<>(List.of(pythonBin, "-m", "oddsmaker_ml", "train",
                "--model", "all", "--source", source, "--out", outDir.toString(),
                "--game-id", gameId, "--environment", environment,
                "--model-version", modelVersion));
        if ("clickhouse".equals(source)) {
            cmd.add("--clickhouse-url");
            cmd.add(resolveClickhouseHttpUrl());
        }
        return cmd;
    }

    /** 触发单类打分：CH 不可用（available=false）如实不算完成；异常记入 errors 不中断。 */
    private void safeScore(String gameId, String type, List<String> errors,
                           List<String> scoreTypes, Supplier<Map<String, Object>> scoring) {
        try {
            Map<String, Object> resp = scoring.get();
            if (resp != null && Boolean.TRUE.equals(resp.get("available"))) {
                scoreTypes.add(type);
                logger.info("ml retrain: game={} 打分回写完成 type={}", gameId, type);
            } else {
                logger.info("ml retrain: game={} 打分跳过 type={}（ClickHouse 不可用）", gameId, type);
            }
        } catch (Exception e) {
            errors.add("打分 " + type + ": " + e.getMessage());
        }
    }

    private void auditResult(String gameId, AuditLogEntity.AuditResult result, String details) {
        try {
            auditLogService.log(AuditLogEntity.AuditAction.UPDATE, "ml_retrain", gameId, gameId,
                    details, result, ACTOR, null, null, null, null, null);
        } catch (Exception e) {
            logger.error("ml retrain: 写审计失败 game={} result={}: {}", gameId, result, e.getMessage(), e);
        }
    }

    private String envOrNull() {
        return environment == null || environment.isBlank() ? null : environment;
    }

    private static String truncate(String message) {
        if (message == null) {
            return "";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
