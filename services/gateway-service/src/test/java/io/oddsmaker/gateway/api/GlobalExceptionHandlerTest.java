package io.oddsmaker.gateway.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 全局异常处理器：toCode 全状态码映射 + 兜底 500 + request-id 透传 + 无 reason 降级。
 */
@DisplayName("全局异常处理器测试")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private MockServerWebExchange exchange() {
        MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/batch").build());
        ex.getAttributes().put("x-request-id", "rid-1");
        return ex;
    }

    @Test
    @DisplayName("413 payload_too_large / 429 too_many_requests / 401 unauthorized / 403 forbidden / 400 bad_request")
    void toCodeCoversCommonStatuses() {
        assertEquals("payload_too_large", code(HttpStatus.PAYLOAD_TOO_LARGE));
        assertEquals("too_many_requests", code(HttpStatus.TOO_MANY_REQUESTS));
        assertEquals("unauthorized", code(HttpStatus.UNAUTHORIZED));
        assertEquals("forbidden", code(HttpStatus.FORBIDDEN));
        assertEquals("bad_request", code(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("未枚举状态码降级为 status 文本")
    void toCodeFallsBackToStatusText() {
        assertEquals("503 service_unavailable", code(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("无 reason 的 ResponseStatusException 降级为状态码文本，request_id 透传")
    void handlesMissingReason() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        var resp = handler.handleRSE(ex, exchange());
        assertEquals(413, resp.getStatusCode().value());
        Map<?, ?> body = resp.getBody();
        assertEquals("payload_too_large", body.get("code"));
        assertEquals("413 PAYLOAD_TOO_LARGE", body.get("message"));
        assertEquals("rid-1", body.get("request_id"));
    }

    @Test
    @DisplayName("任意未知异常兜底 500 internal_error，不泄露异常细节")
    void handlesArbitraryExceptionAs500() {
        var resp = handler.handleAny(new IllegalStateException("boom"), exchange());
        assertEquals(500, resp.getStatusCode().value());
        Map<?, ?> body = resp.getBody();
        assertEquals("internal_error", body.get("code"));
        assertEquals("internal_error", body.get("message"));
        assertEquals("rid-1", body.get("request_id"));
    }

    private String code(HttpStatus status) {
        var resp = handler.handleRSE(new ResponseStatusException(status, "r"), exchange());
        return (String) resp.getBody().get("code");
    }
}
