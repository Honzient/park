from __future__ import annotations

import argparse
import json
import os
import re
import shutil
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Iterable

import cv2
import numpy as np

IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
LABEL_SUFFIX = ".txt"
POINT_PATTERN = re.compile(r"^(\d+)&(\d+)$")
BBOX_PATTERN = re.compile(r"^(\d+)&(\d+)_(\d+)&(\d+)$")


@dataclass
class ConversionStats:
    scanned_images: int = 0
    converted: int = 0
    bbox_from_bbox_segment: int = 0
    bbox_from_polygon_segment: int = 0
    bbox_from_regex_fallback: int = 0
    skipped_parse_bbox: int = 0
    skipped_read_image: int = 0
    skipped_invalid_bbox: int = 0
    skipped_small_box: int = 0
    skipped_small_area_ratio: int = 0
    skipped_write_error: int = 0
    hardlink_fallback_to_copy: int = 0
    issues: list[dict[str, str]] = field(default_factory=list)

    def append_issue(self, *, reason: str, image: Path, detail: str, limit: int) -> None:
        if len(self.issues) >= limit:
            return
        self.issues.append(
            {
                "reason": reason,
                "image": str(image),
                "detail": detail,
            }
        )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Convert CCPD filename annotations to YOLO labels")
    parser.add_argument("--input-dir", required=True, help="CCPD image root directory")
    parser.add_argument("--output-dir", required=True, help="Output root directory")
    parser.add_argument("--class-id", type=int, default=0, help="YOLO class id")
    parser.add_argument("--class-name", default="plate", help="Class name for summary record")
    parser.add_argument(
        "--output-prefix",
        default="",
        help="Optional relative subfolder prefix under output images/labels, e.g. ccpd2019",
    )
    parser.add_argument(
        "--image-suffixes",
        default=".jpg,.jpeg,.png,.bmp,.webp",
        help="Comma-separated image suffix list",
    )
    parser.add_argument("--min-box-width", type=int, default=8, help="Minimum bbox width in pixels")
    parser.add_argument("--min-box-height", type=int, default=8, help="Minimum bbox height in pixels")
    parser.add_argument(
        "--min-box-area-ratio",
        type=float,
        default=0.0005,
        help="Minimum bbox area ratio (bbox_area / image_area)",
    )
    parser.add_argument(
        "--copy-mode",
        choices=["copy", "hardlink"],
        default="copy",
        help="How to place images into output/images",
    )
    parser.add_argument("--summary-name", default="ccpd_convert_summary.json", help="Summary JSON filename")
    parser.add_argument("--max-issue-samples", type=int, default=200, help="Max issue examples in summary")
    parser.add_argument("--limit", type=int, default=0, help="Only process first N images (0 = all)")
    parser.add_argument("--dry-run", action="store_true", help="Do not write files")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    input_dir = Path(args.input_dir).expanduser().resolve()
    output_dir = Path(args.output_dir).expanduser().resolve()
    if not input_dir.is_dir():
        raise RuntimeError(f"input dir not found: {input_dir}")
    if args.class_id < 0:
        raise RuntimeError("class id must be >= 0")
    output_prefix = _normalize_output_prefix(args.output_prefix)

    suffixes = _parse_suffixes(args.image_suffixes)
    image_files = list(_iter_images(input_dir, suffixes))
    if args.limit > 0:
        image_files = image_files[: int(args.limit)]

    images_out = output_dir / "images"
    labels_out = output_dir / "labels"
    if not args.dry_run:
        images_out.mkdir(parents=True, exist_ok=True)
        labels_out.mkdir(parents=True, exist_ok=True)

    stats = ConversionStats()
    for image_path in image_files:
        stats.scanned_images += 1
        relative = image_path.relative_to(input_dir)

        parsed = _parse_bbox_from_filename(image_path.stem)
        if parsed is None:
            stats.skipped_parse_bbox += 1
            stats.append_issue(
                reason="parse_bbox_failed",
                image=image_path,
                detail=f"filename stem: {image_path.stem}",
                limit=max(0, int(args.max_issue_samples)),
            )
            continue
        x1_raw, y1_raw, x2_raw, y2_raw, source = parsed

        shape = _read_image_shape(image_path)
        if shape is None:
            stats.skipped_read_image += 1
            stats.append_issue(
                reason="read_image_failed",
                image=image_path,
                detail="cv2.imdecode returned None",
                limit=max(0, int(args.max_issue_samples)),
            )
            continue
        image_h, image_w = shape

        x1, y1, x2, y2 = _clip_bbox(x1_raw, y1_raw, x2_raw, y2_raw, image_w, image_h)
        bbox_w = x2 - x1
        bbox_h = y2 - y1
        if bbox_w <= 0 or bbox_h <= 0:
            stats.skipped_invalid_bbox += 1
            stats.append_issue(
                reason="invalid_bbox_after_clip",
                image=image_path,
                detail=f"raw=({x1_raw},{y1_raw},{x2_raw},{y2_raw}), clipped=({x1},{y1},{x2},{y2})",
                limit=max(0, int(args.max_issue_samples)),
            )
            continue

        if bbox_w < int(args.min_box_width) or bbox_h < int(args.min_box_height):
            stats.skipped_small_box += 1
            stats.append_issue(
                reason="bbox_too_small",
                image=image_path,
                detail=f"width={bbox_w}, height={bbox_h}",
                limit=max(0, int(args.max_issue_samples)),
            )
            continue

        area_ratio = (bbox_w * bbox_h) / float(max(1, image_w * image_h))
        if area_ratio < float(args.min_box_area_ratio):
            stats.skipped_small_area_ratio += 1
            stats.append_issue(
                reason="bbox_area_ratio_too_small",
                image=image_path,
                detail=f"ratio={area_ratio:.8f}",
                limit=max(0, int(args.max_issue_samples)),
            )
            continue

        label_line = _to_yolo_line(
            class_id=int(args.class_id),
            x1=x1,
            y1=y1,
            x2=x2,
            y2=y2,
            image_w=image_w,
            image_h=image_h,
        )
        output_relative = output_prefix / relative if output_prefix is not None else relative
        image_target = images_out / output_relative
        label_target = labels_out / output_relative.with_suffix(LABEL_SUFFIX)
        if not args.dry_run:
            image_target.parent.mkdir(parents=True, exist_ok=True)
            label_target.parent.mkdir(parents=True, exist_ok=True)
            try:
                _materialize_image(image_path, image_target, copy_mode=args.copy_mode, stats=stats)
                label_target.write_text(label_line + "\n", encoding="utf-8")
            except Exception as exc:
                stats.skipped_write_error += 1
                stats.append_issue(
                    reason="write_failed",
                    image=image_path,
                    detail=str(exc),
                    limit=max(0, int(args.max_issue_samples)),
                )
                continue

        stats.converted += 1
        if source == "bbox_segment":
            stats.bbox_from_bbox_segment += 1
        elif source == "polygon_segment":
            stats.bbox_from_polygon_segment += 1
        else:
            stats.bbox_from_regex_fallback += 1

    summary = {
        "created_at": datetime.now().isoformat(timespec="seconds"),
        "input_dir": str(input_dir),
        "output_dir": str(output_dir),
        "output_images_dir": str(images_out),
        "output_labels_dir": str(labels_out),
        "class_id": int(args.class_id),
        "class_name": args.class_name,
        "parameters": {
            "image_suffixes": sorted(suffixes),
            "min_box_width": int(args.min_box_width),
            "min_box_height": int(args.min_box_height),
            "min_box_area_ratio": float(args.min_box_area_ratio),
            "copy_mode": args.copy_mode,
            "output_prefix": args.output_prefix,
            "limit": int(args.limit),
            "dry_run": bool(args.dry_run),
        },
        "stats": {
            "scanned_images": stats.scanned_images,
            "converted": stats.converted,
            "bbox_from_bbox_segment": stats.bbox_from_bbox_segment,
            "bbox_from_polygon_segment": stats.bbox_from_polygon_segment,
            "bbox_from_regex_fallback": stats.bbox_from_regex_fallback,
            "skipped_parse_bbox": stats.skipped_parse_bbox,
            "skipped_read_image": stats.skipped_read_image,
            "skipped_invalid_bbox": stats.skipped_invalid_bbox,
            "skipped_small_box": stats.skipped_small_box,
            "skipped_small_area_ratio": stats.skipped_small_area_ratio,
            "skipped_write_error": stats.skipped_write_error,
            "hardlink_fallback_to_copy": stats.hardlink_fallback_to_copy,
        },
        "issues_sampled": stats.issues,
    }
    summary_path = output_dir / args.summary_name
    if not args.dry_run:
        summary_path.parent.mkdir(parents=True, exist_ok=True)
        summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        summary["summary_path"] = str(summary_path)

    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


def _parse_suffixes(raw: str) -> set[str]:
    suffixes = {item.strip().lower() for item in raw.split(",") if item.strip()}
    if not suffixes:
        raise RuntimeError("image suffixes cannot be empty")
    for suffix in suffixes:
        if not suffix.startswith("."):
            raise RuntimeError(f"invalid image suffix (missing dot): {suffix}")
    return suffixes


def _normalize_output_prefix(raw: str) -> Path | None:
    value = (raw or "").strip().replace("\\", "/")
    if not value:
        return None
    prefix = Path(value)
    if prefix.is_absolute():
        raise RuntimeError("output prefix must be a relative path")
    if any(part in {"", ".", ".."} for part in prefix.parts):
        raise RuntimeError("output prefix cannot contain '.', '..' or empty parts")
    return prefix


def _iter_images(input_dir: Path, suffixes: set[str]) -> Iterable[Path]:
    for image_path in input_dir.rglob("*"):
        if not image_path.is_file():
            continue
        if image_path.suffix.lower() in suffixes:
            yield image_path


def _parse_bbox_from_filename(stem: str) -> tuple[int, int, int, int, str] | None:
    parts = stem.split("-")
    if len(parts) >= 3:
        bbox = _parse_bbox_segment(parts[2])
        if bbox is not None:
            return bbox[0], bbox[1], bbox[2], bbox[3], "bbox_segment"
    if len(parts) >= 4:
        points = _parse_polygon_segment(parts[3])
        if points is not None:
            x_values = [point[0] for point in points]
            y_values = [point[1] for point in points]
            return min(x_values), min(y_values), max(x_values), max(y_values), "polygon_segment"

    match = re.search(r"(\d+)&(\d+)_(\d+)&(\d+)", stem)
    if match:
        x1 = int(match.group(1))
        y1 = int(match.group(2))
        x2 = int(match.group(3))
        y2 = int(match.group(4))
        return min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2), "regex_fallback"
    return None


def _parse_bbox_segment(value: str) -> tuple[int, int, int, int] | None:
    match = BBOX_PATTERN.fullmatch(value.strip())
    if not match:
        return None
    x1 = int(match.group(1))
    y1 = int(match.group(2))
    x2 = int(match.group(3))
    y2 = int(match.group(4))
    return min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2)


def _parse_polygon_segment(value: str) -> list[tuple[int, int]] | None:
    tokens = [token.strip() for token in value.split("_") if token.strip()]
    points: list[tuple[int, int]] = []
    for token in tokens:
        match = POINT_PATTERN.fullmatch(token)
        if not match:
            return None
        points.append((int(match.group(1)), int(match.group(2))))
    if len(points) < 4:
        return None
    return points


def _read_image_shape(image_path: Path) -> tuple[int, int] | None:
    try:
        data = np.fromfile(str(image_path), dtype=np.uint8)
        if data.size <= 0:
            return None
        image = cv2.imdecode(data, cv2.IMREAD_COLOR)
        if image is None:
            return None
        height, width = image.shape[:2]
        if height <= 0 or width <= 0:
            return None
        return int(height), int(width)
    except Exception:
        return None


def _clip_bbox(x1: int, y1: int, x2: int, y2: int, image_w: int, image_h: int) -> tuple[int, int, int, int]:
    left = max(0, min(x1, image_w))
    top = max(0, min(y1, image_h))
    right = max(0, min(x2, image_w))
    bottom = max(0, min(y2, image_h))
    return left, top, right, bottom


def _to_yolo_line(class_id: int, x1: int, y1: int, x2: int, y2: int, image_w: int, image_h: int) -> str:
    x_center = ((x1 + x2) / 2.0) / float(image_w)
    y_center = ((y1 + y2) / 2.0) / float(image_h)
    width = (x2 - x1) / float(image_w)
    height = (y2 - y1) / float(image_h)
    return f"{class_id} {x_center:.6f} {y_center:.6f} {width:.6f} {height:.6f}"


def _materialize_image(source: Path, target: Path, copy_mode: str, stats: ConversionStats) -> None:
    if target.exists():
        target.unlink()
    if copy_mode == "copy":
        shutil.copy2(source, target)
        return
    try:
        os.link(str(source), str(target))
    except Exception:
        shutil.copy2(source, target)
        stats.hardlink_fallback_to_copy += 1


if __name__ == "__main__":
    raise SystemExit(main())
