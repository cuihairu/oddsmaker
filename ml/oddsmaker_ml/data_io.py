"""数据读取：ClickHouse HTTP（JSONEachRow，仅标准库 urllib）+ 合成数据入口。

SQL 与线上表/视图对齐：`events`、`v_user_features_30d` 口径、`risk_events`、
`risk_actions`、`v_ltv_by_cohort_day`、`v_user_first_seen`（见 schema/sql/clickhouse/）。
"""

from __future__ import annotations

import json
import urllib.request
from typing import Any

from .label import CHURN_HORIZON_DAYS, PLTV_MATURE_DAYS

DEFAULT_TIMEOUT_SECONDS = 60


def read_json_each_row(
    url: str, query: str, timeout: int = DEFAULT_TIMEOUT_SECONDS,
) -> list[dict[str, Any]]:
    """ClickHouse HTTP 接口执行查询，按 JSONEachRow 解析为 dict 列表。"""
    req = urllib.request.Request(
        url.rstrip("/") + "/",
        data=(query.strip() + "\nFORMAT JSONEachRow").encode("utf-8"),
        headers={"Content-Type": "text/plain; charset=utf-8"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = resp.read().decode("utf-8")
    rows: list[dict[str, Any]] = []
    for line in body.splitlines():
        line = line.strip()
        if line:
            rows.append(json.loads(line))
    return rows


CHURN_TRAINING_SQL = """
/* 流失训练集：快照日 = {snapshot}，特征窗 = 快照前 30 天，标签窗 = 快照后 {horizon} 天 */
SELECT
  f.user_id,
  f.days_inactive_30d,
  f.session_count_30d,
  f.event_count_30d,
  f.revenue_total_30d,
  ifNull(a.forward_active, 0) AS forward_active
FROM
(
  SELECT
    user_id,
    dateDiff('day', max(event_date), toDate('{snapshot}')) AS days_inactive_30d,
    uniqExactIf(session_id, session_id != '') AS session_count_30d,
    count() AS event_count_30d,
    sumIf(revenue_amount, revenue_amount > 0) AS revenue_total_30d
  FROM events
  WHERE game_id = '{game_id}' AND environment = '{environment}'
    AND user_id != ''
    AND event_date > toDate('{snapshot}') - 30 AND event_date <= toDate('{snapshot}')
  GROUP BY user_id
) AS f
LEFT JOIN
(
  SELECT user_id, count() AS forward_active
  FROM events
  WHERE game_id = '{game_id}' AND environment = '{environment}'
    AND user_id != ''
    AND event_date > toDate('{snapshot}') AND event_date <= toDate('{snapshot}') + {horizon}
  GROUP BY user_id
) AS a USING (user_id)
"""

RISK_TRAINING_SQL = """
/* 主体风险训练集：30 天严重度命中 + 规则多样性 + 升级处置标签 */
/* severity 字面量为大写——对齐 risk-job 落库值（'CRITICAL'/'HIGH'/'MEDIUM'/'LOW'） */
SELECT
  f.subject_id,
  f.critical_30d,
  f.high_30d,
  f.medium_30d,
  f.low_30d,
  f.distinct_rules_30d,
  ifNull(e.escalated, 0) AS escalated
FROM
(
  SELECT
    subject_id,
    countIf(severity = 'CRITICAL') AS critical_30d,
    countIf(severity = 'HIGH') AS high_30d,
    countIf(severity = 'MEDIUM') AS medium_30d,
    countIf(severity = 'LOW') AS low_30d,
    uniqExact(rule_id) AS distinct_rules_30d
  FROM risk_events
  WHERE game_id = '{game_id}' AND environment = '{environment}'
    AND subject_id != ''
    AND ts >= now() - INTERVAL 30 DAY
  GROUP BY subject_id
) AS f
LEFT JOIN
(
  SELECT subject_id, 1 AS escalated
  FROM risk_actions
  WHERE game_id = '{game_id}' AND environment = '{environment}'
    AND action IN ('block', 'review')
  GROUP BY subject_id
) AS e USING (subject_id)
"""

PLTV_LTV_SQL = """
/* cohort × age_day 收入明细（v_ltv_by_cohort_day 口径）*/
SELECT cohort_date, age_day, revenue
FROM v_ltv_by_cohort_day
WHERE game_id = '{game_id}' AND environment = '{environment}'
"""

PLTV_COHORT_SQL = """
/* cohort 人数（v_user_first_seen 口径，分母 = 全部用户而非付费用户）*/
SELECT cohort_date, count() AS cohort_size
FROM v_user_first_seen
WHERE game_id = '{game_id}' AND environment = '{environment}'
GROUP BY cohort_date
"""


def load_churn_rows(client, game_id: str, environment: str, snapshot: str) -> dict[str, Any]:
    """拉取流失训练行；返回 {"feature_rows": [...], "user_ids": [...], "labels": [...]}。

    client 只需实现 `query(sql) -> list[dict]`（生产为 ClickHouseClient，测试可注入假实现）。
    标签 = forward_active == 0（快照后 {horizon} 天无事件）。
    """
    sql = CHURN_TRAINING_SQL.format(
        game_id=game_id, environment=environment,
        snapshot=snapshot, horizon=CHURN_HORIZON_DAYS,
    )
    rows = client.query(sql)
    labels = []
    feature_rows = []
    user_ids = []
    for row in rows:
        user_ids.append(str(row.get("user_id") or ""))
        feature_rows.append({k: row.get(k) for k in (
            "days_inactive_30d", "session_count_30d", "event_count_30d", "revenue_total_30d")})
        labels.append(1 if int(float(row.get("forward_active") or 0)) == 0 else 0)
    return {"feature_rows": feature_rows, "user_ids": user_ids, "labels": labels}


def load_risk_rows(client, game_id: str, environment: str) -> dict[str, Any]:
    """拉取主体风险训练行；标签 = escalated（block/review 处置过）。"""
    sql = RISK_TRAINING_SQL.format(game_id=game_id, environment=environment)
    rows = client.query(sql)
    labels = []
    feature_rows = []
    subject_ids = []
    for row in rows:
        subject_ids.append(str(row.get("subject_id") or ""))
        feature_rows.append({k: row.get(k) for k in (
            "critical_30d", "high_30d", "medium_30d", "low_30d", "distinct_rules_30d")})
        labels.append(1 if int(float(row.get("escalated") or 0)) == 1 else 0)
    return {"feature_rows": feature_rows, "subject_ids": subject_ids, "labels": labels}


PROPENSITY_TRAINING_SQL = """
/* 付费倾向训练集：快照日 = {snapshot}，特征窗 = 快照前 30 天，标签窗 = 快照后 {horizon} 天 */
/* 特征口径与流失训练集一致（v_user_features_30d 四特征），标签 = 未来 {horizon} 天内有付费 */
SELECT
  f.user_id,
  f.days_inactive_30d,
  f.session_count_30d,
  f.event_count_30d,
  f.revenue_total_30d,
  ifNull(p.forward_paid, 0) AS forward_paid
FROM
(
  SELECT
    user_id,
    dateDiff('day', max(event_date), toDate('{snapshot}')) AS days_inactive_30d,
    uniqExactIf(session_id, session_id != '') AS session_count_30d,
    count() AS event_count_30d,
    sumIf(revenue_amount, revenue_amount > 0) AS revenue_total_30d
  FROM events
  WHERE game_id = '{game_id}' AND environment = '{environment}'
    AND user_id != ''
    AND event_date > toDate('{snapshot}') - 30 AND event_date <= toDate('{snapshot}')
  GROUP BY user_id
) AS f
LEFT JOIN
(
  SELECT user_id, count() AS forward_paid
  FROM events
  WHERE game_id = '{game_id}' AND environment = '{environment}'
    AND user_id != ''
    AND revenue_amount > 0
    AND event_date > toDate('{snapshot}') AND event_date <= toDate('{snapshot}') + {horizon}
  GROUP BY user_id
) AS p USING (user_id)
"""


def load_propensity_rows(client, game_id: str, environment: str, snapshot: str) -> dict[str, Any]:
    """拉取付费倾向训练行；返回 {"feature_rows": [...], "user_ids": [...], "labels": [...]}。

    标签 = forward_paid > 0（快照后 {horizon} 天内有付费事件）。特征口径同流失训练集。
    """
    sql = PROPENSITY_TRAINING_SQL.format(
        game_id=game_id, environment=environment,
        snapshot=snapshot, horizon=CHURN_HORIZON_DAYS,
    )
    rows = client.query(sql)
    labels = []
    feature_rows = []
    user_ids = []
    for row in rows:
        user_ids.append(str(row.get("user_id") or ""))
        feature_rows.append({k: row.get(k) for k in (
            "days_inactive_30d", "session_count_30d", "event_count_30d", "revenue_total_30d")})
        labels.append(1 if int(float(row.get("forward_paid") or 0)) > 0 else 0)
    return {"feature_rows": feature_rows, "user_ids": user_ids, "labels": labels}


def load_pltv_rows(client, game_id: str, environment: str,
                   mature_days: int = PLTV_MATURE_DAYS) -> dict[str, Any]:
    """拉取 pLTV 训练行：cohort 累计曲线 + cohort 人数。"""
    ltv_rows = client.query(PLTV_LTV_SQL.format(game_id=game_id, environment=environment))
    cohort_rows = client.query(PLTV_COHORT_SQL.format(game_id=game_id, environment=environment))
    return {"ltv_rows": ltv_rows, "cohort_rows": cohort_rows, "mature_days": mature_days}


class ClickHouseClient:
    """最小 ClickHouse HTTP 客户端（urllib，无第三方依赖）。"""

    def __init__(self, url: str, timeout: int = DEFAULT_TIMEOUT_SECONDS):
        self.url = url
        self.timeout = timeout

    def query(self, sql: str) -> list[dict[str, Any]]:
        return read_json_each_row(self.url, sql, timeout=self.timeout)
