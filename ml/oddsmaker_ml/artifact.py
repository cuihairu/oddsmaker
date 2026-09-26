"""模型产物：版本化 JSON（schema_version=1），Java 侧可零 Python 依赖消费。

线性模型以 feature_names + coefficients + intercept 表达（点积 + sigmoid），
pltv 以 multiplier 表达。指标里带 heuristic_baseline——训练模型相对 Java
启发式的增益在产物里可审计。
"""

from __future__ import annotations

import json
import os
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping

SCHEMA_VERSION = 1
MODEL_TYPES = {"churn", "pltv", "risk", "propensity"}


class ArtifactError(ValueError):
    """产物 schema 校验失败。"""


def build_artifact(
    model_type: str,
    model_version: str,
    training_rows: int,
    source: str,
    metrics: Mapping[str, Any],
    extra: Mapping[str, Any],
    game_id: str = "",
    trained_at: str | None = None,
) -> dict[str, Any]:
    """组装产物；字段序稳定（Linked dict），trained_at 缺省取当前 UTC。"""
    if model_type not in MODEL_TYPES:
        raise ArtifactError(f"未知 model_type: {model_type}（支持 {sorted(MODEL_TYPES)}）")
    artifact: dict[str, Any] = {
        "schema_version": SCHEMA_VERSION,
        "model_type": model_type,
        "model_version": model_version,
        "trained_at": trained_at or datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "source": source,
        "game_id": game_id,
        "training_rows": int(training_rows),
        "metrics": dict(metrics),
    }
    artifact.update(extra)
    return artifact


def validate_artifact(artifact: Mapping[str, Any]) -> None:
    """schema 校验；失败抛 ArtifactError（带字段级原因）。"""
    for key in ("schema_version", "model_type", "model_version", "trained_at",
                "training_rows", "metrics"):
        if key not in artifact:
            raise ArtifactError(f"缺少必填字段: {key}")
    if artifact["schema_version"] != SCHEMA_VERSION:
        raise ArtifactError(f"schema_version 不支持: {artifact['schema_version']}")
    if artifact["model_type"] not in MODEL_TYPES:
        raise ArtifactError(f"未知 model_type: {artifact['model_type']}")
    if not isinstance(artifact["metrics"], Mapping) or not artifact["metrics"]:
        raise ArtifactError("metrics 必须为非空对象")
    model_type = artifact["model_type"]
    if model_type in ("churn", "risk", "propensity"):
        names = artifact.get("feature_names")
        coefs = artifact.get("coefficients")
        if not names or not isinstance(names, list):
            raise ArtifactError(f"{model_type} 产物缺少 feature_names 列表")
        if not coefs or not isinstance(coefs, list) or len(coefs) != len(names):
            raise ArtifactError(f"{model_type} 产物 coefficients 必须与 feature_names 等长")
        if "intercept" not in artifact:
            raise ArtifactError(f"{model_type} 产物缺少 intercept")
    else:  # pltv
        m = artifact.get("multiplier")
        if not isinstance(m, (int, float)) or m <= 0:
            raise ArtifactError("pltv 产物 multiplier 必须为正数")


def save_artifact(artifact: Mapping[str, Any], path: str | os.PathLike) -> Path:
    """写 JSON（tmp + 原子替换，避免半写产物被下游读到）。"""
    validate_artifact(artifact)
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(dir=p.parent, prefix=p.name, suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(artifact, f, ensure_ascii=False, indent=2)
            f.write("\n")
        os.replace(tmp_name, p)
    except BaseException:
        if os.path.exists(tmp_name):
            os.unlink(tmp_name)
        raise
    return p


def load_artifact(path: str | os.PathLike) -> dict[str, Any]:
    """读产物并校验 schema；损坏或非法即抛 ArtifactError。"""
    with open(path, encoding="utf-8") as f:
        artifact = json.load(f)
    validate_artifact(artifact)
    return artifact
