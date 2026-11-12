package io.oddsmaker.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.InetSocketAddress;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ClientIpResolver 分支直测：受信代理采信 XFF 最左条目、非受信对端忽略 XFF、空白/无 XFF 回落对端。
 */
@DisplayName("客户端 IP 解析受信代理分支测试")
class ClientIpResolverTest {

    @Test
    @DisplayName("受信对端：XFF 最左条目（原始客户端）被采信")
    void trustedPeerHonorsLeftmostXff() {
        ClientIpResolver r = new ClientIpResolver(Set.of("10.0.0.1"));
        MockServerHttpRequest req = MockServerHttpRequest.post("/v1/batch")
            .remoteAddress(new InetSocketAddress("10.0.0.1", 8080))
            .header("x-forwarded-for", "1.2.3.4, 10.0.0.1")
            .build();
        assertEquals("1.2.3.4", r.resolve(req));
    }

    @Test
    @DisplayName("非受信对端：XFF 被忽略，回落真实 TCP 对端（防伪造）")
    void untrustedPeerIgnoresXff() {
        ClientIpResolver r = new ClientIpResolver(Set.of("10.0.0.1"));
        MockServerHttpRequest req = MockServerHttpRequest.post("/v1/batch")
            .remoteAddress(new InetSocketAddress("10.0.0.99", 8080))
            .header("x-forwarded-for", "1.2.3.4")
            .build();
        assertEquals("10.0.0.99", r.resolve(req));
    }

    @Test
    @DisplayName("空白 XFF 或空受信集：回落对端；无对端返回 null")
    void blankXffAndEmptyTrustedFallback() {
        // 受信对端但 XFF 空白 → 回落对端
        ClientIpResolver r = new ClientIpResolver(Set.of("10.0.0.1"));
        MockServerHttpRequest blank = MockServerHttpRequest.post("/v1/batch")
            .remoteAddress(new InetSocketAddress("10.0.0.1", 8080))
            .header("x-forwarded-for", "   ")
            .build();
        assertEquals("10.0.0.1", r.resolve(blank));

        // 空受信集（默认无受信代理）：即使带 XFF 也忽略 → 回落对端
        ClientIpResolver strict = new ClientIpResolver(Set.of());
        MockServerHttpRequest req = MockServerHttpRequest.post("/v1/batch")
            .remoteAddress(new InetSocketAddress("10.0.0.9", 1234))
            .header("x-forwarded-for", "9.9.9.9")
            .build();
        assertEquals("10.0.0.9", strict.resolve(req));

        // 无对端地址：null
        assertNull(strict.resolve(MockServerHttpRequest.post("/v1/batch")
            .header("x-forwarded-for", "9.9.9.9").build()));
    }
}
