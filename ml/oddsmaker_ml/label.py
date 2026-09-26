"""标签构建：训练目标与 §6 设计一致——

- churn：目标 = 快照日后 14 天无事件（forward inactivity）
- risk：目标 = 主体被升级处置（risk_actions 中的 block / review）
- pltv：成熟 cohort（注册满 30 天）的 D30 ARPU
"""

from __future__ import annotations

from datetime import date, timedelta
from typing import Iterable, Mapping, Set

# risk_actions 里算"升级处置"的动作（alert/webhook 等通知类不算）
ESCALATED_ACTIONS = {"block", "review"}

CHURN_HORIZON_DAYS = 14
PLTV_MATURE_DAYS = 30


def forward_active_users(
    events: Iterable[Mapping], after_date: date, until_date: date,
) -> Set[str]:
    """(after_date, until_date] 窗口内活跃过的用户集合。

    churn 标签 = 特征快照的用户**不**在该集合中（未来 14 天无事件）。
    """
    active: Set[str] = set()
    for ev in events:
        user = str(ev.get("user_id") or "")
        if not user:
            continue
        d = _as_date(ev.get("event_date"))
        if d is not None and after_date < d <= until_date:
            active.add(user)
    return active


def churn_labels_from_events(
    feature_user_ids: list[str],
    forward_events: Iterable[Mapping],
    after_date: date,
    until_date: date,
) -> list[int]:
    """给定特征用户序 + 快照后窗口事件行 → 0/1 标签（1 = 窗口内无事件）。"""
    active = forward_active_users(forward_events, after_date, until_date)
    return [0 if uid in active else 1 for uid in feature_user_ids]


def escalated_subjects(actions: Iterable[Mapping]) -> Set[str]:
    """risk_actions 行（subject_id, action）→ 被升级处置过的主体集合。"""
    escalated: Set[str] = set()
    for row in actions:
        action = str(row.get("action") or "").lower()
        subject = str(row.get("subject_id") or "")
        if subject and action in ESCALATED_ACTIONS:
            escalated.add(subject)
    return escalated


def risk_labels_from_actions(
    feature_subject_ids: list[str],
    actions: Iterable[Mapping],
) -> list[int]:
    """给定特征主体序 + 处置行 → 0/1 标签（1 = 有升级处置）。"""
    escalated = escalated_subjects(actions)
    return [1 if sid in escalated else 0 for sid in feature_subject_ids]


def maturity_cutoff(today: date, mature_days: int = PLTV_MATURE_DAYS) -> date:
    """注册不晚于该日期的 cohort 才有完整 D30 观测（对齐 Java matureCutoff）。"""
    return today - timedelta(days=mature_days)


def _as_date(value) -> date | None:
    if isinstance(value, date):
        return value
    try:
        return date.fromisoformat(str(value)[:10])
    except (TypeError, ValueError):
        return None
