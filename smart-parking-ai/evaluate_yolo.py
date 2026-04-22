import argparse
import json
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

from core.reporting import extract_metric_bundle
from core.runtime_env import bootstrap_runtime_env


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Evaluate YOLOv11 model on val/test split")
    parser.add_argument("--model", required=True, help="Trained model path, e.g. best.pt")
    parser.add_argument("--dataset-yaml", required=True, help="Path to dataset.yaml")
    parser.add_argument("--split", choices=["val", "test"], default="test")
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--batch", type=int, default=16)
    parser.add_argument("--device", default="", help="cpu / 0 / 0,1 etc; empty means auto")
    parser.add_argument("--workers", type=int, default=0, help="Dataloader workers")
    parser.add_argument("--conf", type=float, default=-1.0, help="Confidence threshold (optional)")
    parser.add_argument("--iou", type=float, default=-1.0, help="IoU threshold (optional)")
    parser.add_argument("--max-det", type=int, default=0, help="Max detections per image (optional)")
    parser.add_argument(
        "--val-arg",
        action="append",
        default=[],
        help="Extra YOLO val kwarg in key=value form. Repeatable.",
    )
    parser.add_argument("--tag", action="append", default=[], help="Experiment tag. Repeatable.")
    parser.add_argument("--notes", default="", help="Evaluation notes")
    parser.add_argument(
        "--save-report",
        default="artifacts/eval/latest_eval_report.json",
        help="JSON report path",
    )
    return parser.parse_args()


def _parse_val_arg_value(raw_value: str) -> Any:
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


def _parse_val_arg_items(items: list[str]) -> dict[str, Any]:
    parsed: dict[str, Any] = {}
    for item in items:
        if "=" not in item:
            raise RuntimeError(f"invalid --val-arg '{item}', expected key=value")
        key, raw_value = item.split("=", 1)
        key = key.strip()
        if not key:
            raise RuntimeError(f"invalid --val-arg '{item}', key is empty")
        parsed[key] = _parse_val_arg_value(raw_value)
    return parsed


def main() -> int:
    bootstrap_runtime_env()
    from ultralytics import YOLO

    args = parse_args()
    model_path = Path(args.model).expanduser().resolve()
    dataset_yaml = Path(args.dataset_yaml).expanduser().resolve()
    if not model_path.is_file():
        raise RuntimeError(f"model file not found: {model_path}")
    if not dataset_yaml.is_file():
        raise RuntimeError(f"dataset yaml not found: {dataset_yaml}")
    extra_val_args = _parse_val_arg_items(list(args.val_arg))

    model = YOLO(str(model_path))
    base_kwargs: dict[str, Any] = {
        "data": str(dataset_yaml),
        "split": args.split,
        "imgsz": max(64, int(args.imgsz)),
        "batch": max(1, int(args.batch)),
        "workers": max(0, int(args.workers)),
        "verbose": True,
    }
    if args.device:
        base_kwargs["device"] = args.device
    if args.conf >= 0:
        base_kwargs["conf"] = max(0.0, min(float(args.conf), 1.0))
    if args.iou >= 0:
        base_kwargs["iou"] = max(0.0, min(float(args.iou), 1.0))
    if args.max_det > 0:
        base_kwargs["max_det"] = int(args.max_det)

    kwargs = dict(base_kwargs)
    for key, value in extra_val_args.items():
        if key in base_kwargs:
            raise RuntimeError(f"--val-arg '{key}' conflicts with existing dedicated argument")
        kwargs[key] = value

    metrics = model.val(**kwargs)
    report = {
        "created_at": datetime.now().isoformat(timespec="seconds"),
        "engine": "yolov11",
        "model": str(model_path),
        "dataset_yaml": str(dataset_yaml),
        "split": args.split,
        "imgsz": max(64, int(args.imgsz)),
        "batch": max(1, int(args.batch)),
        "workers": max(0, int(args.workers)),
        "device": args.device or "auto",
        "experiment": {
            "tags": list(args.tag),
            "notes": args.notes,
        },
        "cli": {
            "argv": list(sys.argv),
            "val_arg_items": list(args.val_arg),
        },
        "evaluation_kwargs": kwargs,
        "extra_val_args": extra_val_args,
        "metrics": extract_metric_bundle(metrics),
    }

    report_path = Path(args.save_report).expanduser().resolve()
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
