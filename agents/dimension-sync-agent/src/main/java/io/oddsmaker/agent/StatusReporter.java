package io.oddsmaker.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 同步状态上报（Control /api/dimensions/sync-status，x-admin-token 鉴权）。
 * 心跳语义：每轮循环都报（无论有无变更），Control 侧 last_push_at 即存活心跳；
 * status.url 未配置则整段静默跳过；上报失败只打日志不影响同步主链路。
 */
public final class StatusReporter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentConfig cfg;
    private final HttpClient http;

    public StatusReporter(AgentConfig cfg) {
        this(cfg, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    StatusReporter(AgentConfig cfg, HttpClient http) {
        this.cfg = cfg;
        this.http = http;
    }

    public boolean enabled() {
        return cfg.statusUrl != null && !cfg.statusUrl.isBlank();
    }

    /** @return true=已上报且成功；false=未启用或失败（失败已记日志）。 */
    public boolean report(Checkpoint cp) {
        if (!enabled()) {
            return false;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("gameId", cfg.gameId);
            payload.put("environment", cfg.environment);
            payload.put("sourceKey", cfg.sourceKey != null ? cfg.sourceKey : name());
            payload.put("sourceType", cfg.sourceType);
            payload.put("cursor", cp.cursor);
            payload.put("lastEventTs", cp.lastEventTs);
            payload.put("pushedCount", cp.pushedCount);
            payload.put("errorCount", cp.errorCount);
            payload.put("lastError", cp.lastError);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.statusUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("x-admin-token", cfg.statusToken != null ? cfg.statusToken : "")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                System.err.println("[status] 同步状态上报失败 HTTP " + resp.statusCode());
                return false;
            }
            return true;
        } catch (Exception e) {
            System.err.println("[status] 同步状态上报异常: " + e.getMessage());
            return false;
        }
    }

    private String name() {
        return "agent-" + cfg.sourceType;
    }
}
