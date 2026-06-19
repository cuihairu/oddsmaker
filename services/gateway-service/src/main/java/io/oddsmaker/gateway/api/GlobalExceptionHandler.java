package io.oddsmaker.gateway.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,Object>> handleRSE(ResponseStatusException ex, ServerWebExchange exchange) {
        String rid = (String) exchange.getAttributes().getOrDefault("x-request-id", "");
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of(
                        "code", toCode(ex.getStatusCode()),
                        "message", ex.getReason() == null ? ex.getStatusCode().toString() : ex.getReason(),
                        "request_id", rid
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,Object>> handleAny(Exception ex, ServerWebExchange exchange) {
        String rid = (String) exchange.getAttributes().getOrDefault("x-request-id", "");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "code", "internal_error",
                        "message", "internal_error",
                        "request_id", rid
                ));
    }

    private String toCode(HttpStatus status) {
        if (status == HttpStatus.PAYLOAD_TOO_LARGE) return "payload_too_large";
        if (status == HttpStatus.TOO_MANY_REQUESTS) return "too_many_requests";
        if (status == HttpStatus.UNAUTHORIZED) return "unauthorized";
        if (status == HttpStatus.FORBIDDEN) return "forbidden";
        if (status == HttpStatus.BAD_REQUEST) return "bad_request";
        return status.name().toLowerCase();
    }
}
