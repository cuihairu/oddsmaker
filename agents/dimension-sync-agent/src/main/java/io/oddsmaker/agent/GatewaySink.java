package io.oddsmaker.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gateway 推送端：维度变更打包为 NDJSON 事件（POST {gateway}/v1/batch，x-api-key 鉴权），
 * 按 batchSize 分块。事件契约：event_name=dimension_define / event_type=dimension /
 * device_id=oddsmaker-agent（Gateway 必填字段，维度数据本身不按设备作用域）；
 * props 内嵌 attributes，交由 dimension-sync-job 的 putAttribute 归一化。
 */
public final class GatewaySink {

    public static final String EVENT_NAME = "dimension_define";
    public static final String EVENT_TYPE = "dimension";
    public static final String DEVICE_ID = "oddsmaker-agent";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentConfig cfg;
    private final HttpClient http;

    public GatewaySink(AgentConfig cfg) {
        this(cfg, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    GatewaySink(AgentConfig cfg, HttpClient http) {
        this.cfg = cfg;
        this.http = http;
    }

    /** 分块推送全部变更；任一块非 2xx 抛异常（上层不落 checkpoint，重放幂等）。返回成功推送条数。 */
    public long push(List<DimensionChange> changes) throws Exception {
        long pushed = 0;
        for (int from = 0; from < changes.size(); from += cfg.batchSize) {
            List<DimensionChange> chunk = changes.subList(from, Math.min(from + cfg.batchSize, changes.size()));
            String body = toNdjson(chunk);
            HttpResponse<String> resp = http.send(request(body), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("Gateway 推送失败 HTTP " + resp.statusCode()
                        + ": " + excerpt(resp.body()));
            }
            pushed += chunk.size();
        }
        return pushed;
    }

    private HttpRequest request(String body) throws Exception {
        return HttpRequest.newBuilder()
                .uri(URI.create(cfg.gatewayEndpoint + "/v1/batch"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-ndjson")
                .header("x-api-key", cfg.gatewayApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    String toNdjson(List<DimensionChange> changes) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (DimensionChange c : changes) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(MAPPER.writeValueAsString(toEvent(c)));
        }
        return sb.toString();
    }

    Map<String, Object> toEvent(DimensionChange c) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("dim_type", c.dimType);
        props.put("resource_id", c.resourceId);
        props.put("op", c.op);
        props.put("version_ts", c.versionTs);
        props.put("attributes", new LinkedHashMap<>(c.attributes));

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_id", UUID.randomUUID().toString());
        event.put("event_name", EVENT_NAME);
        event.put("event_type", EVENT_TYPE);
        event.put("game_id", cfg.gameId);
        event.put("environment", cfg.environment);
        event.put("device_id", DEVICE_ID);
        event.put("ts_server", System.currentTimeMillis());
        event.put("props", props);
        return event;
    }

    private static String excerpt(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "...";
    }
}
