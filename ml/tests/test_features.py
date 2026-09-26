"""特征构建测试：口径对齐 v_user_features_30d / risk_events / v_ltv_by_cohort_day。"""

import oddsmaker_ml.features as f


def test_churn_frame_column_order_and_coercion():
    rows = [
        {"days_inactive_30d": "3", "session_count_30d": 12, "event_count_30d": None,
         "revenue_total_30d": "99.5"},
        {"days_inactive_30d": "", "session_count_30d": "garbage", "event_count_30d": 7,
         "revenue_total_30d": 0},
    ]
    frame = f.churn_feature_frame(rows)
    assert list(frame.columns) == f.CHURN_FEATURES
    assert frame.shape == (2, 4)
    assert frame.iloc[0].tolist() == [3.0, 12.0, 0.0, 99.5]   # None → 0
    assert frame.iloc[1].tolist() == [0.0, 0.0, 7.0, 0.0]     # 空串/非法 → 0


def test_risk_frame_column_order():
    frame = f.risk_feature_frame([{"critical_30d": 1, "high_30d": 2}])
    assert list(frame.columns) == f.RISK_FEATURES
    assert frame.iloc[0]["critical_30d"] == 1.0
    assert frame.iloc[0]["distinct_rules_30d"] == 0.0  # 缺失 → 0


def test_aggregate_risk_events_counts_and_rules():
    events = [
        {"subject_id": "u1", "severity": "critical", "rule_id": "r1"},
        {"subject_id": "u1", "severity": "critical", "rule_id": "r2"},
        {"subject_id": "u1", "severity": "high", "rule_id": "r1"},
        {"subject_id": "u1", "severity": "unknown", "rule_id": "r9"},   # 未知严重度不计数
        {"subject_id": "u2", "severity": "low", "rule_id": "r3"},
        {"subject_id": "", "severity": "low", "rule_id": "r3"},         # 空主体跳过
    ]
    rows = f.aggregate_risk_events(events)
    by = {r["subject_id"]: r for r in rows}
    assert by["u1"]["critical_30d"] == 2
    assert by["u1"]["high_30d"] == 1
    assert by["u1"]["medium_30d"] == 0
    assert by["u1"]["distinct_rules_30d"] == 3   # r1/r2/r9
    assert by["u2"]["low_30d"] == 1
    assert len(rows) == 2


def test_cohort_cumulative_mirrors_java_semantics():
    ltv_rows = [
        {"cohort_date": "2026-01-01", "age_day": 0, "revenue": 10},
        {"cohort_date": "2026-01-01", "age_day": 2, "revenue": 5},
        {"cohort_date": "2026-01-01", "age_day": 6, "revenue": 3},
        {"cohort_date": "2026-01-01", "age_day": -1, "revenue": 100},  # 丢弃
        {"cohort_date": "2026-01-02", "age_day": 0, "revenue": 7},
    ]
    cohort_rows = [
        {"cohort_date": "2026-01-01", "cohort_size": 10},
        {"cohort_date": "2026-01-02", "cohort_size": 4},
    ]
    cum, sizes = f.cohort_cumulative(ltv_rows, cohort_rows)
    assert sizes == {"2026-01-01": 10, "2026-01-02": 4}
    assert f.cum_through(cum["2026-01-01"], 1) == 10.0   # 缺 day1 → 取 ≤cap 的最大观测日
    assert f.cum_through(cum["2026-01-01"], 6) == 18.0   # 10+5+3
    assert f.cum_through(cum["2026-01-01"], 29) == 18.0
    assert f.cum_through(cum["2026-01-02"], 6) == 7.0
    assert f.cum_through({}, 6) == 0.0                    # 无观测 → 0（对齐 cumThrough）


def test_num_helper_edge_cases():
    assert f._num(True) == 1.0
    assert f._num(False) == 0.0
    assert f._num("  ") == 0.0
    assert f._num(object()) == 0.0
    assert f._num("2.5") == 2.5
