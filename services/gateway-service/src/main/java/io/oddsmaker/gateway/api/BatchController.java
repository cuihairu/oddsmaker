package io.oddsmaker.gateway.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oddsmaker.common.model.Event;
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
import java.util.concurrent.CopyOnWriteArrayList;
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

    public BatchController(
            ObjectMapper om,
            AvroPublisher publisher,
            DlqPublisher dlq,
            PropsPolicy propsPolicy,
            JsonSchemaValidator schemaValidator,
            PolicyService policyService,
            PiiPolicy piiPolicy
    ) {
        this.om = om;
        this.publisher = publisher;
        this.dlq = dlq;
        this.propsPolicy = propsPolicy;
        this.schemaValidator = schemaValidator;
        this.policyService = policyService;
        this.piiPolicy = piiPolicy;
    }

    public static class BatchResponse {
        public List<String> accepted = new CopyOnWriteArrayList<>();
        public List<Map<String, String>> rejected = new CopyOnWriteArrayList<>();
        public int next_hint_ms = 3000;
    }

    @PostMapping(value = "/batch", consumes = {MediaType.APPLICATION_JSON_VALUE, "application/x-ndjson"})
    public Mono<BatchResponse> batch(
            @RequestHeader(value = "content-encoding", required = false) String encoding,
            @RequestHeader(value = "content-type", required = false) String contentType,
            org.springframework.http.server.reactive.ServerHttpRequest req,
            @RequestBody Mono<byte[]> bodyBytesMono
    ) {
        return bodyBytesMono.map(bytes -> {
            byte[] raw = maybeGunzip(bytes, encoding);
            if (propsPolicy.exceedsRequestLimit(raw)) {
                throw new ResponseStatusException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, "request_too_large");
            }

            List<Event> events = parseEvents(raw, contentType);
            String userAgent = req.getHeaders().getFirst("user-agent");
            String clientIp = extractClientIp(req);
            String apiKey = req.getHeaders().getFirst("x-api-key");
            PolicyService.Policy policy = policyService.getPolicy(apiKey);
            PiiPolicy.Overrides piiOverrides = policyToOverrides(policy);

            BatchResponse resp = new BatchResponse();
            for (Event event : events) {
                if (event == null) {
                    continue;  // Skip null events
                }
                normalizeCompatFields(event);
                if (event.eventId == null || event.eventName == null || event.gameId == null || event.environment == null || event.deviceId == null) {
                    reject(resp, event, "invalid_schema");
                    continue;
                }
                if (event.eventType == null || event.eventType.isBlank()) {
                    event.eventType = inferEventType(event.eventName);
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
                try {
                    publisher.publish(event);
                    resp.accepted.add(event.eventId);
                } catch (Exception ex) {
                    reject(resp, event, "kafka_error");
                }
            }
            return resp;
        });
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

    private Event readCompatEvent(JsonNode node) {
        JsonNode normalizedNode = normalizeTimestampFields(node);
        Event event = om.convertValue(normalizedNode, Event.class);
        if (event.gameId == null) {
            if (node.hasNonNull("game_id")) {
                event.gameId = node.get("game_id").asText();
            }
        }
        if (event.environment == null) {
            if (node.hasNonNull("environment")) {
                event.environment = node.get("environment").asText();
            } else if (node.hasNonNull("environment_id")) {
                event.environment = normalizeEnvironment(node.get("environment_id").asText());
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

    private JsonNode normalizeTimestampFields(JsonNode node) {
        if (!(node instanceof ObjectNode objectNode)) {
            return node;
        }
        ObjectNode normalized = objectNode.deepCopy();
        normalizeTimestampField(normalized, "ts_client");
        normalizeTimestampField(normalized, "tsClient");
        normalizeTimestampField(normalized, "ts_server");
        normalizeTimestampField(normalized, "tsServer");
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
        if (name.contains("ad_")) {
            return "ad";
        }
        if (name.contains("level") || name.contains("quest")) {
            return "progression";
        }
        if (name.contains("session")) {
            return "session";
        }
        if (name.contains("error") || name.contains("crash")) {
            return "error";
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
