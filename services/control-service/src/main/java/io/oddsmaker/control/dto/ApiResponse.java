package io.oddsmaker.control.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * 统一API响应包装类
 * 提供标准化的API响应格式
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    /**
     * 响应状态码
     */
    public int code;

    /**
     * 响应消息
     */
    public String message;

    /**
     * 响应数据
     */
    public T data;

    /**
     * 响应时间戳
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    public LocalDateTime timestamp;

    /**
     * 请求追踪ID
     */
    public String traceId;

    /**
     * 分页信息（当数据为分页结果时）
     */
    public PageInfo pageInfo;

    // 构造函数
    public ApiResponse() {
        this.timestamp = LocalDateTime.now();
    }

    public ApiResponse(int code, String message) {
        this();
        this.code = code;
        this.message = message;
    }

    public ApiResponse(int code, String message, T data) {
        this(code, message);
        this.data = data;
    }

    // 静态工厂方法
    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>(200, "Success");
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "Success", data);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(200, message, data);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(500, message);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message);
    }

    public static <T> ApiResponse<T> badRequest(String message) {
        return new ApiResponse<>(400, message);
    }

    public static <T> ApiResponse<T> unauthorized(String message) {
        return new ApiResponse<>(401, message != null ? message : "Unauthorized");
    }

    public static <T> ApiResponse<T> forbidden(String message) {
        return new ApiResponse<>(403, message != null ? message : "Forbidden");
    }

    public static <T> ApiResponse<T> notFound(String message) {
        return new ApiResponse<>(404, message != null ? message : "Not Found");
    }

    public static <T> ApiResponse<T> conflict(String message) {
        return new ApiResponse<>(409, message);
    }

    public static <T> ApiResponse<T> validationError(String message, T errors) {
        return new ApiResponse<>(400, message, errors);
    }

    // 分页响应
    public static <T> ApiResponse<T> page(T data, long total, int page, int size) {
        ApiResponse<T> response = success(data);
        response.pageInfo = new PageInfo(total, page, size);
        return response;
    }

    // 设置追踪ID
    public ApiResponse<T> withTraceId(String traceId) {
        this.traceId = traceId;
        return this;
    }

    // 判断是否成功
    public boolean isSuccess() {
        return code >= 200 && code < 300;
    }

    /**
     * 分页信息
     */
    public static class PageInfo {
        public long total;        // 总记录数
        public int page;          // 当前页码
        public int size;          // 每页大小
        public long totalPages;   // 总页数

        public PageInfo(long total, int page, int size) {
            this.total = total;
            this.page = page;
            this.size = size;
            this.totalPages = size > 0 ? (total + size - 1) / size : 0;
        }
    }
}