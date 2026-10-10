package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.ApiKeyEntity;
import io.oddsmaker.control.jpa.ApiKeyRepo;
import io.oddsmaker.control.jpa.DimensionPullConfigEntity;
import io.oddsmaker.control.jpa.DimensionPullConfigRepo;
import io.oddsmaker.control.jpa.DimensionSyncStatusEntity;
import io.oddsmaker.control.jpa.DimensionSyncStatusRepo;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.GameRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 维度同步 HTTP Pull 链路（dimension-sync.md HTTP Pull 落地）。
 *
 * Control scheduler 按 (game, environment, sourceKey) 配置定期拉游戏方查询接口：
 * GET {endpoint}?updated_after=&limit=（Bearer 凭证 AES-GCM 加密托管），响应含条目数组与
 * next_cursor（断点续传：推送成功才前进 cursor，缺省回落本页最大 version_ts）。行数据翻译成
 * RawDimensionChange(source_type=pull) 形状的 dimension_define NDJSON 事件，POST 既有
 * Gateway /v1/batch 入口（x-api-key，与 Agent/Webhook Push 同通道），下游 dimension-sync-job
 * 写 item_dim/level_dim（ReplacingMergeTree(version_ts) 重放幂等）。
 *
 * 事务口径：runPull 不开 Spring 事务（HTTP 调用不持库连接），进度/状态各走 repo 自带事务；
 * 失败只记 error_count/last_error 不改 cursor，下一轮以旧断点重放同窗口。
 */
@Service
public class DimensionPullService {

    private static final Logger logger = LoggerFactory.getLogger(DimensionPullService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String SOURCE_TYPE = "pull";
    public static final String EVENT_NAME = "dimension_define";
    public static final String EVENT_TYPE = "dimension";
    public static final String DEVICE_ID = "oddsmaker-control-pull";

    /** 响应条目数组的兼容键（按序探测）；next_cursor 为断点键 */
    static final Set<String> ARRAY_KEYS = Set.of("items", "resources", "levels", "data");
    /** 控制列：进 props / 决定 version_ts，不进 attributes */
    static final Set<String> CONTROL_KEYS = Set.of(
            "dim_type", "dimension_type", "resource_id", "item_code", "level_id", "id", "code",
            "op", "version_ts", "updated_at", "updated_ts");
    /** version_ts 候选键（epoch millis 数字优先，ISO-8601 文本次之） */
    static final String[] VERSION_KEYS = {"version_ts", "updated_at", "updated_ts"};

    private final DimensionPullConfigRepo configRepo;
    private final DimensionSyncStatusRepo statusRepo;
    private final GameRepo gameRepo;
    private final GameEnvironmentRepo envRepo;
    private final ApiKeyRepo keyRepo;
    private final DimensionCredentialCipher cipher;
    private final RestTemplate restTemplate;
    private final String gatewayUrl;

    public DimensionPullService(DimensionPullConfigRepo configRepo,
                                DimensionSyncStatusRepo statusRepo,
                                GameRepo gameRepo,
                                GameEnvironmentRepo envRepo,
                                ApiKeyRepo keyRepo,
                                DimensionCredentialCipher cipher,
                                RestTemplate restTemplate,
                                @Value("${oddsmaker.gateway.url:http://localhost:8080}") String gatewayUrl) {
        this.configRepo = configRepo;
        this.statusRepo = statusRepo;
        this.gameRepo = gameRepo;
        this.envRepo = envRepo;
        this.keyRepo = keyRepo;
        this.cipher = cipher;
        this.restTemplate = restTemplate;
        this.gatewayUrl = gatewayUrl;
    }

    // ===== 请求/响应体 =====

    /** 配置请求体：environment/sourceKey/endpoint 必填，credential 建档必填（更新缺省保留），其余可缺省。 */
    public static final class PullConfigUpsert {
        public String environment;
        public String sourceKey;
        public String dimType;
        public String endpoint;
        public String credential;
        public Integer intervalSeconds;
        public Integer pageLimit;
        public Boolean enabled;
    }

    /** 配置响应：凭证密文永不回显。 */
    public static Map<String, Object> toView(DimensionPullConfigEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id);
        m.put("gameId", e.gameId);
        m.put("environment", e.environment);
        m.put("sourceKey", e.sourceKey);
        m.put("dimType", e.dimType);
        m.put("endpoint", e.endpoint);
        m.put("enabled", e.enabled);
        m.put("intervalSeconds", e.intervalSeconds);
        m.put("pageLimit", e.pageLimit);
        m.put("lastCursor", e.lastCursor);
        m.put("lastPullAt", e.lastPullAt);
        m.put("lastEventTs", e.lastEventTs);
        m.put("pushedCount", e.pushedCount);
        m.put("errorCount", e.errorCount);
        m.put("lastError", e.lastError);
        m.put("updatedAt", e.updatedAt);
        return m;
    }

    /** 单轮拉取结果。 */
    public record PullRunResult(boolean success, int pushed, String cursor, String error) {
        static PullRunResult ok(int pushed, String cursor) {
            return new PullRunResult(true, pushed, cursor, null);
        }

        static PullRunResult fail(String error) {
            return new PullRunResult(false, 0, null, error);
        }
    }

    // ===== CRUD =====

    @Transactional
    public Map<String, Object> create(String gameId, PullConfigUpsert req) {
        requireGame(gameId);
        requireUpsert(req, true);
        DimensionPullConfigEntity e = new DimensionPullConfigEntity();
        e.id = "dpc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        e.gameId = gameId;
        e.environment = requireEnvironment(gameId, req.environment);
        e.sourceKey = req.sourceKey.trim();
        requireSourceKeyUnique(e);
        e.dimType = req.dimType == null || req.dimType.isBlank() ? "item" : normalizeDimType(req.dimType.trim());
        e.endpoint = requireEndpoint(req.endpoint.trim());
        e.credentialEncrypted = cipher.encrypt(req.credential.trim());
        e.enabled = req.enabled == null ? Boolean.TRUE : req.enabled;
        e.intervalSeconds = req.intervalSeconds == null ? 300 : requireInterval(req.intervalSeconds);
        e.pageLimit = req.pageLimit == null ? 1000 : requirePageLimit(req.pageLimit);
        e.createdAt = LocalDateTime.now();
        e.updatedAt = e.createdAt;
        return toView(configRepo.save(e));
    }

    @Transactional
    public Map<String, Object> update(String gameId, String id, PullConfigUpsert req) {
        DimensionPullConfigEntity e = findOwned(gameId, id);
        String oldEnv = e.environment;
        String oldKey = e.sourceKey;
        // credential 缺省 = 保留既有凭证（更新其余字段不动密钥）
        if (req.credential != null && !req.credential.isBlank()) {
            e.credentialEncrypted = cipher.encrypt(req.credential.trim());
        }
        if (req.environment != null && !req.environment.isBlank()) {
            e.environment = requireEnvironment(gameId, req.environment);
        }
        if (req.sourceKey != null && !req.sourceKey.isBlank()) {
            e.sourceKey = req.sourceKey.trim();
        }
        if (!e.environment.equals(oldEnv) || !e.sourceKey.equals(oldKey)) {
            requireSourceKeyUnique(e);
        }
        if (req.dimType != null && !req.dimType.isBlank()) {
            e.dimType = normalizeDimType(req.dimType.trim());
        }
        if (req.endpoint != null && !req.endpoint.isBlank()) {
            e.endpoint = requireEndpoint(req.endpoint.trim());
        }
        if (req.intervalSeconds != null) {
            e.intervalSeconds = requireInterval(req.intervalSeconds);
        }
        if (req.pageLimit != null) {
            e.pageLimit = requirePageLimit(req.pageLimit);
        }
        if (req.enabled != null) {
            e.enabled = req.enabled;
        }
        e.updatedAt = LocalDateTime.now();
        return toView(configRepo.save(e));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String gameId) {
        requireGame(gameId);
        return configRepo.findByGameId(gameId).stream()
                .map(DimensionPullService::toView)
                .collect(Collectors.toList());
    }

    @Transactional
    public void delete(String gameId, String id) {
        configRepo.delete(findOwned(gameId, id));
    }

    // ===== 拉取 =====

    /** 调度入口：15s tick，按配置 intervalSeconds 判到期；单页有界延迟，续页下一 tick 继续。 */
    @Scheduled(fixedDelay = 15000)
    public void runDuePulls() {
        for (DimensionPullConfigEntity config : configRepo.findAll()) {
            if (!Boolean.TRUE.equals(config.enabled)) {
                continue;
            }
            long intervalMs = Math.max(10, config.intervalSeconds == null ? 300 : config.intervalSeconds) * 1000L;
            if (config.lastPullAt != null
                    && Duration.between(config.lastPullAt, LocalDateTime.now()).toMillis() < intervalMs) {
                continue;
            }
            try {
                runPull(config);
            } catch (Exception e) {
                // runPull 内部已兜底，此处防御调度线程因意外异常中断后续配置
                logger.warn("HTTP Pull 意外失败 {} {}/{}: {}", config.gameId, config.environment, config.sourceKey, e.toString());
            }
        }
    }

    /** 手动触发（控制台/运维用）。 */
    public PullRunResult runNow(String gameId, String id) {
        return runPull(findOwned(gameId, id));
    }

    /**
     * 单轮拉取：一页 fetch + 翻译 + 推送 + 断点前进 + 状态记录。无 Spring 事务（HTTP 不持库
     * 连接），repo.save 各自提交；推送失败不改 cursor（下一轮旧断点重放同窗口，下游幂等）。
     */
    public PullRunResult runPull(DimensionPullConfigEntity config) {
        LocalDateTime now = LocalDateTime.now();
        try {
            String credential = cipher.decrypt(config.credentialEncrypted);
            PullPage page = fetchPage(config, credential);
            List<Map<String, Object>> events = translateRows(page.rows(), config.gameId, config.environment, config.dimType);
            if (!events.isEmpty()) {
                pushToGateway(config.gameId, config.environment, toNdjson(events));
            }
            // 断点前进：优先服务端 next_cursor，缺省回落本页最大 version_ts（无行/无时间保持原断点）
            String nextCursor = page.nextCursor();
            long maxEventTs = maxVersionTs(events);
            if (nextCursor == null) {
                nextCursor = maxEventTs > 0 ? String.valueOf(maxEventTs) : config.lastCursor;
            }
            config.lastCursor = nextCursor;
            config.lastPullAt = now;
            if (maxEventTs > 0) {
                config.lastEventTs = LocalDateTime.ofInstant(Instant.ofEpochMilli(maxEventTs), ZoneOffset.UTC);
            }
            config.pushedCount += events.size();
            config.errorCount = 0L;
            config.lastError = null;
            config.updatedAt = now;
            configRepo.save(config);
            recordStatus(config);
            return PullRunResult.ok(events.size(), nextCursor);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            config.errorCount = (config.errorCount == null ? 0L : config.errorCount) + 1L;
            config.lastError = truncate(message, 500);
            config.lastPullAt = now;
            config.updatedAt = now;
            configRepo.save(config);
            recordStatus(config);
            logger.warn("HTTP Pull 失败 {} {}/{}: {}", config.gameId, config.environment, config.sourceKey, message);
            return PullRunResult.fail(message);
        }
    }

    // ===== HTTP 两端 =====

    /** 拉一页：GET {endpoint}?updated_after={cursor}&limit={n}，Bearer 鉴权。 */
    PullPage fetchPage(DimensionPullConfigEntity config, String credential) {
        String url = buildEndpointUrl(config.endpoint, config.lastCursor, config.pageLimit);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + credential);
        ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("游戏 API 返回 HTTP " + resp.getStatusCode().value());
        }
        if (resp.getBody() == null || resp.getBody().isBlank()) {
            throw new IllegalStateException("游戏 API 响应为空");
        }
        return parseGameResponse(resp.getBody());
    }

    /** 推既有事件入口：NDJSON POST Gateway /v1/batch（x-api-key，与 Agent 同契约）。 */
    void pushToGateway(String gameId, String environment, String ndjson) {
        String apiKey = pickScopedKey(gameId, environment);
        if (apiKey == null) {
            throw new IllegalStateException("该游戏环境没有可用的 server/admin API Key，无法推送维度事件");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", apiKey);
        headers.setContentType(MediaType.parseMediaType("application/x-ndjson"));
        ResponseEntity<String> resp = restTemplate.exchange(
                gatewayUrl + "/v1/batch", HttpMethod.POST, new HttpEntity<>(ndjson, headers), String.class);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Gateway 推送失败 HTTP " + resp.getStatusCode().value());
        }
    }

    /**
     * 选绑定到 (gameId, 环境名) 的 ACTIVE server/admin key——与
     * {@link InspectorProxyService} 的 pickScopedKey 同语义（独立私有实现，不改已交付代理链）。
     */
    private String pickScopedKey(String gameId, String environment) {
        List<ApiKeyEntity> keys = keyRepo.findByGameIdAndStatus(gameId, ApiKeyEntity.ApiKeyStatus.ACTIVE);
        for (ApiKeyEntity key : keys) {
            if (key.keyType != ApiKeyEntity.ApiKeyType.SERVER && key.keyType != ApiKeyEntity.ApiKeyType.ADMIN) {
                continue;
            }
            GameEnvironmentEntity env = envRepo.findById(key.environmentId)
                    .map(e -> (GameEnvironmentEntity) org.hibernate.Hibernate.unproxy(e))
                    .orElse(null);
            if (env == null || env.deletedAt != null || !gameId.equals(env.gameId)) {
                continue;
            }
            if (environment.equalsIgnoreCase(env.name)) {
                return key.apiKey;
            }
        }
        return null;
    }

    // ===== 纯函数：URL 拼装 / 响应解析 / 行翻译 / NDJSON =====

    /** 断点拼 URL：cursor 非空带 updated_after（URL 编码），endpoint 已带查询串用 & 续接。 */
    static String buildEndpointUrl(String endpoint, String cursor, int limit) {
        StringBuilder sb = new StringBuilder(endpoint.trim());
        String separator = sb.indexOf("?") >= 0 ? "&" : "?";
        if (cursor != null && !cursor.isBlank()) {
            sb.append(separator).append("updated_after=")
                    .append(URLEncoder.encode(cursor.trim(), StandardCharsets.UTF_8));
            separator = "&";
        }
        return sb.append(separator).append("limit=").append(limit).toString();
    }

    /** 响应解析：条目数组按 items/resources/levels/data 探测，next_cursor 空串归 null。 */
    static PullPage parseGameResponse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("游戏 API 响应不是合法 JSON");
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("游戏 API 响应不是 JSON 对象");
        }
        JsonNode rows = null;
        for (String key : ARRAY_KEYS) {
            if (root.path(key).isArray()) {
                rows = root.path(key);
                break;
            }
        }
        if (rows == null) {
            throw new IllegalArgumentException("游戏 API 响应缺少条目数组（items/resources/levels/data 之一）");
        }
        List<JsonNode> items = new ArrayList<>();
        rows.forEach(items::add);
        String nextCursor = root.path("next_cursor").asText("");
        return new PullPage(items, nextCursor.isBlank() ? null : nextCursor);
    }

    /** 单页结果：行 + 服务端断点。 */
    record PullPage(List<JsonNode> rows, String nextCursor) {
    }

    /** 行翻译成 dimension_define 事件（RawDimensionChange(source_type=pull) 形状）。 */
    static List<Map<String, Object>> translateRows(List<JsonNode> rows, String gameId, String environment, String defaultDimType) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (JsonNode row : rows) {
            events.add(rowToEvent(row, gameId, environment, defaultDimType));
        }
        return events;
    }

    /** NDJSON 序列化（行分隔，与 GatewaySink 同契约）。 */
    static String toNdjson(List<Map<String, Object>> events) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> event : events) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            try {
                sb.append(MAPPER.writeValueAsString(event));
            } catch (Exception e) {
                throw new IllegalArgumentException("事件序列化失败: " + e.getMessage());
            }
        }
        return sb.toString();
    }

    /** 本页最大 version_ts（断点回落值与 lastEventTs 来源；无时间字段返回 0）。 */
    static long maxVersionTs(List<Map<String, Object>> events) {
        long max = 0;
        for (Map<String, Object> event : events) {
            Object ts = ((Map<?, ?>) event.get("props")).get("version_ts");
            if (ts instanceof Number n && n.longValue() > max) {
                max = n.longValue();
            }
        }
        return max;
    }

    /** 行翻译：控制列抽 props，其余进 attributes（对齐 dimension-sync-job parseProps 归一化兜底）。 */
    static Map<String, Object> rowToEvent(JsonNode row, String gameId, String environment, String defaultDimType) {
        if (!row.isObject()) {
            throw new IllegalArgumentException("条目不是 JSON 对象");
        }
        String dimType = firstNonEmptyText(row, defaultDimType, "dim_type", "dimension_type");
        String op = firstNonEmptyText(row, "upsert", "op");
        String resourceId = firstNonEmptyText(row, null, "resource_id", "item_code", "level_id", "id", "code");
        if (resourceId == null) {
            throw new IllegalArgumentException("条目缺少资源标识（resource_id/item_code/level_id/id/code 之一）");
        }
        long versionTs = parseVersionTs(row, System.currentTimeMillis());

        Map<String, Object> attributes = new LinkedHashMap<>();
        row.fields().forEachRemaining(e -> {
            if (!CONTROL_KEYS.contains(e.getKey())) {
                attributes.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asText(""));
            }
        });

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("source_type", SOURCE_TYPE);
        props.put("dim_type", normalizeDimType(dimType));
        props.put("resource_id", resourceId);
        props.put("op", op);
        props.put("version_ts", versionTs);
        props.put("attributes", attributes);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_id", UUID.randomUUID().toString());
        event.put("event_name", EVENT_NAME);
        event.put("event_type", EVENT_TYPE);
        event.put("game_id", gameId);
        event.put("environment", environment);
        event.put("device_id", DEVICE_ID);
        event.put("ts_server", System.currentTimeMillis());
        event.put("props", props);
        return event;
    }

    /** version_ts 候选键解析：epoch millis 数字优先，ISO-8601 文本次之，都解析不了取 now。 */
    static long parseVersionTs(JsonNode row, long now) {
        for (String key : VERSION_KEYS) {
            JsonNode v = row.path(key);
            if (v.isMissingNode() || v.isNull()) {
                continue;
            }
            long ts = parseTimestamp(v);
            if (ts > 0) {
                return ts;
            }
        }
        return now;
    }

    static long parseTimestamp(JsonNode v) {
        if (v.isNumber()) {
            return v.asLong();
        }
        String text = v.asText("").trim();
        if (text.isEmpty()) {
            return 0;
        }
        if (text.chars().allMatch(Character::isDigit)) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // 落到无时区本地时间解析
        }
        try {
            return LocalDateTime.parse(text).atZone(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return 0;
        }
    }

    /** resource/items→item，levels→level，其余原样（与 JdbcSource/dimension-sync-job 同口径）。 */
    static String normalizeDimType(String v) {
        if (v == null) {
            return "item";
        }
        return switch (v.trim().toLowerCase()) {
            case "resource", "items" -> "item";
            case "levels" -> "level";
            default -> v.trim().toLowerCase();
        };
    }

    /** 依次取第一个非空文本键；全空返回 fallback。 */
    static String firstNonEmptyText(JsonNode node, String fallback, String... keys) {
        for (String key : keys) {
            String v = node.path(key).asText("");
            if (!v.isBlank()) {
                return v.trim();
            }
        }
        return fallback;
    }

    // ===== 内部 =====

    /** 同步状态落 dimension_sync_status（source_type=pull），与 Agent 上报同表同口径可观测。 */
    private void recordStatus(DimensionPullConfigEntity config) {
        DimensionSyncStatusEntity status = statusRepo
                .findByGameIdAndEnvironmentAndSourceKey(config.gameId, config.environment, config.sourceKey)
                .orElseGet(DimensionSyncStatusEntity::new);
        if (status.id == null) {
            status.id = "dps_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            status.gameId = config.gameId;
            status.environment = config.environment;
            status.sourceKey = config.sourceKey;
            status.createdAt = LocalDateTime.now();
        }
        status.sourceType = SOURCE_TYPE;
        status.cursor = config.lastCursor;
        status.lastEventTs = config.lastEventTs;
        status.lastPushAt = LocalDateTime.now();
        status.pushedCount = config.pushedCount;
        status.errorCount = config.errorCount;
        status.lastError = config.lastError;
        status.updatedAt = LocalDateTime.now();
        statusRepo.save(status);
    }

    private void requireGame(String gameId) {
        gameRepo.findById(gameId)
                .filter(game -> game.deletedAt == null)
                .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameId));
    }

    private DimensionPullConfigEntity findOwned(String gameId, String id) {
        DimensionPullConfigEntity e = configRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Pull config not found: " + id));
        if (!gameId.equals(e.gameId)) {
            throw new IllegalArgumentException("Pull config not found: " + id);
        }
        return e;
    }

    private void requireUpsert(PullConfigUpsert req, boolean creating) {
        if (req.environment == null || req.environment.isBlank()) {
            throw new IllegalArgumentException("environment 不能为空");
        }
        if (req.sourceKey == null || req.sourceKey.isBlank()) {
            throw new IllegalArgumentException("sourceKey 不能为空");
        }
        if (req.endpoint == null || req.endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint 不能为空");
        }
        if (creating && (req.credential == null || req.credential.isBlank())) {
            throw new IllegalArgumentException("credential 不能为空");
        }
    }

    /** 环境名必须属于该游戏（防跨游戏环境串写）。 */
    private String requireEnvironment(String gameId, String environment) {
        String name = environment.trim();
        envRepo.findByGameIdAndNameAndDeletedAtIsNull(gameId, name).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Environment not found: " + name));
        return name;
    }

    /** 唯一键 (game, environment, sourceKey) 冲突校验。 */
    private void requireSourceKeyUnique(DimensionPullConfigEntity e) {
        configRepo.findByGameIdAndEnvironmentAndSourceKey(e.gameId, e.environment, e.sourceKey)
                .ifPresent(other -> {
                    if (!other.id.equals(e.id)) {
                        throw new IllegalArgumentException("同游戏同环境已存在同名 sourceKey: " + e.sourceKey);
                    }
                });
    }

    private static String requireEndpoint(String endpoint) {
        String lower = endpoint.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException("endpoint 必须以 http:// 或 https:// 开头");
        }
        return endpoint;
    }

    private static int requireInterval(Integer seconds) {
        if (seconds < 10 || seconds > 86400) {
            throw new IllegalArgumentException("intervalSeconds 需在 10..86400 之间");
        }
        return seconds;
    }

    private static int requirePageLimit(Integer limit) {
        if (limit < 1 || limit > 5000) {
            throw new IllegalArgumentException("pageLimit 需在 1..5000 之间");
        }
        return limit;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
