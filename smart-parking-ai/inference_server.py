from __future__ import annotations

import os
import re
import tempfile
from dataclasses import dataclass
from pathlib import Path
from threading import Lock
from time import perf_counter
from typing import Any

import cv2
import numpy as np
from fastapi import FastAPI, File, Form, UploadFile
from fastapi.responses import JSONResponse

from core.ocr_worker import PaddleOcrWorker
from core.runtime_env import bootstrap_runtime_env

bootstrap_runtime_env()

from ultralytics import YOLO

CHINA_PLATE_PATTERN = re.compile(
    r"([京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼使领][A-HJ-NP-Z][A-HJ-NP-Z0-9]{4,5}[A-HJ-NP-Z0-9挂学警港澳])"
)


def parse_bool(raw: str | None, default: bool) -> bool:
    if raw is None:
        return default
    normalized = raw.strip().lower()
    if normalized in {"1", "true", "yes", "y", "on"}:
        return True
    if normalized in {"0", "false", "no", "n", "off"}:
        return False
    return default


def parse_float(raw: str | None, default: float) -> float:
    try:
        if raw is None or not raw.strip():
            return default
        return float(raw.strip())
    except Exception:
        return default


def parse_int(raw: str | None, default: int) -> int:
    try:
        if raw is None or not raw.strip():
            return default
        return int(raw.strip())
    except Exception:
        return default


def _safe_float(value: Any, default: float = 0.0) -> float:
    try:
        return float(value)
    except Exception:
        return default


def error_payload(message: str) -> dict[str, Any]:
    return {
        "status": "error",
        "message": message,
    }


def error_response(message: str, status_code: int) -> JSONResponse:
    return JSONResponse(status_code=status_code, content=error_payload(message))


def infer_error_status_code(message: str) -> int:
    normalized = (message or "").strip().lower()
    if "no plate recognized" in normalized or "did not return a valid plate number" in normalized:
        return 422
    if "timed out" in normalized or "timeout" in normalized:
        return 504
    return 500


def decode_image_bytes(data: bytes, source_label: str) -> np.ndarray:
    array = np.frombuffer(data, dtype=np.uint8)
    frame = cv2.imdecode(array, cv2.IMREAD_COLOR)
    if frame is None:
        raise RuntimeError(f"failed to decode image: {source_label}")
    return frame


def select_best(candidates: list[dict[str, Any]]) -> dict[str, Any] | None:
    if not candidates:
        return None
    return max(candidates, key=lambda item: float(item.get("confidence", 0.0)))


def resolve_model_arg(raw_path: str, fallback_model: str) -> tuple[str, str | None]:
    model_path = Path(raw_path.strip()).expanduser() if raw_path and raw_path.strip() else None
    if model_path and model_path.is_file() and model_path.stat().st_size > 0:
        return str(model_path.resolve()), None

    fallback = Path(fallback_model.strip()).expanduser() if fallback_model and fallback_model.strip() else None
    if fallback and fallback.is_file() and fallback.stat().st_size > 0:
        warning = f"primary model unavailable, fallback model will be used: {fallback.resolve()}"
        return str(fallback.resolve()), warning

    if model_path is None:
        raise RuntimeError("model path is empty")
    if not model_path.exists():
        raise RuntimeError(f"YOLO model not found: {model_path}")
    if model_path.is_file() and model_path.stat().st_size <= 0:
        raise RuntimeError(f"YOLO model file is empty: {model_path}")
    raise RuntimeError(f"YOLO model is invalid: {model_path}")


@dataclass(frozen=True)
class RuntimeConfig:
    ai_root: Path
    model: str
    fallback_model: str
    conf_threshold: float
    yolo_device: str
    ocr_device: str
    enable_ocr: bool
    video_frame_step: int
    video_max_frames: int
    video_target_fps: float
    video_early_stop_conf: float
    video_early_stop_min_frames: int


def load_runtime_config() -> RuntimeConfig:
    ai_root = Path(__file__).resolve().parent
    return RuntimeConfig(
        ai_root=ai_root,
        model=(os.getenv("PARKING_RECOGNITION_MODEL", "models/yolo11n_plate.pt") or "models/yolo11n_plate.pt").strip(),
        fallback_model=(os.getenv("PARKING_RECOGNITION_FALLBACK_MODEL", "") or "").strip(),
        conf_threshold=parse_float(os.getenv("PARKING_RECOGNITION_CONF"), 0.45),
        yolo_device=(os.getenv("PARKING_RECOGNITION_YOLO_DEVICE", "0") or "0").strip(),
        ocr_device=(os.getenv("PARKING_RECOGNITION_OCR_DEVICE", "gpu") or "gpu").strip(),
        enable_ocr=parse_bool(os.getenv("PARKING_RECOGNITION_ENABLE_OCR"), True),
        video_frame_step=parse_int(os.getenv("PARKING_RECOGNITION_VIDEO_FRAME_STEP"), 12),
        video_max_frames=parse_int(os.getenv("PARKING_RECOGNITION_VIDEO_MAX_FRAMES"), 240),
        video_target_fps=parse_float(os.getenv("PARKING_RECOGNITION_VIDEO_TARGET_FPS"), 5.0),
        video_early_stop_conf=parse_float(os.getenv("PARKING_RECOGNITION_VIDEO_EARLY_STOP_CONF"), 0.94),
        video_early_stop_min_frames=parse_int(os.getenv("PARKING_RECOGNITION_VIDEO_EARLY_STOP_MIN_FRAMES"), 3),
    )


class InferenceRuntime:
    def __init__(self) -> None:
        self.config = load_runtime_config()
        self.lock = Lock()
        self.yolo_model: YOLO | None = None
        self.ocr_worker: PaddleOcrWorker | None = None
        self.model_source = ""
        self.model_warning = ""
        self.model_load_ms = 0.0
        self.warmup_ms = 0.0
        self.warmup_warning = ""
        self.yolo_runtime_device = self.config.yolo_device or "auto"
        self.ocr_runtime_device = "disabled"
        self.ocr_warning = ""

    def _run_warmup(self, yolo: YOLO, ocr_worker: PaddleOcrWorker | None) -> tuple[float, str]:
        warmup_start = perf_counter()
        warning = ""
        try:
            # Warm up CUDA kernels for detector so first real request does not pay cold-start cost.
            warmup_frame = np.zeros((640, 640, 3), dtype=np.uint8)
            conf = max(0.01, min(float(self.config.conf_threshold), 0.99))
            if self.config.yolo_device and self.config.yolo_device.lower() != "auto":
                _ = yolo.predict(source=warmup_frame, conf=conf, verbose=False, device=self.config.yolo_device)
            else:
                _ = yolo.predict(source=warmup_frame, conf=conf, verbose=False)
            predictor = getattr(yolo, "predictor", None)
            predictor_device = getattr(predictor, "device", None)
            if predictor_device is not None:
                self.yolo_runtime_device = str(predictor_device)

            # Warm up OCR worker path to reduce first OCR request latency.
            if ocr_worker is not None:
                ocr_dummy = np.zeros((96, 320, 3), dtype=np.uint8)
                _ = ocr_worker.recognize(ocr_dummy)
        except Exception as exc:
            warning = f"warm-up failed, runtime will continue without warm-up: {exc}"
        return (perf_counter() - warmup_start) * 1000.0, warning

    def _looks_like_generic_yolo_checkpoint(self, model_path: str) -> bool:
        file_name = Path((model_path or "").strip()).name.lower()
        return bool(re.fullmatch(r"yolo11[nslmx]?\.pt", file_name))

    def ensure_loaded(self) -> None:
        if self.yolo_model is not None:
            return

        with self.lock:
            if self.yolo_model is not None:
                return

            configured_model = self.config.model
            preferred_model = (self.config.ai_root / "models" / "yolo11n_plate.pt").resolve()
            warning_messages: list[str] = []
            if self._looks_like_generic_yolo_checkpoint(configured_model) and preferred_model.is_file():
                warning_messages.append(
                    f"configured model '{configured_model}' looks generic; switched to preferred plate model '{preferred_model}'"
                )
                configured_model = str(preferred_model)

            model_path, warning = resolve_model_arg(configured_model, self.config.fallback_model)
            if warning:
                warning_messages.append(warning)

            start = perf_counter()
            yolo = YOLO(model_path)
            ocr_worker: PaddleOcrWorker | None = None
            if self.config.enable_ocr:
                ocr_worker = PaddleOcrWorker(self.config.ai_root, self.config.ocr_device)
                self.ocr_runtime_device = ocr_worker.runtime_device
                if ocr_worker.warning:
                    self.ocr_warning = ocr_worker.warning
            else:
                self.ocr_runtime_device = "disabled"
                self.ocr_warning = "OCR disabled by configuration"

            self.model_load_ms = (perf_counter() - start) * 1000.0
            self.yolo_model = yolo
            self.ocr_worker = ocr_worker
            self.model_source = model_path
            self.model_warning = "; ".join([text for text in warning_messages if text])
            self.warmup_ms, warmup_warning = self._run_warmup(yolo, ocr_worker)
            self.warmup_warning = warmup_warning
            if warmup_warning:
                if self.model_warning:
                    self.model_warning = f"{self.model_warning}; {warmup_warning}"
                else:
                    self.model_warning = warmup_warning

    def _extract_bbox(self, box: Any, frame: np.ndarray) -> tuple[int, int, int, int]:
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

    def _normalize_plate_text(self, text: str) -> str:
        if not text:
            return ""
        normalized = re.sub(r"[^0-9A-Z\u4e00-\u9fa5]", "", text.upper())
        if not normalized:
            return ""
        matched = CHINA_PLATE_PATTERN.search(normalized)
        if matched:
            return matched.group(1)
        return ""

    def process_frame(self, frame: np.ndarray, conf_threshold: float) -> tuple[list[dict[str, Any]], dict[str, float]]:
        self.ensure_loaded()
        assert self.yolo_model is not None
        if frame is None:
            return [], {"detect_ms": 0.0, "ocr_ms": 0.0, "total_ms": 0.0}
        if self.config.enable_ocr and self.ocr_worker is None:
            raise RuntimeError("OCR worker is not initialized")

        conf = max(0.01, min(float(conf_threshold), 0.99))
        start_total = perf_counter()
        detect_start = perf_counter()
        if self.config.yolo_device and self.config.yolo_device.lower() != "auto":
            results = self.yolo_model.predict(source=frame, conf=conf, verbose=False, device=self.config.yolo_device)
        else:
            results = self.yolo_model.predict(source=frame, conf=conf, verbose=False)
        detect_ms = (perf_counter() - detect_start) * 1000.0

        predictor = getattr(self.yolo_model, "predictor", None)
        predictor_device = getattr(predictor, "device", None)
        if predictor_device is not None:
            self.yolo_runtime_device = str(predictor_device)

        candidates: list[dict[str, Any]] = []
        ocr_ms_total = 0.0
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

                det_conf = max(0.0, min(_safe_float(getattr(box, "conf", [[0.0]])[0], 0.0), 1.0))
                plate = ""
                ocr_conf = 0.0
                if self.ocr_worker is not None:
                    ocr_start = perf_counter()
                    plate, ocr_conf = self.ocr_worker.recognize(crop)
                    ocr_ms = (perf_counter() - ocr_start) * 1000.0
                    ocr_ms_total += ocr_ms
                    plate = self._normalize_plate_text(plate)
                if not plate:
                    continue
                merged = round(det_conf * 0.65 + max(0.0, min(ocr_conf if ocr_conf > 0.0 else 0.5, 1.0)) * 0.35, 4)
                candidates.append(
                    {
                        "plate_number": plate,
                        "confidence": merged,
                        "detection_confidence": det_conf,
                        "ocr_confidence": ocr_conf,
                        "bbox": [x1, y1, x2, y2],
                    }
                )

        deduped: dict[str, dict[str, Any]] = {}
        for candidate in candidates:
            plate = str(candidate.get("plate_number", "")).strip()
            if not plate:
                continue
            previous = deduped.get(plate)
            if previous is None or float(candidate.get("confidence", 0.0)) > float(previous.get("confidence", 0.0)):
                deduped[plate] = candidate

        output = list(deduped.values())
        output.sort(key=lambda item: float(item.get("confidence", 0.0)), reverse=True)
        total_ms = (perf_counter() - start_total) * 1000.0
        timing = {
            "detect_ms": round(detect_ms, 3),
            "ocr_ms": round(ocr_ms_total, 3),
            "total_ms": round(total_ms, 3),
        }
        return output, timing

    def infer_image(self, image_data: bytes, source_label: str, conf: float) -> dict[str, Any]:
        self.ensure_loaded()
        frame = decode_image_bytes(image_data, source_label)
        infer_start = perf_counter()
        with self.lock:
            candidates, timing = self.process_frame(frame, conf)
        inference_ms = (perf_counter() - infer_start) * 1000.0
        best = select_best(candidates)
        if best is None:
            raise RuntimeError("no plate recognized from image")

        payload: dict[str, Any] = {
            "status": "success",
            "engine": "yolov11+paddleocr",
            "mode": "image",
            "source": source_label,
            "model_source": self.model_source,
            "yolo_device_requested": self.config.yolo_device or "auto",
            "yolo_device_runtime": self.yolo_runtime_device,
            "ocr_device_requested": self.config.ocr_device or "auto",
            "ocr_device_runtime": self.ocr_runtime_device,
            "plate_number": str(best["plate_number"]),
            "accuracy": round(float(best["confidence"]) * 100, 2),
            "detections": len(candidates),
            "frames_processed": 1,
            "timings_ms": {
                "model_load": round(self.model_load_ms, 3),
                "warmup": round(self.warmup_ms, 3),
                "inference": round(inference_ms, 3),
                "detect": round(float(timing.get("detect_ms", 0.0)), 3),
                "ocr": round(float(timing.get("ocr_ms", 0.0)), 3),
                "frame_total": round(float(timing.get("total_ms", 0.0)), 3),
                "end_to_end": round(inference_ms, 3),
            },
        }
        if self.ocr_warning:
            payload["ocr_warning"] = self.ocr_warning
        if self.model_warning:
            payload["warning"] = self.model_warning
        return payload

    def infer_video(self, source: str, source_label: str, conf: float, frame_step: int, max_frames: int) -> dict[str, Any]:
        self.ensure_loaded()
        frame_step = max(1, int(frame_step))
        max_frames = max(1, int(max_frames))
        conf = max(0.01, min(float(conf), 0.99))

        cap = cv2.VideoCapture(source)
        if not cap.isOpened():
            raise RuntimeError(f"failed to open video source: {source_label}")

        source_fps = float(cap.get(cv2.CAP_PROP_FPS) or 0.0)
        target_fps = max(self.config.video_target_fps, 0.1)
        auto_step = max(1, int(round(source_fps / target_fps))) if source_fps > 0.0 else 1
        effective_step = max(frame_step, auto_step)
        early_stop_conf = max(0.0, min(self.config.video_early_stop_conf, 1.0))
        early_stop_min_frames = max(1, self.config.video_early_stop_min_frames)

        sampled_count = 0
        total_candidates = 0
        best: dict[str, Any] | None = None
        detect_ms_total = 0.0
        ocr_ms_total = 0.0
        frame_total_ms = 0.0
        infer_start = perf_counter()

        try:
            with self.lock:
                while sampled_count < max_frames:
                    if effective_step > 1:
                        skip_ok = True
                        for _ in range(effective_step - 1):
                            if not cap.grab():
                                skip_ok = False
                                break
                        if not skip_ok:
                            break
                    ok, frame = cap.read()
                    if not ok:
                        break
                    sampled_count += 1
                    candidates, timing = self.process_frame(frame, conf)
                    detect_ms_total += float(timing.get("detect_ms", 0.0))
                    ocr_ms_total += float(timing.get("ocr_ms", 0.0))
                    frame_total_ms += float(timing.get("total_ms", 0.0))
                    total_candidates += len(candidates)
                    current_best = select_best(candidates)
                    if current_best is not None:
                        if best is None or float(current_best["confidence"]) > float(best["confidence"]):
                            best = current_best
                        if sampled_count >= early_stop_min_frames and float(best["confidence"]) >= early_stop_conf:
                            break
        finally:
            cap.release()

        if best is None:
            raise RuntimeError("no plate recognized from video source")

        inference_ms = (perf_counter() - infer_start) * 1000.0
        payload: dict[str, Any] = {
            "status": "success",
            "engine": "yolov11+paddleocr",
            "mode": "video",
            "source": source_label,
            "model_source": self.model_source,
            "yolo_device_requested": self.config.yolo_device or "auto",
            "yolo_device_runtime": self.yolo_runtime_device,
            "ocr_device_requested": self.config.ocr_device or "auto",
            "ocr_device_runtime": self.ocr_runtime_device,
            "plate_number": str(best["plate_number"]),
            "accuracy": round(float(best["confidence"]) * 100, 2),
            "detections": total_candidates,
            "frames_processed": sampled_count,
            "effective_frame_step": effective_step,
            "source_fps": round(source_fps, 3),
            "timings_ms": {
                "model_load": round(self.model_load_ms, 3),
                "warmup": round(self.warmup_ms, 3),
                "inference": round(inference_ms, 3),
                "detect": round(detect_ms_total, 3),
                "ocr": round(ocr_ms_total, 3),
                "frame_total": round(frame_total_ms, 3),
                "end_to_end": round(inference_ms, 3),
            },
        }
        if self.ocr_warning:
            payload["ocr_warning"] = self.ocr_warning
        if self.model_warning:
            payload["warning"] = self.model_warning
        return payload


app = FastAPI(title="Smart Parking Inference API", version="2.0.0")
runtime = InferenceRuntime()


@app.on_event("startup")
def startup() -> None:
    runtime.ensure_loaded()


@app.on_event("shutdown")
def shutdown() -> None:
    worker = runtime.ocr_worker
    if worker is not None:
        worker.close()


@app.get("/health")
def health() -> dict[str, Any]:
    try:
        runtime.ensure_loaded()
    except Exception as exc:
        return {"status": "error", "message": str(exc)}
    payload: dict[str, Any] = {
        "status": "ok",
        "engine": "yolov11+paddleocr",
        "model_source": runtime.model_source,
        "model_load_ms": round(runtime.model_load_ms, 3),
        "warmup_ms": round(runtime.warmup_ms, 3),
        "yolo_device_requested": runtime.config.yolo_device or "auto",
        "yolo_device_runtime": runtime.yolo_runtime_device,
        "ocr_device_requested": runtime.config.ocr_device or "auto",
        "ocr_device_runtime": runtime.ocr_runtime_device,
    }
    if runtime.ocr_warning:
        payload["ocr_warning"] = runtime.ocr_warning
    if runtime.model_warning:
        payload["warning"] = runtime.model_warning
    return payload


@app.post("/recognize/image")
async def recognize_image(file: UploadFile = File(...), conf: float = Form(default=0.45)) -> JSONResponse:
    try:
        image_data = await file.read()
        if not image_data:
            return error_response("image file is empty", 400)
        source_label = file.filename or "uploaded-image"
        payload = runtime.infer_image(image_data=image_data, source_label=source_label, conf=conf)
        return JSONResponse(status_code=200, content=payload)
    except Exception as exc:
        message = str(exc)
        return error_response(message, infer_error_status_code(message))


@app.post("/recognize/video")
async def recognize_video(
    file: UploadFile | None = File(default=None),
    stream_url: str | None = Form(default=None, alias="streamUrl"),
    conf: float | None = Form(default=None),
    video_frame_step: int | None = Form(default=None, alias="videoFrameStep"),
    video_max_frames: int | None = Form(default=None, alias="videoMaxFrames"),
) -> JSONResponse:
    source = (stream_url or "").strip()
    if file is None and not source:
        return error_response("video file or stream URL is required", 400)

    conf_value = runtime.config.conf_threshold if conf is None else float(conf)
    frame_step = runtime.config.video_frame_step if video_frame_step is None else int(video_frame_step)
    max_frames = runtime.config.video_max_frames if video_max_frames is None else int(video_max_frames)

    temp_video_path: str | None = None
    source_label = source
    try:
        if file is not None:
            video_data = await file.read()
            if not video_data:
                return error_response("video file is empty", 400)
            suffix = Path(file.filename or "").suffix or ".mp4"
            with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
                tmp.write(video_data)
                temp_video_path = tmp.name
            source = temp_video_path
            source_label = file.filename or "uploaded-video"

        payload = runtime.infer_video(
            source=source,
            source_label=source_label,
            conf=conf_value,
            frame_step=frame_step,
            max_frames=max_frames,
        )
        return JSONResponse(status_code=200, content=payload)
    except Exception as exc:
        message = str(exc)
        return error_response(message, infer_error_status_code(message))
    finally:
        if temp_video_path:
            try:
                os.remove(temp_video_path)
            except OSError:
                pass
