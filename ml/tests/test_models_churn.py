"""churn 模型测试：可训练 vs 启发式基线（同特征同口径对打）。"""

import numpy as np
import pytest

from oddsmaker_ml.features import CHURN_FEATURES
from oddsmaker_ml.models_churn import artifact_extra, heuristic_churn_scores, train_churn
from oddsmaker_ml.synthetic import synthetic_churn


def test_heuristic_mirror_of_java_churnscorer():
    X = np.array([
        [14.0, 30.0, 0.0, 0.0],    # 完全不活跃：1.0*0.7 + 0*0.3 = 0.7
        [0.0, 0.0, 10.0, 0.0],     # 活跃但无会话：0 + 1*0.3 = 0.3
        [7.0, 15.0, 10.0, 50.0],   # 付费缓冲：0.5*0.7 + 0.5*0.3 - 0.1 = 0.4
        [28.0, 60.0, 10.0, 0.0],   # 全部钳制到 1：0.7 + 0 - 0 = 0.7
    ])
    scores = heuristic_churn_scores(X)
    assert scores.tolist() == pytest.approx([0.7, 0.3, 0.4, 0.7])


def test_trained_model_beats_heuristic_on_synthetic():
    X, y = synthetic_churn(4000, seed=42)
    result = train_churn(X, y)
    assert result["metrics"]["auc"] > 0.7          # §6 验收线
    assert result["metrics"]["auc"] >= result["baseline_metrics"]["auc"]
    assert 0.0 <= result["proba"].min() and result["proba"].max() <= 1.0


def test_artifact_fields_consistent():
    X, y = synthetic_churn(1000, seed=42)
    result = train_churn(X, y)
    assert result["feature_names"] == CHURN_FEATURES
    assert len(result["coefficients"]) == len(CHURN_FEATURES)
    extra = artifact_extra(result)
    assert extra["feature_names"] == CHURN_FEATURES
    assert extra["auc_gain_vs_heuristic"] == round(
        result["metrics"]["auc"] - result["baseline_metrics"]["auc"], 4)


def test_single_class_labels_raise():
    X, y = synthetic_churn(500, seed=42)
    with pytest.raises(ValueError):
        train_churn(X, np.ones_like(y))


def test_row_count_mismatch_raises():
    X, _ = synthetic_churn(100, seed=42)
    with pytest.raises(ValueError):
        train_churn(X, np.zeros(50, dtype=int))
