package io.oddsmaker.gateway.security;

import io.oddsmaker.common.auth.HmacSigner;
import io.oddsmaker.gateway.config.AuthService;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.springframework.core.io.buffer.DataBufferUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Component
public class HmacFilter implements WebFilter {
    private final AuthService authService;

    public HmacFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!"/v1/batch".equals(path)) return chain.filter(exchange);

        String apiKey = exchange.getRequest().getHeaders().getFirst("x-api-key");
        if (apiKey == null) return unauthorized(exchange, "missing_api_key");
        String secret = authService.getSecret(apiKey);
        if (secret == null) return unauthorized(exchange, "invalid_api_key");

        String sig = exchange.getRequest().getHeaders().getFirst("x-signature");
        if (sig == null || sig.isEmpty()) {
            // 签名可选：未提供则放行（可按需改为强制）
            return chain.filter(exchange);
        }

        // 签名格式: t=TIMESTAMP, s=hex(hmacSha256(secret, t + '.' + body))
        String tPrefix = "t=";
        String sPrefix = "s=";
        String[] parts = sig.split(",");
        String t = null, s = null;
        for (String p : parts) {
            p = p.trim();
            if (p.startsWith(tPrefix)) t = p.substring(tPrefix.length());
            if (p.startsWith(sPrefix)) s = p.substring(sPrefix.length());
        }
        if (t == null || s == null) return unauthorized(exchange, "invalid_signature");

        try {
            long ts = Long.parseLong(t);
            long now = Instant.now().getEpochSecond();
            if (Math.abs(now - ts) > 300) return unauthorized(exchange, "signature_expired");
        } catch (NumberFormatException e) {
            return unauthorized(exchange, "invalid_signature");
        }

        // 缓存请求体并验证 HMAC，再重放给后续链路
        return DataBufferUtils.join(exchange.getRequest().getBody()).flatMap(dataBuffer -> {
            byte[] bodyBytes = new byte[dataBuffer.readableByteCount()];
            dataBuffer.read(bodyBytes);
            DataBufferUtils.release(dataBuffer);

            String computed = HmacSigner.hmacSha256Hex(secret, t + "." + new String(bodyBytes, StandardCharsets.UTF_8));
            if (!computed.equalsIgnoreCase(s)) {
                return unauthorized(exchange, "invalid_signature");
            }

            DataBufferFactory bufferFactory = exchange.getResponse().bufferFactory();
            ServerHttpRequest decorated = new ServerHttpRequestDecorator(exchange.getRequest()) {
                @Override
                public Flux<DataBuffer> getBody() {
                    return Flux.just(bufferFactory.wrap(bodyBytes));
                }
            };
            return chain.filter(exchange.mutate().request(decorated).build());
        });
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String reason) {
        exchange.getResponse().setRawStatusCode(401);
        exchange.getResponse().getHeaders().add(HttpHeaders.CONTENT_TYPE, "application/json");
        String json = "{\"error\":\"" + reason + "\"}";
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Flux.just(buffer));
    }
}
