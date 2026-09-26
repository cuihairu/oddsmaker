"""CLI 端到端测试：train（合成源，全链路）+ validate，零网络。"""

import json
from pathlib import Path

import pytest

from oddsmaker_ml.artifact import load_artifact
from oddsmaker_ml.cli import main


def test_train_all_synthetic_e2e(tmp_path, capsys):
    out = tmp_path / "artifacts"
    code = main(["train", "--model", "all", "--source", "synthetic",
                 "--out", str(out)])
    assert code == 0
    for name in ("churn", "pltv", "risk"):
        artifact = load_artifact(out / f"{name}.json")
        assert artifact["source"] == "synthetic"
        assert artifact["training_rows"] > 0

    churn = load_artifact(out / "churn.json")
    assert churn["metrics"]["auc"] > 0.7                    # §6 验收线
    assert churn["auc_gain_vs_heuristic"] >= 0

    risk = load_artifact(out / "risk.json")
    assert risk["metrics"]["auc"] > 0.7

    pltv = load_artifact(out / "pltv.json")
    assert pltv["multiplier"] > 0                       # extra 字段合并到产物顶层

    stdout = capsys.readouterr().out
    assert "训练完成" in stdout and "启发式基线" in stdout


def test_train_is_deterministic_by_seed(tmp_path):
    out1, out2 = tmp_path / "a", tmp_path / "b"
    main(["train", "--model", "churn", "--source", "synthetic", "--out", str(out1)])
    main(["train", "--model", "churn", "--source", "synthetic", "--out", str(out2)])
    a = json.loads((out1 / "churn.json").read_text(encoding="utf-8"))
    b = json.loads((out2 / "churn.json").read_text(encoding="utf-8"))
    assert a["metrics"] == b["metrics"]                     # trained_at 不参与比较
    assert a["coefficients"] == b["coefficients"]


def test_train_single_model_only_writes_that_artifact(tmp_path):
    out = tmp_path / "artifacts"
    code = main(["train", "--model", "pltv", "--source", "synthetic", "--out", str(out)])
    assert code == 0
    assert (out / "pltv.json").exists()
    assert not (out / "churn.json").exists()
    assert not (out / "risk.json").exists()


def test_train_single_failure_does_not_block_others(tmp_path, capsys):
    out = tmp_path / "artifacts"
    # n-users=1 使 churn 合成器抛错；pltv/risk 不受影响 → 汇总退出码 1
    code = main(["train", "--model", "all", "--source", "synthetic",
                 "--n-users", "1", "--out", str(out)])
    assert code == 1
    err = capsys.readouterr().err
    assert "churn" in err
    assert not (out / "churn.json").exists()
    assert (out / "pltv.json").exists()
    assert (out / "risk.json").exists()


def test_validate_command(tmp_path, capsys):
    out = tmp_path / "artifacts"
    main(["train", "--model", "churn", "--source", "synthetic", "--out", str(out)])
    good = out / "churn.json"
    bad = tmp_path / "broken.json"
    bad.write_text(json.dumps({"model_type": "churn"}), encoding="utf-8")

    assert main(["validate", str(good)]) == 0
    assert main(["validate", str(bad)]) == 1
    assert "[ok]" in capsys.readouterr().out


def test_validate_missing_file_fails(tmp_path):
    assert main(["validate", str(tmp_path / "nope.json")]) == 1


def test_clickhouse_source_not_reachable_fails_gracefully(tmp_path, capsys):
    # 无人监听的端口：churn 训练失败但流程不崩，退出码 1
    code = main(["train", "--model", "churn", "--source", "clickhouse",
                 "--clickhouse-url", "http://127.0.0.1:1", "--out",
                 str(tmp_path / "artifacts")])
    assert code == 1
    assert "churn" in capsys.readouterr().err


def test_artifacts_dir_created(tmp_path):
    out = tmp_path / "deep" / "nested"
    main(["train", "--model", "risk", "--source", "synthetic", "--out", str(out)])
    assert Path(out / "risk.json").exists()
