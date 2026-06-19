package io.oddsmaker.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RateLimiterService {
    private final int rpmApi;
    private final int rpmIp;
    private final Map<String, Window> bucketsApi = new ConcurrentHashMap<>();
    private final Map<String, Window> bucketsIp = new ConcurrentHashMap<>();

    public RateLimiterService(@Value("${oddsmaker.ratelimit.rpm:600}") int rpmApi,
                              @Value("${oddsmaker.ratelimit.ipRpm:300}") int rpmIp) {
        this.rpmApi = rpmApi; this.rpmIp = rpmIp;
    }

    public boolean allowApiKey(String apiKey) { return allow(bucketsApi, rpmApi, apiKey); }
    public boolean allowIp(String ip) { return allow(bucketsIp, rpmIp, ip); }
    public boolean allowApiKey(String apiKey, int limitOverride) { return allow(bucketsApi, limitOverride, apiKey); }
    public boolean allowIp(String ip, int limitOverride) { return allow(bucketsIp, limitOverride, ip); }

    private boolean allow(Map<String, Window> buckets, int limit, String key) {
        long minute = Instant.now().getEpochSecond() / 60L;
        Window w = buckets.computeIfAbsent(key, k -> new Window(minute));
        synchronized (w) {
            if (w.window != minute) { w.window = minute; w.counter.set(0); }
            return w.counter.incrementAndGet() <= limit;
        }
    }

    private static class Window {
        long window; AtomicInteger counter = new AtomicInteger();
        Window(long w) { this.window = w; }
    }
}
