package io.oddsmaker.control.exception;

/**
 * 资源未找到异常
 * 当请求的资源不存在时抛出
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public ResourceNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }

    public ResourceNotFoundException(String resourceType, String resourceId) {
        super(String.format("%s with ID '%s' not found", resourceType, resourceId));
    }
}