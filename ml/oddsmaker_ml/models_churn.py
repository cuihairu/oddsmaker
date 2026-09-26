"""churn 可训练模型 + Java ChurnScorer 启发式基线（同特征同口径对打）。

启发式镜像（ChurnScorer.java）：
    inactivity   = clamp(days_inactive / 14, 0, 1)
    disengagement = 1 - clamp(sessions_30d / 30, 0, 1)
    score = 0.7 * inactivity + 0.3 * disengagement - (0.1 if revenue > 0)
    clamp [0, 1]
"""

from __future__ import annotations

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

from .evaluate import classification_metrics
from .features import CHURN_FEATURES

RANDOM_STATE = 42


def heuristic_churn_scores(X: np.ndarray) -> np.ndarray:
    """Java ChurnScorer 的向量化镜像；X 列序 = CHURN_FEATURES。"""
    days_inactive = X[:, 0]
    sessions = X[:, 1]
    revenue = X[:, 3]
    inactivity = np.clip(days_inactive / 14.0, 0.0, 1.0)
    disengagement = 1.0 - np.clip(sessions / 30.0, 0.0, 1.0)
    score = inactivity * 0.7 + disengagement * 0.3
    score = np.where(revenue > 0, score - 0.1, score)
    return np.clip(score, 0.0, 1.0)


def train_churn(X: np.ndarray, y: np.ndarray, seed: int = RANDOM_STATE) -> dict:
    """标准化 + LogisticRegression；返回模型、概率、指标与启发式基线指标。"""
    if X.shape[0] != y.shape[0]:
        raise ValueError("X 与 y 行数不一致")
    if len(np.unique(y)) < 2:
        raise ValueError("标签只有一类，无法训练（检查标签窗/快照日配置）")
    model = Pipeline([
        ("scaler", StandardScaler()),
        ("lr", LogisticRegression(max_iter=1000, random_state=seed)),
    ])
    model.fit(X, y)
    proba = model.predict_proba(X)[:, 1]
    return {
        "model": model,
        "proba": proba,
        "metrics": classification_metrics(y, proba),
        "baseline_metrics": classification_metrics(y, heuristic_churn_scores(X)),
        "feature_names": list(CHURN_FEATURES),
        "coefficients": [round(float(c), 6) for c in model.named_steps["lr"].coef_[0]],
        "intercept": round(float(model.named_steps["lr"].intercept_[0]), 6),
    }


def artifact_extra(result: dict) -> dict:
    """产物 extra 字段（特征/系数/基线增益）。"""
    auc_gain = round(result["metrics"]["auc"] - result["baseline_metrics"]["auc"], 4)
    return {
        "feature_names": result["feature_names"],
        "coefficients": result["coefficients"],
        "intercept": result["intercept"],
        "label_definition": "snapshot 后 14 天无事件 = 1",
        "heuristic_baseline": result["baseline_metrics"],
        "auc_gain_vs_heuristic": auc_gain,
        "serving": "score = sigmoid(intercept + Σ coefficient × feature)；特征序 = feature_names",
    }
