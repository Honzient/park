import argparse
import json
from pathlib import Path

from core.dataset import prepare_yolo_dataset


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Prepare YOLO dataset with train/val/test split")
    parser.add_argument("--images-dir", required=True, help="Source image directory")
    parser.add_argument("--labels-dir", required=True, help="Source label directory (YOLO txt format)")
    parser.add_argument("--output-dir", required=True, help="Output dataset directory")
    parser.add_argument("--train-ratio", type=float, default=0.7)
    parser.add_argument("--val-ratio", type=float, default=0.2)
    parser.add_argument("--test-ratio", type=float, default=0.1)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument(
        "--class-names",
        default="plate",
        help="Comma-separated class names in label index order, e.g. plate",
    )
    parser.add_argument(
        "--no-validate-labels",
        action="store_true",
        help="Skip YOLO label format validation when pairing samples",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    class_names = [item.strip() for item in args.class_names.split(",") if item.strip()]
    summary = prepare_yolo_dataset(
        images_dir=Path(args.images_dir),
        labels_dir=Path(args.labels_dir),
        output_dir=Path(args.output_dir),
        train_ratio=args.train_ratio,
        val_ratio=args.val_ratio,
        test_ratio=args.test_ratio,
        seed=args.seed,
        class_names=class_names,
        validate_labels=not args.no_validate_labels,
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
