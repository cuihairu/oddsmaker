"""产物 schema 测试：build/validate/save/load 与失败路径。"""

import json

import pytest

from oddsmaker_ml.artifact import (
    ArtifactError,
    build_artifact,
    load_artifact,
    save_artifact,
    validate_artifact,
)

METRICS = {"auc": 0.85, "log_loss": 0.4, "brier": 0.15, "pr_auc": 0.7}


def churn_artifact(**overrides):
    base = {
        "model_type": "churn",
        "model_version": "v0.1.0",
        "training_rows": 100,
        "source": "synthetic",
        "metrics": METRICS,
        "extra": {
            "feature_names": ["a", "b"],
            "coefficients": [1.0, -2.0],
            "intercept": 0.5,
        },
    }
    base.update(overrides)
    return base


def test_build_artifact_fields_and_trained_at():
    at = "2026-09-26T00:00:00+00:00"
    a = build_artifact(
        "churn", model_version="v0.1.0", training_rows=10, source="synthetic",
        metrics=METRICS, extra={"feature_names": ["a"], "coefficients": [1.0],
                                "intercept": 0.0},
        game_id="g1", trained_at=at,
    )
    assert a["schema_version"] == 1
    assert a["model_type"] == "churn"
    assert a["trained_at"] == at
    assert a["game_id"] == "g1"
    validate_artifact(a)  # 不抛


def test_build_rejects_unknown_model_type():
    with pytest.raises(ArtifactError):
        build_artifact("chaos", model_version="v", training_rows=1,
                       source="synthetic", metrics={"auc": 1.0}, extra={})


def test_propensity_artifact_validates_like_churn():
    """propensity 走线性分支：feature_names + 等长 coefficients + intercept。"""
    a = build_artifact(
        "propensity", model_version="v0.1.0", training_rows=10, source="synthetic",
        metrics=METRICS, extra={"feature_names": ["a", "b"], "coefficients": [1.0, -2.0],
                                "intercept": 0.5},
        game_id="g1",
    )
    validate_artifact(a)  # 不抛

    b = build_artifact(
        "propensity", model_version="v0.1.0", training_rows=10, source="synthetic",
        metrics=METRICS, extra={"feature_names": ["a", "b"], "coefficients": [1.0],
                                "intercept": 0.5},
        game_id="g1",
    )
    with pytest.raises(ArtifactError):
        validate_artifact(b)   # 系数与特征名长度不一致同样拒绝


def test_validate_failures():
    a = churn_artifact()
    del a["metrics"]
    with pytest.raises(ArtifactError):
        validate_artifact(a)

    a = churn_artifact()
    a["metrics"] = {}
    with pytest.raises(ArtifactError):
        validate_artifact(a)

    a = churn_artifact()
    a["extra"]["coefficients"] = [1.0]
    b = {**a, **a.pop("extra")}
    with pytest.raises(ArtifactError):
        validate_artifact(b)   # 系数与特征名长度不一致

    a = churn_artifact()
    extra = a.pop("extra")
    del extra["intercept"]
    b = {**a, **extra}
    with pytest.raises(ArtifactError):
        validate_artifact(b)

    a = churn_artifact()
    extra = a.pop("extra")
    b = {**a, **extra, "model_type": "pltv", "multiplier": 0.0}
    with pytest.raises(ArtifactError):
        validate_artifact(b)   # pltv multiplier 必须为正

    a = churn_artifact()
    a["schema_version"] = 99
    with pytest.raises(ArtifactError):
        validate_artifact(a)


def test_save_load_round_trip(tmp_path):
    a = build_artifact(
        "pltv", model_version="v0.1.0", training_rows=5, source="synthetic",
        metrics={"holdout_mape_trained": 0.1},
        extra={"multiplier": 3.2},
    )
    path = save_artifact(a, tmp_path / "nested" / "pltv.json")   # 自动建父目录
    loaded = load_artifact(path)
    assert loaded["multiplier"] == 3.2      # extra 字段合并到产物顶层
    assert path.read_text(encoding="utf-8").endswith("\n")
    assert json.loads(path.read_text(encoding="utf-8"))["schema_version"] == 1


def test_save_cleans_tmp_on_failure(tmp_path):
    broken = {"model_type": "churn"}   # 缺一堆字段 → save 前 validate 抛
    with pytest.raises(ArtifactError):
        save_artifact(broken, tmp_path / "x.json")
    leftovers = list(tmp_path.glob("*.tmp"))
    assert leftovers == []
