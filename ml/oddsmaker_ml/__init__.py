"""Oddsmaker 训练管线（P4.4）。

三个可训练模型，与 Java 侧可解释启发式一一对应（同特征口径，启发式作基线同台评估）：

- churn：流失预测——Java `ChurnScorer`（不活跃天数 0.7 + 会话衰减 0.3 - 付费缓冲）
- pltv：D7→D30 乘数——Java `LtvForecastAssembler`（成熟 cohort 比值均值的 WLS 升级）
- risk：主体风险分——Java `RiskScorer`（严重度固定 4/3/2/1 加权的可学习升级）

产物为版本化 JSON（含特征名/系数/指标/启发式基线），Java 侧可零运行时依赖地
按特征序做点积打分回写 predictions。
"""

__version__ = "0.1.0"
