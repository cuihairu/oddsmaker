package io.oddsmaker.gateway.config;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AuthService {
    private final Map<String, String> localSecrets;
    private final WebClient client;
    private final String controlUrl;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    static class CacheEntry { String secret; long expireAt; }

    public AuthService(Environment env) {
        this.localSecrets = Binder.get(env).bind("oddsmaker.auth.keys", Map.class).orElse(Map.of());
        this.controlUrl = Binder.get(env).bind("oddsmaker.control.url", String.class).orElse(null);
        this.client = controlUrl == null ? null : WebClient.builder().baseUrl(controlUrl).build();
    }

    public String getSecret(String apiKey) {
        if (apiKey == null) return null;
        // 1) cache
        CacheEntry ce = cache.get(apiKey);
        long now = Instant.now().getEpochSecond();
        if (ce != null && ce.expireAt > now) return ce.secret;
        // 2) control-service
        if (client != null) {
            try {
                var resp = client.get().uri("/api/keys/"+apiKey)
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .onErrorResume(e -> Mono.empty())
                        .block();
                if (resp != null && resp.get("secret") != null) {
                    String s = String.valueOf(resp.get("secret"));
                    CacheEntry ne = new CacheEntry(); ne.secret = s; ne.expireAt = now + 60; // 60s TTL
                    cache.put(apiKey, ne);
                    return s;
                }
            } catch (Exception ignored) {}
        }
        // 3) fallback local
        return localSecrets.get(apiKey);
    }
}
