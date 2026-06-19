package io.oddsmaker.control.exception;

/**
 * 限流异常
 * 当API调用频率超过限制时抛出
 */
public class RateLimitExceededException extends RuntimeException {

    private final String userId;
    private final String endpoint;
    private final long retryAfter;

    public RateLimitExceededException(String message) {
        super(message);
        this.userId = null;
        this.endpoint = null;
        this.retryAfter = 0;
    }

    public RateLimitExceededException(String message, String userId, String endpoint, long retryAfter) {
        super(message);
        this.userId = userId;
        this.endpoint = endpoint;
        this.retryAfter = retryAfter;
    }

    public String getUserId() {
        return userId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public long getRetryAfter() {
        return retryAfter;
    }
}