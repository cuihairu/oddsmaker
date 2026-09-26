"""标签构建测试：前向不活跃、升级处置、成熟阈值。"""

from datetime import date

from oddsmaker_ml.label import (
    churn_labels_from_events,
    escalated_subjects,
    forward_active_users,
    maturity_cutoff,
    risk_labels_from_actions,
)


def test_forward_active_users_window_boundaries():
    events = [
        {"user_id": "a", "event_date": "2026-03-01"},  # 快照日当不算（窗口开区间）
        {"user_id": "b", "event_date": "2026-03-02"},  # 窗口首日
        {"user_id": "c", "event_date": "2026-03-15"},  # 窗口末日
        {"user_id": "d", "event_date": "2026-03-16"},  # 窗口后不算
        {"user_id": "", "event_date": "2026-03-05"},   # 空用户忽略
        {"user_id": "e", "event_date": "垃圾"},         # 非法日期忽略
    ]
    active = forward_active_users(events, date(2026, 3, 1), date(2026, 3, 15))
    assert active == {"b", "c"}


def test_churn_labels_from_events():
    events = [{"user_id": "u2", "event_date": "2026-03-10"}]
    labels = churn_labels_from_events(
        ["u1", "u2", "u3"], events, date(2026, 3, 1), date(2026, 3, 15))
    assert labels == [1, 0, 1]   # u2 窗口内活跃 → 0；其余 14 天无事件 → 1


def test_escalated_actions_whitelist():
    actions = [
        {"subject_id": "s1", "action": "block"},
        {"subject_id": "s2", "action": "review"},
        {"subject_id": "s3", "action": "alert"},     # 通知类不算
        {"subject_id": "s4", "action": "webhook"},   # 通知类不算
        {"subject_id": "s5", "action": "BLOCK"},     # 大小写不敏感
        {"subject_id": "", "action": "block"},       # 空主体忽略
    ]
    assert escalated_subjects(actions) == {"s1", "s2", "s5"}


def test_risk_labels_from_actions():
    actions = [{"subject_id": "s1", "action": "block"}]
    assert risk_labels_from_actions(["s1", "s2"], actions) == [1, 0]


def test_maturity_cutoff_matches_java():
    # 2026-03-01 往前 30 天 = 2026-01-30；对齐 LtvForecastAssembler.matureCutoff
    assert maturity_cutoff(date(2026, 3, 1)) == date(2026, 1, 30)
