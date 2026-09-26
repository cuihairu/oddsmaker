"""pLTV 可训练乘数 + Java LtvForecastAssembler 基线（成熟 cohort 比值均值）。

- 基线（镜像 Java）：multiplier = mean(d30/d7)，成熟 cohort 等权。
- 可训练（本管线）：ARPU 口径的过原点加权最小二乘——
      multiplier = Σ w_i · d7_arpu_i · d30_arpu_i / Σ w_i · d7_arpu_i²
  w_i = cohort 人数（大 cohort 观测更稳）。比等权比值均值对离群 cohort 更稳。
- 评估：按 cohort 时间切分（早 70% 拟合 / 晚 30% 留出），留出 cohort 上比 MAPE。
"""

from __future__ import annotations

from datetime import date

import numpy as np

from .evaluate import regression_metrics
from .features import cohort_cumulative, cum_through
from .label import PLTV_MATURE_DAYS, maturity_cutoff

MIN_D7_FOR_FIT = 0.0  # d7_arpu > 0 才参与拟合（0 无从外推）


def _cohort_points(
    cum: dict[str, dict[int, float]],
    sizes: dict[str, int],
    cutoff: date,
) -> list[dict]:
    """成熟 cohort 的 (cohort, d7_arpu, d30_arpu, size) 点集（按 cohort 日排序）。"""
    points = []
    for cohort in sorted(sizes):
        size = sizes[cohort]
        if size <= 0 or cohort not in cum:
            continue
        if date.fromisoformat(cohort) > cutoff:
            continue
        d7 = cum_through(cum[cohort], 6)
        d30 = cum_through(cum[cohort], 29)
        d7_arpu = d7 / size
        d30_arpu = d30 / size
        if d7_arpu > MIN_D7_FOR_FIT and d30_arpu > 0:
            points.append({
                "cohort": cohort, "size": size,
                "d7_arpu": d7_arpu, "d30_arpu": d30_arpu,
            })
    return points


def fit_multiplier_wls(points: list[dict]) -> float:
    """过原点 WLS：m = Σ w·x·y / Σ w·x²，w = cohort size。"""
    if not points:
        raise ValueError("无成熟 cohort 可拟合（检查 today/mature_days）")
    x = np.array([p["d7_arpu"] for p in points])
    y = np.array([p["d30_arpu"] for p in points])
    w = np.array([float(p["size"]) for p in points])
    denom = float((w * x * x).sum())
    if denom <= 0:
        raise ValueError("拟合分母为 0（cohort 收入全为 0）")
    return float((w * x * y).sum() / denom)


def fit_multiplier_baseline(points: list[dict]) -> float:
    """Java 启发式镜像：成熟 cohort 比值 d30/d7 的等权均值。"""
    if not points:
        raise ValueError("无成熟 cohort 可拟合（检查 today/mature_days）")
    ratios = [p["d30_arpu"] / p["d7_arpu"] for p in points if p["d7_arpu"] > 0]
    if not ratios:
        raise ValueError("无有效比值（cohort D7 ARPU 全为 0）")
    return float(np.mean(ratios))


def train_pltv(
    ltv_rows: list[dict],
    cohort_rows: list[dict],
    today: date,
    mature_days: int = PLTV_MATURE_DAYS,
    holdout_fraction: float = 0.3,
) -> dict:
    """拟合乘数并对比基线；返回乘数、指标与各 cohort 的 pLTV 外推。"""
    if not 0.0 < holdout_fraction < 0.9:
        raise ValueError("holdout_fraction 需在 (0, 0.9)")
    cum, sizes = cohort_cumulative(ltv_rows, cohort_rows)
    cutoff = maturity_cutoff(today, mature_days)
    points = _cohort_points(cum, sizes, cutoff)
    if len(points) < 2:
        raise ValueError("成熟 cohort 不足 2 个，无法做留出评估")

    split = max(1, int(len(points) * (1.0 - holdout_fraction)))
    fit_points, holdout_points = points[:split], points[split:]
    if not holdout_points:  # 边界保护：至少留 1 个 cohort 评估
        holdout_points, fit_points = points[-1:], points[:-1]

    multiplier = fit_multiplier_wls(fit_points)
    baseline = fit_multiplier_baseline(fit_points)

    trained_mape = regression_metrics(
        [p["d30_arpu"] for p in holdout_points],
        [p["d7_arpu"] * multiplier for p in holdout_points],
    )
    baseline_mape = regression_metrics(
        [p["d30_arpu"] for p in holdout_points],
        [p["d7_arpu"] * baseline for p in holdout_points],
    )

    forecasts = []
    for p in points:
        forecasts.append({
            "cohort": p["cohort"], "cohort_size": p["size"],
            "obs_d7_arpu": round(p["d7_arpu"], 2),
            "obs_d30_arpu": round(p["d30_arpu"], 2),
            "predicted_d30_arpu": round(p["d7_arpu"] * multiplier, 2),
        })
    # 未成熟 cohort：只有 D7 观测，乘数外推
    for cohort in sorted(sizes):
        if cohort in {p["cohort"] for p in points} or sizes[cohort] <= 0:
            continue
        d7 = cum_through(cum.get(cohort, {}), 6)
        if d7 <= 0:
            continue
        forecasts.append({
            "cohort": cohort, "cohort_size": sizes[cohort],
            "obs_d7_arpu": round(d7 / sizes[cohort], 2),
            "predicted_d30_arpu": round(d7 / sizes[cohort] * multiplier, 2),
            "immature": True,
        })

    return {
        "multiplier": multiplier,
        "baseline_multiplier": baseline,
        "holdout_metrics_trained": trained_mape,
        "holdout_metrics_baseline": baseline_mape,
        "based_on_cohorts": len(fit_points),
        "holdout_cohorts": len(holdout_points),
        "mature_days": mature_days,
        "forecasts": forecasts,
    }


def artifact_extra(result: dict) -> dict:
    return {
        "multiplier": round(result["multiplier"], 6),
        "baseline_multiplier": round(result["baseline_multiplier"], 6),
        "fit_method": "ARPU 过原点加权最小二乘（w = cohort_size）",
        "label_definition": "成熟 cohort（注册满 30 天）D30 ARPU ← D7 ARPU 外推",
        "holdout_metrics_trained": result["holdout_metrics_trained"],
        "heuristic_baseline": result["holdout_metrics_baseline"],
        "based_on_cohorts": result["based_on_cohorts"],
        "serving": "predicted_d30_arpu = obs_d7_arpu × multiplier",
    }
