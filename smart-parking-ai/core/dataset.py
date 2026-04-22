from __future__ import annotations

import json
import random
import shutil
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Iterable

IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
LABEL_SUFFIX = ".txt"


@dataclass(frozen=True)
class Sample:
    image: Path
    label: Path
    relative_stem: Path


@dataclass
class CollectSummary:
    scanned_images: int = 0
    matched_pairs: int = 0
    skipped_missing_label: int = 0
    skipped_empty_label: int = 0
    skipped_invalid_label: int = 0
    invalid_label_examples: list[dict[str, str]] = field(default_factory=list)

    def append_invalid_label(self, label_path: Path, reason: str, limit: int = 50) -> None:
        if len(self.invalid_label_examples) >= limit:
            return
        self.invalid_label_examples.append(
            {
                "label_path": str(label_path),
                "reason": reason,
            }
        )


def prepare_yolo_dataset(
    images_dir: Path,
    labels_dir: Path,
    output_dir: Path,
    train_ratio: float,
    val_ratio: float,
    test_ratio: float,
    seed: int,
    class_names: list[str],
    validate_labels: bool = True,
) -> dict:
    images_dir = images_dir.expanduser().resolve()
    labels_dir = labels_dir.expanduser().resolve()
    output_dir = output_dir.expanduser().resolve()

    if not images_dir.is_dir():
        raise RuntimeError(f"images dir not found: {images_dir}")
    if not labels_dir.is_dir():
        raise RuntimeError(f"labels dir not found: {labels_dir}")
    if not class_names:
        raise RuntimeError("class names cannot be empty")

    ratios = _normalize_ratios(train_ratio, val_ratio, test_ratio)
    samples, collect_summary = _collect_samples(
        images_dir=images_dir,
        labels_dir=labels_dir,
        max_class_id=len(class_names) - 1,
        validate_labels=validate_labels,
    )
    if not samples:
        raise RuntimeError("no valid image/label pairs found")

    split_counts = _allocate_counts(len(samples), ratios)
    split_map = _split_samples(samples, split_counts, seed)

    _reset_output_dir(output_dir)
    for split_name, split_samples in split_map.items():
        _materialize_split(split_samples, output_dir, split_name)

    dataset_yaml = output_dir / "dataset.yaml"
    _write_dataset_yaml(dataset_yaml, output_dir, class_names)

    summary = {
        "created_at": datetime.now().isoformat(timespec="seconds"),
        "source_images_dir": str(images_dir),
        "source_labels_dir": str(labels_dir),
        "output_dir": str(output_dir),
        "dataset_yaml": str(dataset_yaml),
        "seed": seed,
        "ratios": {
            "train": ratios[0],
            "val": ratios[1],
            "test": ratios[2],
        },
        "validate_labels": bool(validate_labels),
        "total_samples": len(samples),
        "splits": {name: len(value) for name, value in split_map.items()},
        "class_names": class_names,
        "collection": collect_summary,
    }
    summary_path = output_dir / "split_summary.json"
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    summary["split_summary"] = str(summary_path)
    return summary


def _reset_output_dir(output_dir: Path) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    for child_name in ("images", "labels", "dataset.yaml", "split_summary.json"):
        target = output_dir / child_name
        if target.is_dir():
            shutil.rmtree(target, ignore_errors=True)
        elif target.exists():
            target.unlink()


def _normalize_ratios(train_ratio: float, val_ratio: float, test_ratio: float) -> tuple[float, float, float]:
    raw = [float(train_ratio), float(val_ratio), float(test_ratio)]
    if any(item <= 0 for item in raw):
        raise RuntimeError("train/val/test ratios must be > 0")
    total = sum(raw)
    return raw[0] / total, raw[1] / total, raw[2] / total


def _collect_samples(images_dir: Path, labels_dir: Path, max_class_id: int, validate_labels: bool) -> tuple[list[Sample], dict]:
    samples: list[Sample] = []
    summary = CollectSummary()
    for image_path in images_dir.rglob("*"):
        if image_path.suffix.lower() not in IMAGE_SUFFIXES:
            continue
        summary.scanned_images += 1
        relative = image_path.relative_to(images_dir)
        label_relative = relative.with_suffix(LABEL_SUFFIX)
        label_path = labels_dir / label_relative
        if not label_path.is_file():
            summary.skipped_missing_label += 1
            continue
        if label_path.stat().st_size <= 0:
            summary.skipped_empty_label += 1
            continue

        if validate_labels:
            is_valid, reason = _validate_yolo_label_file(label_path, max_class_id=max_class_id)
            if not is_valid:
                summary.skipped_invalid_label += 1
                summary.append_invalid_label(label_path=label_path, reason=reason)
                continue

        samples.append(Sample(image=image_path, label=label_path, relative_stem=relative.with_suffix("")))
        summary.matched_pairs += 1
    return samples, {
        "scanned_images": summary.scanned_images,
        "matched_pairs": summary.matched_pairs,
        "skipped_missing_label": summary.skipped_missing_label,
        "skipped_empty_label": summary.skipped_empty_label,
        "skipped_invalid_label": summary.skipped_invalid_label,
        "invalid_label_examples": summary.invalid_label_examples,
    }


def _validate_yolo_label_file(label_path: Path, max_class_id: int) -> tuple[bool, str]:
    try:
        lines = label_path.read_text(encoding="utf-8").splitlines()
    except Exception as exc:
        return False, f"cannot read label file: {exc}"

    valid_lines = 0
    for index, raw_line in enumerate(lines, start=1):
        line = raw_line.strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) != 5:
            return False, f"line {index}: expected 5 fields, got {len(parts)}"

        try:
            class_id = int(parts[0])
        except Exception:
            return False, f"line {index}: invalid class id '{parts[0]}'"
        if class_id < 0 or class_id > max_class_id:
            return False, f"line {index}: class id out of range ({class_id})"

        try:
            x_center = float(parts[1])
            y_center = float(parts[2])
            width = float(parts[3])
            height = float(parts[4])
        except Exception:
            return False, f"line {index}: bbox contains non-float value"

        if not (0.0 <= x_center <= 1.0 and 0.0 <= y_center <= 1.0):
            return False, f"line {index}: center is out of [0,1]"
        if not (0.0 < width <= 1.0 and 0.0 < height <= 1.0):
            return False, f"line {index}: width/height is out of (0,1]"

        x1 = x_center - width / 2.0
        x2 = x_center + width / 2.0
        y1 = y_center - height / 2.0
        y2 = y_center + height / 2.0
        tolerance = 1e-6
        if x1 < -tolerance or y1 < -tolerance or x2 > 1.0 + tolerance or y2 > 1.0 + tolerance:
            return False, f"line {index}: bbox exceeds image range"

        valid_lines += 1

    if valid_lines <= 0:
        return False, "file has no non-empty label lines"
    return True, ""


def _allocate_counts(total: int, ratios: tuple[float, float, float]) -> tuple[int, int, int]:
    if total < 3:
        raise RuntimeError("dataset must contain at least 3 samples to split into train/val/test")

    raw = [ratios[0] * total, ratios[1] * total, ratios[2] * total]
    counts = [max(1, int(item)) for item in raw]

    while sum(counts) > total:
        index = max(range(3), key=lambda i: counts[i])
        if counts[index] > 1:
            counts[index] -= 1
        else:
            break

    while sum(counts) < total:
        fraction = [raw[i] - int(raw[i]) for i in range(3)]
        index = max(range(3), key=lambda i: fraction[i])
        counts[index] += 1

    if any(item <= 0 for item in counts):
        raise RuntimeError(f"invalid split counts: {counts}")

    return counts[0], counts[1], counts[2]


def _split_samples(samples: list[Sample], counts: tuple[int, int, int], seed: int) -> dict[str, list[Sample]]:
    items = list(samples)
    random.Random(seed).shuffle(items)

    train_count, val_count, test_count = counts
    train_items = items[:train_count]
    val_items = items[train_count:train_count + val_count]
    test_items = items[train_count + val_count:train_count + val_count + test_count]

    return {"train": train_items, "val": val_items, "test": test_items}


def _materialize_split(samples: Iterable[Sample], output_dir: Path, split_name: str) -> None:
    image_root = output_dir / "images" / split_name
    label_root = output_dir / "labels" / split_name
    image_root.mkdir(parents=True, exist_ok=True)
    label_root.mkdir(parents=True, exist_ok=True)

    for sample in samples:
        relative_parent = sample.relative_stem.parent
        image_target_dir = image_root / relative_parent
        label_target_dir = label_root / relative_parent
        image_target_dir.mkdir(parents=True, exist_ok=True)
        label_target_dir.mkdir(parents=True, exist_ok=True)

        shutil.copy2(sample.image, image_target_dir / sample.image.name)
        shutil.copy2(sample.label, label_target_dir / (sample.relative_stem.name + LABEL_SUFFIX))


def _write_dataset_yaml(dataset_yaml: Path, root_dir: Path, class_names: list[str]) -> None:
    lines = [
        f"path: {root_dir.as_posix()}",
        "train: images/train",
        "val: images/val",
        "test: images/test",
        "names:",
    ]
    for idx, name in enumerate(class_names):
        lines.append(f"  {idx}: {name}")
    dataset_yaml.write_text("\n".join(lines) + "\n", encoding="utf-8")
