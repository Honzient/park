from __future__ import annotations

import argparse
import csv
import json
import os
import statistics
import subprocess
import sys
from pathlib import Path
from typing import Any

from core.runtime_env import bootstrap_runtime_env


AI_ROOT = Path(__file__).resolve().parent
PROJECT_ROOT = AI_ROOT.parent
DEFAULT_RECORD_ROOT = PROJECT_ROOT / "project_archive" / "moved_dirs" / "plate_thesis_records"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run multi-seed formal training repeats for CCPD mixed plate detection."
    )
    parser.add_argument(
        "--dataset-yaml",
        default=r"D:\ccpd\ccpd_mixed_yolo_split\dataset.yaml",
        help="Dataset YAML path.",
    )
    parser.add_argument(
        "--record-root",
        default=str(DEFAULT_RECORD_ROOT),
        help="Root experiment directory.",
    )
    parser.add_argument(
        "--multiseed-dirname",
        default="multiseed",
        help="Subdirectory created under record root for multi-seed artifacts.",
    )
    parser.add_argument(
        "--python-exe",
        default=sys.executable,
        help="CUDA-enabled Python interpreter.",
    )
    parser.add_argument("--device", default="0", help="YOLO device argument.")
    parser.add_argument("--workers", type=int, default=4, help="Dataloader workers.")
    parser.add_argument("--seeds", nargs="+", type=int, default=[42, 3407, 2026], help="Seed list.")
    parser.add_argument(
        "--base-train-report",
        default=str(DEFAULT_RECORD_ROOT / "reports" / "training" / "final_ab3_plate960_coslr_f20_full_e20.json"),
        help="Existing formal training report used as the reference seed.",
    )
    parser.add_argument(
        "--base-eval-report",
        default=str(DEFAULT_RECORD_ROOT / "reports" / "eval" / "final_ab3_plate960_coslr_f20_full_e20_test_eval.json"),
        help="Existing formal test evaluation report used as the reference seed.",
    )
    parser.add_argument("--skip-existing", action="store_true", help="Reuse finished reports when present.")
    return parser.parse_args()


def ensure_dirs(root: Path) -> dict[str, Path]:
    paths = {
        "root": root,
        "plans": root / "plans",
        "logs": root / "logs",
        "reports_train": root / "reports" / "training",
        "reports_eval": root / "reports" / "eval",
        "summaries": root / "summaries",
        "runs": root / "yolo_runs",
    }
    for path in paths.values():
        path.mkdir(parents=True, exist_ok=True)
    return paths


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def save_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def save_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def run_and_tee(command: list[str], cwd: Path, log_path: Path, env: dict[str, str]) -> None:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    print(f"[cmd] {' '.join(command)}")
    with log_path.open("w", encoding="utf-8") as log_file:
        process = subprocess.Popen(
            command,
            cwd=str(cwd),
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )
        assert process.stdout is not None
        for line in process.stdout:
            stdout_encoding = sys.stdout.encoding or "utf-8"
            sys.stdout.buffer.write(line.encode(stdout_encoding, errors="replace"))
            sys.stdout.flush()
            log_file.write(line)
        return_code = process.wait()
    if return_code != 0:
        raise RuntimeError(f"command failed with exit code {return_code}: {' '.join(command)}")


def format_train_arg(value: Any) -> str:
    if isinstance(value, bool):
        return json.dumps(value)
    if value is None:
        return "null"
    if isinstance(value, (list, dict)):
        return json.dumps(value, ensure_ascii=False)
    return str(value)


def build_train_command(
    args: argparse.Namespace,
    config: dict[str, Any],
    paths: dict[str, Path],
    experiment_name: str,
    seed: int,
    report_path: Path,
) -> list[str]:
    command = [
        args.python_exe,
        str((AI_ROOT / "train_yolo.py").resolve()),
        "--dataset-yaml",
        str(Path(args.dataset_yaml).resolve()),
        "--base-model",
        str(config["base_model"]),
        "--epochs",
        str(int(config["epochs"])),
        "--imgsz",
        str(int(config["imgsz"])),
        "--batch",
        str(int(config["batch"])),
        "--device",
        args.device,
        "--workers",
        str(int(args.workers)),
        "--patience",
        str(int(config["patience"])),
        "--seed",
        str(int(seed)),
        "--project",
        str(paths["runs"].resolve()),
        "--name",
        experiment_name,
        "--notes",
        f"multi-seed formal retraining for {config['base_experiment']} with seed={seed}",
        "--save-report",
        str(report_path.resolve()),
        "--tag",
        "ccpd_mixed",
        "--tag",
        "final",
        "--tag",
        "multi_seed",
        "--tag",
        f"seed_{seed}",
    ]
    for key, value in config["extra_train_args"].items():
        command.extend(["--train-arg", f"{key}={format_train_arg(value)}"])
    return command


def build_eval_command(
    args: argparse.Namespace,
    weights_path: Path,
    experiment_name: str,
    imgsz: int,
    batch: int,
    report_path: Path,
) -> list[str]:
    return [
        args.python_exe,
        str((AI_ROOT / "evaluate_yolo.py").resolve()),
        "--model",
        str(weights_path.resolve()),
        "--dataset-yaml",
        str(Path(args.dataset_yaml).resolve()),
        "--split",
        "test",
        "--imgsz",
        str(int(imgsz)),
        "--batch",
        str(int(batch)),
        "--device",
        args.device,
        "--workers",
        str(int(args.workers)),
        "--tag",
        "ccpd_mixed",
        "--tag",
        experiment_name,
        "--notes",
        f"standalone test evaluation for {experiment_name}",
        "--save-report",
        str(report_path.resolve()),
    ]


def build_recovered_train_report(
    base_train_payload: dict[str, Any],
    args: argparse.Namespace,
    config: dict[str, Any],
    paths: dict[str, Path],
    experiment_name: str,
    seed: int,
    report_path: Path,
) -> dict[str, Any]:
    run_dir = paths["runs"] / experiment_name
    weights_dir = run_dir / "weights"
    best_weights = weights_dir / "best.pt"
    last_weights = weights_dir / "last.pt"
    payload = json.loads(json.dumps(base_train_payload, ensure_ascii=False))
    payload["created_at"] = datetime_now_iso()
    payload["experiment"] = {
        "tags": ["ccpd_mixed", "final", "multi_seed", f"seed_{seed}"],
        "notes": f"recovered multi-seed formal retraining record for {config['base_experiment']} with seed={seed}",
    }
    payload["cli"] = {
        "argv": [
            str((AI_ROOT / "run_plate_multiseed.py").resolve()),
            "--record-root",
            str(Path(args.record_root).resolve()),
            "--seed",
            str(seed),
            "--recover-existing-run",
        ],
        "train_arg_items": [f"{key}={format_train_arg(value)}" for key, value in config["extra_train_args"].items()],
    }
    payload["training"] = {
        "epochs": int(config["epochs"]),
        "imgsz": int(config["imgsz"]),
        "batch": int(config["batch"]),
        "device": args.device or "auto",
        "workers": int(args.workers),
        "patience": int(config["patience"]),
        "seed": int(seed),
        "project": str(paths["runs"]),
        "name": experiment_name,
        "run_dir": str(run_dir),
        "best_weights": str(best_weights) if best_weights.is_file() else "",
        "last_weights": str(last_weights) if last_weights.is_file() else "",
        "eval_weights": str(best_weights if best_weights.is_file() else last_weights),
        "extra_train_args": config["extra_train_args"],
        "resolved_train_kwargs": {
            "data": str(Path(args.dataset_yaml).resolve()),
            "epochs": int(config["epochs"]),
            "imgsz": int(config["imgsz"]),
            "batch": int(config["batch"]),
            "workers": int(args.workers),
            "patience": int(config["patience"]),
            "seed": int(seed),
            "project": str(paths["runs"]),
            "name": experiment_name,
            "device": args.device,
            **config["extra_train_args"],
        },
    }
    payload["metrics"] = {"val": {}, "test": {}}
    payload["raw_train_result"] = "recovered from existing run directory"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return payload


def datetime_now_iso() -> str:
    from datetime import datetime

    return datetime.now().isoformat(timespec="seconds")


def extract_row(seed: int, source: str, train_payload: dict[str, Any], eval_payload: dict[str, Any]) -> dict[str, Any]:
    training = train_payload.get("training", {})
    metrics = eval_payload.get("metrics", {})
    speed = metrics.get("speed", {})
    return {
        "seed": int(seed),
        "source": source,
        "experiment": str(training.get("name", "")),
        "base_model": str(train_payload.get("base_model", "")),
        "epochs": int(training.get("epochs", 0) or 0),
        "imgsz": int(training.get("imgsz", 0) or 0),
        "batch": int(training.get("batch", 0) or 0),
        "precision": float(metrics.get("box_mp", 0.0) or 0.0),
        "recall": float(metrics.get("box_mr", 0.0) or 0.0),
        "map50": float(metrics.get("box_map50", 0.0) or 0.0),
        "map50_95": float(metrics.get("box_map", 0.0) or 0.0),
        "inference_ms": float(speed.get("inference", 0.0) or 0.0),
        "best_weights": str(training.get("best_weights", "")),
        "run_dir": str(training.get("run_dir", "")),
        "train_report": str(train_payload.get("_report_path", "")),
        "eval_report": str(eval_payload.get("_report_path", "")),
    }


def summarize_rows(paths: dict[str, Path], rows: list[dict[str, Any]]) -> dict[str, Any]:
    rows = sorted(rows, key=lambda item: item["seed"])
    save_json(paths["summaries"] / "seed_stability_runs.json", rows)

    headers = [
        "seed",
        "source",
        "experiment",
        "base_model",
        "epochs",
        "imgsz",
        "batch",
        "precision",
        "recall",
        "map50",
        "map50_95",
        "inference_ms",
        "best_weights",
        "run_dir",
        "train_report",
        "eval_report",
    ]
    csv_path = paths["summaries"] / "seed_stability_runs.csv"
    with csv_path.open("w", encoding="utf-8", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=headers)
        writer.writeheader()
        writer.writerows(rows)

    metrics = ["precision", "recall", "map50", "map50_95", "inference_ms"]
    stats: dict[str, Any] = {
        "num_seeds": len(rows),
        "seeds": [int(item["seed"]) for item in rows],
        "metrics": {},
        "best_run": max(rows, key=lambda item: float(item["map50_95"])),
        "worst_run": min(rows, key=lambda item: float(item["map50_95"])),
    }
    for metric in metrics:
        values = [float(item[metric]) for item in rows]
        stats["metrics"][metric] = {
            "mean": statistics.mean(values),
            "std": statistics.stdev(values) if len(values) > 1 else 0.0,
            "min": min(values),
            "max": max(values),
        }
    save_json(paths["summaries"] / "seed_stability_stats.json", stats)

    md_lines = [
        "# Multi-Seed Final Summary",
        "",
        "| Seed | Source | Experiment | mAP50-95 | Recall | Precision | Inference ms |",
        "| ---: | --- | --- | ---: | ---: | ---: | ---: |",
    ]
    for row in rows:
        md_lines.append(
            "| "
            + " | ".join(
                [
                    str(row["seed"]),
                    str(row["source"]),
                    row["experiment"],
                    f"{row['map50_95']:.4f}",
                    f"{row['recall']:.4f}",
                    f"{row['precision']:.4f}",
                    f"{row['inference_ms']:.3f}",
                ]
            )
            + " |"
        )
    md_lines.extend(
        [
            "",
            "## Aggregate Statistics",
            "",
            f"- Seeds: `{', '.join(str(seed) for seed in stats['seeds'])}`",
            f"- mAP50-95: `{stats['metrics']['map50_95']['mean']:.4f} +/- {stats['metrics']['map50_95']['std']:.4f}`",
            f"- Precision: `{stats['metrics']['precision']['mean']:.4f} +/- {stats['metrics']['precision']['std']:.4f}`",
            f"- Recall: `{stats['metrics']['recall']['mean']:.4f} +/- {stats['metrics']['recall']['std']:.4f}`",
            f"- Inference: `{stats['metrics']['inference_ms']['mean']:.3f} +/- {stats['metrics']['inference_ms']['std']:.3f} ms/image`",
            "",
            f"- Best seed: `{stats['best_run']['seed']}` (`mAP50-95={stats['best_run']['map50_95']:.4f}`)",
            f"- Worst seed: `{stats['worst_run']['seed']}` (`mAP50-95={stats['worst_run']['map50_95']:.4f}`)",
        ]
    )
    save_text(paths["summaries"] / "seed_stability_summary.md", "\n".join(md_lines) + "\n")
    return stats


def write_plan(paths: dict[str, Path], args: argparse.Namespace, config: dict[str, Any]) -> None:
    content = f"""# Multi-Seed Final Plan

- Dataset: `{Path(args.dataset_yaml).resolve()}`
- Record root: `{paths['root']}`
- Python: `{args.python_exe}`
- Device: `{args.device}`
- Seeds: `{', '.join(str(seed) for seed in args.seeds)}`

## Base Formal Configuration

- Reference experiment: `{config['base_experiment']}`
- Base model: `{config['base_model']}`
- Epochs: `{config['epochs']}`
- Image size: `{config['imgsz']}`
- Batch: `{config['batch']}`
- Patience: `{config['patience']}`
- Extra train args: `{json.dumps(config['extra_train_args'], ensure_ascii=False)}`

## Rule

- Reuse existing `seed={config['base_seed']}` final run.
- Train remaining seeds with the exact same final configuration.
- Aggregate mean and standard deviation for thesis reporting.
"""
    save_text(paths["plans"] / "multiseed_plan.md", content)


def main() -> int:
    args = parse_args()
    bootstrap_runtime_env(AI_ROOT)
    record_root = Path(args.record_root).expanduser().resolve()
    multiseed_root = record_root / args.multiseed_dirname
    paths = ensure_dirs(multiseed_root)

    env = os.environ.copy()
    env["PYTHONUTF8"] = "1"

    base_train_report_path = Path(args.base_train_report).expanduser().resolve()
    base_eval_report_path = Path(args.base_eval_report).expanduser().resolve()
    if not base_train_report_path.is_file():
        raise RuntimeError(f"base train report not found: {base_train_report_path}")
    if not base_eval_report_path.is_file():
        raise RuntimeError(f"base eval report not found: {base_eval_report_path}")

    base_train = load_json(base_train_report_path)
    base_eval = load_json(base_eval_report_path)
    base_train["_report_path"] = str(base_train_report_path)
    base_eval["_report_path"] = str(base_eval_report_path)

    training = base_train.get("training", {})
    config = {
        "base_experiment": str(training.get("name", "")),
        "base_model": str(base_train.get("base_model", "")),
        "epochs": int(training.get("epochs", 0) or 0),
        "imgsz": int(training.get("imgsz", 0) or 0),
        "batch": int(training.get("batch", 0) or 0),
        "patience": int(training.get("patience", 0) or 0),
        "base_seed": int(training.get("seed", 42) or 42),
        "extra_train_args": dict(training.get("extra_train_args", {})),
    }
    write_plan(paths, args, config)

    rows: list[dict[str, Any]] = []
    for seed in args.seeds:
        if int(seed) == int(config["base_seed"]):
            rows.append(extract_row(seed=int(seed), source="existing_final_run", train_payload=base_train, eval_payload=base_eval))
            continue

        experiment_name = f"{config['base_experiment']}_seed{int(seed)}"
        train_report_path = paths["reports_train"] / f"{experiment_name}.json"
        eval_report_path = paths["reports_eval"] / f"{experiment_name}_test_eval.json"
        train_log = paths["logs"] / f"{experiment_name}.train.log"
        eval_log = paths["logs"] / f"{experiment_name}.eval.log"
        run_dir = paths["runs"] / experiment_name
        best_weights = run_dir / "weights" / "best.pt"

        if not train_report_path.is_file() and best_weights.is_file():
            build_recovered_train_report(
                base_train_payload=base_train,
                args=args,
                config=config,
                paths=paths,
                experiment_name=experiment_name,
                seed=int(seed),
                report_path=train_report_path,
            )

        if not (args.skip_existing and train_report_path.is_file() and eval_report_path.is_file()):
            train_command = build_train_command(
                args=args,
                config=config,
                paths=paths,
                experiment_name=experiment_name,
                seed=int(seed),
                report_path=train_report_path,
            )
            if not train_report_path.is_file():
                run_and_tee(train_command, cwd=AI_ROOT, log_path=train_log, env=env)

            train_payload = load_json(train_report_path)
            best_weights = Path(str(train_payload.get("training", {}).get("best_weights", ""))).expanduser()
            if not best_weights.is_file():
                raise RuntimeError(f"best weights not found for seed {seed}: {best_weights}")

            eval_command = build_eval_command(
                args=args,
                weights_path=best_weights,
                experiment_name=experiment_name,
                imgsz=config["imgsz"],
                batch=config["batch"],
                report_path=eval_report_path,
            )
            if not eval_report_path.is_file():
                run_and_tee(eval_command, cwd=AI_ROOT, log_path=eval_log, env=env)

        train_payload = load_json(train_report_path)
        eval_payload = load_json(eval_report_path)
        train_payload["_report_path"] = str(train_report_path)
        eval_payload["_report_path"] = str(eval_report_path)
        rows.append(extract_row(seed=int(seed), source="retrained", train_payload=train_payload, eval_payload=eval_payload))

    stats = summarize_rows(paths, rows)
    print(json.dumps({"multiseed_root": str(multiseed_root), "stats": stats}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
