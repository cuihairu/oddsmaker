package io.oddsmaker.gateway.security;

import io.oddsmaker.gateway.config.RateLimiterService;
import io.oddsmaker.gateway.config.PolicyService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
public class RateLimitFilter implements WebFilter {
    private final RateLimiterService limiter;
    private final PolicyService policies;

    public RateLimitFilter(RateLimiterService limiter, PolicyService policies) { this.limiter = limiter; this.policies = policies; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!"/v1/batch".equals(path)) return chain.filter(exchange);
        String apiKey = exchange.getRequest().getHeaders().getFirst("x-api-key");
        if (apiKey == null || apiKey.isBlank()) apiKey = "anonymous";
        String ip = clientIp(exchange);

        var p = policies.getPolicy(apiKey);
        boolean okApi = (p != null && p.rpm != null) ? limiter.allowApiKey(apiKey, p.rpm) : limiter.allowApiKey(apiKey);
        boolean okIp = (p != null && p.ipRpm != null) ? limiter.allowIp(ip, p.ipRpm) : limiter.allowIp(ip);
        if (!okApi || !okIp) {
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            exchange.getResponse().getHeaders().add(HttpHeaders.RETRY_AFTER, "60");
            String rid = RequestIdFilter.ensureRequestId(exchange);
            String json = "{\"code\":\"too_many_requests\",\"message\":\"rate limited\",\"request_id\":\""+rid+"\"}";
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8))));
        }
        return chain.filter(exchange);
    }

    private String clientIp(ServerWebExchange exchange) {
        String xff = exchange.getRequest().getHeaders().getFirst("x-forwarded-for");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return exchange.getRequest().getRemoteAddress() != null ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress() : "unknown";
    }
}
