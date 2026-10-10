package io.oddsmaker.gateway.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oddsmaker.common.model.Event;
import io.oddsmaker.gateway.config.BlockListClient;
import io.oddsmaker.gateway.config.AuthService;
import io.oddsmaker.gateway.config.JsonSchemaValidator;
import io.oddsmaker.gateway.config.PiiPolicy;
import io.oddsmaker.gateway.config.PolicyService;
import io.oddsmaker.gateway.config.PropsPolicy;
import io.oddsmaker.gateway.config.TrustPolicy;
import io.oddsmaker.gateway.crash.CrashFingerprinter;
import io.oddsmaker.gateway.inspector.EventInspectorBuffer;
import io.oddsmaker.gateway.kafka.AvroPublisher;
import io.oddsmaker.gateway.kafka.DlqPublisher;
import io.oddsmaker.gateway.metrics.DataQualityCounters;
import io.oddsmaker.gateway.metrics.DataQualityCounters.Counter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

@RestController
@RequestMapping("/v1")
public class BatchController {
    private final ObjectMapper om;
    private final AvroPublisher publisher;
    private final DlqPublisher dlq;
    private final PropsPolicy propsPolicy;
    private final JsonSchemaValidator schemaValidator;
    private final PolicyService policyService;
    private final PiiPolicy piiPolicy;
    private final BlockListClient blockListClient;
    private final io.oddsmaker.gateway.security.ReplayGuard replayGuard;
    private final EventInspectorBuffer inspector;
    private final DataQualityCounters counters;

    /** 迟到阈值 5min，对齐 enrich 侧 BoundedOutOfOrderness（EventsEnrichJob watermark）。 */
    static final long LATE_THRESHOLD_MS = 300_000L;

    public BatchController(
            ObjectMapper om,
            AvroPublisher publisher,
            DlqPublisher dlq,
            PropsPolicy propsPolicy,
            JsonSchemaValidator schemaValidator,
            PolicyService policyService,
            PiiPolicy piiPolicy,
            BlockListClient blockListClient,
            io.oddsmaker.gateway.security.ReplayGuard replayGuard,
            EventInspectorBuffer inspector,
            DataQualityCounters counters
    ) {
        this.om = om;
        this.publisher = publisher;
        this.dlq = dlq;
        this.propsPolicy = propsPolicy;
        this.schemaValidator = schemaValidator;
        this.policyService = policyService;
        this.piiPolicy = piiPolicy;
        this.blockListClient = blockListClient;
        this.replayGuard = replayGuard;
        this.inspector = inspector;
        this.counters = counters;
    }

    public static class BatchResponse {
        public List<String> accepted = new CopyOnWriteArrayList<>();
        public List<Map<String, String>> rejected = new CopyOnWriteArrayList<>();
        public int sampled_out = 0;
        public int duplicates = 0;
        public int next_hint_ms = 3000;
    }

    @PostMapping(value = "/batch", consumes = {MediaType.APPLICATION_JSON_VALUE, "application/x-ndjson"})
    public Mono<BatchResponse> batch(
            @RequestHeader(value = "content-encoding", required = false) String encoding,
            @RequestHeader(value = "content-type", required = false) String contentType,
            org.springframework.http.server.reactive.ServerHttpRequest req,
            org.springframework.web.server.ServerWebExchange exchange,
            @RequestBody Mono<byte[]> bodyBytesMono
    ) {
        return bodyBytesMono.flatMap(bytes -> {
            byte[] raw = maybeGunzip(bytes, encoding);
            if (propsPolicy.exceedsRequestLimit(raw)) {
                throw new ResponseStatusException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, "request_too_large");
            }

            List<Event> events = parseEvents(raw, contentType);
            String userAgent = req.getHeaders().getFirst("user-agent");
            String clientIp = extractClientIp(req);
            String apiKey = req.getHeaders().getFirst("x-api-key");
            // HmacFilter 已保证 /v1/batch 到达此处时 key 上下文必在（无 key/无效 key 均 401 于 filter）
            AuthService.ApiKeyContext keyContext = (AuthService.ApiKeyContext) exchange.getAttributes()
                .get("oddsmaker.api_key_context");
            if (!keyContext.envWritable()) {
                throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "environment_unavailable");
            }
            PolicyService.Policy policy = policyService.getPolicy(apiKey);
            PiiPolicy.Overrides piiOverrides = policyToOverrides(policy);

            // 第一遍：规范化 + 基础校验 + 收集封禁检查目标
            BatchResponse resp = new BatchResponse();
            List<Event> validEvents = new ArrayList<>();
            for (Event event : events) {
                // parseEvents 的 JSON/ndjson 两条路径均已跳过 null（isNull 元素/readCompatEvent 返回 null），
                // 此处 event 不可能为 null，无需判空
                normalizeCompatFields(event);
                // B10 数据质量计数：作用域与 inspect() 同口径（scoped 用 key 作用域，
                // 携带错误作用域的事件恰恰要落回开发者自己的计数视图）
                String[] counterScope = counterScope(event, keyContext);
                counters.record(counterScope[0], counterScope[1], Counter.RECEIVED);
                if (event.eventId == null || event.eventName == null || event.gameId == null || event.environment == null || event.deviceId == null) {
                    reject(resp, event, keyContext, "invalid_schema");
                    inspect(event, keyContext, EventInspectorBuffer.OUTCOME_REJECTED, "invalid_schema", null);
                    continue;
                }
                if (!matchesApiKeyScope(event, keyContext)) {
                    reject(resp, event, keyContext, "api_key_scope_mismatch");
                    inspect(event, keyContext, EventInspectorBuffer.OUTCOME_REJECTED, "api_key_scope_mismatch", null);
                    continue;
                }
                // B7 §4.4 Schema 事件面收敛：key 作用域的 ACTIVE EventSchema 未定义该事件 → 拒收
                // （rejectUnknownEvents 默认 true 由 Control 下发；dev 环境豁免：联调期事件先行于 Schema 收敛）
                if (isUnknownEvent(event, keyContext)) {
                    reject(resp, event, keyContext, "unknown_event");
                    inspect(event, keyContext, EventInspectorBuffer.OUTCOME_REJECTED, "unknown_event", null);
                    continue;
                }
                // eventType 兜底已由上方 normalizeCompatFields 完成，此处必非空
                // 风控前置：事件时间戳信差检查（默认 ±24h，可配 oddsmaker.risk.max-event-ts-drift-ms）
                if (!replayGuard.isTimestampPlausible(event.tsClient, System.currentTimeMillis())) {
                    reject(resp, event, keyContext, "invalid_timestamp");
                    inspect(event, keyContext, EventInspectorBuffer.OUTCOME_REJECTED, "invalid_timestamp", null);
                    continue;
                }
                if (event.tsServer == null) {
                    event.tsServer = Instant.now().toEpochMilli();
                }
                if (event.userAgent == null) {
                    event.userAgent = userAgent;
                }
                if (event.clientIp == null) {
                    event.clientIp = clientIp;
                }
                applyPropsFilter(event, policy);
                if (event.props != null && piiPolicy.hasBlockedKeys(event.props, piiOverrides)) {
                    reject(resp, event, keyContext, "pii_blocked");
                    inspect(event, keyContext, EventInspectorBuffer.OUTCOME_REJECTED, "pii_blocked", null);
                    continue;
                }
                if (event.props != null) {
                    event.props = piiPolicy.sanitizeProps(event.props, piiOverrides);
                }
                event.clientIp = piiPolicy.sanitizeClientIp(event.clientIp, piiOverrides);
                if (propsPolicy.exceedsEventLimit(event)) {
                    reject(resp, event, keyContext, "payload_too_large");
                    inspect(event, keyContext, EventInspectorBuffer.OUTCOME_REJECTED, "payload_too_large", null);
                    continue;
                }
                String schemaError = schemaValidator.validate(event);
                if (schemaError != null) {
                    reject(resp, event, keyContext, "invalid_schema");
                    // schema 校验明细进检视面（响应体只回笼统 reason，明细是 Debug View 的核心价值）
                    inspect(event, keyContext, EventInspectorBuffer.OUTCOME_REJECTED, "invalid_schema", schemaError);
                    continue;
                }
                // 事件契约 v2：source/trust_level 为网关权威字段——按 key 档位推导回填，
                // 自抬（CLIENT key 声明 server 档、trust_level 高于推导档）整事件拒绝
                String trustError = TrustPolicy.apply(event, keyContext.keyRole);
                if (trustError != null) {
                    reject(resp, event, keyContext, "trust_escalation", trustError);
                    inspect(event, keyContext, EventInspectorBuffer.OUTCOME_REJECTED, "trust_escalation", trustError);
                    continue;
                }
                // 风控前置：event_id 幂等吸收——schema 合法后才占用幂等位，
                // SDK 重试导致的重复事件静默去重（计入 duplicates，不重复发布、不进 DLQ）
                if (!replayGuard.consumeEventId(event.eventId)) {
                    resp.duplicates++;
                    resp.accepted.add(event.eventId);
                    // 幂等吸收计入 duplicates 且占 accepted 位（恒等式 received = accepted + Σrejected + sampled_out）
                    counters.record(counterScope[0], counterScope[1], Counter.DUPLICATES);
                    counters.record(counterScope[0], counterScope[1], Counter.ACCEPTED);
                    inspect(event, keyContext, EventInspectorBuffer.OUTCOME_DUPLICATE, null, null);
                    continue;
                }
                // 崩溃指纹：error 类型事件注入 crash_hash/crash_message（SDK 已提供则保留），
                // 供 ClickHouse v_crash_top_groups 聚合分组；在 PII 清洗后计算保证指纹一致
                CrashFingerprinter.enrich(event);
                validEvents.add(event);
            }

            // 没有有效事件，直接返回
            if (validEvents.isEmpty()) {
                return Mono.just(resp);
            }

            // 环境级确定性采样：按 device_id 哈希分桶，保证同一设备的事件采样结果稳定，
            // 避免漏斗/留存分析因随机采样断裂。被采样丢弃的事件计入 sampled_out，不进 DLQ。
            final List<Event> eventsToPublish;
            if (keyContext.samplingEnabled()) {
                List<Event> sampledEvents = new ArrayList<>(validEvents.size());
                for (Event event : validEvents) {
                    if (sampledIn(event, keyContext.envSampleRate)) {
                        sampledEvents.add(event);
                    } else {
                        resp.sampled_out++;
                        String[] sampledScope = counterScope(event, keyContext);
                        counters.record(sampledScope[0], sampledScope[1], Counter.SAMPLED_OUT);
                        inspect(event, keyContext, EventInspectorBuffer.OUTCOME_SAMPLED_OUT, null, null);
                    }
                }
                if (sampledEvents.isEmpty()) {
                    return Mono.just(resp);
                }
                eventsToPublish = sampledEvents;
            } else {
                eventsToPublish = validEvents;
            }

            // 2) 构建封禁检查目标（device_id + user_id）
            String gameId = eventsToPublish.get(0).gameId;
            List<BlockListClient.BatchTarget> targets = new ArrayList<>();
            for (Event event : eventsToPublish) {
                // device_id 过 invalid_schema + schema minLength=1 后恒非 null 且非空（见下方注释）
                targets.add(new BlockListClient.BatchTarget("device_id", event.deviceId));
                if (event.userId != null && !event.userId.isEmpty()) {
                    targets.add(new BlockListClient.BatchTarget("player_id", event.userId));
                }
            }

            // 注：不设 targets 空快路径——device_id 过 schema minLength=1 后 targets 恒非空，
            // 空列表场景由 BlockListClient.batchCheck 直接返回空结果兜底

            // 3) 批量检查封禁
            return blockListClient.batchCheck(gameId, targets)
                    .map(blockedMap -> {
                        // 4) 处理事件：封禁的拒绝，非封禁的发布
                        for (Event event : eventsToPublish) {
                            String[] publishScope = counterScope(event, keyContext);
                            if (isBlocked(event, blockedMap)) {
                                reject(resp, event, keyContext, "blocked");
                                inspect(event, keyContext, EventInspectorBuffer.OUTCOME_REJECTED, "blocked", null);
                                continue;
                            }
                            try {
                                publisher.publish(event);
                                resp.accepted.add(event.eventId);
                                counters.record(publishScope[0], publishScope[1], Counter.ACCEPTED);
                                if (event.tsServer != null
                                        && event.tsServer - event.tsClient > LATE_THRESHOLD_MS) {
                                    counters.record(publishScope[0], publishScope[1], Counter.LATE);
                                }
                                inspect(event, keyContext, EventInspectorBuffer.OUTCOME_ACCEPTED, null, null);
                            } catch (Exception ex) {
                                reject(resp, event, keyContext, "kafka_error");
                                inspect(event, keyContext, EventInspectorBuffer.OUTCOME_REJECTED, "kafka_error", null);
                            }
                        }
                        return resp;
                    });
        });
    }

    /**
     * props 白名单过滤：key 级 allowlist 优先，否则回落通用 allowlist。
     * policy 为 null 仅在 HmacFilter 缓存过期 race 下可达（getContext 二次查询返回 null），
     * 走通用过滤兜底。
     */
    private void applyPropsFilter(Event event, PolicyService.Policy policy) {
        if (event.props == null) {
            return;
        }
        if (policy != null && policy.propsAllowlist != null && !policy.propsAllowlist.isEmpty()) {
            event.props = propsPolicy.filterWithAllowlist(event.props, policy.propsAllowlist);
        } else {
            event.props = propsPolicy.filter(event.props);
        }
    }

    private boolean isBlocked(Event event, Map<String, Boolean> blockedMap) {
        // device_id 恒非 null（invalid_schema 前置校验保证），无需判空
        if (Boolean.TRUE.equals(blockedMap.get("device_id:" + event.deviceId))) return true;
        if (event.userId != null) {
            Boolean b = blockedMap.get("player_id:" + event.userId);
            if (Boolean.TRUE.equals(b)) return true;
        }
        return false;
    }

    /**
     * 确定性采样：以 device_id（缺失时退化为 event_id）哈希分桶。
     * 同一设备的所有事件落同一侧，保证漏斗与留存口径一致。
     * 使用 SHA-256 而非 String.hashCode：后者对规整前缀字符串聚集严重，会导致采样偏斜。
     */
    private boolean sampledIn(Event event, double sampleRate) {
        String seed = event.deviceId != null && !event.deviceId.isEmpty()
            ? event.deviceId
            : String.valueOf(event.eventId);
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            int bucket = Math.floorMod(
                ((digest[0] & 0xFF) << 24) | ((digest[1] & 0xFF) << 16) | ((digest[2] & 0xFF) << 8) | (digest[3] & 0xFF),
                10_000);
            return bucket < (int) Math.round(sampleRate * 10_000);
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 为 JVM 必备算法，理论上不可达
            return true;
        }
    }

    private boolean matchesApiKeyScope(Event event, AuthService.ApiKeyContext keyContext) {
        if (keyContext == null || !keyContext.isScoped()) {
            return true;
        }
        return keyContext.gameId.equals(event.gameId)
            && keyContext.environment.equals(event.environment);
    }

    /**
     * B7 §4.4 未知事件判定：仅对已下发 Schema 事件面的 scoped key 生效——
     * rejectUnknownEvents 非 true（未下发/null/false）不启用；dev 环境豁免；
     * eventNames 空清单=Schema 无事件定义，一切事件视为未知（诚实语义）。
     * static 便于无容器单测直接覆盖分支。
     */
    static boolean isUnknownEvent(Event event, AuthService.ApiKeyContext keyContext) {
        if (keyContext == null || !keyContext.isScoped()
                || !Boolean.TRUE.equals(keyContext.rejectUnknownEvents)) {
            return false;
        }
        if ("dev".equalsIgnoreCase(keyContext.environment)) {
            return false;
        }
        return keyContext.eventNames == null || !keyContext.eventNames.contains(event.eventName);
    }

    private List<Event> parseEvents(byte[] raw, String contentType) {
        try {
            String ct = contentType == null ? "application/json" : contentType.toLowerCase(Locale.ROOT);
            if (ct.contains("ndjson")) {
                List<Event> out = new ArrayList<>();
                String s = new String(raw);
                for (String line : s.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    try {
                        // readCompatEvent 对 null 令牌在返回前即抛（convertValue 得 null 引用后
                        // 的 event.gameId 访问 NPE），null 行走本 catch 跳过——返回值恒非 null
                        out.add(readCompatEvent(line));
                    } catch (Exception e) {
                        // Skip malformed lines
                    }
                }
                return out;
            }
            JsonNode node = om.readTree(raw);
            if (node.isArray()) {
                List<Event> out = new ArrayList<>();
                for (JsonNode child : node) {
                    // Jackson 数组迭代不产生 null 引用（null 元素一律为 NullNode），isNull 判定即可
                    if (child.isNull()) {
                        continue;  // Skip null elements
                    }
                    try {
                        out.add(readCompatEvent(child));
                    } catch (Exception e) {
                        // Skip malformed elements
                    }
                }
                return out;
            }
            return List.of(readCompatEvent(node));
        } catch (Exception e) {
            throw new ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "Invalid JSON payload: " + e.getMessage()
            );
        }
    }

    private Event readCompatEvent(String raw) throws Exception {
        JsonNode node = om.readTree(raw);
        return readCompatEvent(node);
    }

    /**
     * v1 事件契约已废弃的多租户路由字段：入口处剔除。
     * tenant_id/org_id 不参与映射（单公司部署无租户语义），仅携带这两个字段的事件
     * 会因缺少 game_id 被拒绝为 invalid_schema。
     */
    private static final Set<String> DEPRECATED_ROUTING_FIELDS = Set.of("tenant_id", "org_id");

    private Event readCompatEvent(JsonNode node) {
        JsonNode normalizedNode = normalizeCompatNode(node);
        Event event = om.convertValue(normalizedNode, Event.class);
        // 注：game_id/environment/event_type/revenue_* 的 snake_case 兜底映射已删除——
        // gateway ObjectMapper 已配置 SNAKE_CASE 命名策略，convertValue 直接完成映射
        if (event.gameId == null) {
            if (node.hasNonNull("project_id")) {
                event.gameId = node.get("project_id").asText();
            } else if (node.hasNonNull("app_id")) {
                event.gameId = parseGameIdFromAppId(node.get("app_id").asText());
            }
        }
        if (event.environment == null) {
            if (node.hasNonNull("environment_id")) {
                event.environment = normalizeEnvironment(node.get("environment_id").asText());
            } else if (node.hasNonNull("app_id")) {
                event.environment = parseEnvironmentFromAppId(node.get("app_id").asText());
            }
        }
        if (node.hasNonNull("ts_client")) {
            Long tsClient = parseEpochMillis(node.get("ts_client"));
            if (tsClient != null) {
                event.tsClient = tsClient;
            }
        }
        if (node.hasNonNull("ts_server")) {
            Long tsServer = parseEpochMillis(node.get("ts_server"));
            if (tsServer != null) {
                event.tsServer = tsServer;
            }
        }
        return event;
    }

    private JsonNode normalizeCompatNode(JsonNode node) {
        if (!(node instanceof ObjectNode objectNode)) {
            return node;
        }
        ObjectNode normalized = objectNode.deepCopy();
        normalizeTimestampField(normalized, "ts_client");
        normalizeTimestampField(normalized, "tsClient");
        normalizeTimestampField(normalized, "ts_server");
        normalizeTimestampField(normalized, "tsServer");
        normalized.remove(DEPRECATED_ROUTING_FIELDS);
        return normalized;
    }

    private void normalizeTimestampField(ObjectNode node, String fieldName) {
        if (!node.hasNonNull(fieldName)) {
            return;
        }
        Long epochMillis = parseEpochMillis(node.get(fieldName));
        if (epochMillis != null) {
            node.put(fieldName, epochMillis);
        }
    }

    private void normalizeCompatFields(Event event) {
        if (event.environment != null) {
            event.environment = normalizeEnvironment(event.environment);
        }
        if (event.eventType == null || event.eventType.isBlank()) {
            event.eventType = inferEventType(event.eventName);
        }
    }

    /**
     * 事件类型推断：P3 九类 session/user/business/resource/progression/design/error/ad/risk
     * （experiment 为平台附加类型）。SDK 未显式声明 event_type 时按事件名关键词推断。
     */
    private String inferEventType(String eventName) {
        if (eventName == null) {
            return "business";
        }
        String name = eventName.toLowerCase(Locale.ROOT);
        if (name.contains("risk") || name.contains("fraud")) {
            return "risk";
        }
        if (name.contains("experiment")) {
            return "experiment";
        }
        if (name.contains("ad_") || name.startsWith("ad")) {
            return "ad";
        }
        if (name.contains("level") || name.contains("quest") || name.contains("achievement")) {
            return "progression";
        }
        if (name.contains("session")) {
            return "session";
        }
        if (name.contains("error") || name.contains("crash")) {
            return "error";
        }
        if (name.contains("resource_") || name.contains("currency_")
                || name.contains("item_") || name.contains("economy")) {
            return "resource";
        }
        if (name.contains("user") || name.contains("login") || name.contains("register")
                || name.contains("signup") || name.contains("auth")) {
            return "user";
        }
        if (name.startsWith("design")) {
            return "design";
        }
        return "business";
    }

    private String normalizeEnvironment(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.contains("__")) {
            value = value.substring(value.lastIndexOf("__") + 2);
        }
        if (value.startsWith("env_")) {
            // startsWith("env_") 保证 lastIndexOf('_') >= 3，idx 恒非负
            int idx = value.lastIndexOf('_');
            if (idx + 1 < value.length()) {
                value = value.substring(idx + 1);
            }
        }
        return switch (value) {
            case "production" -> "prod";
            case "development" -> "dev";
            default -> value;
        };
    }

    private String parseGameIdFromAppId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String suffix : List.of("__prod", "__production", "__staging", "__stage", "__dev", "__development")) {
            if (normalized.endsWith(suffix) && value.length() > suffix.length()) {
                return value.substring(0, value.length() - suffix.length());
            }
        }
        for (String suffix : List.of("_prod", "_production", "_staging", "_stage", "_dev", "_development")) {
            if (normalized.endsWith(suffix) && value.length() > suffix.length()) {
                return value.substring(0, value.length() - suffix.length());
            }
        }
        return value;
    }

    private String parseEnvironmentFromAppId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (String suffix : List.of("__production", "_production")) {
            if (normalized.endsWith(suffix)) {
                return "prod";
            }
        }
        for (String suffix : List.of("__development", "_development")) {
            if (normalized.endsWith(suffix)) {
                return "dev";
            }
        }
        for (String env : List.of("prod", "staging", "stage", "dev")) {
            if (normalized.endsWith("__" + env) || normalized.endsWith("_" + env)) {
                return normalizeEnvironment(env);
            }
        }
        return null;
    }

    private Long parseEpochMillis(JsonNode node) {
        // 两个调用点均以 hasNonNull 守卫，node 不可能为 null/isNull，无需防御分支
        if (node.isNumber()) {
            return node.asLong();
        }
        if (!node.isTextual()) {
            return null;
        }
        String value = node.asText();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String extractClientIp(org.springframework.http.server.reactive.ServerHttpRequest req) {
        String xff = req.getHeaders().getFirst("x-forwarded-for");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        if (req.getRemoteAddress() != null) {
            return req.getRemoteAddress().getAddress().getHostAddress();
        }
        return null;
    }

    private PiiPolicy.Overrides policyToOverrides(PolicyService.Policy policy) {
        if (policy == null) {
            return null;
        }
        PiiPolicy.Overrides overrides = new PiiPolicy.Overrides();
        if (policy.piiEmail != null) {
            overrides.emailMode = parseMode(policy.piiEmail);
        }
        if (policy.piiPhone != null) {
            overrides.phoneMode = parseMode(policy.piiPhone);
        }
        if (policy.piiIp != null) {
            overrides.ipMode = parseIpMode(policy.piiIp);
        }
        if (policy.denyKeys != null && !policy.denyKeys.isEmpty()) {
            overrides.denyKeys = new java.util.HashSet<>(policy.denyKeys.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList());
        }
        if (policy.maskKeys != null && !policy.maskKeys.isEmpty()) {
            overrides.maskKeys = new java.util.HashSet<>(policy.maskKeys.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList());
        }
        applySchemaPiiPolicy(overrides, policy.schemaPiiPolicy);
        return overrides;
    }

    /**
     * PII 优先级链（B7 边界闭合）：环境级 ACTIVE EventSchema 的 piiPolicy 压在 ApiKey 级策略之上——
     * email/phone/ip 模式字段取更高一层的合法值，denyKeys/maskKeys 与 ApiKey 级名单取并集（任一层收紧即生效）；
     * JSON 非法或单个字段值非法时该字段回落 ApiKey 级/网关默认（与 Schema 事件面 fail-open 同口径）。
     * 契约：{"email":"allow|mask|drop","phone":"allow|mask|drop","ip":"allow|coarse|drop","denyKeys":[...],"maskKeys":[...]}
     */
    private void applySchemaPiiPolicy(PiiPolicy.Overrides overrides, String schemaPiiPolicy) {
        if (schemaPiiPolicy == null || schemaPiiPolicy.isBlank()) {
            return;
        }
        JsonNode json;
        try {
            json = om.readTree(schemaPiiPolicy);
        } catch (Exception e) {
            return;
        }
        if (json == null || !json.isObject()) {
            return;
        }
        PiiPolicy.Mode mode = strictMode(json.get("email"));
        if (mode != null) {
            overrides.emailMode = mode;
        }
        mode = strictMode(json.get("phone"));
        if (mode != null) {
            overrides.phoneMode = mode;
        }
        PiiPolicy.IpMode ipMode = strictIpMode(json.get("ip"));
        if (ipMode != null) {
            overrides.ipMode = ipMode;
        }
        unionKeys(overrides, json.get("denyKeys"), true);
        unionKeys(overrides, json.get("maskKeys"), false);
    }

    /** Schema 级模式值严格解析：仅接受白名单三值，缺失/非字符串/非法值一律 null（回落下一层）。 */
    private PiiPolicy.Mode strictMode(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        return switch (node.asText().toLowerCase(Locale.ROOT)) {
            case "allow" -> PiiPolicy.Mode.ALLOW;
            case "mask" -> PiiPolicy.Mode.MASK;
            case "drop" -> PiiPolicy.Mode.DROP;
            default -> null;
        };
    }

    private PiiPolicy.IpMode strictIpMode(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        return switch (node.asText().toLowerCase(Locale.ROOT)) {
            case "allow" -> PiiPolicy.IpMode.ALLOW;
            case "coarse" -> PiiPolicy.IpMode.COARSE;
            case "drop" -> PiiPolicy.IpMode.DROP;
            default -> null;
        };
    }

    /** Schema 级名单与 ApiKey 级并集（小写归一同 ApiKey 路径）；空数组/非数组不动。 */
    private void unionKeys(PiiPolicy.Overrides overrides, JsonNode listNode, boolean deny) {
        if (listNode == null || !listNode.isArray() || listNode.isEmpty()) {
            return;
        }
        Set<String> merged = new java.util.HashSet<>();
        Set<String> existing = deny ? overrides.denyKeys : overrides.maskKeys;
        if (existing != null) {
            merged.addAll(existing);
        }
        for (JsonNode item : listNode) {
            if (item.isTextual() && !item.asText().isBlank()) {
                merged.add(item.asText().toLowerCase(Locale.ROOT));
            }
        }
        if (deny) {
            overrides.denyKeys = merged;
        } else {
            overrides.maskKeys = merged;
        }
    }

    private void reject(BatchResponse resp, Event event, AuthService.ApiKeyContext keyContext, String reason) {
        reject(resp, event, keyContext, reason, null);
    }

    private void reject(BatchResponse resp, Event event, AuthService.ApiKeyContext keyContext,
                        String reason, String detail) {
        // 全部调用点均传入非 null event（循环内构造），无需判空
        HashMap<String, String> rej = new HashMap<>();
        rej.put("event_id", String.valueOf(event.eventId));
        rej.put("reason", reason);
        resp.rejected.add(rej);
        dlq.publish(event.eventId, reason, toJsonSilently(event));
        // B10：拒绝计数随 DLQ 发布同点落地（dlq 归档与计数一处收口，不会漏分支）
        String[] scope = counterScope(event, keyContext);
        counters.record(scope[0], scope[1], rejectCounter(reason));
    }

    /** 计数作用域：与 inspect() 同口径——scoped 用 key 作用域，非 scoped 用事件自带字段（可空 → unknown 桶）。 */
    private static String[] counterScope(Event event, AuthService.ApiKeyContext keyContext) {
        if (keyContext.isScoped()) {
            return new String[]{keyContext.gameId, keyContext.environment};
        }
        return new String[]{event.gameId, event.environment};
    }

    /** 拒绝 reason → 计数维度；网关枚举封闭（BatchController 拒绝路径全集），default 不可达。 */
    private static DataQualityCounters.Counter rejectCounter(String reason) {
        return switch (reason) {
            case "invalid_schema" -> Counter.REJECTED_SCHEMA;
            case "unknown_event" -> Counter.REJECTED_UNKNOWN_EVENT;
            case "invalid_timestamp" -> Counter.REJECTED_INVALID_TIMESTAMP;
            case "pii_blocked" -> Counter.REJECTED_PII_BLOCKED;
            case "payload_too_large" -> Counter.REJECTED_PAYLOAD_TOO_LARGE;
            case "trust_escalation" -> Counter.REJECTED_TRUST_ESCALATION;
            case "blocked" -> Counter.REJECTED_BLOCKED;
            case "api_key_scope_mismatch" -> Counter.REJECTED_SCOPE_MISMATCH;
            case "kafka_error" -> Counter.REJECTED_KAFKA_ERROR;
            default -> null;
        };
    }

    /**
     * 检视记录（Live Inspector）：作用域优先取 key 作用域而非事件自带字段——
     * 携带错误 game_id/environment 的事件（api_key_scope_mismatch）恰恰是接入调试
     * 最需要看到的，落在开发者自己作用域的视图里才可见。keyContext 由 HmacFilter
     * 保证非 null；路由字段缺失时 record 内部跳过（DLQ 已有全量）。
     */
    private void inspect(Event event, AuthService.ApiKeyContext keyContext,
                         String outcome, String reason, String detail) {
        String gameId = keyContext.isScoped() ? keyContext.gameId : event.gameId;
        String environment = keyContext.isScoped() ? keyContext.environment : event.environment;
        inspector.record(gameId, environment, outcome, reason, detail, event);
    }

    private String toJsonSilently(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] maybeGunzip(byte[] raw, String encoding) {
        try {
            if (encoding != null && encoding.toLowerCase(Locale.ROOT).contains("gzip")) {
                try (InputStream gis = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                    return gis.readAllBytes();
                }
            }
        } catch (Exception ignored) {
        }
        return raw;
    }

    private PiiPolicy.Mode parseMode(String s) {
        // 两个调用点均有 policy.piiXxx != null 守卫，s 不可能为 null
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "allow" -> PiiPolicy.Mode.ALLOW;
            case "drop" -> PiiPolicy.Mode.DROP;
            default -> PiiPolicy.Mode.MASK;
        };
    }

    private PiiPolicy.IpMode parseIpMode(String s) {
        // 唯一调用点已有 policy.piiIp != null 守卫，s 不可能为 null
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "allow" -> PiiPolicy.IpMode.ALLOW;
            case "drop" -> PiiPolicy.IpMode.DROP;
            default -> PiiPolicy.IpMode.COARSE;
        };
    }
}
