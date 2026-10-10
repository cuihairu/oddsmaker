package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.DimensionPullConfigEntity;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 维度同步 Pull 配置 YAML 形态（dimension-sync.md「配置驱动」目标形态的 Control 落地面）。
 *
 * <pre>
 * # oddsmaker 维度同步 Pull 配置导出（凭证不回显；导入时 credential 缺省=保留既有）
 * dimensions:
 *   items:
 *     environment: prod
 *     dimType: item
 *     endpoint: https://api.game-x.com/v1/items
 *     credential: bearer-token        # 仅首建必填；导出永不包含
 *     intervalSeconds: 300
 *     pageLimit: 1000
 *     enabled: true
 * </pre>
 *
 * 键口径：dimensions 映射键即 sourceKey，配置定位键 = (game, environment, sourceKey)——与
 * dimension_pull_config 唯一约束同口径，改 environment 即另一环境的档。加载用 SafeConstructor
 * （只认标量/集合，不实例化任意对象），未知字段精确报错（防 endpont 类手误静默失效）。
 */
public final class DimensionPullYaml {

    /** 导出注释头：说明凭证不落盘的 import 语义。 */
    static final String EXPORT_HEADER =
            "# oddsmaker 维度同步 Pull 配置导出（凭证不回显；导入时 credential 缺省=保留既有）\n";

    /** 条目允许的字段（白名单；顺序即报错提示顺序） */
    private static final List<String> ALLOWED_KEYS = List.of(
            "environment", "dimType", "endpoint", "credential", "intervalSeconds", "pageLimit", "enabled");
    private static final Set<String> ALLOWED_KEY_SET = new HashSet<>(ALLOWED_KEYS);

    private DimensionPullYaml() {
    }

    /** 配置导出为 YAML：凭证永不落盘（密文也不导出），其余字段 1:1。 */
    public static String toYaml(Collection<DimensionPullConfigEntity> configs) {
        Map<String, Object> entries = new LinkedHashMap<>();
        for (DimensionPullConfigEntity e : configs) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("environment", e.environment);
            fields.put("dimType", e.dimType);
            fields.put("endpoint", e.endpoint);
            fields.put("intervalSeconds", e.intervalSeconds);
            fields.put("pageLimit", e.pageLimit);
            fields.put("enabled", e.enabled);
            entries.put(e.sourceKey, fields);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("dimensions", entries);
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return EXPORT_HEADER + new Yaml(options).dump(root);
    }

    /**
     * YAML 解析为 upsert 请求列表（映射键即 sourceKey）。结构错误（根键缺失/未知字段/缺必填/
     * 类型不符）一律 IllegalArgumentException 精确到 sourceKey；字段级业务校验（endpoint 形态、
     * 区间、环境存在性）交由 create/update 原有校验链。
     */
    public static List<DimensionPullService.PullConfigUpsert> parse(String yaml) {
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        } catch (Exception e) {
            throw new IllegalArgumentException("YAML 解析失败: " + e.getMessage());
        }
        if (!(loaded instanceof Map<?, ?> root) || !(root.get("dimensions") instanceof Map<?, ?> dims)) {
            throw new IllegalArgumentException("YAML 需含 dimensions 根键（映射键为 sourceKey）");
        }
        List<DimensionPullService.PullConfigUpsert> result = new ArrayList<>();
        for (Map.Entry<?, ?> entry : dims.entrySet()) {
            result.add(parseEntry(entry));
        }
        return result;
    }

    private static DimensionPullService.PullConfigUpsert parseEntry(Map.Entry<?, ?> entry) {
        String sourceKey = String.valueOf(entry.getKey()).trim();
        if (sourceKey.isEmpty()) {
            throw new IllegalArgumentException("dimensions 存在空 sourceKey 键");
        }
        if (!(entry.getValue() instanceof Map<?, ?> fields)) {
            throw new IllegalArgumentException("「" + sourceKey + "」配置需是字段映射");
        }
        for (Object key : fields.keySet()) {
            if (!ALLOWED_KEY_SET.contains(String.valueOf(key))) {
                throw new IllegalArgumentException("「" + sourceKey + "」存在未知字段 " + key
                        + "（允许: " + String.join("/", ALLOWED_KEYS) + "）");
            }
        }
        DimensionPullService.PullConfigUpsert upsert = new DimensionPullService.PullConfigUpsert();
        upsert.sourceKey = sourceKey;
        upsert.environment = requireText(sourceKey, fields, "environment");
        upsert.endpoint = requireText(sourceKey, fields, "endpoint");
        upsert.dimType = optionalText(sourceKey, fields, "dimType");
        upsert.credential = optionalText(sourceKey, fields, "credential");
        upsert.intervalSeconds = optionalInt(sourceKey, fields, "intervalSeconds");
        upsert.pageLimit = optionalInt(sourceKey, fields, "pageLimit");
        upsert.enabled = optionalBool(sourceKey, fields, "enabled");
        return upsert;
    }

    private static String requireText(String sourceKey, Map<?, ?> fields, String key) {
        String value = optionalText(sourceKey, fields, key);
        if (value == null) {
            throw new IllegalArgumentException("「" + sourceKey + "」缺少 " + key);
        }
        return value;
    }

    private static String optionalText(String sourceKey, Map<?, ?> fields, String key) {
        Object value = fields.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("「" + sourceKey + "」" + key + " 需是非空文本");
        }
        return text.trim();
    }

    private static Integer optionalInt(String sourceKey, Map<?, ?> fields, String key) {
        Object value = fields.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("「" + sourceKey + "」" + key + " 需是整数");
        }
        return number.intValue();
    }

    private static Boolean optionalBool(String sourceKey, Map<?, ?> fields, String key) {
        Object value = fields.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Boolean bool)) {
            throw new IllegalArgumentException("「" + sourceKey + "」" + key + " 需是 true/false");
        }
        return bool;
    }
}
