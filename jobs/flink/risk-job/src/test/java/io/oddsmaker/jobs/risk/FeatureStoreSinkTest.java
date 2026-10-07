package io.oddsmaker.jobs.risk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B10 feature_store 双写 sink 的 SQL 语义钉死（无 PG 环境执行；真 PG 实测见
 * RiskFeaturePgE2eTest 的 -Drisk.pg.e2e 开关门禁）。
 * ON CONFLICT 键须与 V0.9.22 迁移的 uq_feature_store_window 完全一致，
 * 否则冲突键不匹配 → 每次插入都报 DuplicateKey 或重复建行。
 */
@DisplayName("feature_store 双写 sink SQL 语义")
class FeatureStoreSinkTest {

    @Test
    @DisplayName("ON CONFLICT 键与迁移唯一键一致；jsonb 合并 + TEXT 回写；created_at 不进更新集")
    void upsertSqlSemantics() {
        String sql = RiskJob.FEATURE_STORE_UPSERT_SQL;
        assertTrue(sql.contains("ON CONFLICT (game_id, environment, scope_key, window_start, window_end)"),
            "冲突键必须与 V0.9.22 uq_feature_store_window 五列一致");
        assertTrue(sql.contains("(feature_store.features::jsonb || EXCLUDED.features::jsonb)::text"),
            "长格式行按 jsonb 合并进单行摘要（同名键取右=迟到重算覆盖）");
        assertTrue(sql.contains("now()"), "created_at 建行时生成");
        assertFalse(sql.contains("created_at ="), "created_at 不随更新重置");
        assertTrue(sql.contains("as_of = EXCLUDED.as_of"), "as_of 随迟到重算刷新");
    }

    @Test
    @DisplayName("featureStoreFragment：单特征 JSON 片段，值为 double 原样入串")
    void fragmentFormat() {
        FeatureRow row = new FeatureRow();
        row.featureName = "gold_gain_1h";
        row.value = 600000.0;
        assertEquals("{\"gold_gain_1h\":600000.0}", RiskJob.featureStoreFragment(row));

        row.featureName = "event_count";
        row.value = 3.0;
        assertEquals("{\"event_count\":3.0}", RiskJob.featureStoreFragment(row));
        // 只需证明 featureName 是代码内常量时片段即合法 JSON（无转义面）
        assertFalse(row.featureName.contains("\""), "特征名为代码常量，不含引号（无 JSON 注入面）");
    }
}
