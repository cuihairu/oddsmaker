package io.oddsmaker.control.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.RiskSampleSetEntity;
import io.oddsmaker.control.jpa.RiskSampleSetRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 策略实验室样本集（V0.4）：命名留档一批样本事件，改规则后重放对比。
 * 校验口径与试算回放 dry-run 完全同款（RiskLabReplayService.validateSamples），
 * 保证留档样本之后任何一次重放都能算；留档不可改，删除重建。
 */
@Service
@Transactional
public class RiskSampleSetService {

    private final RiskSampleSetRepo repo;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLog;

    public RiskSampleSetService(RiskSampleSetRepo repo, ObjectMapper objectMapper, AuditLogService auditLog) {
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.auditLog = auditLog;
    }

    /** 样本集列表（不含样本内容，载入走详情） */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String gameId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RiskSampleSetEntity e : repo.findByGameIdOrderByCreatedAtDesc(gameId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id);
            m.put("name", e.name);
            m.put("description", e.description);
            m.put("sampleCount", e.sampleCount);
            m.put("createdBy", e.createdBy);
            m.put("createdAt", e.createdAt);
            out.add(m);
        }
        return out;
    }

    /** 新建样本集：name 唯一（同游戏），samples 走 dry-run 同款校验后原样序列化留档 */
    public Map<String, Object> create(String gameId, String name, String description,
                                      List<Map<String, Object>> samples, String operator) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("样本集名称不能为空");
        }
        String trimmedName = name.trim();
        if (trimmedName.length() > 100) {
            throw new IllegalArgumentException("样本集名称过长（≤100 字符）");
        }
        RiskLabReplayService.validateSamples(samples);
        if (description != null && description.length() > 500) {
            throw new IllegalArgumentException("描述过长（≤500 字符）");
        }
        if (repo.existsByGameIdAndName(gameId, trimmedName)) {
            throw new IllegalArgumentException("同名样本集已存在：" + trimmedName);
        }
        String samplesJson;
        try {
            samplesJson = objectMapper.writeValueAsString(samples);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("样本序列化失败", e);
        }

        RiskSampleSetEntity entity = new RiskSampleSetEntity();
        entity.id = "rss_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        entity.gameId = gameId;
        entity.name = trimmedName;
        entity.description = description == null || description.isBlank() ? null : description.trim();
        entity.samples = samplesJson;
        entity.sampleCount = samples.size();
        entity.createdBy = operator;
        // 预置时间戳：@CreationTimestamp 落库正确但不回填实体内存值，响应需要非空 createdAt（仓内惯例同 TrackingPlanService）
        LocalDateTime now = LocalDateTime.now();
        entity.createdAt = now;
        entity.updatedAt = now;
        repo.save(entity);

        auditLog.log(
            AuditLogEntity.AuditAction.CREATE,
            "risk_sample_set",
            entity.id,
            trimmedName,
            "样本集留档 " + samples.size() + " 条样本",
            AuditLogEntity.AuditResult.SUCCESS,
            operator,
            null,
            null,
            null,
            null,
            Map.of("gameId", gameId, "sampleCount", samples.size())
        );
        return detailRow(entity);
    }

    /** 详情（含解析后的样本列表，供载入回放） */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String gameId, String id) {
        RiskSampleSetEntity e = repo.findById(id).orElse(null);
        if (e == null || !e.gameId.equals(gameId)) {
            throw new IllegalArgumentException("样本集不存在");
        }
        return detailRow(e);
    }

    /** 删除留档（硬删；试算回放本身不依赖样本集，删了随时可重建） */
    public boolean delete(String gameId, String id, String operator) {
        RiskSampleSetEntity e = repo.findById(id).orElse(null);
        if (e == null || !e.gameId.equals(gameId)) {
            return false;
        }
        repo.delete(e);
        auditLog.log(
            AuditLogEntity.AuditAction.DELETE,
            "risk_sample_set",
            e.id,
            e.name,
            "删除样本集（" + e.sampleCount + " 条样本）",
            AuditLogEntity.AuditResult.SUCCESS,
            operator,
            null,
            null,
            null,
            null,
            Map.of("gameId", gameId)
        );
        return true;
    }

    private Map<String, Object> detailRow(RiskSampleSetEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id);
        m.put("gameId", e.gameId);
        m.put("name", e.name);
        m.put("description", e.description);
        m.put("sampleCount", e.sampleCount);
        m.put("createdBy", e.createdBy);
        m.put("createdAt", e.createdAt);
        try {
            m.put("samples", objectMapper.readValue(e.samples,
                    new TypeReference<List<Map<String, Object>>>() {}));
        } catch (JsonProcessingException ex) {
            // 留档前已过校验，理论上不可达；兜底给空列表而非 500
            m.put("samples", List.of());
        }
        return m;
    }
}
