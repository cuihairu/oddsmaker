package io.oddsmaker.jobs.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class RuleFetcher implements Runnable {
    private static final AtomicReference<RuleFetcher> instance = new AtomicReference<>();

    // 原双检+CAS 形态的 false 侧（外层判空与 CAS 之间仅并发竞争窗口可进，单测无法确定性注入），
    // 改 synchronized 单检：锁互斥同样保证「至多一个 fetcher 启动」，竞争败者由原「构造不启动」
    // 变为「等锁后见非空直接返回」，外部可观察行为等价；非空侧顺序调用即可达。
    // startOnce 仅作业启动期调用，无热路径性能敏感。
    public static synchronized void startOnce(String controlUrl, String gameId, String adminToken, long intervalMs) {
        if (instance.get() != null) {
            return;
        }
        RuleFetcher fetcher = new RuleFetcher(controlUrl, gameId, adminToken, intervalMs);
        instance.set(fetcher);
        fetcher.start();
    }

    private final String controlUrl;
    private final String gameId;
    private final String adminToken;
    private final long intervalMs;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile boolean running = true;
    private Thread thread;

    public RuleFetcher(String controlUrl, String gameId, String adminToken, long intervalMs) {
        this.controlUrl = controlUrl.replaceAll("/$", "");
        this.gameId = gameId;
        this.adminToken = adminToken;
        this.intervalMs = intervalMs;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public void start() {
        thread = new Thread(this, "oddsmaker-rule-fetcher");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    @Override
    public void run() {
        while (running) {
            try {
                fetchAndApply();
            } catch (Exception e) {
                System.err.println("[rule-fetcher] failed: " + e.getMessage());
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void fetchAndApply() throws Exception {
        String url = controlUrl + "/api/risk-dashboard/rules/" + gameId;
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (adminToken != null && !adminToken.isEmpty()) {
            reqBuilder.header("x-admin-token", adminToken);
        }
        HttpResponse<String> resp = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            System.err.println("[rule-fetcher] HTTP " + resp.statusCode() + ", keeping existing rules");
            return;
        }

        JsonNode arr = mapper.readTree(resp.body());
        if (!arr.isArray() || arr.isEmpty()) return;

        // 按 ruleType 收敛：同类型取 riskScore 最高的那条
        Map<String, RuleConfig.RuleSpec> collected = new LinkedHashMap<>();

        for (JsonNode rule : arr) {
            String type = rule.path("ruleType").asText("");
            if (type.isEmpty()) continue;
            int threshold = rule.path("triggerThreshold").asInt(0);
            if (threshold <= 0) continue;

            String ruleId = rule.path("id").asText(null);
            String actionType = rule.path("actionType").asText("ALERT");
            int riskScore = rule.path("riskScore").asInt(0);
            String riskLevel = rule.path("riskLevel").asText("MEDIUM");

            // 同类型取 riskScore 最高的
            RuleConfig.RuleSpec existing = collected.get(type);
            if (existing == null || riskScore > existing.riskScore) {
                collected.put(type, new RuleConfig.RuleSpec(
                        ruleId, type, threshold, actionType, riskScore, riskLevel));
            }
        }

        if (collected.isEmpty()) return;

        RuleConfig.update(new RuleConfig(collected));
        System.out.println("[rule-fetcher] rules refreshed from " + url
                + " (" + arr.size() + " rules, " + collected.size() + " types active)");
    }
}
