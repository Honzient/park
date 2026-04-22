from __future__ import annotations

import inspect
from importlib import import_module
from pathlib import Path
from time import perf_counter
from typing import Any

from core.ocr_worker import PaddleOcrWorker
from core.plate_patterns import normalize_plate_text
from core.runtime_env import bootstrap_runtime_env

bootstrap_runtime_env()

from ultralytics import YOLO


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
    raise RuntimeError("failed to initialize paddleocr: no valid initialization attempt")


class PlateRecognizer:
    def __init__(
        self,
        yolo_model_path: str,
        enable_ocr: bool = True,
        fallback_model: str | None = None,
        yolo_device: str = "",
        ocr_device: str = "gpu",
    ):
        self.yolo_device_requested = self._normalize_yolo_device(yolo_device)
        self.yolo_runtime_device = self.yolo_device_requested if self.yolo_device_requested != "auto" else "unknown"
        self.ocr_device_requested = self._normalize_ocr_device(ocr_device)
        self.ocr_runtime_device = "disabled"
        self.last_timing = {
            "detect_ms": 0.0,
            "ocr_ms": 0.0,
            "total_ms": 0.0,
        }
        self.model_source, self.yolo = self._load_yolo(yolo_model_path, fallback_model)
        self.ocr_warning = ""
        self.ocr_worker: PaddleOcrWorker | None = None
        self.ocr = None
        self._build_ocr_backend(enable_ocr=enable_ocr, ocr_device=self.ocr_device_requested)

    def _load_yolo(self, primary_model: str, fallback_model: str | None) -> tuple[str, YOLO]:
        errors: list[str] = []

        primary = Path((primary_model or "").strip()).expanduser()
        if primary_model and primary_model.strip():
            if primary.is_file():
                if primary.stat().st_size <= 0:
                    errors.append(f"primary model file is empty: {primary}")
                else:
                    try:
                        return str(primary.resolve()), YOLO(str(primary.resolve()))
                    except Exception as exc:
                        errors.append(f"failed to load primary model '{primary}': {exc}")
            else:
                errors.append(f"primary model not found: {primary}")
        else:
            errors.append("primary model path is empty")

        fallback = (fallback_model or "").strip()
        if fallback:
            fallback_path = Path(fallback).expanduser()
            if not fallback_path.is_file():
                errors.append(f"fallback model not found: {fallback_path}")
            elif fallback_path.stat().st_size <= 0:
                errors.append(f"fallback model file is empty: {fallback_path}")
            else:
                try:
                    resolved = str(fallback_path.resolve())
                    return resolved, YOLO(resolved)
                except Exception as exc:
                    errors.append(f"failed to load fallback model '{fallback_path}': {exc}")

        raise RuntimeError("failed to load YOLOv11 model: " + " | ".join(errors))

    def _build_ocr_backend(self, enable_ocr: bool, ocr_device: str) -> None:
        if not enable_ocr:
            self.ocr_runtime_device = "disabled"
            self.ocr_warning = "paddleocr disabled by config"
            return

        ai_root = Path(__file__).resolve().parents[1]
        worker_error = ""
        try:
            self.ocr_worker = PaddleOcrWorker(ai_root=ai_root, ocr_device=ocr_device)
            self.ocr_runtime_device = self.ocr_worker.runtime_device
            if self.ocr_worker.warning:
                self._append_ocr_warning(self.ocr_worker.warning)
            return
        except Exception as exc:
            worker_error = str(exc).strip()
            self.ocr_worker = None

        self._append_ocr_warning(f"OCR worker unavailable, fallback to in-process OCR: {worker_error}")
        self.ocr = self._build_paddle_ocr(ocr_device)

    def _build_paddle_ocr(self, ocr_device: str):
        try:
            paddle_ocr_cls = getattr(import_module("paddleocr"), "PaddleOCR")
        except Exception as exc:
            raise RuntimeError(f"paddleocr unavailable: {exc}") from exc
        param_names, accepts_kwargs = _resolve_paddle_ocr_signature(paddle_ocr_cls)

        prefer_gpu = ocr_device in {"gpu", "cuda", "cuda:0", "gpu:0", "0"}
        compiled_with_cuda = self._is_paddle_cuda_build()
        device_fallback_warning = ""
        if prefer_gpu and not compiled_with_cuda:
            device_fallback_warning = "paddlepaddle is CPU build; fallback to CPU OCR"
            prefer_gpu = False

        ai_root = Path(__file__).resolve().parents[1]
        local_det_dir = ai_root / "models" / "paddle" / "ch_PP-OCRv4_det_infer"
        local_rec_dir = ai_root / "models" / "paddle" / "ch_PP-OCRv4_rec_infer"
        local_cls_dir = ai_root / "models" / "paddle" / "ch_ppocr_mobile_v2.0_cls_infer"
        if not (local_det_dir.is_dir() and local_rec_dir.is_dir()):
            local_det_dir = ai_root / "models" / "paddle" / "PP-OCRv4_mobile_det"
            local_rec_dir = ai_root / "models" / "paddle" / "PP-OCRv4_mobile_rec"
        if not local_cls_dir.is_dir():
            local_cls_dir = ai_root / "models" / "paddle" / "PP-LCNet_x1_0_textline_ori"
        has_local_models = local_det_dir.is_dir() and local_rec_dir.is_dir()

        strategy_kwargs = []
        if has_local_models:
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
                    self.ocr_runtime_device = "gpu" if use_gpu else "cpu"
                    if device_fallback_warning:
                        self._append_ocr_warning(device_fallback_warning)
                    if prefer_gpu and not use_gpu:
                        self._append_ocr_warning("GPU OCR init failed; fallback to CPU OCR")
                    return ocr
                except Exception as exc:
                    last_exc = exc

        raise RuntimeError(f"failed to initialize paddleocr: {last_exc}") from last_exc

    def _is_paddle_cuda_build(self) -> bool:
        try:
            paddle_module = import_module("paddle")
            device_api = getattr(paddle_module, "device", None)
            checker = getattr(device_api, "is_compiled_with_cuda", None)
            if callable(checker):
                return bool(checker())
        except Exception:
            return False
        return False

    def _normalize_yolo_device(self, raw: str | None) -> str:
        value = (raw or "").strip()
        return value if value else "auto"

    def _normalize_ocr_device(self, raw: str | None) -> str:
        value = (raw or "").strip().lower()
        if value in {"", "auto"}:
            return "gpu"
        if value in {"gpu", "cuda", "cuda:0", "gpu:0", "0"}:
            return "gpu"
        return "cpu"

    def _append_ocr_warning(self, message: str) -> None:
        if not message:
            return
        if not self.ocr_warning:
            self.ocr_warning = message
            return
        if message not in self.ocr_warning:
            self.ocr_warning = f"{self.ocr_warning}; {message}"

    def close(self) -> None:
        worker = self.ocr_worker
        self.ocr_worker = None
        if worker is not None:
            worker.close()

    def __del__(self) -> None:
        try:
            self.close()
        except Exception:
            pass

    def process_frame(self, frame: Any, conf_threshold: float = 0.45) -> list[dict[str, Any]]:
        start_total = perf_counter()
        if frame is None:
            self.last_timing = {"detect_ms": 0.0, "ocr_ms": 0.0, "total_ms": 0.0}
            return []

        if self.ocr is None and self.ocr_worker is None:
            self.last_timing = {"detect_ms": 0.0, "ocr_ms": 0.0, "total_ms": 0.0}
            return []

        conf = max(0.01, min(float(conf_threshold), 0.99))
        detect_start = perf_counter()
        if self.yolo_device_requested == "auto":
            results = self.yolo.predict(source=frame, conf=conf, verbose=False)
        else:
            results = self.yolo.predict(source=frame, conf=conf, verbose=False, device=self.yolo_device_requested)
        detect_ms = (perf_counter() - detect_start) * 1000.0

        predictor = getattr(self.yolo, "predictor", None)
        predictor_device = getattr(predictor, "device", None)
        if predictor_device is not None:
            self.yolo_runtime_device = str(predictor_device)
        elif self.yolo_device_requested != "auto":
            self.yolo_runtime_device = self.yolo_device_requested

        candidates: list[dict[str, Any]] = []
        ocr_ms = 0.0
        for result in results:
            boxes = getattr(result, "boxes", None)
            if boxes is None:
                continue

            for box in boxes:
                x1, y1, x2, y2 = self._extract_bbox(box, frame)
                if x2 <= x1 or y2 <= y1:
                    continue

                crop = frame[y1:y2, x1:x2]
                if crop is None or getattr(crop, "size", 0) == 0:
                    continue

                ocr_start = perf_counter()
                plate, ocr_conf = self._ocr_plate(crop)
                ocr_ms += (perf_counter() - ocr_start) * 1000.0
                if not plate:
                    continue

                det_conf = self._extract_confidence(box)
                score = self._merge_confidence(det_conf, ocr_conf)
                candidates.append(
                    {
                        "plate_number": plate,
                        "confidence": score,
                        "detection_confidence": det_conf,
                        "ocr_confidence": ocr_conf,
                        "crop_image": crop,
                        "bbox": [x1, y1, x2, y2],
                        "model_source": self.model_source,
                    }
                )

        deduped = self._deduplicate_candidates(candidates)
        deduped.sort(key=lambda item: float(item.get("confidence", 0.0)), reverse=True)
        total_ms = (perf_counter() - start_total) * 1000.0
        self.last_timing = {
            "detect_ms": round(detect_ms, 3),
            "ocr_ms": round(ocr_ms, 3),
            "total_ms": round(total_ms, 3),
        }
        return deduped

    def _extract_bbox(self, box: Any, frame: Any) -> tuple[int, int, int, int]:
        raw = box.xyxy[0]
        x1, y1, x2, y2 = map(int, raw)

        height, width = frame.shape[:2]
        margin_x = max(2, int((x2 - x1) * 0.08))
        margin_y = max(2, int((y2 - y1) * 0.12))

        x1 = max(0, x1 - margin_x)
        y1 = max(0, y1 - margin_y)
        x2 = min(width, x2 + margin_x)
        y2 = min(height, y2 + margin_y)
        return x1, y1, x2, y2

    def _extract_confidence(self, box: Any) -> float:
        return max(0.0, min(_safe_float(getattr(box, "conf", [[0.0]])[0], 0.0), 1.0))

    def _ocr_plate(self, image: Any) -> tuple[str, float]:
        worker = self.ocr_worker
        if worker is not None:
            try:
                plate, score = worker.recognize(image)
                normalized = normalize_plate_text(plate)
                if normalized:
                    return normalized, max(0.0, min(score, 1.0))
            except Exception as exc:
                self._append_ocr_warning(f"ocr worker runtime failed: {exc}")

        if self.ocr is None:
            return "", 0.0

        try:
            try:
                raw = self.ocr.ocr(image, cls=True)
            except TypeError:
                raw = self.ocr.ocr(image)
        except Exception as exc:
            self._append_ocr_warning(f"paddleocr runtime failed: {exc}")
            return "", 0.0

        tokens: list[tuple[str, float]] = []
        self._collect_text_tokens(raw, tokens)
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

    def _collect_text_tokens(self, value: Any, out: list[tuple[str, float]]) -> None:
        if value is None:
            return

        if isinstance(value, str):
            text = value.strip()
            if text:
                out.append((text, 0.0))
            return

        if isinstance(value, dict):
            for nested in value.values():
                self._collect_text_tokens(nested, out)
            return

        if isinstance(value, (list, tuple)):
            if len(value) == 2 and isinstance(value[0], str):
                text = value[0].strip()
                if text:
                    out.append((text, _safe_float(value[1], 0.0)))
                return
            for nested in value:
                self._collect_text_tokens(nested, out)

    def _merge_confidence(self, det_conf: float, ocr_conf: float) -> float:
        detection = max(0.0, min(det_conf, 1.0))
        ocr = max(0.0, min(ocr_conf if ocr_conf > 0.0 else 0.5, 1.0))
        return round(detection * 0.65 + ocr * 0.35, 4)

    def _deduplicate_candidates(self, candidates: list[dict[str, Any]]) -> list[dict[str, Any]]:
        best_map: dict[str, dict[str, Any]] = {}
        for candidate in candidates:
            plate = str(candidate.get("plate_number", "")).strip()
            if not plate:
                continue
            previous = best_map.get(plate)
            if previous is None or float(candidate.get("confidence", 0.0)) > float(previous.get("confidence", 0.0)):
                best_map[plate] = candidate
        return list(best_map.values())
