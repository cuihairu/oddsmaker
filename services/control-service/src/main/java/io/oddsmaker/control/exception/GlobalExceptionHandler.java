package io.oddsmaker.control.exception;

import io.oddsmaker.control.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * 统一处理应用程序中的各种异常，返回标准化的错误响应
 */
@RestControllerAdvice
@Hidden
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理参数验证异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationExceptions(
            MethodArgumentNotValidException ex) {

        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        logger.warn("Validation failed: {}", errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.validationError("Validation failed", errors));
    }

    /**
     * 处理约束违反异常
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleConstraintViolationException(
            ConstraintViolationException ex) {

        Map<String, String> errors = ex.getConstraintViolations().stream()
            .collect(Collectors.toMap(
                violation -> violation.getPropertyPath().toString(),
                ConstraintViolation::getMessage
            ));

        logger.warn("Constraint violation: {}", errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.validationError("Constraint validation failed", errors));
    }

    /**
     * 处理非法参数异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<String>> handleIllegalArgumentException(
            IllegalArgumentException ex) {

        logger.warn("Illegal argument: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.badRequest(ex.getMessage()));
    }

    /**
     * 处理非法状态异常
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<String>> handleIllegalStateException(
            IllegalStateException ex) {

        logger.warn("Illegal state: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiResponse.conflict(ex.getMessage()));
    }

    /**
     * 处理认证异常
     */
    @ExceptionHandler({AuthenticationException.class, BadCredentialsException.class})
    public ResponseEntity<ApiResponse<String>> handleAuthenticationException(
            AuthenticationException ex) {

        logger.warn("Authentication failed: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ApiResponse.unauthorized("Authentication failed"));
    }

    /**
     * 处理访问拒绝异常
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<String>> handleAccessDeniedException(
            AccessDeniedException ex) {

        logger.warn("Access denied: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ApiResponse.forbidden("Access denied"));
    }

    /**
     * 处理缺少请求参数异常
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<String>> handleMissingServletRequestParameterException(
            MissingServletRequestParameterException ex) {

        String message = String.format("Missing required parameter: %s", ex.getParameterName());
        logger.warn("Missing parameter: {}", message);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.badRequest(message));
    }

    /**
     * 处理缺少请求头异常
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<String>> handleMissingRequestHeaderException(
            MissingRequestHeaderException ex) {

        String message = String.format("Missing required header: %s", ex.getHeaderName());
        logger.warn("Missing header: {}", message);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.badRequest(message));
    }

    /**
     * 处理方法参数类型不匹配异常
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<String>> handleMethodArgumentTypeMismatchException(
            MethodArgumentTypeMismatchException ex) {

        String message = String.format("Invalid value '%s' for parameter '%s'. Expected type: %s",
            ex.getValue(), ex.getName(), ex.getRequiredType().getSimpleName());
        logger.warn("Type mismatch: {}", message);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.badRequest(message));
    }

    /**
     * 处理HTTP请求方法不支持异常
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<String>> handleHttpRequestMethodNotSupportedException(
            HttpRequestMethodNotSupportedException ex) {

        String message = String.format("Method '%s' not supported. Supported methods: %s",
            ex.getMethod(), ex.getSupportedHttpMethods());
        logger.warn("Method not supported: {}", message);

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(ApiResponse.error(405, message));
    }

    /**
     * 处理HTTP媒体类型不支持异常
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<String>> handleHttpMediaTypeNotSupportedException(
            HttpMediaTypeNotSupportedException ex) {

        String message = String.format("Media type '%s' not supported. Supported types: %s",
            ex.getContentType(), ex.getSupportedMediaTypes());
        logger.warn("Media type not supported: {}", message);

        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            .body(ApiResponse.error(415, message));
    }

    /**
     * 处理HTTP消息不可读异常
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<String>> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException ex) {

        String message = "Invalid JSON format or missing request body";
        logger.warn("Message not readable: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.badRequest(message));
    }

    /**
     * 处理404异常
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<String>> handleNoHandlerFoundException(
            NoHandlerFoundException ex) {

        String message = String.format("Endpoint '%s %s' not found", ex.getHttpMethod(), ex.getRequestURL());
        logger.warn("Endpoint not found: {}", message);

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiResponse.notFound(message));
    }

    /**
     * 处理日期时间解析异常
     */
    @ExceptionHandler(DateTimeParseException.class)
    public ResponseEntity<ApiResponse<String>> handleDateTimeParseException(
            DateTimeParseException ex) {

        String message = String.format("Invalid date/time format: %s", ex.getParsedString());
        logger.warn("Date parsing error: {}", message);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.badRequest(message));
    }

    /**
     * 处理数字格式异常
     */
    @ExceptionHandler(NumberFormatException.class)
    public ResponseEntity<ApiResponse<String>> handleNumberFormatException(
            NumberFormatException ex) {

        String message = "Invalid number format";
        logger.warn("Number format error: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.badRequest(message));
    }

    /**
     * 处理空指针异常
     */
    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<ApiResponse<String>> handleNullPointerException(
            NullPointerException ex, WebRequest request) {

        logger.error("Null pointer exception occurred at {}: {}",
            request.getDescription(false), ex.getMessage(), ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error("An unexpected error occurred"));
    }

    /**
     * 处理资源未找到异常
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<String>> handleResourceNotFoundException(
            ResourceNotFoundException ex) {

        logger.warn("Resource not found: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiResponse.notFound(ex.getMessage()));
    }

    /**
     * 处理业务异常
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<String>> handleBusinessException(
            BusinessException ex) {

        logger.warn("Business exception: {}", ex.getMessage());

        return ResponseEntity.status(ex.getStatus())
            .body(ApiResponse.error(ex.getStatus().value(), ex.getMessage()));
    }

    /**
     * 处理限流异常
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiResponse<String>> handleRateLimitExceededException(
            RateLimitExceededException ex) {

        logger.warn("Rate limit exceeded: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .body(ApiResponse.error(429, ex.getMessage()));
    }

    /**
     * 处理所有其他未捕获的异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<String>> handleGenericException(
            Exception ex, WebRequest request) {

        logger.error("Unexpected error occurred at {}: {}",
            request.getDescription(false), ex.getMessage(), ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error("An unexpected error occurred. Please contact support if the issue persists."));
    }
}