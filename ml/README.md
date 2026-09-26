# oddsmaker-ml 训练管线（P4.4）

把 P6 落地的三个可解释启发式（Java 侧 `ChurnScorer` / `LtvForecastAssembler` / `RiskScorer`）补上**可训练管线**：同特征口径下，用从处置/流失标签中学出的参数替代手工权重，每个产物自带启发式基线对照——模型比启发式好在哪，可审计。

依赖保持轻量（numpy / pandas / scikit-learn），全部离线可跑：演示与测试用种子合成的合成数据，不联网下载任何模型/资源。

## 三个模型

| model | 预测目标 | 特征（口径对齐） | Java 启发式基线 | 可训练版 |
|---|---|---|---|---|
| `churn` | 快照后 14 天无事件（§6 目标定义） | `v_user_features_30d`：不活跃天数 / 30 天会话数 / 事件数 / 收入 | `ChurnScorer`：0.7×不活跃 + 0.3×会话衰减 − 付费缓冲 | StandardScaler + LogisticRegression |
| `risk` | 主体被升级处置（`risk_actions` 中 block/review） | `risk_events` 30 天：各严重度命中数 + 规则多样性 | `RiskScorer`：固定 4/3/2/1 严重度加权 / 40 | LR（class_weight=balanced，学习严重度权重） |
| `pltv` | 成熟 cohort 的 D30 ARPU | `v_ltv_by_cohort_day` + `v_user_first_seen`：D7 ARPU → D30 ARPU | `LtvForecastAssembler`：成熟 cohort 比值等权均值 | 过原点 WLS（w = cohort 人数），留出 cohort 上与基线比 MAPE |

设计原则：**可解释优先**。三个可训练版都是线性模型（系数即权重），产物 JSON 直接给出 `feature_names + coefficients + intercept`，Java 侧做点积 + sigmoid 即可打分，无需 Python 运行时。

## 一键跑通（合成数据，无需 ClickHouse）

```bash
python3 -m venv .venv
.venv/bin/pip install numpy pandas scikit-learn   # pytest 为 dev 依赖
cd ml   # 仓库 ml/ 目录（本目录）

# 全链路：数据 → 特征 → 训练 → 评估（含启发式基线对打）→ 产物导出
.venv/bin/python -m oddsmaker_ml train --model all --source synthetic --out artifacts
# → artifacts/churn.json / pltv.json / risk.json

# 校验产物
.venv/bin/python -m oddsmaker_ml validate artifacts/*.json
```

单模型：`--model churn|pltv|risk`；换种子：`--seed`（同种子结果完全可复现）。

## 真实数据（ClickHouse HTTP）

```bash
.venv/bin/python -m oddsmaker_ml train --model churn --source clickhouse \
  --clickhouse-url http://localhost:8123 \
  --game-id game_demo --environment prod \
  --snapshot 2026-09-01 \
  --out artifacts
```

- 前置：`schema/sql/clickhouse/schema.sql`、`ml.sql`、`ltv.sql` 已执行（`events` / `risk_events` / `risk_actions` / `v_ltv_by_cohort_day` / `v_user_first_seen`）。
- churn 标签 SQL：快照前 30 天特征 + 快照后 14 天活跃反推标签，一次查询返回。
- 客户端只依赖标准库 urllib（JSONEachRow），不引入额外依赖。

## 模型产物（JSON，schema_version=1）

```jsonc
{
  "schema_version": 1,
  "model_type": "churn",
  "model_version": "v0.1.0",
  "trained_at": "2026-09-26T…",
  "source": "synthetic | clickhouse",
  "game_id": "game_demo",
  "training_rows": 4000,
  "metrics": { "auc": 0.88, "log_loss": 0.31, "brier": 0.11, "pr_auc": 0.72 },
  "feature_names": ["days_inactive_30d", "session_count_30d", "event_count_30d", "revenue_total_30d"],
  "coefficients": [3.1, -0.6, -0.2, -0.4],
  "intercept": -0.8,
  "label_definition": "snapshot 后 14 天无事件 = 1",
  "heuristic_baseline": { "auc": 0.84, "…": "同特征同口径的 Java 启发式指标" },
  "auc_gain_vs_heuristic": 0.04,
  "serving": "score = sigmoid(intercept + Σ coefficient × feature)"
}
```

写回链路（已交付，Control 侧）：`POST /api/ml-artifacts` 注册版本化产物（校验语义对齐 Python `validate_artifact`，同版本重训覆盖，写审计）→ `PredictionMetricsService` 的 `refreshChurn / refreshRiskScore / refreshPltv` 批量打分（按 `feature_names` 从 ClickHouse 取特征，线性点积 + sigmoid；pltv 为 D7 收入 × 产物乘数）→ 写 `predictions` 表（TTL 语义照旧）。产物在场且特征口径匹配时优先模型分（`path=model`，落库 `model_id/model_version`），缺失、损坏或不匹配回落既有启发式（`path=heuristic`，`heuristic_churn_v1 / rule_aggregate_v1 / cohort_ratio_v1`），两条路径在返回体与落库行上均可区分。ClickHouse 未配置时诚实降级（`available=false`），不伪造结果。

## 开发

```bash
.venv/bin/pip install pytest
.venv/bin/pytest        # 全部离线，秒级
```

## 已知边界

- 仅线性可解释模型；GBDT（XGBoost/LightGBM）等精度升级按需再加（接口不变，替换 `models_*` 内部即可）。
- churn 评估在单快照上做（时间外推验证需多快照滚动截取，真实数据接入时再加）。
- propensity（付费倾向，§6 列出的第三类）未含在本批，可按 churn 管线复制扩展。
- 实时打分（Flink 消费事件查模型）不在本批：先批量日打分回写 predictions。
