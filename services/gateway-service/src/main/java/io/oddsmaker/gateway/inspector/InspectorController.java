package io.oddsmaker.gateway.inspector;

import io.oddsmaker.gateway.config.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 实时事件检视 API（Live Inspector，竞品差距 P7-1）。
 *
 * GET /v1/inspector/recent?game_id=&environment=&limit=&outcome=
 *
 * 鉴权：与 /v1/batch 相同的 x-api-key（client/server/admin 均可读——检视是接入调试面，
 * 客户端开发者正是第一用户）。不强制 HMAC：GET 无请求体，签名约定（t + '.' + body）
 * 不适用；读取面泄漏风险由作用域过滤兜底。
 *
 * 作用域：scoped key（绑定 game+environment）只能看到自己作用域的记录，
 * 查询参数中的 game_id/environment 被强制覆盖为 key 作用域；
 * 非 scoped key（本地开发静态 key）必须显式传参，否则 400。
 */
@RestController
@RequestMapping("/v1/inspector")
public class InspectorController {

    static final int MAX_LIMIT = 200;
    static final int DEFAULT_LIMIT = 50;

    private final AuthService authService;
    private final EventInspectorBuffer buffer;

    public InspectorController(AuthService authService, EventInspectorBuffer buffer) {
        this.authService = authService;
        this.buffer = buffer;
    }

    @GetMapping("/recent")
    public Mono<Map<String, Object>> recent(
            @RequestHeader(value = "x-api-key", required = false) String apiKey,
            @RequestParam(value = "game_id", required = false) String gameIdParam,
            @RequestParam(value = "environment", required = false) String environmentParam,
            @RequestParam(value = "limit", required = false) Integer limitParam,
            @RequestParam(value = "outcome", required = false) String outcomeParam) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing_api_key");
        }
        AuthService.ApiKeyContext keyContext = authService.getContext(apiKey);
        if (keyContext == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_api_key");
        }

        final String gameId;
        final String environment;
        if (keyContext.isScoped()) {
            // scoped key：强制按 key 作用域过滤，忽略显式传参（防止越权窥视其他作用域）
            gameId = keyContext.gameId;
            environment = keyContext.environment;
        } else {
            if (isBlank(gameIdParam) || isBlank(environmentParam)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing_scope");
            }
            gameId = gameIdParam;
            environment = environmentParam;
        }

        String outcome = null;
        if (outcomeParam != null && !outcomeParam.isBlank()) {
            outcome = outcomeParam.toLowerCase(Locale.ROOT);
            if (!EventInspectorBuffer.OUTCOMES.contains(outcome)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_outcome");
            }
        }

        int limit = limitParam == null ? DEFAULT_LIMIT : Math.min(Math.max(limitParam, 1), MAX_LIMIT);

        List<EventInspectorBuffer.InspectorRecord> records =
            buffer.recent(gameId, environment, outcome, limit);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("game_id", gameId);
        body.put("environment", environment);
        body.put("count", records.size());
        body.put("events", records);
        return Mono.just(body);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
