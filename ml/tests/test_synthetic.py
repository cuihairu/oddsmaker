"""合成数据测试：确定性、形状与结构不变量。"""

import numpy as np
import pytest

from oddsmaker_ml.features import cohort_cumulative, cum_through
from oddsmaker_ml.synthetic import (
    PLTV_TRUE_MULTIPLIER,
    synthetic_churn,
    synthetic_pltv,
    synthetic_risk,
)


def test_churn_deterministic_by_seed():
    X1, y1 = synthetic_churn(500, seed=42)
    X2, y2 = synthetic_churn(500, seed=42)
    X3, y3 = synthetic_churn(500, seed=43)
    np.testing.assert_array_equal(X1, X2)
    np.testing.assert_array_equal(y1, y2)
    assert not np.array_equal(X1, X3)


def test_churn_shapes_and_label_domain():
    X, y = synthetic_churn(1000, seed=42)
    assert X.shape == (1000, 4)
    assert set(np.unique(y)) <= {0, 1}
    rate = y.mean()
    assert 0.2 < rate < 0.6, f"正例占比异常: {rate}"
    assert (X[:, 0] >= 0).all()        # days_inactive 非负
    assert (X[:, 1] >= 0).all()        # sessions 非负
    assert (X[:, 3] >= 0).all()        # revenue 非负


def test_risk_shapes_and_low_frequency_positives():
    X, y = synthetic_risk(2000, seed=43)
    assert X.shape == (2000, 5)
    assert set(np.unique(y)) <= {0, 1}
    assert (X >= 0).all()
    assert 0.02 < y.mean() < 0.6       # 升级处置是稀疏但非平凡的


def test_pltv_structure_and_cumulation():
    ltv_rows, cohort_rows = synthetic_pltv(20, seed=44)
    assert len(cohort_rows) == 20
    assert len(ltv_rows) == 20 * 30    # 每天 1 行：day 0..29
    assert all(r["revenue"] > 0 for r in ltv_rows)   # multiplier ≥ 1.05 ⇒ 日收入恒正

    cum, sizes = cohort_cumulative(ltv_rows, cohort_rows)
    assert len(sizes) == 20
    for cohort in sizes:
        assert cum_through(cum[cohort], 29) > cum_through(cum[cohort], 6)  # D30 > D7


def test_pltv_multiplier_clustered_around_true_value():
    from oddsmaker_ml.models_pltv import _cohort_points, fit_multiplier_wls
    from oddsmaker_ml.label import maturity_cutoff
    from datetime import date

    ltv_rows, cohort_rows = synthetic_pltv(60, seed=44)
    cum, sizes = cohort_cumulative(ltv_rows, cohort_rows)
    cutoff = maturity_cutoff(date(2026, 3, 1))
    points = _cohort_points(cum, sizes, cutoff)
    assert len(points) >= 10
    m = fit_multiplier_wls(points)
    assert abs(m - PLTV_TRUE_MULTIPLIER) / PLTV_TRUE_MULTIPLIER < 0.05


def test_tiny_inputs_raise():
    with pytest.raises(ValueError):
        synthetic_churn(1)
    with pytest.raises(ValueError):
        synthetic_risk(1)
    with pytest.raises(ValueError):
        synthetic_pltv(3)
