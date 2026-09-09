package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.SymbolMappingEntity;
import io.oddsmaker.control.jpa.SymbolMappingRepo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 崩溃堆栈符号化：按 (game, platform, version) 匹配 ACTIVE 符号映射，
 * 对混淆堆栈依序执行 mapping_rules 中的正则替换（dSYM/Proguard/source map 的规则化表达）。
 * 实际映射文件的完整解析由专门符号化服务负责，本服务提供控制面可用的轻量规则引擎。
 */
@Service
public class SymbolicationService {

    private final SymbolMappingRepo symbolMappingRepo;
    private final ObjectMapper objectMapper;

    public SymbolicationService(SymbolMappingRepo symbolMappingRepo, ObjectMapper objectMapper) {
        this.symbolMappingRepo = symbolMappingRepo;
        this.objectMapper = objectMapper;
    }

    /** 符号化结果 */
    public record SymbolicationResult(String mappingId, String platform, String appVersion,
                                      String symbolized, int rulesApplied) {}

    /** 自动匹配映射并符号化；无可用映射时原样返回（rulesApplied=0） */
    public SymbolicationResult symbolicate(String gameId, String platform, String appVersion,
                                           String stackTrace) {
        if (stackTrace == null || stackTrace.isBlank()) {
            throw new IllegalArgumentException("stackTrace is required");
        }
        SymbolMappingEntity mapping = symbolMappingRepo.findActive(gameId, platform, appVersion).stream()
            .findFirst()
            .orElse(null);
        if (mapping == null || mapping.mappingRules == null || mapping.mappingRules.isBlank()) {
            return new SymbolicationResult(null, platform, appVersion, stackTrace, 0);
        }
        List<Rule> rules = parseRules(mapping.mappingRules);
        String symbolized = stackTrace;
        int applied = 0;
        for (Rule rule : rules) {
            String replaced = rule.pattern().matcher(symbolized).replaceAll(rule.replacement());
            if (!replaced.equals(symbolized)) {
                applied++;
            }
            symbolized = replaced;
        }
        return new SymbolicationResult(mapping.id, platform, appVersion, symbolized, applied);
    }

    /** 符号化规则：正则 + 替换串 */
    public record Rule(Pattern pattern, String replacement) {}

    /** 解析规则 JSON：[{pattern, replacement}]，非法规则跳过 */
    List<Rule> parseRules(String rulesJson) {
        List<Rule> rules = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(rulesJson);
            if (!root.isArray()) {
                return rules;
            }
            for (JsonNode node : root) {
                String pattern = node.path("pattern").asText(null);
                String replacement = node.path("replacement").asText("");
                if (pattern == null || pattern.isBlank()) {
                    continue;
                }
                try {
                    rules.add(new Rule(Pattern.compile(pattern), replacement));
                } catch (IllegalArgumentException ignore) {
                    // 非法正则跳过，不阻断整体符号化
                }
            }
        } catch (Exception ignore) {
            // JSON 解析失败按无规则处理
        }
        return rules;
    }

    /** 堆栈概要：首行异常信息 + 前几帧（列表展示用） */
    public static Map<String, Object> stackSummary(String stackTrace, int frames) {
        Map<String, Object> out = new LinkedHashMap<>();
        String[] lines = stackTrace == null ? new String[0] : stackTrace.split("\\r?\\n");
        List<String> top = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("...")) {
                continue;
            }
            top.add(line.trim());
            if (top.size() >= frames) {
                break;
            }
        }
        out.put("exception", top.isEmpty() ? "" : top.get(0));
        out.put("frames", top);
        return out;
    }
}
