package io.oddsmaker.gateway.security;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class RequestIdFilter implements WebFilter {
    public static final String ATTR = "x-request-id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String rid = ensureRequestId(exchange);
        exchange.getResponse().getHeaders().set("x-request-id", rid);
        return chain.filter(exchange);
    }

    public static String ensureRequestId(ServerWebExchange exchange) {
        String rid = exchange.getRequest().getHeaders().getFirst("x-request-id");
        if (rid == null || rid.isBlank()) rid = gen();
        exchange.getAttributes().put(ATTR, rid);
        return rid;
    }

    private static String gen() {
        return UUID.randomUUID().toString();
    }
}
