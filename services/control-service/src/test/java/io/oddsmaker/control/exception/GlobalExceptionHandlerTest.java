package io.oddsmaker.control.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 全局异常处理测试：每类异常映射到对应 HTTP 状态码与 ApiResponse 结构。
 */
@DisplayName("全局异常处理测试")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("参数与校验类异常映射 400")
    void validationExceptions() {
        assertEquals(400, handler.handleIllegalArgumentException(new IllegalArgumentException("bad")).getStatusCode().value());
        assertEquals(409, handler.handleIllegalStateException(new IllegalStateException("state")).getStatusCode().value());
        assertEquals(400, handler.handleMissingServletRequestParameterException(
            new MissingServletRequestParameterException("gameId", "String")).getStatusCode().value());
        assertEquals(400, handler.handleMissingRequestHeaderException(
            new MissingRequestHeaderException("X-Token", null)).getStatusCode().value());
        assertEquals(400, handler.handleMethodArgumentTypeMismatchException(
            new MethodArgumentTypeMismatchException("x", Long.class, "id", null, null)).getStatusCode().value());
        assertEquals(400, handler.handleHttpMessageNotReadableException(
            new HttpMessageNotReadableException("bad", (org.springframework.http.HttpInputMessage) null)).getStatusCode().value());
    }

    @Test
    @DisplayName("认证/授权异常映射 401/403")
    void authExceptions() {
        assertEquals(401, handler.handleAuthenticationException(new BadCredentialsException("x")).getStatusCode().value());
        assertEquals(403, handler.handleAccessDeniedException(new AccessDeniedException("x")).getStatusCode().value());
    }

    @Test
    @DisplayName("方法/媒体类型不支持映射 405/415")
    void methodExceptions() {
        assertEquals(405, handler.handleHttpRequestMethodNotSupportedException(
            new HttpRequestMethodNotSupportedException("PUT")).getStatusCode().value());
        assertEquals(415, handler.handleHttpMediaTypeNotSupportedException(
            new HttpMediaTypeNotSupportedException("x")).getStatusCode().value());
    }

    @Test
    @DisplayName("业务/资源/限流异常映射")
    void businessExceptions() {
        assertNotNull(handler.handleBusinessException(new BusinessException("biz")));
        assertNotNull(handler.handleResourceNotFoundException(new ResourceNotFoundException("nf")));
        assertNotNull(handler.handleRateLimitExceededException(new RateLimitExceededException("rl")));
        assertEquals(429, handler.handleRateLimitExceededException(new RateLimitExceededException("rl")).getStatusCode().value());
        assertNotNull(handler.handleGenericException(new RuntimeException("boom"),
            new org.springframework.web.context.request.ServletWebRequest(
                new org.springframework.mock.web.MockHttpServletRequest())));
    }
}
