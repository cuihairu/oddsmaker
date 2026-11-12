package io.oddsmaker.gateway.config;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 客户端 IP 解析（P1 X-Forwarded-For 伪造修复）。
 *
 * 默认只信真实连接对端（TCP peer）；X-Forwarded-For 仅在直接对端是配置的受信代理时采信，
 * 且取最左（原始客户端）条目。无受信代理配置时 XFF 一律忽略——防客户端伪造 XFF 头
 * 绕过 IP 限流桶、污染 client_ip（风控/PII 主体）。
 */
@Component
public class ClientIpResolver {

    private final Set<String> trustedProxies;

    @org.springframework.beans.factory.annotation.Autowired
    public ClientIpResolver(Environment env) {
        List<String> configured = Binder.get(env)
            .bind("oddsmaker.gateway.trusted-proxies", List.class).orElse(List.of());
        this.trustedProxies = configured.stream()
            .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    /** 测试用：直接指定受信代理集合（空集 = 忽略一切 XFF）。 */
    public ClientIpResolver(Set<String> trustedProxies) {
        this.trustedProxies = trustedProxies == null ? Set.of() : trustedProxies;
    }

    /**
     * 解析客户端 IP：对端是受信代理且带非空 XFF → 取 XFF 最左条目（客户端原始地址）；
     * 否则返回真实对端地址（XFF 不采信，防伪造）；无对端地址返回 null。
     */
    public String resolve(ServerHttpRequest req) {
        String peer = req.getRemoteAddress() != null
            ? req.getRemoteAddress().getAddress().getHostAddress() : null;
        if (peer != null && trustedProxies.contains(peer)) {
            String xff = req.getHeaders().getFirst("x-forwarded-for");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return peer;
    }
}
