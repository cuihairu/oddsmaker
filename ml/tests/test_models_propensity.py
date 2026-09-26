"""propensity（付费倾向）模型测试：可训练 vs 启发式基线（同特征同口径对打）。"""

import numpy as np
import pytest

from oddsmaker_ml.features import CHURN_FEATURES, PROPENSITY_FEATURES
from oddsmaker_ml.models_propensity import (
    artifact_extra,
    heuristic_propensity_scores,
    train_propensity,
)
from oddsmaker_ml.synthetic import synthetic_propensity


def test_feature_alias_is_churn_features():
    """付费倾向与流失同特征同口径（v_user_features_30d），仅标签不同。"""
    assert PROPENSITY_FEATURES == CHURN_FEATURES


def test_heuristic_mirror_of_java_propensityscorer():
    """与 Java PropensityScorer 同输入同结果（双端锁定的 golden 值）。"""
    X = np.array([
        [10.0, 20.0, 200.0, 50.0],   # 付费 + 高活跃：0.12+0.55+0.08-0.04 = 0.71
        [5.0, 3.0, 40.0, 0.0],       # 未付费低活跃：0.12+0.012-0.02 = 0.112
        [30.0, 0.0, 0.0, 0.0],       # 全截断到下限：0.12-0.12 → 0.01
        [0.0, 60.0, 900.0, 120.0],   # 会话截断 50：0.12+0.55+0.2 = 0.87
    ])
    scores = heuristic_propensity_scores(X)
    assert scores.tolist() == pytest.approx([0.71, 0.112, 0.01, 0.87])


def test_trained_model_beats_heuristic_on_synthetic():
    X, y = synthetic_propensity(4000, seed=45)
    result = train_propensity(X, y)
    assert result["metrics"]["auc"] > 0.7
    assert result["metrics"]["auc"] > result["baseline_metrics"]["auc"]  # 严格增益
    assert 0.0 <= result["proba"].min() and result["proba"].max() <= 1.0


def test_gain_stable_across_seeds():
    for seed in (45, 46, 48):
        X, y = synthetic_propensity(2000, seed=seed)
        result = train_propensity(X, y)
        assert result["metrics"]["auc"] > result["baseline_metrics"]["auc"], f"seed={seed}"


def test_artifact_fields_consistent():
    X, y = synthetic_propensity(1000, seed=45)
    result = train_propensity(X, y)
    assert result["feature_names"] == PROPENSITY_FEATURES
    assert len(result["coefficients"]) == len(PROPENSITY_FEATURES)
    extra = artifact_extra(result)
    assert extra["feature_names"] == PROPENSITY_FEATURES
    assert extra["label_definition"] == "snapshot 后 14 天内有付费事件 = 1"
    assert extra["auc_gain_vs_heuristic"] == round(
        result["metrics"]["auc"] - result["baseline_metrics"]["auc"], 4)


def test_single_class_labels_raise():
    X, y = synthetic_propensity(500, seed=45)
    with pytest.raises(ValueError):
        train_propensity(X, np.ones_like(y))


def test_row_count_mismatch_raises():
    X, _ = synthetic_propensity(100, seed=45)
    with pytest.raises(ValueError):
        train_propensity(X, np.zeros(50, dtype=int))
