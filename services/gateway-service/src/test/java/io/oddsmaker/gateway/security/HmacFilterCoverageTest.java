package io.oddsmaker.gateway.security;

import io.oddsmaker.gateway.config.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * HmacFilter 拦截分支：非 batch 路径放行、缺 key/无效 key 401、
 * 签名头格式错误/时间戳非数字/签名不匹配 401。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HMAC 过滤器拦截测试")
class HmacFilterCoverageTest {

    @Mock
    AuthService authService;

    @Mock
    ReplayGuard replayGuard;

    @Mock
    WebFilterChain chain;

    private HmacFilter filter;

    @BeforeEach
    void setUp() {
        filter = new HmacFilter(authService, replayGuard);
    }

    private AuthService.ApiKeyContext key() {
        AuthService.ApiKeyContext ctx = new AuthService.ApiKeyContext();
        ctx.apiKey = "pk_ok";
        ctx.secret = "sk";
        ctx.canWrite = true;
        ctx.envStatus = "active";
        return ctx;
    }

    private MockServerWebExchange post(String uri, String apiKey, String sig, String body) {
        MockServerHttpRequest.BodyBuilder b = MockServerHttpRequest.post(uri)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (apiKey != null) b.header("x-api-key", apiKey);
        if (sig != null) b.header("x-signature", sig);
        return MockServerWebExchange.from(b.body(body));
    }

    private int status(MockServerWebExchange ex) {
        return ex.getResponse().getStatusCode() == null ? 200
            : ex.getResponse().getStatusCode().value();
    }

    @Test
    @DisplayName("非 /v1/batch 路径：直接放行过滤链")
    void nonBatchPathPassesThrough() {
        MockServerWebExchange ex = MockServerWebExchange.from(
            MockServerHttpRequest.get("/v1/other").build());
        when(chain.filter(any())).thenReturn(reactor.core.publisher.Mono.empty());
        filter.filter(ex, chain).block();
        assertEquals(200, status(ex)); // 未被过滤器改写状态码
    }

    @Test
    @DisplayName("缺 x-api-key：401 missing_api_key")
    void missingApiKeyRejected() {
        MockServerWebExchange ex = post("/v1/batch", null, null, "[]");
        filter.filter(ex, chain).block();
        assertEquals(401, status(ex));
    }

    @Test
    @DisplayName("key 上下文为 null：401 invalid_api_key")
    void unknownKeyRejected() {
        when(authService.getContext("pk_unknown")).thenReturn(null);
        MockServerWebExchange ex = post("/v1/batch", "pk_unknown", null, "[]");
        filter.filter(ex, chain).block();
        assertEquals(401, status(ex));
    }

    @Test
    @DisplayName("key 无写权限：401 invalid_api_key")
    void readOnlyKeyRejected() {
        AuthService.ApiKeyContext ro = key();
        ro.canWrite = false;
        when(authService.getContext("pk_ro")).thenReturn(ro);
        MockServerWebExchange ex = post("/v1/batch", "pk_ro", null, "[]");
        filter.filter(ex, chain).block();
        assertEquals(401, status(ex));
    }

    @Test
    @DisplayName("签名头无 t=/s= 片段：401 invalid_signature")
    void malformedSignatureRejected() {
        when(authService.getContext("pk_ok")).thenReturn(key());
        MockServerWebExchange ex = post("/v1/batch", "pk_ok", "garbage", "[]");
        filter.filter(ex, chain).block();
        assertEquals(401, status(ex));
    }

    @Test
    @DisplayName("时间戳非数字：401 invalid_signature")
    void nonNumericTimestampRejected() {
        when(authService.getContext("pk_ok")).thenReturn(key());
        MockServerWebExchange ex = post("/v1/batch", "pk_ok", "t=abc, s=ff", "[]");
        filter.filter(ex, chain).block();
        assertEquals(401, status(ex));
    }

    @Test
    @DisplayName("签名值不匹配：401 invalid_signature")
    void wrongSignatureRejected() {
        when(authService.getContext("pk_ok")).thenReturn(key());
        long now = java.time.Instant.now().getEpochSecond();
        MockServerWebExchange ex = post("/v1/batch", "pk_ok", "t=" + now + ", s=deadbeef", "[]");
        filter.filter(ex, chain).block();
        assertEquals(401, status(ex));
    }
}
