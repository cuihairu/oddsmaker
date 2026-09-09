package io.oddsmaker.control.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.time.format.DateTimeParseException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全局异常处理全量 handler 测试：每类异常的状态码与响应结构。
 */
@DisplayName("全局异常处理全量测试")
class GlobalExceptionHandlerFullTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final ServletWebRequest request = new ServletWebRequest(
        new org.springframework.mock.web.MockHttpServletRequest());

    @Test
    @DisplayName("400：参数校验/约束违反/缺参/缺头/类型不匹配/不可读消息")
    void badRequestFamily() {
        // MethodArgumentNotValidException（含字段错误映射 lambda）
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "req");
        binding.addError(new FieldError("req", "gameId", "不能为空"));
        var validation = handler.handleValidationExceptions(
            new org.springframework.web.bind.MethodArgumentNotValidException(null, binding));
        assertEquals(400, validation.getStatusCode().value());
        assertNotNull(validation.getBody());

        // ConstraintViolationException（含 lambda）
        @SuppressWarnings("unchecked")
        ConstraintViolation<String> violation = org.mockito.Mockito.mock(ConstraintViolation.class);
        Path path = org.mockito.Mockito.mock(Path.class);
        org.mockito.Mockito.lenient().when(path.toString()).thenReturn("name");
        org.mockito.Mockito.lenient().when(violation.getPropertyPath()).thenReturn(path);
        org.mockito.Mockito.lenient().when(violation.getMessage()).thenReturn("必填");
        var constraint = handler.handleConstraintViolationException(
            new ConstraintViolationException(Set.of(violation)));
        assertEquals(400, constraint.getStatusCode().value());

        assertEquals(400, handler.handleMissingServletRequestParameterException(
            new MissingServletRequestParameterException("gameId", "String")).getStatusCode().value());
        assertEquals(400, handler.handleMissingRequestHeaderException(
            new MissingRequestHeaderException("X-Token", null)).getStatusCode().value());
        assertEquals(400, handler.handleMethodArgumentTypeMismatchException(
            new MethodArgumentTypeMismatchException("x", Long.class, "id", null, null)).getStatusCode().value());
        assertEquals(400, handler.handleHttpMessageNotReadableException(
            new HttpMessageNotReadableException("bad", (org.springframework.http.HttpInputMessage) null)).getStatusCode().value());
        assertEquals(400, handler.handleIllegalArgumentException(new IllegalArgumentException("x")).getStatusCode().value());
    }

    @Test
    @DisplayName("409/401/403/404/405/415/时间与数字格式/空指针")
    void otherFamilies() {
        assertEquals(409, handler.handleIllegalStateException(new IllegalStateException("x")).getStatusCode().value());
        assertEquals(401, handler.handleAuthenticationException(new BadCredentialsException("x")).getStatusCode().value());
        assertEquals(403, handler.handleAccessDeniedException(new AccessDeniedException("x")).getStatusCode().value());
        assertEquals(405, handler.handleHttpRequestMethodNotSupportedException(
            new HttpRequestMethodNotSupportedException("PUT")).getStatusCode().value());
        assertEquals(415, handler.handleHttpMediaTypeNotSupportedException(
            new HttpMediaTypeNotSupportedException("x")).getStatusCode().value());
        assertEquals(404, handler.handleNoHandlerFoundException(
            new NoHandlerFoundException("GET", "/none", null)).getStatusCode().value());
        assertEquals(400, handler.handleDateTimeParseException(
            new DateTimeParseException("bad date", "2026-13-01", 0)).getStatusCode().value());
        assertEquals(400, handler.handleNumberFormatException(
            new NumberFormatException("For input string")).getStatusCode().value());
        assertNotNull(handler.handleNullPointerException(new NullPointerException("x"), request));
        assertNotNull(handler.handleGenericException(new RuntimeException("boom"), request));
    }

    @Test
    @DisplayName("业务异常族：资源不存在/业务规则/限流")
    void businessFamily() {
        assertEquals(404, handler.handleResourceNotFoundException(
            new ResourceNotFoundException("game", "g1")).getStatusCode().value());
        assertEquals(400, handler.handleBusinessException(
            new BusinessException("bad request")).getStatusCode().value());
        assertEquals(429, handler.handleRateLimitExceededException(
            new RateLimitExceededException("too fast")).getStatusCode().value());
    }
}
