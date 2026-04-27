from __future__ import annotations

import argparse
import csv
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any

from core.runtime_env import bootstrap_runtime_env


AI_ROOT = Path(__file__).resolve().parent
PROJECT_ROOT = AI_ROOT.parent
DEFAULT_RECORD_ROOT = PROJECT_ROOT / "project_archive" / "moved_dirs" / "plate_thesis_records"
DEFAULT_GENERIC_BASE_MODEL = AI_ROOT / "models" / "yolo11n.pt"
DEFAULT_PLATE_BASE_MODEL = AI_ROOT / "models" / "yolo11n_plate.pt"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run a paper-friendly GPU ablation suite for CCPD mixed plate detection"
    )
    parser.add_argument(
        "--dataset-yaml",
        default=r"D:\ccpd\ccpd_mixed_yolo_split\dataset.yaml",
        help="Dataset YAML path",
    )
    parser.add_argument(
        "--record-root",
        default=str(DEFAULT_RECORD_ROOT),
        help="Root directory used to store paper records, logs, reports, and YOLO runs",
    )
    parser.add_argument(
        "--python-exe",
        default=sys.executable,
        help="Python interpreter to use. Use a CUDA-enabled interpreter for GPU training.",
    )
    parser.add_argument("--device", default="0", help="YOLO device argument, e.g. 0")
    parser.add_argument("--phase", choices=["setup", "ablation", "final", "all"], default="all")
    parser.add_argument(
        "--workers",
        type=int,
        default=0,
        help="Dataloader workers. Default 0 is sandbox-safe on Windows.",
    )
    parser.add_argument("--ablation-epochs", type=int, default=8)
    parser.add_argument("--ablation-fraction", type=float, default=0.2)
    parser.add_argument("--final-epochs", type=int, default=20)
    parser.add_argument("--final-patience", type=int, default=8)
    parser.add_argument("--skip-existing", action="store_true", help="Reuse existing reports if present")
    parser.add_argument(
        "--generic-base-model",
        default=str(DEFAULT_GENERIC_BASE_MODEL.resolve()),
        help="Generic pretrained base model",
    )
    parser.add_argument(
        "--plate-base-model",
        default=str(DEFAULT_PLATE_BASE_MODEL.resolve()),
        help="Plate-specific pretrained base model",
    )
    return parser.parse_args()


def ensure_dirs(record_root: Path) -> dict[str, Path]:
    paths = {
        "root": record_root,
        "meta": record_root / "meta",
        "plans": record_root / "plans",
        "logs": record_root / "logs",
        "training_reports": record_root / "reports" / "training",
        "eval_reports": record_root / "reports" / "eval",
        "summaries": record_root / "summaries",
        "runs": record_root / "yolo_runs",
    }
    for path in paths.values():
        path.mkdir(parents=True, exist_ok=True)
    return paths


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


def capture_command_output(command: list[str], cwd: Path, env: dict[str, str]) -> str:
    completed = subprocess.run(
        command,
        cwd=str(cwd),
        env=env,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            f"command failed with exit code {completed.returncode}: {' '.join(command)}\n{completed.stdout}\n{completed.stderr}"
        )
    return completed.stdout


def save_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def save_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def verify_gpu(env: dict[str, str], python_exe: str, meta_dir: Path) -> None:
    probe = capture_command_output(
        [
            python_exe,
            "-c",
            (
                "import json,torch; "
                "payload={"
                "'python':__import__('sys').version,"
                "'torch':getattr(torch,'__version__','unknown'),"
                "'cuda_available':bool(torch.cuda.is_available()),"
                "'device_count':int(torch.cuda.device_count()),"
                "'device_name':(torch.cuda.get_device_name(0) if torch.cuda.is_available() else '')"
                "}; print(json.dumps(payload, ensure_ascii=False))"
            ),
        ],
        cwd=AI_ROOT,
        env=env,
    ).strip()
    save_text(meta_dir / "python_gpu_probe.json", probe + "\n")
    payload = json.loads(probe)
    if not payload.get("cuda_available"):
        raise RuntimeError(
            f"Selected interpreter is not CUDA-enabled: {python_exe}. "
            "Please rerun with a GPU Python, for example D:\\Python\\python.exe."
        )


def capture_meta(args: argparse.Namespace, paths: dict[str, Path], env: dict[str, str]) -> None:
    dataset_yaml = Path(args.dataset_yaml).resolve()
    split_summary = dataset_yaml.parent / "split_summary.json"
    if dataset_yaml.is_file():
        shutil.copy2(dataset_yaml, paths["meta"] / "dataset.yaml")
    if split_summary.is_file():
        shutil.copy2(split_summary, paths["meta"] / "split_summary.json")

    nvidia_smi = subprocess.run(
        ["nvidia-smi"],
        cwd=str(AI_ROOT),
        env=env,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    save_text(paths["meta"] / "nvidia_smi.txt", (nvidia_smi.stdout or "") + (nvidia_smi.stderr or ""))

    python_path = capture_command_output([args.python_exe, "-c", "import sys; print(sys.executable)"], AI_ROOT, env)
    save_text(paths["meta"] / "python_executable.txt", python_path)

    verify_gpu(env=env, python_exe=args.python_exe, meta_dir=paths["meta"])


def write_plan(args: argparse.Namespace, paths: dict[str, Path]) -> None:
    plan = f"""# Plate Thesis Experiment Plan

## Scope

- Dataset: `{Path(args.dataset_yaml).resolve()}`
- Record root: `{paths['root']}`
- GPU interpreter: `{args.python_exe}`
- Device: `{args.device}`

## Assumption

- The provided `ccpd_mixed_yolo_split` dataset is a YOLO single-class detection dataset.
- This suite therefore optimizes the plate detector stage.
- Full character-string OCR recognition should be treated as a follow-up stage using the CCPD filename labels as text supervision.

## Ablation Design

1. `ab0_generic640_default_f20`
   - Generic COCO `models/yolo11n.pt`
   - `imgsz=640`, default scheduler
2. `ab1_generic640_coslr_f20`
   - Generic COCO `models/yolo11n.pt`
   - `imgsz=640`, `cos_lr=true`
3. `ab2_plate640_coslr_f20`
   - Plate-specific `models/yolo11n_plate.pt`
   - `imgsz=640`, `cos_lr=true`
4. `ab3_plate960_coslr_f20`
   - Plate-specific `models/yolo11n_plate.pt`
   - `imgsz=960`, `cos_lr=true`
5. `ab4_plate960_coslr_lowaug_f20`
   - Plate-specific `models/yolo11n_plate.pt`
   - `imgsz=960`, `cos_lr=true`
   - Reduced augmentation tuned for plate geometry

All ablations use `fraction={args.ablation_fraction}`, `epochs={args.ablation_epochs}`, and GPU training.

## Final Training Rule

- Select the ablation with the highest test `mAP50-95`.
- Retrain from the same initialization on the full training split with `fraction=1.0`.
- Run `{args.final_epochs}` epochs with patience `{args.final_patience}`.
"""
    save_text(paths["plans"] / "experiment_plan.md", plan)


def build_ablation_experiments(args: argparse.Namespace) -> list[dict[str, Any]]:
    fraction_arg = f"fraction={args.ablation_fraction}"
    return [
        {
            "name": "ab0_generic640_default_f20",
            "base_model": str(Path(args.generic_base_model).resolve()),
            "epochs": args.ablation_epochs,
            "imgsz": 640,
            "batch": 8,
            "tags": ["ccpd_mixed", "ablation", "generic", "default"],
            "notes": "generic yolo11n baseline on mixed CCPD, imgsz=640, default scheduler",
            "train_args": [fraction_arg],
        },
        {
            "name": "ab1_generic640_coslr_f20",
            "base_model": str(Path(args.generic_base_model).resolve()),
            "epochs": args.ablation_epochs,
            "imgsz": 640,
            "batch": 8,
            "tags": ["ccpd_mixed", "ablation", "generic", "coslr"],
            "notes": "generic yolo11n with cosine LR, imgsz=640",
            "train_args": [fraction_arg, "cos_lr=true"],
        },
        {
            "name": "ab2_plate640_coslr_f20",
            "base_model": str(Path(args.plate_base_model).resolve()),
            "epochs": args.ablation_epochs,
            "imgsz": 640,
            "batch": 8,
            "tags": ["ccpd_mixed", "ablation", "plate_init", "coslr"],
            "notes": "plate-specific initialization with cosine LR, imgsz=640",
            "train_args": [fraction_arg, "cos_lr=true"],
        },
        {
            "name": "ab3_plate960_coslr_f20",
            "base_model": str(Path(args.plate_base_model).resolve()),
            "epochs": args.ablation_epochs,
            "imgsz": 960,
            "batch": 4,
            "tags": ["ccpd_mixed", "ablation", "plate_init", "coslr", "img960"],
            "notes": "plate-specific initialization with cosine LR, imgsz=960",
            "train_args": [fraction_arg, "cos_lr=true"],
        },
        {
            "name": "ab4_plate960_coslr_lowaug_f20",
            "base_model": str(Path(args.plate_base_model).resolve()),
            "epochs": args.ablation_epochs,
            "imgsz": 960,
            "batch": 4,
            "tags": ["ccpd_mixed", "ablation", "plate_init", "coslr", "img960", "lowaug"],
            "notes": "plate-specific initialization with cosine LR and reduced geometry/color augmentation",
            "train_args": [
                fraction_arg,
                "cos_lr=true",
                "fliplr=0.0",
                "translate=0.05",
                "scale=0.30",
                "mosaic=0.50",
                "close_mosaic=5",
                "hsv_s=0.40",
                "hsv_v=0.20",
            ],
        },
    ]


def build_train_command(
    args: argparse.Namespace,
    paths: dict[str, Path],
    experiment: dict[str, Any],
    report_path: Path,
) -> list[str]:
    command = [
        args.python_exe,
        str((AI_ROOT / "train_yolo.py").resolve()),
        "--dataset-yaml",
        str(Path(args.dataset_yaml).resolve()),
        "--base-model",
        experiment["base_model"],
        "--epochs",
        str(experiment["epochs"]),
        "--imgsz",
        str(experiment["imgsz"]),
        "--batch",
        str(experiment["batch"]),
        "--device",
        args.device,
        "--workers",
        str(args.workers),
        "--project",
        str(paths["runs"].resolve()),
        "--name",
        experiment["name"],
        "--notes",
        experiment["notes"],
        "--save-report",
        str(report_path.resolve()),
    ]
    for tag in experiment["tags"]:
        command.extend(["--tag", tag])
    for item in experiment["train_args"]:
        command.extend(["--train-arg", item])
    if "patience" in experiment:
        command.extend(["--patience", str(experiment["patience"])])
    return command


def build_eval_command(
    args: argparse.Namespace,
    best_weights: Path,
    experiment_name: str,
    notes: str,
    eval_report_path: Path,
) -> list[str]:
    return [
        args.python_exe,
        str((AI_ROOT / "evaluate_yolo.py").resolve()),
        "--model",
        str(best_weights.resolve()),
        "--dataset-yaml",
        str(Path(args.dataset_yaml).resolve()),
        "--split",
        "test",
        "--imgsz",
        "960",
        "--batch",
        "4",
        "--device",
        args.device,
        "--workers",
        str(args.workers),
        "--tag",
        "ccpd_mixed",
        "--tag",
        experiment_name,
        "--notes",
        notes,
        "--save-report",
        str(eval_report_path.resolve()),
    ]


def experiment_outputs(paths: dict[str, Path], experiment_name: str) -> dict[str, Path]:
    return {
        "train_report": paths["training_reports"] / f"{experiment_name}.json",
        "eval_report": paths["eval_reports"] / f"{experiment_name}_test_eval.json",
        "train_log": paths["logs"] / f"{experiment_name}.train.log",
        "eval_log": paths["logs"] / f"{experiment_name}.eval.log",
    }


def should_skip(skip_existing: bool, train_report: Path, eval_report: Path) -> bool:
    return skip_existing and train_report.is_file() and eval_report.is_file()


def run_single_experiment(
    args: argparse.Namespace,
    paths: dict[str, Path],
    env: dict[str, str],
    experiment: dict[str, Any],
) -> dict[str, Any]:
    outputs = experiment_outputs(paths=paths, experiment_name=experiment["name"])
    if should_skip(args.skip_existing, outputs["train_report"], outputs["eval_report"]):
        print(f"[skip] {experiment['name']} already has training and evaluation reports")
        return {
            "name": experiment["name"],
            "train_report": str(outputs["train_report"]),
            "eval_report": str(outputs["eval_report"]),
            "skipped": True,
        }

    train_command = build_train_command(args=args, paths=paths, experiment=experiment, report_path=outputs["train_report"])
    run_and_tee(command=train_command, cwd=AI_ROOT, log_path=outputs["train_log"], env=env)

    train_payload = load_json(outputs["train_report"])
    best_weights = Path(str(train_payload.get("training", {}).get("best_weights", ""))).expanduser()
    if not best_weights.is_file():
        raise RuntimeError(f"best weights not found for {experiment['name']}: {best_weights}")

    eval_command = build_eval_command(
        args=args,
        best_weights=best_weights,
        experiment_name=experiment["name"],
        notes=f"standalone test evaluation for {experiment['name']}",
        eval_report_path=outputs["eval_report"],
    )
    run_and_tee(command=eval_command, cwd=AI_ROOT, log_path=outputs["eval_log"], env=env)

    return {
        "name": experiment["name"],
        "train_report": str(outputs["train_report"]),
        "eval_report": str(outputs["eval_report"]),
        "skipped": False,
    }


def make_row(train_payload: dict[str, Any], eval_payload: dict[str, Any]) -> dict[str, Any]:
    training = train_payload.get("training", {})
    extra = training.get("extra_train_args", {})
    metrics = eval_payload.get("metrics", {})
    speed = metrics.get("speed", {})
    return {
        "experiment": training.get("name", ""),
        "base_model": train_payload.get("base_model", ""),
        "epochs": int(training.get("epochs", 0) or 0),
        "imgsz": int(training.get("imgsz", 0) or 0),
        "batch": int(training.get("batch", 0) or 0),
        "fraction": float(extra.get("fraction", 1.0) or 1.0),
        "cos_lr": bool(extra.get("cos_lr", False)),
        "fliplr": float(extra.get("fliplr", 0.5) or 0.0) if "fliplr" in extra else "",
        "translate": float(extra.get("translate", 0.1) or 0.0) if "translate" in extra else "",
        "scale": float(extra.get("scale", 0.5) or 0.0) if "scale" in extra else "",
        "mosaic": float(extra.get("mosaic", 1.0) or 0.0) if "mosaic" in extra else "",
        "precision": float(metrics.get("box_mp", 0.0) or 0.0),
        "recall": float(metrics.get("box_mr", 0.0) or 0.0),
        "map50": float(metrics.get("box_map50", 0.0) or 0.0),
        "map50_95": float(metrics.get("box_map", 0.0) or 0.0),
        "inference_ms": float(speed.get("inference", 0.0) or 0.0),
        "notes": train_payload.get("experiment", {}).get("notes", ""),
        "best_weights": training.get("best_weights", ""),
        "run_dir": training.get("run_dir", ""),
        "train_report": "",
        "eval_report": "",
    }


def summarize_reports(paths: dict[str, Path], summary_prefix: str) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for train_report in sorted(paths["training_reports"].glob("*.json")):
        eval_report = paths["eval_reports"] / f"{train_report.stem}_test_eval.json"
        if not eval_report.is_file():
            continue
        train_payload = load_json(train_report)
        eval_payload = load_json(eval_report)
        row = make_row(train_payload=train_payload, eval_payload=eval_payload)
        row["train_report"] = str(train_report.resolve())
        row["eval_report"] = str(eval_report.resolve())
        rows.append(row)

    rows.sort(key=lambda item: item["experiment"])
    save_json(paths["summaries"] / f"{summary_prefix}.json", rows)

    csv_path = paths["summaries"] / f"{summary_prefix}.csv"
    headers = [
        "experiment",
        "base_model",
        "epochs",
        "imgsz",
        "batch",
        "fraction",
        "cos_lr",
        "fliplr",
        "translate",
        "scale",
        "mosaic",
        "precision",
        "recall",
        "map50",
        "map50_95",
        "inference_ms",
        "notes",
        "best_weights",
        "run_dir",
        "train_report",
        "eval_report",
    ]
    with csv_path.open("w", encoding="utf-8", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=headers)
        writer.writeheader()
        writer.writerows(rows)

    lines = [
        f"# {summary_prefix.replace('_', ' ').title()}",
        "",
        "| Experiment | Base | Img | Batch | Fraction | cos_lr | mAP50-95 | Recall | Inference ms |",
        "| --- | --- | ---: | ---: | ---: | --- | ---: | ---: | ---: |",
    ]
    for row in rows:
        base_name = Path(str(row["base_model"])).name
        lines.append(
            "| "
            + " | ".join(
                [
                    row["experiment"],
                    base_name,
                    str(row["imgsz"]),
                    str(row["batch"]),
                    f"{row['fraction']:.2f}",
                    str(row["cos_lr"]),
                    f"{row['map50_95']:.4f}",
                    f"{row['recall']:.4f}",
                    f"{row['inference_ms']:.3f}",
                ]
            )
            + " |"
        )
    save_text(paths["summaries"] / f"{summary_prefix}.md", "\n".join(lines) + "\n")
    return rows


def pick_best_ablation(rows: list[dict[str, Any]]) -> dict[str, Any]:
    ablations = [row for row in rows if row["experiment"].startswith("ab")]
    if not ablations:
        raise RuntimeError("No ablation rows found to select a final configuration")
    ablations.sort(key=lambda row: (row["map50_95"], row["map50"], row["recall"]), reverse=True)
    return ablations[0]


def build_final_experiment(args: argparse.Namespace, best_row: dict[str, Any]) -> dict[str, Any]:
    train_payload = load_json(Path(best_row["train_report"]))
    training = train_payload.get("training", {})
    extra = dict(training.get("extra_train_args", {}))
    extra["fraction"] = 1.0

    train_args = [f"{key}={json.dumps(value)}" if isinstance(value, bool) else f"{key}={value}" for key, value in extra.items()]
    return {
        "name": f"final_{best_row['experiment']}_full_e{args.final_epochs}",
        "base_model": train_payload.get("base_model", ""),
        "epochs": args.final_epochs,
        "imgsz": int(training.get("imgsz", 640) or 640),
        "batch": int(training.get("batch", 8) or 8),
        "patience": args.final_patience,
        "tags": ["ccpd_mixed", "final", "paper_candidate", best_row["experiment"]],
        "notes": (
            f"final full-data retraining from best ablation {best_row['experiment']} "
            f"(selected by test mAP50-95={best_row['map50_95']:.4f})"
        ),
        "train_args": train_args,
    }


def write_selection_note(paths: dict[str, Path], best_row: dict[str, Any], final_experiment: dict[str, Any]) -> None:
    note = {
        "selected_ablation": best_row,
        "final_experiment": final_experiment,
    }
    save_json(paths["plans"] / "selected_final_config.json", note)


def main() -> int:
    args = parse_args()
    bootstrap_runtime_env(AI_ROOT)
    record_root = Path(args.record_root).expanduser().resolve()
    paths = ensure_dirs(record_root)
    env = os.environ.copy()
    env["PYTHONUTF8"] = "1"

    capture_meta(args=args, paths=paths, env=env)
    write_plan(args=args, paths=paths)

    if args.phase == "setup":
        print(f"Setup complete. Records will be saved under: {record_root}")
        return 0

    if args.phase in {"ablation", "all"}:
        ablations = build_ablation_experiments(args)
        completed = []
        for experiment in ablations:
            completed.append(run_single_experiment(args=args, paths=paths, env=env, experiment=experiment))
        save_json(paths["meta"] / "ablation_runs.json", completed)

    rows = summarize_reports(paths=paths, summary_prefix="experiment_summary")
    best_ablation = pick_best_ablation(rows)
    save_json(paths["summaries"] / "best_ablation.json", best_ablation)

    if args.phase in {"final", "all"}:
        final_experiment = build_final_experiment(args=args, best_row=best_ablation)
        write_selection_note(paths=paths, best_row=best_ablation, final_experiment=final_experiment)
        run_single_experiment(args=args, paths=paths, env=env, experiment=final_experiment)
        rows = summarize_reports(paths=paths, summary_prefix="experiment_summary")

    print(json.dumps({"record_root": str(record_root), "best_ablation": best_ablation}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
