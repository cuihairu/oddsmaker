package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主体累计风险分读取（B6 边界闭合）：risk-job 每次评估落 CH risk_scores
 * （ReplacingMergeTree(updated_at) 主体快照），此前只落库无控制面读取端点。
 * 读最新一行即主体当前累计分（ORDER BY updated_at DESC LIMIT 1，合并前后都正确）。
 */
@Service
@Transactional(readOnly = true)
public class RiskScoreService {

    private final ClickHouseClient clickHouseClient;
    private final ObjectMapper objectMapper;

    public RiskScoreService(ClickHouseClient clickHouseClient, ObjectMapper objectMapper) {
        this.clickHouseClient = clickHouseClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 主体最新累计分快照：found=true 携 score/reasons（解析为 {ruleId,contribution} 数组）/updatedAt；
     * 未落过分返回 found=false；CH 未配置抛 BusinessException（CH_UNAVAILABLE 前缀，与导出同款）。
     */
    public Map<String, Object> latest(String gameId, String subjectType, String subjectId) {
        if (subjectType == null || subjectType.isBlank() || subjectId == null || subjectId.isBlank()) {
            throw new IllegalArgumentException("subjectType 与 subjectId 不能为空");
        }
        if (!clickHouseClient.isAvailable()) {
            throw new BusinessException("CH_UNAVAILABLE: ClickHouse 未配置，无法读取主体风险分");
        }
        List<Map<String, Object>> rows = clickHouseClient.query(
                "SELECT subject_type, subject_id, score, reasons, updated_at FROM risk_scores "
                        + "WHERE game_id = ? AND subject_type = ? AND subject_id = ? "
                        + "ORDER BY updated_at DESC LIMIT 1",
                gameId, subjectType.trim(), subjectId.trim());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gameId", gameId);
        out.put("subjectType", subjectType.trim());
        out.put("subjectId", subjectId.trim());
        if (rows == null || rows.isEmpty()) {
            out.put("found", false);
            return out;
        }
        Map<String, Object> row = rows.get(0);
        out.put("found", true);
        out.put("score", row.get("score"));
        out.put("reasons", parseReasons(row.get("reasons")));
        out.put("updatedAt", String.valueOf(row.get("updated_at")));
        return out;
    }

    /** reasons 列为 JSON 字符串数组（{"rule_id":...,"contribution":...}），逐条解析；坏条目跳过不整批失败 */
    private List<Map<String, Object>> parseReasons(Object raw) {
        List<String> items = new ArrayList<>();
        if (raw instanceof java.sql.Array arr) {
            try {
                Object[] vals = (Object[]) arr.getArray();
                for (Object v : vals) items.add(String.valueOf(v));
            } catch (Exception ignored) {
                // 读数组失败按空明细处理
            }
        } else if (raw instanceof List<?> list) {
            for (Object v : list) items.add(String.valueOf(v));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String item : items) {
            try {
                JsonNode n = objectMapper.readTree(item);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ruleId", n.path("rule_id").asText(null));
                m.put("contribution", n.path("contribution").asInt());
                out.add(m);
            } catch (Exception ignored) {
                // 非法 JSON 条目跳过（落库侧排序已确定，个别坏条目不整批失败）
            }
        }
        return out;
    }
}
