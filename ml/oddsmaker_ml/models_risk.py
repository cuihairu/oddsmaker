"""risk 可训练模型 + Java RiskScorer 启发式基线（固定 4/3/2/1 权重）。

启发式镜像（RiskScorer.java）：
    weighted = critical*4 + high*3 + medium*2 + low*1
    score = min(weighted / 40, 1)
可训练版让严重度权重（及规则多样性）从处置标签中学出，输出校准概率而非手工归一。
"""

from __future__ import annotations

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

from .evaluate import classification_metrics
from .features import RISK_FEATURES

RANDOM_STATE = 42
SEVERITY_WEIGHTS = {"critical": 4.0, "high": 3.0, "medium": 2.0, "low": 1.0}
FULL_SCALE_WEIGHTED = 40.0


def heuristic_risk_scores(X: np.ndarray) -> np.ndarray:
    """Java RiskScorer 的向量化镜像；X 列序 = RISK_FEATURES（末列 distinct_rules 不参与）。"""
    weighted = (
        X[:, 0] * SEVERITY_WEIGHTS["critical"]
        + X[:, 1] * SEVERITY_WEIGHTS["high"]
        + X[:, 2] * SEVERITY_WEIGHTS["medium"]
        + X[:, 3] * SEVERITY_WEIGHTS["low"]
    )
    return np.minimum(weighted / FULL_SCALE_WEIGHTED, 1.0)


def train_risk(X: np.ndarray, y: np.ndarray, seed: int = RANDOM_STATE) -> dict:
    """标准化 + LogisticRegression（class_weight=balanced：升级处置是稀疏正例）。"""
    if X.shape[0] != y.shape[0]:
        raise ValueError("X 与 y 行数不一致")
    if len(np.unique(y)) < 2:
        raise ValueError("标签只有一类，无法训练（检查处置动作白名单/时间窗）")
    model = Pipeline([
        ("scaler", StandardScaler()),
        ("lr", LogisticRegression(max_iter=1000, random_state=seed,
                                  class_weight="balanced")),
    ])
    model.fit(X, y)
    proba = model.predict_proba(X)[:, 1]
    return {
        "model": model,
        "proba": proba,
        "metrics": classification_metrics(y, proba),
        "baseline_metrics": classification_metrics(y, heuristic_risk_scores(X)),
        "feature_names": list(RISK_FEATURES),
        "coefficients": [round(float(c), 6) for c in model.named_steps["lr"].coef_[0]],
        "intercept": round(float(model.named_steps["lr"].intercept_[0]), 6),
    }


def artifact_extra(result: dict) -> dict:
    auc_gain = round(result["metrics"]["auc"] - result["baseline_metrics"]["auc"], 4)
    return {
        "feature_names": result["feature_names"],
        "coefficients": result["coefficients"],
        "intercept": result["intercept"],
        "label_definition": "risk_actions 中 block/review 处置过 = 1（30 天窗）",
        "heuristic_baseline": result["baseline_metrics"],
        "auc_gain_vs_heuristic": auc_gain,
        "heuristic_weights": SEVERITY_WEIGHTS,
        "serving": "score = sigmoid(intercept + Σ coefficient × feature)；特征序 = feature_names",
    }
