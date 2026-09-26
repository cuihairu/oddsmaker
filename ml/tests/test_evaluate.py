"""评估指标测试。"""

import pytest

from oddsmaker_ml.evaluate import (
    calibration_bins,
    classification_metrics,
    regression_metrics,
)


def test_perfect_classifier():
    m = classification_metrics([0, 1, 0, 1], [0.1, 0.9, 0.2, 0.8])
    assert m["auc"] == 1.0
    assert 0 < m["log_loss"] < 0.35


def test_constant_scores_auc_is_half():
    m = classification_metrics([0, 1, 0, 1], [0.5, 0.5, 0.5, 0.5])
    assert m["auc"] == 0.5


def test_empty_raises():
    with pytest.raises(ValueError):
        classification_metrics([], [])


def test_single_class_auc_does_not_throw():
    m = classification_metrics([1, 1, 1], [0.2, 0.8, 0.5])
    assert m["auc"] == 0.5   # 全同类 → 不可分，记 0.5 而非抛异常


def test_calibration_bins_counts_sum():
    y = [0, 1] * 50
    s = [i / 99 for i in range(100)]
    bins = calibration_bins(y, s, n_bins=10)
    assert len(bins) == 10
    assert sum(b["count"] for b in bins) == 100
    assert all(0.0 <= b["mean_predicted"] <= 1.0 for b in bins)


def test_regression_metrics_and_mape_zero_guard():
    m = regression_metrics([2, 0, 4], [3, 0, 2])
    assert m["mae"] == 1.0
    assert m["rmse"] == pytest.approx((5 / 3) ** 0.5, rel=1e-3)   # round4 后 1.291
    assert m["mape"] == pytest.approx(0.5)   # 真值 0 的点跳过


def test_regression_empty_raises():
    with pytest.raises(ValueError):
        regression_metrics([], [])
