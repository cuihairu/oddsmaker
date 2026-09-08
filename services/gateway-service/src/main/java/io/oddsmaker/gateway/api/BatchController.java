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
import io.oddsmaker.gateway.kafka.AvroPublisher;
import io.oddsmaker.gateway.kafka.DlqPublisher;
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

    public BatchController(
            ObjectMapper om,
            AvroPublisher publisher,
            DlqPublisher dlq,
            PropsPolicy propsPolicy,
            JsonSchemaValidator schemaValidator,
            PolicyService policyService,
            PiiPolicy piiPolicy,
            BlockListClient blockListClient,
            io.oddsmaker.gateway.security.ReplayGuard replayGuard
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
            AuthService.ApiKeyContext keyContext = (AuthService.ApiKeyContext) exchange.getAttributes()
                .get("oddsmaker.api_key_context");
            if (keyContext != null && !keyContext.envWritable()) {
                throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "environment_unavailable");
            }
            PolicyService.Policy policy = policyService.getPolicy(apiKey);
            PiiPolicy.Overrides piiOverrides = policyToOverrides(policy);

            // 第一遍：规范化 + 基础校验 + 收集封禁检查目标
            BatchResponse resp = new BatchResponse();
            List<Event> validEvents = new ArrayList<>();
            for (Event event : events) {
                if (event == null) {
                    continue;
                }
                normalizeCompatFields(event);
                if (event.eventId == null || event.eventName == null || event.gameId == null || event.environment == null || event.deviceId == null) {
                    reject(resp, event, "invalid_schema");
                    continue;
                }
                if (!matchesApiKeyScope(event, keyContext)) {
                    reject(resp, event, "api_key_scope_mismatch");
                    continue;
                }
                if (event.eventType == null || event.eventType.isBlank()) {
                    event.eventType = inferEventType(event.eventName);
                }
                // 风控前置：事件时间戳信差检查（默认 ±24h，可配 oddsmaker.risk.max-event-ts-drift-ms）
                if (!replayGuard.isTimestampPlausible(event.tsClient, System.currentTimeMillis())) {
                    reject(resp, event, "invalid_timestamp");
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
                if (event.props != null) {
                    if (policy != null && policy.propsAllowlist != null && !policy.propsAllowlist.isEmpty()) {
                        event.props = propsPolicy.filterWithAllowlist(event.props, policy.propsAllowlist);
                    } else {
                        event.props = propsPolicy.filter(event.props);
                    }
                }
                if (event.props != null && piiPolicy.hasBlockedKeys(event.props, piiOverrides)) {
                    reject(resp, event, "pii_blocked");
                    continue;
                }
                if (event.props != null) {
                    event.props = piiPolicy.sanitizeProps(event.props, piiOverrides);
                }
                event.clientIp = piiPolicy.sanitizeClientIp(event.clientIp, piiOverrides);
                if (propsPolicy.exceedsEventLimit(event)) {
                    reject(resp, event, "payload_too_large");
                    continue;
                }
                String schemaError = schemaValidator.validate(event);
                if (schemaError != null) {
                    reject(resp, event, "invalid_schema");
                    continue;
                }
                // 风控前置：event_id 幂等吸收——schema 合法后才占用幂等位，
                // SDK 重试导致的重复事件静默去重（计入 duplicates，不重复发布、不进 DLQ）
                if (!replayGuard.consumeEventId(event.eventId)) {
                    resp.duplicates++;
                    resp.accepted.add(event.eventId);
                    continue;
                }
                validEvents.add(event);
            }

            // 没有有效事件，直接返回
            if (validEvents.isEmpty()) {
                return Mono.just(resp);
            }

            // 环境级确定性采样：按 device_id 哈希分桶，保证同一设备的事件采样结果稳定，
            // 避免漏斗/留存分析因随机采样断裂。被采样丢弃的事件计入 sampled_out，不进 DLQ。
            final List<Event> eventsToPublish;
            if (keyContext != null && keyContext.samplingEnabled()) {
                List<Event> sampledEvents = new ArrayList<>(validEvents.size());
                for (Event event : validEvents) {
                    if (sampledIn(event, keyContext.envSampleRate)) {
                        sampledEvents.add(event);
                    } else {
                        resp.sampled_out++;
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
                if (event.deviceId != null && !event.deviceId.isEmpty()) {
                    targets.add(new BlockListClient.BatchTarget("device_id", event.deviceId));
                }
                if (event.userId != null && !event.userId.isEmpty()) {
                    targets.add(new BlockListClient.BatchTarget("player_id", event.userId));
                }
            }

            // 无封禁检查目标 → 直接发布
            if (targets.isEmpty()) {
                for (Event event : eventsToPublish) {
                    try {
                        publisher.publish(event);
                        resp.accepted.add(event.eventId);
                    } catch (Exception ex) {
                        reject(resp, event, "kafka_error");
                    }
                }
                return Mono.just(resp);
            }

            // 3) 批量检查封禁
            return blockListClient.batchCheck(gameId, targets)
                    .map(blockedMap -> {
                        // 4) 处理事件：封禁的拒绝，非封禁的发布
                        for (Event event : eventsToPublish) {
                            if (isBlocked(event, blockedMap)) {
                                reject(resp, event, "blocked");
                                continue;
                            }
                            try {
                                publisher.publish(event);
                                resp.accepted.add(event.eventId);
                            } catch (Exception ex) {
                                reject(resp, event, "kafka_error");
                            }
                        }
                        return resp;
                    });
        });
    }

    private boolean isBlocked(Event event, Map<String, Boolean> blockedMap) {
        if (event.deviceId != null) {
            Boolean b = blockedMap.get("device_id:" + event.deviceId);
            if (Boolean.TRUE.equals(b)) return true;
        }
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
                        Event event = readCompatEvent(line);
                        if (event != null) {
                            out.add(event);
                        }
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
                    if (child == null || child.isNull()) {
                        continue;  // Skip null elements
                    }
                    try {
                        Event event = readCompatEvent(child);
                        if (event != null) {
                            out.add(event);
                        }
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
        if (event.gameId == null) {
            if (node.hasNonNull("game_id")) {
                event.gameId = node.get("game_id").asText();
            } else if (node.hasNonNull("project_id")) {
                event.gameId = node.get("project_id").asText();
            } else if (node.hasNonNull("app_id")) {
                event.gameId = parseGameIdFromAppId(node.get("app_id").asText());
            }
        }
        if (event.environment == null) {
            if (node.hasNonNull("environment")) {
                event.environment = node.get("environment").asText();
            } else if (node.hasNonNull("environment_id")) {
                event.environment = normalizeEnvironment(node.get("environment_id").asText());
            } else if (node.hasNonNull("app_id")) {
                event.environment = parseEnvironmentFromAppId(node.get("app_id").asText());
            }
        }
        if (event.eventType == null && node.hasNonNull("event_type")) {
            event.eventType = node.get("event_type").asText();
        }
        if (event.revenueAmount == null && node.hasNonNull("revenue_amount")) {
            event.revenueAmount = node.get("revenue_amount").asDouble();
        }
        if (event.revenueCurrency == null && node.hasNonNull("revenue_currency")) {
            event.revenueCurrency = node.get("revenue_currency").asText();
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
            int idx = value.lastIndexOf('_');
            if (idx >= 0 && idx + 1 < value.length()) {
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
        if (node == null || node.isNull()) {
            return null;
        }
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
        return overrides;
    }

    private void reject(BatchResponse resp, Event event, String reason) {
        HashMap<String, String> rej = new HashMap<>();
        rej.put("event_id", event != null ? String.valueOf(event.eventId) : "");
        rej.put("reason", reason);
        resp.rejected.add(rej);
        dlq.publish(event != null ? event.eventId : null, reason, toJsonSilently(event));
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
        if (s == null) {
            return null;
        }
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "allow" -> PiiPolicy.Mode.ALLOW;
            case "drop" -> PiiPolicy.Mode.DROP;
            default -> PiiPolicy.Mode.MASK;
        };
    }

    private PiiPolicy.IpMode parseIpMode(String s) {
        if (s == null) {
            return null;
        }
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "allow" -> PiiPolicy.IpMode.ALLOW;
            case "drop" -> PiiPolicy.IpMode.DROP;
            default -> PiiPolicy.IpMode.COARSE;
        };
    }
}
