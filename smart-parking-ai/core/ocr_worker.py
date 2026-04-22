from __future__ import annotations

import base64
import json
import subprocess
import sys
import os
from pathlib import Path
from typing import Any

import cv2
import numpy as np

from core.runtime_env import bootstrap_runtime_env


class PaddleOcrWorker:
    def __init__(self, ai_root: Path, ocr_device: str = "gpu", startup_timeout_sec: int = 120):
        resolved_root = bootstrap_runtime_env(ai_root)
        worker_entry = ai_root / "core" / "ocr_worker_entry.py"
        if not worker_entry.is_file():
            raise RuntimeError(f"OCR worker entry not found: {worker_entry}")

        worker_env = os.environ.copy()
        worker_env.setdefault("PYTHONIOENCODING", "utf-8")
        worker_env.setdefault("PYTHONUTF8", "1")
        worker_env.setdefault("YOLO_CONFIG_DIR", str(resolved_root / ".ultralytics"))
        worker_env.setdefault("ULTRALYTICS_SETTINGS_DIR", str(resolved_root / ".ultralytics"))
        worker_env.setdefault("XDG_CACHE_HOME", str(resolved_root / ".cache"))
        worker_env.setdefault("TMPDIR", str(resolved_root / ".tmp"))
        worker_env.setdefault("TMP", str(resolved_root / ".tmp"))
        worker_env.setdefault("TEMP", str(resolved_root / ".tmp"))
        worker_env.setdefault("PADDLE_HOME", str(resolved_root / ".paddle"))
        worker_env.setdefault("PADDLEX_HOME", str(resolved_root / ".paddlex"))
        worker_env.setdefault("PPOCR_HOME", str(resolved_root / ".paddleocr"))
        worker_env.setdefault("HOME", str(resolved_root))
        worker_env.setdefault("USERPROFILE", str(resolved_root))
        worker_env.setdefault("PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK", "True")
        existing_pythonpath = worker_env.get("PYTHONPATH", "")
        if existing_pythonpath:
            worker_env["PYTHONPATH"] = str(resolved_root) + os.pathsep + existing_pythonpath
        else:
            worker_env["PYTHONPATH"] = str(resolved_root)

        self._process = subprocess.Popen(
            [sys.executable, str(worker_entry), str(ai_root), str(ocr_device)],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
            cwd=str(ai_root),
            env=worker_env,
        )
        self._stdin = self._process.stdin
        self._stdout = self._process.stdout
        self._stderr = self._process.stderr
        if self._stdin is None or self._stdout is None or self._stderr is None:
            raise RuntimeError("failed to open OCR worker stdio")

        ready = self._read_response()
        if ready.get("status") != "ready":
            message = str(ready.get("message", "OCR worker startup failed"))
            trace = str(ready.get("traceback", "") or "").strip()
            self.close()
            if trace:
                message = f"{message}\n{trace}"
            raise RuntimeError(message)

        self.runtime_device = str(ready.get("ocr_runtime_device", "unknown"))
        self.warning = str(ready.get("warning", "") or "")

    def _parse_response(self, line: str) -> dict[str, Any]:
        try:
            payload = json.loads(line.strip())
            if isinstance(payload, dict):
                return payload
        except Exception:
            pass
        raise ValueError(f"invalid OCR worker response: {line.strip()}")

    def _read_response(self) -> dict[str, Any]:
        while True:
            line = self._stdout.readline()
            if not line:
                err = self._stderr.read().strip()
                raise RuntimeError(f"OCR worker exited unexpectedly. stderr={err}")
            stripped = line.strip()
            if not stripped:
                continue
            try:
                return self._parse_response(stripped)
            except ValueError:
                continue

    def recognize(self, image: np.ndarray) -> tuple[str, float]:
        if image is None or image.size == 0:
            return "", 0.0
        ok, encoded = cv2.imencode(".png", image)
        if not ok:
            return "", 0.0
        request = {
            "op": "ocr",
            "image_b64": base64.b64encode(encoded.tobytes()).decode("ascii"),
        }
        self._stdin.write(json.dumps(request, ensure_ascii=False) + "\n")
        self._stdin.flush()
        response = self._read_response()
        if response.get("status") != "success":
            message = str(response.get("message", "OCR worker request failed"))
            trace = str(response.get("traceback", "") or "").strip()
            if trace:
                message = f"{message}\n{trace}"
            raise RuntimeError(message)
        plate = str(response.get("plate_number", "") or "")
        confidence = float(response.get("confidence", 0.0) or 0.0)
        return plate, confidence

    def close(self) -> None:
        try:
            if self._process.poll() is None and self._stdin is not None:
                self._stdin.write(json.dumps({"op": "shutdown"}, ensure_ascii=False) + "\n")
                self._stdin.flush()
        except Exception:
            pass
        try:
            if self._process.poll() is None:
                self._process.terminate()
        except Exception:
            pass
