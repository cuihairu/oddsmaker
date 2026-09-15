package io.oddsmaker.gateway.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API Key 校验服务。
 *
 * 动态凭据只从 Control Service 的受服务间令牌保护的内部接口读取。
 * 本地静态 key 仅用于开发和测试，因此没有 game/environment 绑定作用域。
 */
@Component
public class AuthService {
    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private final Map<String, String> localSecrets;
    private final java.net.http.HttpClient client;
    private final String controlUrl;
    private final String internalToken;
    private final MeterRegistry meters;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** 远程 key 查找结果指标：outcome=ok|http_error|exception|not_configured，code=HTTP 状态码（非 HTTP 场景 none）。 */
    static final String REMOTE_LOOKUP_METRIC = "oddsmaker.gateway.remote.key.lookup.total";

    static class CacheEntry {
        ApiKeyContext context;
        long expireAt;
    }

    public static class ApiKeyContext {
        public String apiKey;
        public String secret;
        public String gameId;
        public String environment;
        public String keyRole; // client|server|admin
        public String envStatus; // active|inactive|maintenance
        public Boolean envEnableSampling;
        public Double envSampleRate;
        public Boolean canWrite;
        public Boolean requireHmac;
        public Integer rpm;
        public Integer ipRpm;
        public List<String> propsAllowlist;
        public String piiEmail;
        public String piiPhone;
        public String piiIp;
        public List<String> denyKeys;
        public List<String> maskKeys;

        public boolean isScoped() {
            return gameId != null && !gameId.isBlank() && environment != null && !environment.isBlank();
        }

        public boolean allowsWrite() {
            return canWrite == null || canWrite;
        }

        /**
         * 环境非 active（维护/停用）时拒绝写入。
         */
        public boolean envWritable() {
            return envStatus == null || "active".equalsIgnoreCase(envStatus);
        }

        /**
         * 环境级采样：null 或 1.0 表示全量。
         */
        public boolean samplingEnabled() {
            return Boolean.TRUE.equals(envEnableSampling)
                && envSampleRate != null
                && envSampleRate > 0
                && envSampleRate < 1.0;
        }
    }

    public AuthService(Environment env, MeterRegistry meters) {
        this.localSecrets = Binder.get(env).bind("oddsmaker.auth.keys", Map.class).orElse(Map.of());
        this.controlUrl = Binder.get(env).bind("oddsmaker.control.url", String.class).orElse(null);
        this.internalToken = Binder.get(env).bind("oddsmaker.control.internal-token", String.class).orElse("");
        this.meters = meters;
        this.client = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(2))
            .build();
    }

    public ApiKeyContext getContext(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        long now = Instant.now().getEpochSecond();
        CacheEntry cached = cache.get(apiKey);
        if (cached != null && cached.expireAt > now) {
            return cached.context;
        }

        ApiKeyContext remote = fetchRemoteContext(apiKey);
        if (remote != null) {
            CacheEntry entry = new CacheEntry();
            entry.context = remote;
            entry.expireAt = now + 60;
            cache.put(apiKey, entry);
            return remote;
        }

        String localSecret = localSecrets.get(apiKey);
        if (localSecret == null) {
            return null;
        }
        ApiKeyContext local = new ApiKeyContext();
        local.apiKey = apiKey;
        local.secret = localSecret;
        local.canWrite = true;
        return local;
    }

    /**
     * 从 Control 拉取 key 上下文。HmacFilter 跑在 reactor 事件循环上，
     * WebClient.block() 会被 reactor 禁止（"blocking not supported in thread reactor-http"），
     * 因此这里用 JDK 同步客户端：60s 缓存之下，3s 上限的一次同步调用可接受。
     */
    private ApiKeyContext fetchRemoteContext(String apiKey) {
        if (controlUrl == null || controlUrl.isBlank() || internalToken == null || internalToken.isBlank()) {
            meters.counter(REMOTE_LOOKUP_METRIC, "outcome", "not_configured", "code", "none").increment();
            return null;
        }
        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(controlUrl + "/internal/api-keys/" + apiKey))
                .header("x-internal-token", internalToken)
                .header("Accept", "application/json")
                .timeout(java.time.Duration.ofSeconds(3))
                .GET()
                .build();
            java.net.http.HttpResponse<String> response =
                client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                meters.counter(REMOTE_LOOKUP_METRIC,
                    "outcome", "http_error", "code", String.valueOf(response.statusCode())).increment();
                logger.warn("Remote key lookup failed for {}: HTTP {}", apiKey, response.statusCode());
                return null;
            }
            meters.counter(REMOTE_LOOKUP_METRIC, "outcome", "ok", "code", "200").increment();
            return mapper.readValue(response.body(), ApiKeyContext.class);
        } catch (Exception e) {
            meters.counter(REMOTE_LOOKUP_METRIC, "outcome", "exception", "code", "none").increment();
            logger.warn("Remote key lookup error for {}: {}", apiKey, e.toString());
            return null;
        }
    }
}
