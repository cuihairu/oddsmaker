"""数据读取测试：JSONEachRow HTTP 解析与 load_* 行→标签装配。"""

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from oddsmaker_ml.data_io import (
    ClickHouseClient,
    load_churn_rows,
    load_pltv_rows,
    load_propensity_rows,
    load_risk_rows,
    read_json_each_row,
)


class FakeCHHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"])).decode()
        # 记录请求体供断言（FORMAT JSONEachRow 由客户端拼上）
        self.server.last_body = body
        self.server.last_path = self.path
        rows = getattr(self.server, "rows", [])
        payload = "\n".join(json.dumps(r) for r in rows)
        data = payload.encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):  # 静默
        pass


@pytest.fixture()
def ch_server():
    server = HTTPServer(("127.0.0.1", 0), FakeCHHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield server
    server.shutdown()
    server.server_close()


def test_read_json_each_row_posts_format_suffix(ch_server):
    ch_server.rows = [{"a": 1}, {"a": 2}]
    rows = read_json_each_row(f"http://127.0.0.1:{ch_server.server_port}", "SELECT 1")
    assert rows == [{"a": 1}, {"a": 2}]
    assert ch_server.last_body.endswith("FORMAT JSONEachRow")
    assert "SELECT 1" in ch_server.last_body
    assert ch_server.last_path == "/"       # ClickHouse HTTP 根路径


def test_read_json_each_row_empty_result(ch_server):
    ch_server.rows = []
    rows = read_json_each_row(f"http://127.0.0.1:{ch_server.server_port}", "SELECT 1")
    assert rows == []


def test_read_json_each_row_malformed_line_raises(ch_server):
    ch_server.rows = [{"a": 1}]

    original_handle = ch_server.RequestHandlerClass.do_POST

    def broken(self):
        self.rfile.read(int(self.headers["Content-Length"]))
        data = b'{"a": 1}\nnot-json\n'
        self.send_response(200)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    ch_server.RequestHandlerClass.do_POST = broken
    try:
        with pytest.raises(json.JSONDecodeError):
            read_json_each_row(f"http://127.0.0.1:{ch_server.server_port}", "SELECT 1")
    finally:
        ch_server.RequestHandlerClass.do_POST = original_handle


class StaticClient:
    """假 client：只实现 query(sql)，覆盖 data_io.load_* 的装配逻辑。"""

    def __init__(self, rows):
        self.rows = rows
        self.sqls = []

    def query(self, sql):
        self.sqls.append(sql)
        return self.rows


def test_load_churn_rows_assembles_labels():
    client = StaticClient([
        {"user_id": "u1", "days_inactive_30d": 5, "session_count_30d": 10,
         "event_count_30d": 80, "revenue_total_30d": 0, "forward_active": 0},   # 流失
        {"user_id": "u2", "days_inactive_30d": 1, "session_count_30d": 20,
         "event_count_30d": 200, "revenue_total_30d": 50.5, "forward_active": 3},
    ])
    data = load_churn_rows(client, "g1", "prod", "2026-03-01")
    assert data["user_ids"] == ["u1", "u2"]
    assert data["labels"] == [1, 0]
    assert data["feature_rows"][0]["revenue_total_30d"] == 0
    assert data["feature_rows"][1]["revenue_total_30d"] == 50.5
    sql = client.sqls[0]
    assert "2026-03-01" in sql and "14" in sql     # 快照日与标签窗注入


def test_load_risk_rows_assembles_labels():
    client = StaticClient([
        {"subject_id": "s1", "critical_30d": 2, "high_30d": 1, "medium_30d": 0,
         "low_30d": 0, "distinct_rules_30d": 2, "escalated": 1},
        {"subject_id": "s2", "critical_30d": 0, "high_30d": 0, "medium_30d": 1,
         "low_30d": 4, "distinct_rules_30d": 1, "escalated": 0},
    ])
    data = load_risk_rows(client, "g1", "prod")
    assert data["subject_ids"] == ["s1", "s2"]
    assert data["labels"] == [1, 0]
    assert "risk_events" in client.sqls[0] and "risk_actions" in client.sqls[0]
    # severity 字面量必须大写——对齐 risk-job 落库值（'CRITICAL' 等）
    assert "severity = 'CRITICAL'" in client.sqls[0]
    assert "severity = 'critical'" not in client.sqls[0]


def test_load_propensity_rows_assembles_labels():
    client = StaticClient([
        {"user_id": "u1", "days_inactive_30d": 3, "session_count_30d": 15,
         "event_count_30d": 120, "revenue_total_30d": 30.0, "forward_paid": 2},   # 未来付费
        {"user_id": "u2", "days_inactive_30d": 20, "session_count_30d": 1,
         "event_count_30d": 5, "revenue_total_30d": 0, "forward_paid": 0},
    ])
    data = load_propensity_rows(client, "g1", "prod", "2026-03-01")
    assert data["user_ids"] == ["u1", "u2"]
    assert data["labels"] == [1, 0]
    assert data["feature_rows"][0]["revenue_total_30d"] == 30.0
    sql = client.sqls[0]
    assert "2026-03-01" in sql and "14" in sql     # 快照日与标签窗注入
    assert "revenue_amount > 0" in sql             # 付费标签的过滤条件


def test_load_pltv_rows_passthrough():
    client = StaticClient([{"cohort_date": "2026-01-01", "age_day": 0, "revenue": 1.0}])
    data = load_pltv_rows(client, "g1", "prod")
    assert data["ltv_rows"][0]["revenue"] == 1.0
    assert data["mature_days"] == 30
    assert len(client.sqls) == 2   # ltv 明细 + cohort 人数


def test_clickhouse_client_delegates(ch_server):
    ch_server.rows = [{"x": 1}]
    client = ClickHouseClient(f"http://127.0.0.1:{ch_server.server_port}", timeout=5)
    assert client.query("SELECT x") == [{"x": 1}]
