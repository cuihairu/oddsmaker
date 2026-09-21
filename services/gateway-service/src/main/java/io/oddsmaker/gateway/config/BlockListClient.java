package io.oddsmaker.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 封禁名单客户端（Reactive）
 *
 * 调用 control-service 内部端点 POST /internal/block-lists/batch-check 做封禁查询。
 * 全程 reactive（返回 Mono），禁⽤ `.block()`。
 * 本地 ConcurrentHashMap 缓存，TTL 15s（防 control 抖动 + 降低延迟）。
 */
@Component
public class BlockListClient {

    private static final Logger log = LoggerFactory.getLogger(BlockListClient.class);

    private final WebClient client;
    private final String internalToken;
    private final boolean enabled;
    private final int cacheTtlSeconds;
    private final Duration timeout;

    /** 缓存：key = "targetType:targetValue" → {blocked, expireAt} */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    static class CacheEntry {
        boolean blocked;
        long expireAt;
    }

    public BlockListClient(Environment env) {
        String controlUrl = env.getProperty("oddsmaker.control.url", "http://localhost:8085");
        this.internalToken = env.getProperty("oddsmaker.blocklist.internal-token", "");
        this.enabled = env.getProperty("oddsmaker.blocklist.enabled", boolean.class, true);
        this.cacheTtlSeconds = env.getProperty("oddsmaker.blocklist.cache-ttl-seconds", int.class, 15);
        long timeoutMs = env.getProperty("oddsmaker.blocklist.timeout-ms", long.class, 200L);
        this.timeout = Duration.ofMillis(timeoutMs);
        this.client = WebClient.builder().baseUrl(controlUrl).build();
    }

    /**
     * 批量检查封禁状态。
     * 先去本地缓存命中，未命中再批量请求 control-service。
     */
    public Mono<Map<String, Boolean>> batchCheck(String gameId, List<BatchTarget> targets) {
        if (!enabled || targets == null || targets.isEmpty()) {
            return Mono.just(Collections.emptyMap());
        }

        long now = Instant.now().getEpochSecond();

        // 1) 单次取缓存区分命中/未命中（单次 get 后直接判定，规避二次 get 的竞态防御）
        Map<String, Boolean> result = new java.util.HashMap<>();
        List<BatchTarget> misses = new ArrayList<>();
        for (BatchTarget t : targets) {
            CacheEntry ce = cache.get(cacheKey(t));
            if (ce != null && ce.expireAt > now) {
                result.put(cacheKey(t), ce.blocked);
            } else {
                misses.add(t);
            }
        }

        if (misses.isEmpty()) {
            return Mono.just(result);
        }

        // 2) 未命中部分请求 control
        // 注：错误吸收在 doBatchCheckRequest 内部完成（onErrorResume → Mono.empty()），
        // 此处仅兜底空响应为 emptyMap（=无人被封禁），保证响应链不坍缩
        return doBatchCheckRequest(gameId, misses)
                .defaultIfEmpty(Collections.emptyMap())
                .map(remote -> {
                    // 更新缓存
                    long exp = now + cacheTtlSeconds;
                    for (BatchTarget t : misses) {
                        boolean blocked = remote.getOrDefault(cacheKey(t), false);
                        CacheEntry ne = new CacheEntry();
                        ne.blocked = blocked;
                        ne.expireAt = exp;
                        cache.put(cacheKey(t), ne);
                        result.put(cacheKey(t), blocked);
                    }
                    return result;
                });
    }

    /**
     * 清扫过期缓存条目。targetValue 源自 SDK 事件外部输入（user_id/device_id/ip），
     * 键空间无界——TTL 过期后条目若只被忽略不清除会永久驻留（慢性内存增长）。
     */
    @Scheduled(fixedDelay = 60_000)
    public void evictExpired() {
        long now = Instant.now().getEpochSecond();
        cache.entrySet().removeIf(e -> e.getValue().expireAt <= now);
    }

    // ========== 内部 ==========

    private Mono<Map<String, Boolean>> doBatchCheckRequest(String gameId, List<BatchTarget> targets) {
        Map<String, Object> body = Map.of(
                "gameId", gameId,
                "targets", targets.stream().map(t -> Map.of("targetType", t.targetType, "targetValue", t.targetValue)).collect(Collectors.toList())
        );

        return client.post()
                .uri("/internal/block-lists/batch-check")
                .header("x-internal-token", internalToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(timeout)
                .map(resp -> {
                    Map<String, Boolean> result = new java.util.HashMap<>();
                    Object rawResults = resp.get("results");
                    if (rawResults instanceof List) {
                        for (Object item : (List) rawResults) {
                            if (item instanceof Map) {
                                Map m = (Map) item;
                                String key = m.get("targetType") + ":" + m.get("targetValue");
                                result.put(key, Boolean.TRUE.equals(m.get("blocked")));
                            }
                        }
                    }
                    return result;
                })
                .onErrorResume(e -> {
                    log.debug("blocklist remote call failed: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    private static String cacheKey(BatchTarget t) {
        return t.targetType + ":" + t.targetValue;
    }

    public static class BatchTarget {
        public String targetType;
        public String targetValue;

        public BatchTarget() {}

        public BatchTarget(String targetType, String targetValue) {
            this.targetType = targetType;
            this.targetValue = targetValue;
        }
    }
}
