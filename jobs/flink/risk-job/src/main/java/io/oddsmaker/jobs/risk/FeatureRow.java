package io.oddsmaker.jobs.risk;

/**
 * 特征行（B5 Feature 层，落 PostgreSQL risk_features 表）。
 * 时间字段用 long 毫秒（不持 Timestamp），让 Flink PojoSerializer 直接序列化；
 * scope_key 编码主体：PLAYER:&lt;user_id&gt; / DEVICE:&lt;device_id&gt; / IP:&lt;client_ip&gt;。
 */
public class FeatureRow {
    public String gameId;
    public String environment;
    public String scopeKey;
    public String featureName;
    public long windowStartMs;
    public long windowEndMs;
    public double value;
    /** 产出（upsert）时刻，取算子处理时间 */
    public long asOfMs;

    public FeatureRow() {}

    public FeatureRow(String gameId, String environment, String scopeKey, String featureName,
                      long windowStartMs, long windowEndMs, double value, long asOfMs) {
        this.gameId = gameId;
        this.environment = environment;
        this.scopeKey = scopeKey;
        this.featureName = featureName;
        this.windowStartMs = windowStartMs;
        this.windowEndMs = windowEndMs;
        this.value = value;
        this.asOfMs = asOfMs;
    }

    /** 广播状态键：scope|feature（规则评估按条件定位） */
    public static String stateKey(String scopeKey, String featureName) {
        return scopeKey + "|" + featureName;
    }

    public String stateKey() {
        return stateKey(scopeKey, featureName);
    }
}
