"""命令行入口：python3 -m oddsmaker_ml <train|validate> [options]

一键链路：数据读取（合成/ClickHouse）→ 特征 → 训练 → 评估（含启发式基线）
→ 产物导出（artifacts/<model_type>.json）。
"""

from __future__ import annotations

import argparse
import sys
from datetime import date
from pathlib import Path

import numpy as np

from . import __version__
from .artifact import build_artifact, load_artifact, save_artifact
from .features import churn_feature_frame, propensity_feature_frame, risk_feature_frame
from .models_churn import artifact_extra as churn_extra
from .models_churn import train_churn
from .models_pltv import artifact_extra as pltv_extra
from .models_pltv import train_pltv
from .models_propensity import artifact_extra as propensity_extra
from .models_propensity import train_propensity
from .models_risk import artifact_extra as risk_extra
from .models_risk import train_risk
from .synthetic import synthetic_churn, synthetic_pltv, synthetic_propensity, synthetic_risk

SYNTHETIC_TODAY = date(2026, 3, 1)  # 合成 cohort 覆盖 2026-01-01 起约 2 个月，留足成熟/未成熟两段
DEFAULT_MODEL_VERSION = "v0.1.0"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="oddsmaker_ml",
        description="Oddsmaker 训练管线：churn / pltv / risk / propensity（启发式的可训练升级）",
    )
    parser.add_argument("--version", action="version", version=f"oddsmaker-ml {__version__}")
    sub = parser.add_subparsers(dest="command", required=True)

    train = sub.add_parser("train", help="数据 → 特征 → 训练 → 评估 → 产物导出")
    train.add_argument("--model", choices=["churn", "pltv", "risk", "propensity", "all"], default="all")
    train.add_argument("--source", choices=["synthetic", "clickhouse"], default="synthetic")
    train.add_argument("--out", default="artifacts", help="产物输出目录（默认 ./artifacts）")
    train.add_argument("--seed", type=int, default=42)
    train.add_argument("--model-version", default=DEFAULT_MODEL_VERSION)
    train.add_argument("--n-users", type=int, default=4000, help="合成 churn 用户数")
    train.add_argument("--n-subjects", type=int, default=3000, help="合成 risk 主体数")
    train.add_argument("--n-cohorts", type=int, default=60, help="合成 pltv cohort 数")
    # clickhouse 源参数
    train.add_argument("--clickhouse-url", default="http://localhost:8123")
    train.add_argument("--game-id", default="game_demo")
    train.add_argument("--environment", default="prod")
    train.add_argument("--snapshot", default=str(date.today()),
                       help="churn 特征快照日（YYYY-MM-DD，默认今天）")

    validate = sub.add_parser("validate", help="校验产物 JSON schema")
    validate.add_argument("paths", nargs="+", help="产物文件路径")
    return parser


def run_train(args: argparse.Namespace) -> int:
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    models = ["churn", "pltv", "risk", "propensity"] if args.model == "all" else [args.model]
    exit_code = 0
    for name in models:
        try:
            artifact = _train_one(name, args)
        except Exception as e:  # noqa: BLE001 —— 单模型失败不阻断其余模型，末尾汇总退出码
            print(f"[{name}] 训练失败: {e}", file=sys.stderr)
            exit_code = 1
            continue
        path = save_artifact(artifact, out_dir / f"{name}.json")
        _print_report(name, artifact, path)
    return exit_code


def _train_one(name: str, args: argparse.Namespace) -> dict:
    common = {"model_version": args.model_version, "source": args.source}
    if name == "churn":
        if args.source == "synthetic":
            X, y = synthetic_churn(args.n_users, seed=args.seed)
        else:
            from .data_io import ClickHouseClient, load_churn_rows

            client = ClickHouseClient(args.clickhouse_url)
            data = load_churn_rows(client, args.game_id, args.environment, args.snapshot)
            frame = churn_feature_frame(data["feature_rows"])
            X = frame.to_numpy()
            y = np.asarray(data["labels"], dtype=int)
        result = train_churn(X, y, seed=args.seed)
        return build_artifact(
            "churn", training_rows=int(X.shape[0]), game_id=args.game_id,
            metrics=result["metrics"], extra=churn_extra(result), **common,
        )
    if name == "risk":
        if args.source == "synthetic":
            X, y = synthetic_risk(args.n_subjects, seed=args.seed + 1)
        else:
            from .data_io import ClickHouseClient, load_risk_rows

            client = ClickHouseClient(args.clickhouse_url)
            data = load_risk_rows(client, args.game_id, args.environment)
            frame = risk_feature_frame(data["feature_rows"])
            X = frame.to_numpy()
            y = np.asarray(data["labels"], dtype=int)
        result = train_risk(X, y, seed=args.seed + 1)
        return build_artifact(
            "risk", training_rows=int(X.shape[0]), game_id=args.game_id,
            metrics=result["metrics"], extra=risk_extra(result), **common,
        )
    if name == "propensity":
        if args.source == "synthetic":
            X, y = synthetic_propensity(args.n_users, seed=args.seed + 3)
        else:
            from .data_io import ClickHouseClient, load_propensity_rows

            client = ClickHouseClient(args.clickhouse_url)
            data = load_propensity_rows(client, args.game_id, args.environment, args.snapshot)
            frame = propensity_feature_frame(data["feature_rows"])
            X = frame.to_numpy()
            y = np.asarray(data["labels"], dtype=int)
        result = train_propensity(X, y, seed=args.seed + 3)
        return build_artifact(
            "propensity", training_rows=int(X.shape[0]), game_id=args.game_id,
            metrics=result["metrics"], extra=propensity_extra(result), **common,
        )
    # pltv
    if args.source == "synthetic":
        ltv_rows, cohort_rows = synthetic_pltv(args.n_cohorts, seed=args.seed + 2)
    else:
        from .data_io import ClickHouseClient, load_pltv_rows

        client = ClickHouseClient(args.clickhouse_url)
        data = load_pltv_rows(client, args.game_id, args.environment)
        ltv_rows, cohort_rows = data["ltv_rows"], data["cohort_rows"]
    result = train_pltv(ltv_rows, cohort_rows, today=SYNTHETIC_TODAY if args.source == "synthetic" else date.today())
    return build_artifact(
        "pltv", training_rows=result["based_on_cohorts"], game_id=args.game_id,
        metrics={
            "holdout_mape_trained": result["holdout_metrics_trained"]["mape"],
            "holdout_mae_trained": result["holdout_metrics_trained"]["mae"],
            "holdout_mape_baseline": result["holdout_metrics_baseline"]["mape"],
        },
        extra=pltv_extra(result), **common,
    )


def _print_report(name: str, artifact: dict, path: Path) -> None:
    m = artifact["metrics"]
    print(f"[{name}] 训练完成 → {path}")
    for key, value in m.items():
        print(f"    {key} = {value}")
    baseline = artifact.get("heuristic_baseline")
    if baseline and "auc" in baseline and "auc" in m:
        gain = artifact.get("auc_gain_vs_heuristic")
        print(f"    启发式基线 AUC = {baseline['auc']}，增益 = {gain}")
    if baseline and "mape" in baseline and "holdout_mape_trained" in m:
        print(f"    启发式基线 MAPE = {baseline['mape']}")


def run_validate(args: argparse.Namespace) -> int:
    exit_code = 0
    for p in args.paths:
        try:
            artifact = load_artifact(p)  # load 内含 validate
            print(f"[ok] {p}  model={artifact['model_type']} version={artifact['model_version']}")
        except Exception as e:  # noqa: BLE001
            print(f"[fail] {p}: {e}", file=sys.stderr)
            exit_code = 1
    return exit_code


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "train":
        return run_train(args)
    if args.command == "validate":
        return run_validate(args)
    return 2  # argparse required=True 已拦截，防御兜底


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
