package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.FeatureStoreEntity;
import io.oddsmaker.control.jpa.FeatureStoreRepo;
import io.oddsmaker.control.service.DataQualityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Game Data Health 页取数面（B10，设计定稿 07-b10 §4.5/§5）。
 * 走 /api 的常规 JWT 鉴权；数据源只有 data_quality_metrics 与 feature_store 两张表。
 */
@RestController
@RequestMapping("/api")
public class DataQualityController {

    @Autowired
    private DataQualityService dataQualityService;

    @Autowired
    private FeatureStoreRepo featureStoreRepo;

    /** 近 hours 小时的 5 分钟窗口序列（新窗在前），每窗带五率与恒等式余项。 */
    @GetMapping("/data-quality/series")
    public ResponseEntity<List<Map<String, Object>>> series(
            @RequestParam String gameId,
            @RequestParam String environment,
            @RequestParam(defaultValue = "24") int hours) {
        return ResponseEntity.ok(dataQualityService.series(gameId, environment, hours));
    }

    /** 近 hours 小时汇总：五率 + 拒绝原因 Top（kafka_error 为平台故障，页面单列标记）。 */
    @GetMapping("/data-quality/summary")
    public ResponseEntity<Map<String, Object>> summary(
            @RequestParam String gameId,
            @RequestParam String environment,
            @RequestParam(defaultValue = "24") int hours) {
        return ResponseEntity.ok(dataQualityService.summary(gameId, environment, hours));
    }

    /** 共享 Feature 摘要：按 scope 取近 hours 小时窗口行（新窗在前），features 原样透出。 */
    @GetMapping("/feature-store/{gameId}/{environment}")
    public ResponseEntity<List<Map<String, Object>>> featureStore(
            @PathVariable String gameId,
            @PathVariable String environment,
            @RequestParam String scopeKey,
            @RequestParam(defaultValue = "24") int hours) {
        if (scopeKey == null || scopeKey.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        LocalDateTime since = LocalDateTime.now().minusHours(Math.max(1, hours));
        List<Map<String, Object>> out = new ArrayList<>();
        for (FeatureStoreEntity row : featureStoreRepo
            .findByGameIdAndEnvironmentAndScopeKeyAndWindowEndGreaterThanEqualOrderByWindowEndDesc(
                gameId, environment, scopeKey, since)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("scopeKey", row.scopeKey);
            item.put("windowStart", String.valueOf(row.windowStart));
            item.put("windowEnd", String.valueOf(row.windowEnd));
            item.put("asOf", String.valueOf(row.asOf));
            item.put("features", row.features);
            out.add(item);
        }
        return ResponseEntity.ok(out);
    }
}
