"""特征构建：原始行（dict，JSONEachRow / 合成）→ 固定列序的 DataFrame。

特征口径与 ClickHouse `v_user_features_30d` / risk_events 聚合 / v_ltv_by_cohort_day
对齐；缺失/非数值一律按 0 处理（线上脏数据不阻塞训练，由数据质量指标暴露）。
"""

from __future__ import annotations

from typing import Any, Iterable, Mapping

import pandas as pd

CHURN_FEATURES = [
    "days_inactive_30d",
    "session_count_30d",
    "event_count_30d",
    "revenue_total_30d",
]

RISK_FEATURES = [
    "critical_30d",
    "high_30d",
    "medium_30d",
    "low_30d",
    "distinct_rules_30d",
]

_RISK_SEVERITY_TO_FEATURE = {
    "critical": "critical_30d",
    "high": "high_30d",
    "medium": "medium_30d",
    "low": "low_30d",
}


def _num(value: Any) -> float:
    """数值强制转换；None/空串/非法值 → 0（不抛异常，训练不因脏行中断）。"""
    if value is None:
        return 0.0
    if isinstance(value, bool):
        return float(value)
    if isinstance(value, (int, float)):
        return float(value)
    try:
        s = str(value).strip()
        return float(s) if s else 0.0
    except ValueError:
        return 0.0


def _to_frame(rows: Iterable[Mapping[str, Any]], columns: list[str]) -> pd.DataFrame:
    data = [{c: _num(row.get(c)) for c in columns} for row in rows]
    return pd.DataFrame(data, columns=columns)


def churn_feature_frame(rows: Iterable[Mapping[str, Any]]) -> pd.DataFrame:
    """用户 30 天特征（口径 = v_user_features_30d）。"""
    return _to_frame(rows, CHURN_FEATURES)


def risk_feature_frame(rows: Iterable[Mapping[str, Any]]) -> pd.DataFrame:
    """主体风险特征：30 天各严重度命中数 + 规则多样性。"""
    return _to_frame(rows, RISK_FEATURES)


def aggregate_risk_events(events: Iterable[Mapping[str, Any]]) -> list[dict[str, Any]]:
    """risk_events 行（severity, rule_id, subject_id）→ 主体级特征行。

    严重度未知的不计数但保留主体；规则多样性 = distinct rule_id 数。
    """
    by_subject: dict[str, dict[str, Any]] = {}
    for ev in events:
        subject = str(ev.get("subject_id") or "")
        if not subject:
            continue
        agg = by_subject.setdefault(
            subject, {"critical_30d": 0, "high_30d": 0, "medium_30d": 0,
                      "low_30d": 0, "distinct_rules_30d": 0, "_rules": set()}
        )
        feature = _RISK_SEVERITY_TO_FEATURE.get(str(ev.get("severity") or "").lower())
        if feature:
            agg[feature] += 1
        rule = str(ev.get("rule_id") or "")
        if rule:
            agg["_rules"].add(rule)
    out = []
    for subject, agg in by_subject.items():
        rules = agg.pop("_rules")
        agg["distinct_rules_30d"] = len(rules)
        agg["subject_id"] = subject
        out.append(agg)
    return out


def cohort_cumulative(
    ltv_rows: Iterable[Mapping[str, Any]],
    cohort_rows: Iterable[Mapping[str, Any]],
) -> tuple[dict[str, dict[int, float]], dict[str, int]]:
    """v_ltv_by_cohort_day + v_user_first_seen 行 → (cohort → {age_day → 累计收入}, cohort → 人数)。

    语义对齐 Java LtvForecastAssembler.cumulate：age_day < 0 的行丢弃，逐日累加。
    """
    daily: dict[str, dict[int, float]] = {}
    for row in ltv_rows:
        cohort = str(row.get("cohort_date") or row.get("cohort") or "")
        age_day = int(_num(row.get("age_day")))
        revenue = _num(row.get("revenue"))
        if not cohort or age_day < 0:
            continue
        daily.setdefault(cohort, {})
        daily[cohort][age_day] = daily[cohort].get(age_day, 0.0) + revenue

    cum: dict[str, dict[int, float]] = {}
    for cohort, by_day in daily.items():
        running: dict[int, float] = {}
        total = 0.0
        for age_day in sorted(by_day):
            total += by_day[age_day]
            running[age_day] = total
        cum[cohort] = running

    sizes: dict[str, int] = {}
    for row in cohort_rows:
        cohort = str(row.get("cohort_date") or row.get("cohort") or "")
        if cohort:
            sizes[cohort] = int(_num(row.get("cohort_size")))
    return cum, sizes


def cum_through(cum_by_day: Mapping[int, float], cap_day: int) -> float:
    """截至第 cap_day 天（含）的累计收入；无观测返回 0（对齐 Java cumThrough）。"""
    observed = [d for d in cum_by_day if d <= cap_day]
    return cum_by_day[max(observed)] if observed else 0.0
