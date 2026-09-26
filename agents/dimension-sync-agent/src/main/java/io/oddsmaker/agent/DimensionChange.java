package io.oddsmaker.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 维度变更中间格式（dimension-sync.md 的 RawDimensionChange）。
 * 所有 source 把源头数据翻译成这个格式，下游（Gateway → dimension-sync-job → ClickHouse）只认这一种。
 */
public final class DimensionChange {

    /** item / level（其余值原样透传，由下游归一化） */
    public String dimType;
    /** upsert / delete */
    public String op = "upsert";
    public String resourceId;
    /** 源头变更时间（epoch millis）；缺失时由 source 取当前时间 */
    public long versionTs;
    /** 维度属性（name/rarity/type/...），进事件的 props.attributes */
    public Map<String, String> attributes = new LinkedHashMap<>();

    public DimensionChange() {
    }

    public DimensionChange(String dimType, String op, String resourceId, long versionTs) {
        this.dimType = dimType;
        this.op = op;
        this.resourceId = resourceId;
        this.versionTs = versionTs;
    }

    public DimensionChange attr(String key, String value) {
        if (key != null && !key.isBlank() && value != null) {
            attributes.put(key, value);
        }
        return this;
    }
}
