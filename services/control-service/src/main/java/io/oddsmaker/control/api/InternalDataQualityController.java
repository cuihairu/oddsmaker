package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.DataQualityMetricsEntity;
import io.oddsmaker.control.service.DataQualityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 内部 API —— 网关数据质量快照摄取（B10，设计定稿 07-b10 §4.2）。
 * 靠 AdminTokenFilter + x-admin-token 鉴权（与 /internal 其他端点同通道）。
 * 快照携带自窗口起点以来的累计值，服务层按唯一键整行覆盖；请求类显式列计数字段，
 * 不开放实体直绑（id/updatedAt 不受外部赋值）。
 */
@RestController
@RequestMapping("/internal")
public class InternalDataQualityController {

    @Autowired
    private DataQualityService dataQualityService;

    @PostMapping("/data-quality")
    public ResponseEntity<Map<String, Object>> ingest(@RequestBody Snapshot snapshot) {
        LocalDateTime windowStart;
        try {
            windowStart = snapshot.windowStart == null ? null : LocalDateTime.parse(snapshot.windowStart);
        } catch (DateTimeParseException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "windowStart must be ISO local date-time"));
        }
        try {
            DataQualityMetricsEntity saved = dataQualityService.ingest(toEntity(snapshot, windowStart));
            return ResponseEntity.ok(Map.of("windowStart", String.valueOf(saved.windowStart)));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    private static DataQualityMetricsEntity toEntity(Snapshot snapshot, LocalDateTime windowStart) {
        DataQualityMetricsEntity entity = new DataQualityMetricsEntity();
        entity.gameId = snapshot.gameId;
        entity.environment = snapshot.environment;
        entity.windowStart = windowStart;
        entity.windowSec = snapshot.windowSec;
        entity.received = snapshot.received;
        entity.accepted = snapshot.accepted;
        entity.sampledOut = snapshot.sampledOut;
        entity.rejectedSchema = snapshot.rejectedSchema;
        entity.rejectedUnknownEvent = snapshot.rejectedUnknownEvent;
        entity.rejectedInvalidTimestamp = snapshot.rejectedInvalidTimestamp;
        entity.rejectedPiiBlocked = snapshot.rejectedPiiBlocked;
        entity.rejectedPayloadTooLarge = snapshot.rejectedPayloadTooLarge;
        entity.rejectedTrustEscalation = snapshot.rejectedTrustEscalation;
        entity.rejectedBlocked = snapshot.rejectedBlocked;
        entity.rejectedScopeMismatch = snapshot.rejectedScopeMismatch;
        entity.rejectedKafkaError = snapshot.rejectedKafkaError;
        entity.duplicatesGateway = snapshot.duplicatesGateway;
        entity.duplicatesEnrich = snapshot.duplicatesEnrich;
        entity.late = snapshot.late;
        entity.dlqOther = snapshot.dlqOther;
        return entity;
    }

    /** 网关快照载荷：窗口起点（ISO 本地日期时间）+ 自窗口起点以来的累计计数。 */
    public static class Snapshot {
        public String gameId;
        public String environment;
        public String windowStart;
        public int windowSec = 300;
        public long received;
        public long accepted;
        public long sampledOut;
        public long rejectedSchema;
        public long rejectedUnknownEvent;
        public long rejectedInvalidTimestamp;
        public long rejectedPiiBlocked;
        public long rejectedPayloadTooLarge;
        public long rejectedTrustEscalation;
        public long rejectedBlocked;
        public long rejectedScopeMismatch;
        public long rejectedKafkaError;
        public long duplicatesGateway;
        public long duplicatesEnrich;
        public long late;
        public long dlqOther;
    }
}
