package io.oddsmaker.gateway.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class JsonSchemaValidator {
    private final ObjectMapper om;
    private final JsonNode schema;

    public JsonSchemaValidator(ObjectMapper om) {
        this.om = om;
        try {
            ClassPathResource res = new ClassPathResource("schemas/oddsmaker-event-schema.json");
            try (InputStream is = res.getInputStream()) {
                this.schema = om.readTree(is);
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to load JSON schema", e);
        }
    }

    public String validate(Object eventObj) {
        try {
            JsonNode node = om.valueToTree(eventObj);
            JsonNode required = schema.path("required");
            for (JsonNode field : required) {
                String key = field.asText();
                if (!node.hasNonNull(key)) {
                    return "missing_" + key;
                }
            }
            JsonNode props = schema.path("properties");
            Iterator<Map.Entry<String, JsonNode>> it = props.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String key = entry.getKey();
                JsonNode value = node.get(key);
                if (value == null || value.isNull()) {
                    continue;
                }
                String err = validateField(key, value, entry.getValue());
                if (err != null) {
                    return err;
                }
            }
            return null;
        } catch (Exception e) {
            return "invalid_json";
        }
    }

    private String validateField(String key, JsonNode value, JsonNode rule) {
        if (value.isTextual()) {
            int len = value.asText().length();
            if (rule.has("minLength") && len < rule.get("minLength").asInt()) {
                return key + "_too_short";
            }
            if (rule.has("maxLength") && len > rule.get("maxLength").asInt()) {
                return key + "_too_long";
            }
            if (rule.has("enum")) {
                boolean matched = false;
                for (JsonNode n : rule.get("enum")) {
                    if (value.asText().equals(n.asText())) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    return key + "_invalid_enum";
                }
            }
        }
        if (rule.has("type")) {
            JsonNode type = rule.get("type");
            if (type.isTextual()) {
                String err = validateType(key, value, type.asText(), rule);
                if (err != null) {
                    return err;
                }
            } else if (type.isArray()) {
                boolean matched = false;
                for (JsonNode option : type) {
                    if (option.isTextual() && validateType(key, value, option.asText(), rule) == null) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    return key + "_invalid_type";
                }
            }
        }
        if (rule.has("oneOf")) {
            boolean matched = false;
            for (JsonNode option : rule.get("oneOf")) {
                if (!option.has("type")) {
                    continue;
                }
                JsonNode type = option.get("type");
                if (type.isTextual() && validateType(key, value, type.asText(), option) == null) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return key + "_invalid_type";
            }
        }
        return null;
    }

    private String validateType(String key, JsonNode value, String type, JsonNode rule) {
        return switch (type) {
            case "string" -> value.isTextual() ? null : key + "_invalid_type";
            case "number" -> value.isNumber() ? null : key + "_invalid_type";
            case "integer" -> value.isIntegralNumber() ? null : key + "_invalid_type";
            case "object" -> value.isObject() ? null : key + "_invalid_type";
            case "array" -> validateArray(key, value, rule);
            case "null" -> value.isNull() ? null : key + "_invalid_type";
            default -> null;
        };
    }

    private String validateArray(String key, JsonNode value, JsonNode rule) {
        if (!value.isArray()) {
            return key + "_invalid_type";
        }
        if (rule.has("maxItems") && value.size() > rule.get("maxItems").asInt()) {
            return key + "_too_many_items";
        }
        return null;
    }

    public Map<String, Object> schemaSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", schema.path("$id").asText());
        summary.put("title", schema.path("title").asText());
        return summary;
    }
}
