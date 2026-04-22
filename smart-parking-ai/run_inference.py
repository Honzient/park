from __future__ import annotations

import argparse
import json
from pathlib import Path
from time import perf_counter
from typing import Any

import cv2
import numpy as np

from core.recognizer import PlateRecognizer
from core.video_tracking import PlateVideoTracker


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="YOLOv11 + PaddleOCR plate recognition CLI")
    parser.add_argument("--mode", choices=["image", "video"], required=True)
    parser.add_argument("--model", required=True, help="Path to YOLOv11 plate model")
    parser.add_argument("--fallback-model", default="", help="Optional fallback YOLOv11 model path")
    parser.add_argument("--conf", type=float, default=0.45, help="YOLO confidence threshold")
    parser.add_argument("--input", help="Image/video file path")
    parser.add_argument("--stream-url", help="Video stream URL, e.g. rtsp://...")
    parser.add_argument("--video-frame-step", type=int, default=12)
    parser.add_argument("--video-max-frames", type=int, default=240)
    parser.add_argument("--video-target-fps", type=float, default=5.0)
    parser.add_argument("--video-iou-threshold", type=float, default=0.3)
    parser.add_argument("--video-track-max-missed", type=int, default=3)
    parser.add_argument("--video-min-support", type=int, default=2)
    parser.add_argument("--save-json", default="", help="Optional path used to save the JSON payload")
    parser.add_argument("--save-video", default="", help="Optional path used to save the annotated sampled video")
    parser.add_argument("--yolo-device", default="auto", help="YOLO device: auto / cpu / 0 / 0,1 / cuda:0")
    parser.add_argument("--ocr-device", default="gpu", help="PaddleOCR device preference: gpu / cpu / auto")
    parser.add_argument("--enable-ocr", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--disable-ocr", action="store_true", help="Disable PaddleOCR (not recommended)")
    return parser.parse_args()


def load_image(input_path: str):
    path = Path(input_path)
    if not path.exists():
        raise RuntimeError(f"input image not found: {input_path}")
    data = np.fromfile(str(path), dtype=np.uint8)
    frame = cv2.imdecode(data, cv2.IMREAD_COLOR)
    if frame is None:
        raise RuntimeError(f"failed to decode image: {input_path}")
    return frame


def select_best(candidates: list[dict[str, Any]]) -> dict[str, Any] | None:
    if not candidates:
        return None
    return max(candidates, key=lambda item: float(item.get("confidence", 0.0)))


def ensure_parent(path_text: str) -> Path:
    path = Path(path_text).expanduser().resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    return path


def save_payload(path_text: str, payload: dict[str, Any]) -> str:
    path = ensure_parent(path_text)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return str(path)


def annotate_frame(frame: np.ndarray, candidates: list[dict[str, Any]], winner_track_id: int | None) -> np.ndarray:
    canvas = frame.copy()
    for candidate in candidates:
        bbox = candidate.get("bbox")
        if not bbox or len(bbox) != 4:
            continue
        x1, y1, x2, y2 = map(int, bbox)
        track_id = int(candidate.get("track_id", 0) or 0)
        color = (0, 220, 80) if winner_track_id is not None and track_id == winner_track_id else (0, 180, 255)
        label = (
            f"T{track_id} {candidate.get('plate_number', '')} "
            f"{float(candidate.get('confidence', 0.0)):.2f}"
        ).strip()
        cv2.rectangle(canvas, (x1, y1), (x2, y2), color, 2)
        cv2.putText(
            canvas,
            label,
            (x1, max(20, y1 - 8)),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.55,
            color,
            2,
            cv2.LINE_AA,
        )
    return canvas


def run_image(recognizer: PlateRecognizer, input_path: str, conf: float) -> dict[str, Any]:
    frame = load_image(input_path)
    candidates = recognizer.process_frame(frame, conf)
    best = select_best(candidates)
    if best is None:
        raise RuntimeError("no plate recognized from image")
    frame_timing = recognizer.last_timing if isinstance(recognizer.last_timing, dict) else {}
    return {
        "plate_number": str(best["plate_number"]),
        "accuracy": round(float(best["confidence"]) * 100, 2),
        "detections": len(candidates),
        "frames_processed": 1,
        "detect_ms": round(float(frame_timing.get("detect_ms", 0.0)), 3),
        "ocr_ms": round(float(frame_timing.get("ocr_ms", 0.0)), 3),
        "frame_total_ms": round(float(frame_timing.get("total_ms", 0.0)), 3),
        "candidates": [
            {
                "plate_number": str(item.get("plate_number", "")),
                "confidence": round(float(item.get("confidence", 0.0)), 4),
                "bbox": list(item.get("bbox", [])),
            }
            for item in candidates
        ],
    }


def run_video(
    recognizer: PlateRecognizer,
    conf: float,
    input_path: str | None,
    stream_url: str | None,
    frame_step: int,
    max_frames: int,
    target_fps: float,
    iou_threshold: float,
    track_max_missed: int,
    min_support: int,
    save_video_path: str,
) -> dict[str, Any]:
    source = stream_url if stream_url else input_path
    if not source:
        raise RuntimeError("video source is required")

    cap = cv2.VideoCapture(source)
    if not cap.isOpened():
        raise RuntimeError(f"failed to open video source: {source}")

    frame_step = max(1, frame_step)
    max_frames = max(1, max_frames)
    target_fps = max(0.1, float(target_fps))
    min_support = max(1, int(min_support))

    source_fps = float(cap.get(cv2.CAP_PROP_FPS) or 0.0)
    auto_step = max(1, int(round(source_fps / target_fps))) if source_fps > 0.0 else 1
    effective_step = max(frame_step, auto_step)

    tracker = PlateVideoTracker(iou_threshold=iou_threshold, max_missed=track_max_missed)
    writer = None
    saved_video = ""
    frame_size: tuple[int, int] | None = None

    sampled_count = 0
    total_candidates = 0
    detect_ms_total = 0.0
    ocr_ms_total = 0.0
    frame_total_ms = 0.0
    inference_start = perf_counter()

    try:
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
            candidates = recognizer.process_frame(frame, conf)
            annotated_candidates = tracker.update(sampled_count, candidates)
            frame_timing = recognizer.last_timing if isinstance(recognizer.last_timing, dict) else {}
            detect_ms_total += float(frame_timing.get("detect_ms", 0.0))
            ocr_ms_total += float(frame_timing.get("ocr_ms", 0.0))
            frame_total_ms += float(frame_timing.get("total_ms", 0.0))
            total_candidates += len(annotated_candidates)

            if save_video_path:
                if frame_size is None:
                    frame_size = (int(frame.shape[1]), int(frame.shape[0]))
                    output_path = ensure_parent(save_video_path)
                    save_fps = max(1.0, (source_fps / effective_step) if source_fps > 0.0 else target_fps)
                    writer = cv2.VideoWriter(
                        str(output_path),
                        cv2.VideoWriter_fourcc(*"mp4v"),
                        save_fps,
                        frame_size,
                    )
                    saved_video = str(output_path)
                winner_track_id = None
                current_tracks = tracker.finalize()["tracks"] if False else None
                _ = current_tracks
                annotated_frame = annotate_frame(frame, annotated_candidates, winner_track_id)
                if writer is not None:
                    writer.write(annotated_frame)
    finally:
        cap.release()
        if writer is not None:
            writer.release()

    finalized = tracker.finalize()
    winner = finalized.get("winner")
    if not winner:
        raise RuntimeError("no plate recognized from video source")

    warning = ""
    if int(winner.get("support_frames", 0)) < min_support:
        warning = (
            f"video consensus is weak: support_frames={winner.get('support_frames', 0)} "
            f"< min_support={min_support}"
        )

    inference_ms = (perf_counter() - inference_start) * 1000.0
    winning_score = max(float(winner.get("mean_confidence", 0.0)), float(winner.get("best_confidence", 0.0)))
    distinct_plates = [item.get("plate_number", "") for item in finalized.get("tracks", []) if item.get("plate_number")]

    return {
        "plate_number": str(winner.get("plate_number", "")),
        "accuracy": round(winning_score * 100, 2),
        "detections": total_candidates,
        "frames_processed": sampled_count,
        "support_frames": int(winner.get("support_frames", 0)),
        "vote_ratio": round(float(winner.get("vote_ratio", 0.0)), 4),
        "track_id": int(winner.get("track_id", 0)),
        "tracks_considered": len(finalized.get("tracks", [])),
        "distinct_plates": distinct_plates,
        "track_summaries": finalized.get("tracks", [])[:5],
        "effective_frame_step": effective_step,
        "source_fps": round(source_fps, 3),
        "detect_ms": round(detect_ms_total, 3),
        "ocr_ms": round(ocr_ms_total, 3),
        "frame_total_ms": round(frame_total_ms, 3),
        "inference_ms": round(inference_ms, 3),
        "saved_video": saved_video,
        "warning": warning,
    }


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


def error_payload(message: str) -> dict[str, Any]:
    return {
        "status": "error",
        "message": message,
    }


def main() -> int:
    args = parse_args()
    start_at = perf_counter()
    model_for_loader, warning = resolve_model_arg(args.model, args.fallback_model)

    model_load_start = perf_counter()
    recognizer = PlateRecognizer(
        yolo_model_path=model_for_loader,
        fallback_model=args.fallback_model,
        enable_ocr=not bool(args.disable_ocr),
        yolo_device=args.yolo_device,
        ocr_device=args.ocr_device,
    )
    model_load_ms = (perf_counter() - model_load_start) * 1000.0

    try:
        inference_start = perf_counter()
        if args.mode == "image":
            if not args.input:
                raise RuntimeError("--input is required for image mode")
            result = run_image(recognizer, args.input, args.conf)
            source = args.input
        else:
            if not args.input and not args.stream_url:
                raise RuntimeError("--input or --stream-url is required for video mode")
            result = run_video(
                recognizer=recognizer,
                conf=args.conf,
                input_path=args.input,
                stream_url=args.stream_url,
                frame_step=args.video_frame_step,
                max_frames=args.video_max_frames,
                target_fps=args.video_target_fps,
                iou_threshold=args.video_iou_threshold,
                track_max_missed=args.video_track_max_missed,
                min_support=args.video_min_support,
                save_video_path=args.save_video,
            )
            source = args.stream_url if args.stream_url else args.input
        inference_ms = (perf_counter() - inference_start) * 1000.0
        end_to_end_ms = (perf_counter() - start_at) * 1000.0

        payload: dict[str, Any] = {
            "status": "success",
            "engine": "yolov11+paddleocr",
            "mode": args.mode,
            "source": source,
            "model_source": recognizer.model_source,
            "yolo_device_requested": args.yolo_device if args.yolo_device.strip() else "auto",
            "yolo_device_runtime": recognizer.yolo_runtime_device,
            "ocr_device_requested": args.ocr_device if args.ocr_device.strip() else "auto",
            "ocr_device_runtime": recognizer.ocr_runtime_device,
            "plate_number": result["plate_number"],
            "accuracy": result["accuracy"],
            "detections": result["detections"],
            "frames_processed": result["frames_processed"],
            "timings_ms": {
                "model_load": round(model_load_ms, 3),
                "inference": round(inference_ms, 3),
                "detect": round(float(result.get("detect_ms", 0.0)), 3),
                "ocr": round(float(result.get("ocr_ms", 0.0)), 3),
                "frame_total": round(float(result.get("frame_total_ms", result.get("frame_total_ms", 0.0))), 3),
                "end_to_end": round(end_to_end_ms, 3),
            },
        }
        for extra_key in (
            "candidates",
            "support_frames",
            "vote_ratio",
            "track_id",
            "tracks_considered",
            "distinct_plates",
            "track_summaries",
            "effective_frame_step",
            "source_fps",
            "saved_video",
        ):
            if extra_key in result and result[extra_key]:
                payload[extra_key] = result[extra_key]
        if recognizer.ocr_warning:
            payload["ocr_warning"] = recognizer.ocr_warning
        if warning:
            payload["warning"] = warning
        if result.get("warning"):
            payload["video_warning"] = result["warning"]
        if args.save_json:
            payload["saved_json"] = save_payload(args.save_json, payload)

        print(json.dumps(payload, ensure_ascii=False))
        return 0
    finally:
        recognizer.close()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # pragma: no cover
        print(json.dumps(error_payload(str(exc)), ensure_ascii=False))
        raise SystemExit(1)
