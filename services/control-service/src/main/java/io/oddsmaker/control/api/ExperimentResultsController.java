package io.oddsmaker.control.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.oddsmaker.control.experiment.ExperimentEntity;
import io.oddsmaker.control.experiment.ExperimentMetricSnapshotEntity;
import io.oddsmaker.control.experiment.ExperimentMetricSnapshotRepo;
import io.oddsmaker.control.experiment.ExperimentRepo;
import io.oddsmaker.control.experiment.ExperimentSplitter;
import io.oddsmaker.control.experiment.ExperimentStatsService;
import io.oddsmaker.control.service.ExperimentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 实验指标收集与结果展示 API。
 *
 * - POST /metrics：聚合管道（Flink/ClickHouse）按窗口回填每变体聚合快照（幂等覆盖同窗口数据）；
 * - GET /results：基于快照执行统计检验（比例 z-test / Welch t-test），输出 lift、CI、p 值与显著性。
 */
@RestController
@RequestMapping("/api/experiments/{id}")
public class ExperimentResultsController {

    private final ExperimentRepo experimentRepo;
    private final ExperimentMetricSnapshotRepo snapshotRepo;
    private final ExperimentStatsService statsService;
    private final ExperimentService experimentService;

    public ExperimentResultsController(ExperimentRepo experimentRepo,
                                       ExperimentMetricSnapshotRepo snapshotRepo,
                                       ExperimentStatsService statsService,
                                       ExperimentService experimentService) {
        this.experimentRepo = experimentRepo;
        this.snapshotRepo = snapshotRepo;
        this.statsService = statsService;
        this.experimentService = experimentService;
    }

    public static class MetricSnapshotReq {
        public String metricName;
        public String variant;
        public Long windowStart;
        public Long count;
        public Double sum;
        public Double sumSquares;
        public Long successes;
    }

    public static class MetricsBatchReq {
        public List<MetricSnapshotReq> snapshots;
    }

    /**
     * 指标快照批量回填：同一 (metric, variant, window) 幂等覆盖。
     */
    @PostMapping("/metrics")
    public ResponseEntity<Map<String, Object>> ingestMetrics(@PathVariable String id,
                                                             @RequestBody MetricsBatchReq req) {
        ExperimentEntity experiment = experimentRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Experiment not found: " + id));
        if (req.snapshots == null || req.snapshots.isEmpty()) {
            throw new IllegalArgumentException("snapshots are required");
        }
        int saved = 0;
        for (MetricSnapshotReq s : req.snapshots) {
            if (s.metricName == null || s.metricName.isBlank()) {
                throw new IllegalArgumentException("metricName is required");
            }
            if (s.variant == null || s.variant.isBlank()) {
                throw new IllegalArgumentException("variant is required");
            }
            long count = s.count == null ? 0 : Math.max(0, s.count);
            long successes = s.successes == null ? 0 : Math.max(0, Math.min(s.successes == null ? 0 : s.successes, count));
            long windowStart = s.windowStart == null ? 0L : s.windowStart;

            ExperimentMetricSnapshotEntity entity = snapshotRepo
                .findByExperimentIdAndMetricNameAndVariantAndWindowStart(id, s.metricName, s.variant, windowStart)
                .orElseGet(ExperimentMetricSnapshotEntity::new);
            if (entity.id == null) {
                entity.id = "ems_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
                entity.experimentId = id;
                entity.metricName = s.metricName;
                entity.variant = s.variant;
                entity.windowStart = windowStart;
            }
            entity.count = count;
            entity.sum = s.sum == null ? count : s.sum;
            entity.sumSquares = s.sumSquares == null ? 0.0 : s.sumSquares;
            entity.successes = successes;
            snapshotRepo.save(entity);
            saved++;
        }
        return ResponseEntity.ok(Map.of("experimentId", id, "ingested", saved));
    }

    /**
     * 实验结果：每指标的多变体统计检验报告。
     */
    @GetMapping("/results")
    public ResponseEntity<Map<String, Object>> results(@PathVariable String id) {
        ExperimentEntity experiment = experimentRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Experiment not found: " + id));

        JsonNode config = readConfig(experiment.configJson);
        String control = config.path("control_variant").asText(null);
        List<ExperimentSplitter.Variant> variants = ExperimentSplitter.parseVariants(config);
        if (control == null && !variants.isEmpty()) {
            control = variants.get(0).name;  // 默认第一个变体为基线
        }
        final String controlVariant = control;

        List<ExperimentMetricSnapshotEntity> snapshots =
            snapshotRepo.findByExperimentIdOrderByWindowStartAsc(id);
        Map<String, Map<String, ExperimentStatsService.ArmStat>> byMetric =
            ExperimentStatsService.aggregate(snapshots);

        Map<String, Integer> expectedWeights = new HashMap<>();
        for (ExperimentSplitter.Variant v : variants) {
            expectedWeights.put(v.name, v.weight);
        }
        Map<String, Long> maxCountsPerVariant = new HashMap<>();

        List<Map<String, Object>> metricResults = new ArrayList<>();
        byMetric.forEach((metric, arms) -> {
            List<ExperimentStatsService.Comparison> comparisons =
                statsService.compare(metric, controlVariant, arms);
            Map<String, Object> out = new HashMap<>();
            out.put("metric", metric);
            out.put("control", controlVariant);
            List<Map<String, Object>> armOut = new ArrayList<>();
            Map<String, Long> armCounts = new HashMap<>();
            arms.forEach((variant, stat) -> {
                Map<String, Object> a = new HashMap<>();
                a.put("variant", variant);
                a.put("samples", stat.count);
                a.put("mean", stat.mean());
                a.put("rate", stat.rate());
                armOut.add(a);
                armCounts.put(variant, stat.count);
                maxCountsPerVariant.merge(variant, stat.count, Math::max);
            });
            out.put("arms", armOut);
            out.put("comparisons", comparisons);
            out.put("srm", statsService.srm(armCounts, expectedWeights));
            metricResults.add(out);
        });

        Map<String, Object> response = new HashMap<>();
        response.put("experimentId", id);
        response.put("status", experiment.status);
        response.put("control", control);
        response.put("srm", statsService.srm(maxCountsPerVariant, expectedWeights));
        response.put("metrics", metricResults);
        return ResponseEntity.ok(response);
    }

    private JsonNode readConfig(String configJson) {
        try {
            return ObjectMapperHolder.MAPPER.readTree(configJson == null || configJson.isBlank() ? "{}" : configJson);
        } catch (Exception e) {
            return ObjectMapperHolder.MAPPER.createObjectNode();
        }
    }

    private static final class ObjectMapperHolder {
        static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
    }
}
