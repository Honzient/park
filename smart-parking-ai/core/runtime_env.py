from __future__ import annotations

import os
from pathlib import Path


def bootstrap_runtime_env(ai_root: Path | None = None) -> Path:
    root = (ai_root or Path(__file__).resolve().parents[1]).expanduser().resolve()
    yolo_dir = root / ".ultralytics"
    cache_dir = root / ".cache"
    tmp_dir = root / ".tmp"
    paddle_dir = root / ".paddle"
    paddlex_dir = root / ".paddlex"
    ppocr_dir = root / ".paddleocr"

    yolo_dir.mkdir(parents=True, exist_ok=True)
    cache_dir.mkdir(parents=True, exist_ok=True)
    tmp_dir.mkdir(parents=True, exist_ok=True)
    paddle_dir.mkdir(parents=True, exist_ok=True)
    paddlex_dir.mkdir(parents=True, exist_ok=True)
    ppocr_dir.mkdir(parents=True, exist_ok=True)

    os.environ["YOLO_CONFIG_DIR"] = str(yolo_dir)
    os.environ["ULTRALYTICS_SETTINGS_DIR"] = str(yolo_dir)
    os.environ["XDG_CACHE_HOME"] = str(cache_dir)
    os.environ["TMPDIR"] = str(tmp_dir)
    os.environ["TMP"] = str(tmp_dir)
    os.environ["TEMP"] = str(tmp_dir)
    os.environ["PADDLE_HOME"] = str(paddle_dir)
    os.environ["PADDLEX_HOME"] = str(paddlex_dir)
    os.environ["PPOCR_HOME"] = str(ppocr_dir)
    os.environ["HOME"] = str(root)
    os.environ["USERPROFILE"] = str(root)
    os.environ["PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK"] = "True"
    return root
