import argparse
import json
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

from core.reporting import extract_metric_bundle, to_jsonable
from core.runtime_env import bootstrap_runtime_env


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train YOLOv11 plate detector")
    parser.add_argument("--dataset-yaml", required=True, help="Path to dataset.yaml")
    parser.add_argument("--base-model", default="yolo11n.pt", help="Pretrained model or checkpoint path")
    parser.add_argument("--epochs", type=int, default=100)
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--batch", type=int, default=16)
    parser.add_argument("--device", default="", help="cpu / 0 / 0,1 etc; empty means auto")
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--patience", type=int, default=50)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--project", default="runs/train")
    parser.add_argument("--name", default="plate-yolo11")
    parser.add_argument(
        "--train-arg",
        action="append",
        default=[],
        help="Extra YOLO train kwarg in key=value form. Repeatable.",
    )
    parser.add_argument("--tag", action="append", default=[], help="Experiment tag. Repeatable.")
    parser.add_argument("--notes", default="", help="Experiment notes for thesis traceability")
    parser.add_argument(
        "--save-report",
        default="artifacts/training/latest_training_report.json",
        help="JSON report path",
    )
    parser.add_argument("--no-test-eval", action="store_true", help="Skip test split evaluation")
    parser.add_argument(
        "--skip-dataset-summary",
        action="store_true",
        help="Do not auto-load split_summary.json from dataset directory",
    )
    return parser.parse_args()


def resolve_best_weights(run_dir: Path) -> tuple[Path | None, Path | None]:
    weights_dir = run_dir / "weights"
    best = weights_dir / "best.pt"
    last = weights_dir / "last.pt"
    best_path = best if best.is_file() else None
    last_path = last if last.is_file() else None
    return best_path, last_path


def has_test_split(dataset_yaml: Path) -> bool:
    text = dataset_yaml.read_text(encoding="utf-8")
    for line in text.splitlines():
        if line.strip().startswith("test:"):
            value = line.split(":", 1)[1].strip()
            return bool(value and value.lower() != "null")
    return False


def _parse_train_arg_value(raw_value: str) -> Any:
    value = raw_value.strip()
    lowered = value.lower()
    if lowered == "true":
        return True
    if lowered == "false":
        return False
    if lowered in {"none", "null"}:
        return None
    if re.fullmatch(r"[+-]?\d+", value):
        return int(value)
    if re.fullmatch(r"[+-]?(?:\d+\.\d*|\.\d+|\d+)(?:[eE][+-]?\d+)?", value):
        return float(value)
    if value.startswith("{") or value.startswith("["):
        try:
            return json.loads(value)
        except Exception:
            return value
    return value


def _parse_train_arg_items(items: list[str]) -> dict[str, Any]:
    parsed: dict[str, Any] = {}
    for item in items:
        if "=" not in item:
            raise RuntimeError(f"invalid --train-arg '{item}', expected key=value")
        key, raw_value = item.split("=", 1)
        key = key.strip()
        if not key:
            raise RuntimeError(f"invalid --train-arg '{item}', key is empty")
        parsed[key] = _parse_train_arg_value(raw_value)
    return parsed


def _load_dataset_summary(dataset_yaml: Path, enabled: bool) -> dict[str, Any]:
    if not enabled:
        return {}
    summary_path = dataset_yaml.parent / "split_summary.json"
    if not summary_path.is_file():
        return {}
    try:
        return json.loads(summary_path.read_text(encoding="utf-8"))
    except Exception:
        return {}


def evaluate_split(
    model_path: Path,
    dataset_yaml: Path,
    split: str,
    imgsz: int,
    batch: int,
    device: str,
    workers: int,
) -> dict[str, Any]:
    from ultralytics import YOLO

    model = YOLO(str(model_path))
    kwargs: dict[str, Any] = {
        "data": str(dataset_yaml),
        "split": split,
        "imgsz": imgsz,
        "batch": batch,
        "workers": max(0, int(workers)),
        "verbose": False,
    }
    if device:
        kwargs["device"] = device
    metrics = model.val(**kwargs)
    return extract_metric_bundle(metrics)


def main() -> int:
    bootstrap_runtime_env()
    from ultralytics import YOLO

    args = parse_args()
    dataset_yaml = Path(args.dataset_yaml).expanduser().resolve()
    if not dataset_yaml.is_file():
        raise RuntimeError(f"dataset yaml not found: {dataset_yaml}")
    extra_train_args = _parse_train_arg_items(list(args.train_arg))
    dataset_summary = _load_dataset_summary(dataset_yaml, enabled=not args.skip_dataset_summary)

    model = YOLO(args.base_model)
    base_train_kwargs: dict[str, Any] = {
        "data": str(dataset_yaml),
        "epochs": max(1, int(args.epochs)),
        "imgsz": max(64, int(args.imgsz)),
        "batch": max(1, int(args.batch)),
        "workers": max(0, int(args.workers)),
        "patience": max(1, int(args.patience)),
        "seed": int(args.seed),
        "project": args.project,
        "name": args.name,
        "exist_ok": True,
        "verbose": True,
    }
    if args.device:
        base_train_kwargs["device"] = args.device
    train_kwargs: dict[str, Any] = dict(base_train_kwargs)
    for key, value in extra_train_args.items():
        if key in base_train_kwargs:
            raise RuntimeError(f"--train-arg '{key}' conflicts with existing dedicated argument")
        train_kwargs[key] = value

    train_result = model.train(**train_kwargs)
    run_dir = Path(getattr(train_result, "save_dir", "") or getattr(model.trainer, "save_dir", "")).expanduser().resolve()
    if not run_dir.exists():
        raise RuntimeError("training finished but run directory was not found")

    best_path, last_path = resolve_best_weights(run_dir)
    if best_path is None and last_path is None:
        raise RuntimeError(f"training output does not contain weights: {run_dir / 'weights'}")

    eval_model = best_path or last_path
    val_metrics = evaluate_split(
        model_path=eval_model,
        dataset_yaml=dataset_yaml,
        split="val",
        imgsz=max(64, int(args.imgsz)),
        batch=max(1, int(args.batch)),
        device=args.device,
        workers=max(0, int(args.workers)),
    )

    test_metrics: dict[str, Any] = {}
    if not args.no_test_eval and has_test_split(dataset_yaml):
        test_metrics = evaluate_split(
            model_path=eval_model,
            dataset_yaml=dataset_yaml,
            split="test",
            imgsz=max(64, int(args.imgsz)),
            batch=max(1, int(args.batch)),
            device=args.device,
            workers=max(0, int(args.workers)),
        )

    report = {
        "created_at": datetime.now().isoformat(timespec="seconds"),
        "engine": "yolov11",
        "dataset_yaml": str(dataset_yaml),
        "dataset_summary": dataset_summary,
        "base_model": args.base_model,
        "experiment": {
            "tags": list(args.tag),
            "notes": args.notes,
        },
        "cli": {
            "argv": list(sys.argv),
            "train_arg_items": list(args.train_arg),
        },
        "training": {
            "epochs": max(1, int(args.epochs)),
            "imgsz": max(64, int(args.imgsz)),
            "batch": max(1, int(args.batch)),
            "device": args.device or "auto",
            "workers": max(0, int(args.workers)),
            "patience": max(1, int(args.patience)),
            "seed": int(args.seed),
            "project": args.project,
            "name": args.name,
            "run_dir": str(run_dir),
            "best_weights": str(best_path) if best_path else "",
            "last_weights": str(last_path) if last_path else "",
            "eval_weights": str(eval_model),
            "extra_train_args": to_jsonable(extra_train_args),
            "resolved_train_kwargs": to_jsonable(train_kwargs),
        },
        "metrics": {
            "val": val_metrics,
            "test": test_metrics,
        },
        "raw_train_result": to_jsonable(train_result),
    }

    report_path = Path(args.save_report).expanduser().resolve()
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
