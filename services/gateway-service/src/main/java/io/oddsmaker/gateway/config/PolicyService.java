package io.oddsmaker.gateway.config;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PolicyService {
    private final WebClient client;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    static class CacheEntry { Policy p; long expireAt; }

    public static class Policy {
        public Integer rpm; public Integer ipRpm; public List<String> propsAllowlist;
        public String piiEmail; public String piiPhone; public String piiIp;
        public List<String> denyKeys; public List<String> maskKeys;
    }

    public PolicyService(Environment env) {
        String controlUrl = Binder.get(env).bind("oddsmaker.control.url", String.class).orElse(null);
        this.client = controlUrl == null ? null : WebClient.builder().baseUrl(controlUrl).build();
    }

    public Policy getPolicy(String apiKey) {
        if (apiKey == null) return null;
        long now = Instant.now().getEpochSecond();
        CacheEntry ce = cache.get(apiKey);
        if (ce != null && ce.expireAt > now) return ce.p;
        if (client == null) return null;
        try {
            Map resp = client.get().uri("/api/keys/"+apiKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .onErrorResume(e -> Mono.empty())
                    .block();
            if (resp != null) {
                Policy p = new Policy();
                if (resp.get("rpm") != null) p.rpm = ((Number)resp.get("rpm")).intValue();
                if (resp.get("ipRpm") != null) p.ipRpm = ((Number)resp.get("ipRpm")).intValue();
                Object al = resp.get("propsAllowlist");
                if (al instanceof List) p.propsAllowlist = (List<String>) al;
                if (resp.get("piiEmail") != null) p.piiEmail = String.valueOf(resp.get("piiEmail"));
                if (resp.get("piiPhone") != null) p.piiPhone = String.valueOf(resp.get("piiPhone"));
                if (resp.get("piiIp") != null) p.piiIp = String.valueOf(resp.get("piiIp"));
                Object dk = resp.get("denyKeys"); if (dk instanceof List) p.denyKeys = (List<String>) dk;
                Object mk = resp.get("maskKeys"); if (mk instanceof List) p.maskKeys = (List<String>) mk;
                CacheEntry ne = new CacheEntry(); ne.p = p; ne.expireAt = now + 60; cache.put(apiKey, ne);
                return p;
            }
        } catch (Exception ignored) {}
        return null;
    }
}
