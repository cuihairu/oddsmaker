"""risk 模型测试：可学习严重度权重 vs 固定 4/3/2/1 基线。"""

import numpy as np
import pytest

from oddsmaker_ml.features import RISK_FEATURES
from oddsmaker_ml.models_risk import heuristic_risk_scores, train_risk
from oddsmaker_ml.synthetic import synthetic_risk


def test_heuristic_mirror_of_java_riskscorer():
    X = np.array([
        [10.0, 0.0, 0.0, 0.0, 1.0],   # 10×CRITICAL = 40 → 满分 1.0
        [1.0, 1.0, 1.0, 1.0, 2.0],    # 4+3+2+1 = 10 → 0.25
        [0.0, 0.0, 0.0, 2.0, 1.0],    # 2 → 0.05
        [100.0, 0.0, 0.0, 0.0, 3.0],  # 钳制 1.0
    ])
    scores = heuristic_risk_scores(X)
    assert scores.tolist() == pytest.approx([1.0, 0.25, 0.05, 1.0])
    # distinct_rules（末列）不参与启发式
    assert heuristic_risk_scores(np.array([[0.0, 0, 0, 0, 999]]))[0] == 0.0


def test_trained_model_beats_fixed_weights():
    X, y = synthetic_risk(3000, seed=43)
    result = train_risk(X, y)
    assert result["metrics"]["auc"] > 0.7
    assert result["metrics"]["auc"] >= result["baseline_metrics"]["auc"]


def test_artifact_fields_consistent():
    X, y = synthetic_risk(2000, seed=43)
    result = train_risk(X, y)
    assert result["feature_names"] == RISK_FEATURES
    assert len(result["coefficients"]) == len(RISK_FEATURES)


def test_single_class_labels_raise():
    X, _ = synthetic_risk(500, seed=43)
    with pytest.raises(ValueError):
        train_risk(X, np.zeros_like(X[:, 0], dtype=int))
