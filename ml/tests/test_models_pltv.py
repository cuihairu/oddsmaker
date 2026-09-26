"""pLTV 乘数测试：WLS 可训练乘数 vs 等权比值均值基线。"""

from datetime import date

import pytest

from oddsmaker_ml.features import cohort_cumulative, cum_through
from oddsmaker_ml.label import maturity_cutoff
from oddsmaker_ml.models_pltv import (
    _cohort_points,
    fit_multiplier_baseline,
    fit_multiplier_wls,
    train_pltv,
)
from oddsmaker_ml.synthetic import PLTV_TRUE_MULTIPLIER, synthetic_pltv

TODAY = date(2026, 3, 1)


def _fixture():
    return synthetic_pltv(60, seed=44)


def test_wls_multiplier_recovers_true_value():
    ltv_rows, cohort_rows = _fixture()
    result = train_pltv(ltv_rows, cohort_rows, today=TODAY)
    assert abs(result["multiplier"] - PLTV_TRUE_MULTIPLIER) / PLTV_TRUE_MULTIPLIER < 0.05
    assert result["baseline_multiplier"] > 0
    assert result["based_on_cohorts"] >= 10
    assert result["holdout_cohorts"] >= 1


def test_trained_holdout_mape_not_worse_than_baseline():
    # 两种估计量在同一总体上都是一致的，干净数据上互有胜负属预期；
    # 断言"无灾难性回退"（离群稳健性优势由 test_wls_more_stable_than_mean_ratio_on_outlier 单独覆盖）
    ltv_rows, cohort_rows = _fixture()
    result = train_pltv(ltv_rows, cohort_rows, today=TODAY)
    assert result["holdout_metrics_trained"]["mape"] < 0.2
    assert result["holdout_metrics_trained"]["mape"] <= \
        result["holdout_metrics_baseline"]["mape"] * 1.10


def test_forecasts_cover_immature_cohorts():
    ltv_rows, cohort_rows = _fixture()
    result = train_pltv(ltv_rows, cohort_rows, today=TODAY)
    forecasts = result["forecasts"]
    immature = [p for p in forecasts if p.get("immature")]
    assert immature, "应有未成熟 cohort 走外推"
    for p in immature:
        # obs 与 predicted 各自独立舍入到 2 位，允许 0.05 容差
        assert p["predicted_d30_arpu"] == pytest.approx(
            p["obs_d7_arpu"] * result["multiplier"], abs=0.05)
    # 未成熟 cohort 不应有 D30 观测
    assert all("obs_d30_arpu" not in p for p in immature)


def test_wls_more_stable_than_mean_ratio_on_outlier():
    # 一个 d7 极小、比值极端的 cohort：等权比值均值被拉飞，WLS（size 加权、x² 惩罚小基期）更稳
    points = [
        {"cohort": "2026-01-01", "size": 1000, "d7_arpu": 10.0, "d30_arpu": 32.0},
        {"cohort": "2026-01-02", "size": 1000, "d7_arpu": 10.0, "d30_arpu": 33.0},
        {"cohort": "2026-01-03", "size": 10, "d7_arpu": 0.01, "d30_arpu": 5.0},  # 比值 500
    ]
    wls = fit_multiplier_wls(points)
    baseline = fit_multiplier_baseline(points)
    assert abs(wls - 3.25) < 0.01           # 贴近主体比值趋势（3.2/3.3）
    assert baseline > 100                   # 均值口径被离群比值（500）支配
    assert wls < baseline


def test_no_mature_cohorts_raise():
    ltv_rows, cohort_rows = _fixture()
    cum, sizes = cohort_cumulative(ltv_rows, cohort_rows)
    with pytest.raises(ValueError):
        train_pltv(ltv_rows, cohort_rows, today=date(2026, 1, 5))  # 全部未成熟
    with pytest.raises(ValueError):
        fit_multiplier_wls([])
    with pytest.raises(ValueError):
        fit_multiplier_baseline([])


def test_invalid_holdout_fraction_raise():
    ltv_rows, cohort_rows = _fixture()
    with pytest.raises(ValueError):
        train_pltv(ltv_rows, cohort_rows, today=TODAY, holdout_fraction=1.5)


def test_cohort_points_exclude_zero_revenue():
    ltv_rows = [{"cohort_date": "2026-01-01", "age_day": 0, "revenue": 0}]
    cohort_rows = [{"cohort_date": "2026-01-01", "cohort_size": 100}]
    cum, sizes = cohort_cumulative(ltv_rows, cohort_rows)
    points = _cohort_points(cum, sizes, maturity_cutoff(TODAY))
    assert points == []   # d7_arpu = 0 不参与拟合
