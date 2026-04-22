from __future__ import annotations

import base64
import inspect
import json
import os
import sys
import traceback
from pathlib import Path
from typing import Any

import cv2
import numpy as np

AI_ROOT = Path(__file__).resolve().parents[1]
if str(AI_ROOT) not in sys.path:
    sys.path.insert(0, str(AI_ROOT))

from core.plate_patterns import normalize_plate_text
from core.runtime_env import bootstrap_runtime_env


try:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def _safe_float(value: Any, default: float = 0.0) -> float:
    try:
        return float(value)
    except Exception:
        return default


def _is_unknown_argument_error(exc: Exception, arg_name: str) -> bool:
    message = str(exc).lower()
    if not message:
        return False
    return (
        f"unexpected keyword argument '{arg_name}'" in message
        or ("unknown argument" in message and arg_name.lower() in message)
    )


def _resolve_paddle_ocr_signature(paddle_ocr_cls: Any) -> tuple[set[str], bool]:
    try:
        signature = inspect.signature(paddle_ocr_cls.__init__)
    except Exception:
        return set(), True
    param_names: set[str] = set()
    accepts_kwargs = False
    for name, parameter in signature.parameters.items():
        if name == "self":
            continue
        if parameter.kind == inspect.Parameter.VAR_KEYWORD:
            accepts_kwargs = True
            continue
        if parameter.kind == inspect.Parameter.VAR_POSITIONAL:
            continue
        param_names.add(name)
    return param_names, accepts_kwargs


def _prepare_paddle_ocr_kwargs(base_kwargs: dict[str, Any], param_names: set[str], accepts_kwargs: bool) -> dict[str, Any]:
    kwargs = dict(base_kwargs)
    if "text_detection_model_dir" in param_names and "det_model_dir" in kwargs:
        kwargs["text_detection_model_dir"] = kwargs.pop("det_model_dir")
    if "text_recognition_model_dir" in param_names and "rec_model_dir" in kwargs:
        kwargs["text_recognition_model_dir"] = kwargs.pop("rec_model_dir")
    if "textline_orientation_model_dir" in param_names and "cls_model_dir" in kwargs:
        kwargs["textline_orientation_model_dir"] = kwargs.pop("cls_model_dir")
    if "use_textline_orientation" in param_names and "use_angle_cls" in kwargs:
        kwargs["use_textline_orientation"] = bool(kwargs.pop("use_angle_cls"))
    if "use_doc_orientation_classify" in param_names and "use_doc_orientation_classify" not in kwargs:
        kwargs["use_doc_orientation_classify"] = False
    if "use_doc_unwarping" in param_names and "use_doc_unwarping" not in kwargs:
        kwargs["use_doc_unwarping"] = False
    if not accepts_kwargs and param_names:
        kwargs = {key: value for key, value in kwargs.items() if key in param_names}
    return kwargs


def _create_paddle_ocr(paddle_ocr_cls: Any, base_kwargs: dict[str, Any], use_gpu: bool):
    attempts: list[dict[str, Any]] = [
        {**base_kwargs, "use_gpu": use_gpu},
        {**base_kwargs, "device": "gpu" if use_gpu else "cpu"},
        dict(base_kwargs),
    ]
    last_exc: Exception | None = None
    for attempt in attempts:
        try:
            return paddle_ocr_cls(**attempt)
        except Exception as exc:
            last_exc = exc
            if "use_gpu" in attempt and _is_unknown_argument_error(exc, "use_gpu"):
                continue
            if "device" in attempt and _is_unknown_argument_error(exc, "device"):
                continue
            raise
    if last_exc is not None:
        raise last_exc
    raise RuntimeError("failed to initialize paddleocr worker: no valid initialization attempt")


def _emit(payload: dict[str, Any]) -> None:
    sys.stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def _collect_text_tokens(value: Any, out: list[tuple[str, float]]) -> None:
    if value is None:
        return
    if isinstance(value, str):
        text = value.strip()
        if text:
            out.append((text, 0.0))
        return
    if isinstance(value, dict):
        for nested in value.values():
            _collect_text_tokens(nested, out)
        return
    if isinstance(value, (list, tuple)):
        if len(value) == 2 and isinstance(value[0], str):
            text = value[0].strip()
            if text:
                out.append((text, _safe_float(value[1], 0.0)))
            return
        for nested in value:
            _collect_text_tokens(nested, out)


def _ocr_plate(ocr: Any, image: np.ndarray) -> tuple[str, float]:
    try:
        try:
            raw = ocr.ocr(image, cls=True)
        except TypeError:
            raw = ocr.ocr(image)
    except Exception:
        return "", 0.0
    tokens: list[tuple[str, float]] = []
    _collect_text_tokens(raw, tokens)
    best_text = ""
    best_score = 0.0
    for text, score in tokens:
        normalized = normalize_plate_text(text)
        if not normalized:
            continue
        if score >= best_score:
            best_text = normalized
            best_score = score
    return best_text, max(0.0, min(best_score, 1.0))


def _build_ocr(ai_root: Path, ocr_device: str) -> tuple[Any, str, str]:
    from importlib import import_module

    site_packages = Path(sys.executable).resolve().parent / "Lib" / "site-packages"
    dll_dirs = [
        site_packages / "nvidia" / "cudnn" / "bin",
        site_packages / "nvidia" / "cublas" / "bin",
        site_packages / "nvidia" / "cuda_nvrtc" / "bin",
    ]
    existing = os.environ.get("PATH", "")
    prepend = []
    for dll_dir in dll_dirs:
        if dll_dir.is_dir():
            prepend.append(str(dll_dir))
            if hasattr(os, "add_dll_directory"):
                try:
                    os.add_dll_directory(str(dll_dir))
                except Exception:
                    pass
    if prepend:
        os.environ["PATH"] = ";".join(prepend + [existing])

    paddle_module = import_module("paddle")
    paddle_ocr_cls = getattr(import_module("paddleocr"), "PaddleOCR")
    param_names, accepts_kwargs = _resolve_paddle_ocr_signature(paddle_ocr_cls)
    prefer_gpu = (ocr_device or "").strip().lower() in {"gpu", "cuda", "cuda:0", "gpu:0", "0"}
    warning = ""
    if prefer_gpu and not paddle_module.device.is_compiled_with_cuda():
        prefer_gpu = False
        warning = "paddlepaddle-gpu unavailable, fallback to CPU OCR"

    local_det_dir = ai_root / "models" / "paddle" / "ch_PP-OCRv4_det_infer"
    local_rec_dir = ai_root / "models" / "paddle" / "ch_PP-OCRv4_rec_infer"
    local_cls_dir = ai_root / "models" / "paddle" / "ch_ppocr_mobile_v2.0_cls_infer"
    if not (local_det_dir.is_dir() and local_rec_dir.is_dir()):
        local_det_dir = ai_root / "models" / "paddle" / "PP-OCRv4_mobile_det"
        local_rec_dir = ai_root / "models" / "paddle" / "PP-OCRv4_mobile_rec"
    if not local_cls_dir.is_dir():
        local_cls_dir = ai_root / "models" / "paddle" / "PP-LCNet_x1_0_textline_ori"

    strategy_kwargs: list[dict[str, Any]] = []
    if local_det_dir.is_dir() and local_rec_dir.is_dir():
        strategy_kwargs.append(
            {
                "lang": "ch",
                "det_model_dir": str(local_det_dir),
                "rec_model_dir": str(local_rec_dir),
                "cls_model_dir": str(local_cls_dir) if local_cls_dir.is_dir() else None,
                "use_angle_cls": False,
                "show_log": False,
            }
        )

    strategy_kwargs.extend(
        [
            {"lang": "ch", "use_angle_cls": False, "show_log": False},
            {"lang": "ch", "show_log": False},
            {"lang": "ch"},
        ]
    )

    last_exc: Exception | None = None
    device_attempts = [prefer_gpu]
    if prefer_gpu:
        device_attempts.append(False)

    for use_gpu in device_attempts:
        for kwargs in strategy_kwargs:
            try:
                prepared_kwargs = _prepare_paddle_ocr_kwargs(kwargs, param_names, accepts_kwargs)
                ocr = _create_paddle_ocr(paddle_ocr_cls, prepared_kwargs, use_gpu)
                warmup = np.zeros((32, 128, 3), dtype=np.uint8)
                try:
                    try:
                        _ = ocr.ocr(warmup, cls=True)
                    except TypeError:
                        _ = ocr.ocr(warmup)
                except Exception as runtime_exc:
                    last_exc = runtime_exc
                    continue
                runtime_device = "gpu" if use_gpu else "cpu"
                if prefer_gpu and not use_gpu:
                    warning = (
                        f"{warning}; GPU OCR init failed, fallback to CPU OCR"
                        if warning
                        else "GPU OCR init failed, fallback to CPU OCR"
                    )
                return ocr, runtime_device, warning
            except Exception as exc:
                last_exc = exc
    raise RuntimeError(f"failed to initialize paddleocr worker: {last_exc}") from last_exc


def main() -> int:
    try:
        ai_root = Path((sys.argv[1] if len(sys.argv) > 1 else ".")).resolve()
        ocr_device = sys.argv[2] if len(sys.argv) > 2 else "gpu"
        bootstrap_runtime_env(ai_root)
        ocr, runtime_device, warning = _build_ocr(ai_root, ocr_device)
        _emit({"status": "ready", "ocr_runtime_device": runtime_device, "warning": warning})
    except Exception as exc:
        _emit({"status": "error", "message": str(exc), "traceback": traceback.format_exc()})
        return 1

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
        except Exception:
            _emit({"status": "error", "message": "invalid json request"})
            continue

        op = request.get("op")
        if op == "shutdown":
            _emit({"status": "bye"})
            return 0
        if op != "ocr":
            _emit({"status": "error", "message": f"unsupported op: {op}"})
            continue

        try:
            raw_b64 = request.get("image_b64")
            if not isinstance(raw_b64, str) or not raw_b64:
                raise RuntimeError("invalid OCR payload")
            raw_bytes = base64.b64decode(raw_b64.encode("ascii"))
            array = np.frombuffer(raw_bytes, dtype=np.uint8)
            image = cv2.imdecode(array, cv2.IMREAD_COLOR)
            if image is None:
                raise RuntimeError("failed to decode OCR crop image")
            plate, score = _ocr_plate(ocr, image)
            _emit({"status": "success", "plate_number": plate, "confidence": score})
        except Exception as exc:
            _emit({"status": "error", "message": str(exc), "traceback": traceback.format_exc()})
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
