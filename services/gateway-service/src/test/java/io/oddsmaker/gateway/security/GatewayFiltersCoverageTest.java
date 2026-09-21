package io.oddsmaker.gateway.security;

import io.oddsmaker.gateway.config.PolicyService;
import io.oddsmaker.gateway.config.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RateLimitFilter / RequestIdFilter 的分支直测：非 batch 路径放行、
 * 缺失/空白 x-api-key 回退 anonymous、无 XFF 的 IP 提取、已带请求 ID 直用。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("网关过滤器分支测试")
class GatewayFiltersCoverageTest {

    @Mock
    RateLimiterService limiter;

    @Mock
    PolicyService policies;

    @Mock
    WebFilterChain chain;

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter(limiter, policies);
        lenient().when(policies.getPolicy(anyString())).thenReturn(null);
        lenient().when(limiter.allowApiKey(anyString())).thenReturn(true);
        lenient().when(limiter.allowIp(anyString())).thenReturn(true);
        lenient().when(limiter.allowApiKey(anyString(), anyInt())).thenReturn(true);
        lenient().when(limiter.allowIp(anyString(), anyInt())).thenReturn(true);
        lenient().when(chain.filter(any())).thenReturn(reactor.core.publisher.Mono.empty());
    }

    private int status(MockServerWebExchange ex) {
        return ex.getResponse().getStatusCode() == null ? 200
            : ex.getResponse().getStatusCode().value();
    }

    @Test
    @DisplayName("非 /v1/batch 路径：不经过限流直接放行")
    void nonBatchPathSkipsRateLimit() {
        MockServerWebExchange ex = MockServerWebExchange.from(
            MockServerHttpRequest.get("/v1/health").build());
        filter.filter(ex, chain).block();
        assertEquals(200, status(ex));
        verify(chain).filter(ex);
        verify(limiter, org.mockito.Mockito.never()).allowApiKey(anyString());
    }

    @Test
    @DisplayName("缺失与空白 x-api-key：回退 anonymous 限流桶")
    void missingOrBlankApiKeyFallsBackToAnonymous() {
        MockServerWebExchange noKey = MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/batch").contentType(
                org.springframework.http.MediaType.APPLICATION_JSON).body("[]"));
        filter.filter(noKey, chain).block();
        assertEquals(200, status(noKey));
        verify(limiter).allowApiKey("anonymous");

        MockServerWebExchange blankKey = MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/batch")
                .header("x-api-key", "   ")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body("[]"));
        filter.filter(blankKey, chain).block();
        assertEquals(200, status(blankKey));
        verify(limiter, org.mockito.Mockito.times(2)).allowApiKey("anonymous");
    }

    @Test
    @DisplayName("无 XFF 头且无远端地址：IP 提取回落 unknown")
    void noXffNorRemoteAddressFallsBackToUnknown() {
        MockServerWebExchange ex = MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/batch")
                .header("x-api-key", "pk_x")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body("[]"));
        filter.filter(ex, chain).block();
        assertEquals(200, status(ex));
        verify(limiter).allowIp("unknown");

        // xff 存在但为空串（isEmpty 侧）：同样回落 unknown
        MockServerWebExchange emptyXff = MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/batch")
                .header("x-api-key", "pk_x")
                .header("x-forwarded-for", "")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body("[]"));
        filter.filter(emptyXff, chain).block();
        verify(limiter, org.mockito.Mockito.times(2)).allowIp("unknown");
    }

    @Test
    @DisplayName("限流命中：429 响应携带 request_id 与 retry-after")
    void rateLimitedReturns429() {
        when(limiter.allowApiKey(anyString())).thenReturn(false);
        MockServerWebExchange ex = MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/batch")
                .header("x-api-key", "pk_rl")
                .header("x-forwarded-for", "9.9.9.9")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body("[]"));
        filter.filter(ex, chain).block();
        assertEquals(429, status(ex));
        verify(limiter).allowIp("9.9.9.9"); // XFF 首跳
    }

    @Test
    @DisplayName("RequestIdFilter：请求已带 x-request-id 直用，缺省生成 UUID")
    void requestIdReusedOrGenerated() {
        MockServerWebExchange withId = MockServerWebExchange.from(
            MockServerHttpRequest.get("/v1/anything").header("x-request-id", "abc-123").build());
        assertEquals("abc-123", RequestIdFilter.ensureRequestId(withId));

        MockServerWebExchange noId = MockServerWebExchange.from(
            MockServerHttpRequest.get("/v1/anything").build());
        String generated = RequestIdFilter.ensureRequestId(noId);
        assertTrue(generated.length() > 10);
        assertEquals(generated, noId.getAttributes().get(RequestIdFilter.ATTR));

        // 空白串视为缺省
        MockServerWebExchange blankId = MockServerWebExchange.from(
            MockServerHttpRequest.get("/v1/anything").header("x-request-id", "  ").build());
        assertEquals(RequestIdFilter.ensureRequestId(blankId),
            blankId.getAttributes().get(RequestIdFilter.ATTR));
    }
}
