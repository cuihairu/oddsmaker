"""评估指标：分类（AUC/LogLoss/Brier/校准）与回归（MAE/MAPE/RMSE）。

每个模型产物的 metrics 里同时带 heuristic_baseline（同特征、Java 启发式公式的
同口径指标）——训练管线比启发式好在哪，产物上一眼可见。
"""

from __future__ import annotations

from typing import Sequence

import numpy as np
from sklearn.metrics import (
    auc,
    brier_score_loss,
    log_loss,
    precision_recall_curve,
    roc_auc_score,
)


def classification_metrics(y_true: Sequence[int], scores: Sequence[float]) -> dict[str, float]:
    """概率分数的分类指标；y_true 全同类时 AUC 记 0.5（不可分，不抛异常）。"""
    y = np.asarray(y_true, dtype=int)
    s = np.asarray(scores, dtype=float)
    if y.size == 0:
        raise ValueError("评估样本为空")
    try:
        auc_roc = float(roc_auc_score(y, s))
    except ValueError:
        auc_roc = 0.5
    if not np.isfinite(auc_roc):  # 新版 sklearn 单类时返回 NaN 而非抛错——同样记 0.5
        auc_roc = 0.5
    return {
        "auc": round4(auc_roc),
        # labels=[0, 1]：y_true 单一类时 sklearn log_loss 需显式标签集，否则抛错
        "log_loss": round4(float(log_loss(y, np.clip(s, 1e-6, 1 - 1e-6), labels=[0, 1]))),
        "brier": round4(float(brier_score_loss(y, s))),
        "pr_auc": round4(_pr_auc(y, s)),
    }


def _pr_auc(y: np.ndarray, s: np.ndarray) -> float:
    precision, recall, _ = precision_recall_curve(y, s)
    return float(auc(recall, precision)) if len(precision) > 1 else 0.0


def calibration_bins(
    y_true: Sequence[int], scores: Sequence[float], n_bins: int = 10,
) -> list[dict[str, float]]:
    """等宽分箱校准：每箱 (均值预测, 实际正例率, 样本数)——预测概率是否"敢说真话"。"""
    y = np.asarray(y_true, dtype=int)
    s = np.asarray(scores, dtype=float)
    edges = np.linspace(0.0, 1.0, n_bins + 1)
    bins: list[dict[str, float]] = []
    for i in range(n_bins):
        lo, hi = edges[i], edges[i + 1]
        mask = (s >= lo) & (s < hi) if i < n_bins - 1 else (s >= lo) & (s <= hi)
        n = int(mask.sum())
        bins.append({
            "bin": i,
            "lower": round4(lo),
            "upper": round4(hi),
            "count": n,
            "mean_predicted": round4(float(s[mask].mean())) if n else 0.0,
            "positive_rate": round4(float(y[mask].mean())) if n else 0.0,
        })
    return bins


def regression_metrics(y_true: Sequence[float], y_pred: Sequence[float]) -> dict[str, float]:
    """回归指标；MAPE 跳过真值为 0 的点（分母保护）。"""
    t = np.asarray(y_true, dtype=float)
    p = np.asarray(y_pred, dtype=float)
    if t.size == 0:
        raise ValueError("评估样本为空")
    err = p - t
    nonzero = t != 0
    mape = float(np.abs(err[nonzero] / t[nonzero]).mean()) if nonzero.any() else 0.0
    return {
        "mae": round4(float(np.abs(err).mean())),
        "rmse": round4(float(np.sqrt((err**2).mean()))),
        "mape": round4(mape),
    }


def round4(v: float) -> float:
    return float(round(v, 4))
