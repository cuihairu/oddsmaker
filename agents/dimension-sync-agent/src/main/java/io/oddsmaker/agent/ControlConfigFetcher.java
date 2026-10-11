package io.oddsmaker.agent;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Control 托管配置拉取（GET /api/dimensions/agent-configs/resolved，x-admin-token 鉴权）。
 * 与 StatusReporter 同一鉴权面：复用 status.token 作为 admin token，查询身份取本地
 * game.id / game.environment / status.source-key。
 *
 * 拉取失败（网络/非 2xx/停用档拒绝）一律抛 IOException，由 {@link AgentConfig#load} 兜底回落
 * 本地 agent.properties——Control 不可达不阻断 Agent 启动（配置双源，本地为生存兜底）。
 */
public final class ControlConfigFetcher {

    private final HttpClient http;

    public ControlConfigFetcher() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    ControlConfigFetcher(HttpClient http) {
        this.http = http;
    }

    /** @return Control 下发的完整 properties 文本（AgentConfig 侧 load 合并）。 */
    public String fetch(String configUrl, String gameId, String environment, String agentKey, String token)
            throws IOException, InterruptedException {
        if (configUrl == null || configUrl.isBlank()) {
            throw new IOException("control.config-url 未配置");
        }
        if (gameId == null || gameId.isBlank() || environment == null || environment.isBlank()
                || agentKey == null || agentKey.isBlank()) {
            throw new IOException("Control 配置拉取缺少查询身份（game.id / game.environment / status.source-key）");
        }
        String url = configUrl.trim()
                + (configUrl.indexOf('?') >= 0 ? "&" : "?")
                + "gameId=" + encode(gameId)
                + "&environment=" + encode(environment)
                + "&agentKey=" + encode(agentKey);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("x-admin-token", token != null ? token : "")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("HTTP " + resp.statusCode());
        }
        if (resp.body() == null || resp.body().isBlank()) {
            throw new IOException("响应体为空");
        }
        return resp.body();
    }

    private static String encode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
