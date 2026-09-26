"""合成数据生成器（种子确定，离线可复现）：演示、端到端验收与单测共用。

生成机制刻意做成"有真实可学的联合分布"——标签由潜在状态决定、特征条件于
同一潜在状态生成，保证 LogisticRegression 学到的权重有含义，而非随机可分。
"""

from __future__ import annotations

import numpy as np

from .features import CHURN_FEATURES, RISK_FEATURES

CHURN_POSITIVE_RATE = 0.4   # 合成流失标签的期望正例占比（用于生成自检）
PLTV_TRUE_MULTIPLIER = 3.2  # 合成 pLTV 的真实 D7→D30 乘数均值


def synthetic_churn(n_users: int = 4000, seed: int = 42) -> tuple[np.ndarray, np.ndarray]:
    """流失训练集：X = 30 天特征（列序 = CHURN_FEATURES），y = 未来 14 天无事件。

    潜在状态：engagement 低 → 会话/事件/付费少、不活跃天数高、流失概率高。
    """
    if n_users < 2:
        raise ValueError("n_users 至少为 2")
    rng = np.random.default_rng(seed)
    engagement = rng.uniform(0.0, 1.0, n_users)
    churn_prob = 1.0 / (1.0 + np.exp(4.0 * (engagement - 0.45)))
    label = rng.random(n_users) < churn_prob

    sessions = np.maximum(0, rng.poisson(2.0 + 26.0 * engagement))
    events = sessions * rng.integers(5, 20, n_users)
    payer = rng.random(n_users) < 0.15
    revenue = np.where(
        payer,
        np.round(rng.exponential(60.0, n_users) * (0.3 + engagement)),
        0.0,
    )
    # 不活跃天数条件于标签（流失者已现端倪），区间重叠 5-7 天留出不可分的边界
    days_inactive = np.where(
        label, rng.integers(5, 30, n_users), rng.integers(0, 8, n_users)
    ).astype(float)

    X = np.column_stack([
        days_inactive,
        sessions.astype(float),
        events.astype(float),
        revenue,
    ])
    return X, label.astype(int)


def synthetic_churn_rows(n_users: int = 4000, seed: int = 42) -> list[dict[str, float]]:
    """dict 行形态的流失特征（走 features.churn_feature_frame 的公共入口）。"""
    X, _ = synthetic_churn(n_users, seed)
    return [dict(zip(CHURN_FEATURES, map(float, row))) for row in X]


def synthetic_risk(n_subjects: int = 3000, seed: int = 43) -> tuple[np.ndarray, np.ndarray]:
    """主体风险训练集：X = 风险特征（列序 = RISK_FEATURES），y = 被升级处置。

    潜在 badness 低偏（多数主体干净）；严重度计数条件于 badness（各严重度
    斜率不同 → 固定 4/3/2/1 权重的启发式不是最优，LR 可学出更优权重）。
    """
    if n_subjects < 2:
        raise ValueError("n_subjects 至少为 2")
    rng = np.random.default_rng(seed)
    badness = rng.uniform(0.0, 1.0, n_subjects) ** 1.5   # 低偏：干净主体占多数
    critical = rng.poisson(2.5 * badness**2)
    high = rng.poisson(5.0 * badness)
    medium = rng.poisson(6.0 * badness)
    low = rng.poisson(8.0 * badness)
    distinct_rules = rng.binomial(8, np.clip(0.05 + 0.75 * badness, 0, 1))
    label = rng.random(n_subjects) < 1.0 / (
        1.0 + np.exp(6.0 * (0.55 - badness))
    )
    X = np.column_stack([
        critical, high, medium, low, distinct_rules,
    ]).astype(float)
    return X, label.astype(int)


def synthetic_risk_rows(n_subjects: int = 3000, seed: int = 43) -> list[dict[str, float]]:
    X, _ = synthetic_risk(n_subjects, seed)
    return [dict(zip(RISK_FEATURES, map(float, row))) for row in X]


def synthetic_pltv(
    n_cohorts: int = 60, seed: int = 44,
) -> tuple[list[dict[str, float]], list[dict[str, float]]]:
    """pLTV 训练集：(ltv_rows, cohort_rows)，行口径 = v_ltv_by_cohort_day / v_user_first_seen。

    每个 cohort 有自己的真实乘数 ~ N(3.2, 0.25)；日内分布前载，且保证
    累计到 age_day=6 恰为 D7 收入、累计到 age_day=29 恰为 D30 收入。
    """
    if n_cohorts < 4:
        raise ValueError("n_cohorts 至少为 4")
    rng = np.random.default_rng(seed)

    ltv_rows: list[dict[str, float]] = []
    cohort_rows: list[dict[str, float]] = []
    for i in range(n_cohorts):
        size = int(rng.integers(200, 2000))
        cohort = f"2026-{1 + i // 28:02d}-{1 + i % 28:02d}"
        cohort_rows.append({"cohort_date": cohort, "cohort_size": size})

        d7_revenue = float(rng.lognormal(mean=9.0, sigma=0.6))
        # 下限 1.05：保证 D30 > D7，日内份额恒为正
        multiplier = float(np.clip(rng.normal(PLTV_TRUE_MULTIPLIER, 0.25), 1.05, None))
        d30_revenue = d7_revenue * multiplier * float(rng.lognormal(0.0, 0.05))

        # 前 7 天日内份额（指数衰减归一），保证累计语义精确
        w7 = np.exp(-np.linspace(0.0, 1.6, 7))
        w7 /= w7.sum()
        for day in range(7):
            ltv_rows.append({
                "cohort_date": cohort, "age_day": day,
                "revenue": d7_revenue * float(w7[day]),
            })
        w23 = np.exp(-np.linspace(0.0, 2.2, 23))
        w23 /= w23.sum()
        for day in range(7, 30):
            ltv_rows.append({
                "cohort_date": cohort, "age_day": day,
                "revenue": (d30_revenue - d7_revenue) * float(w23[day - 7]),
            })
    return ltv_rows, cohort_rows
