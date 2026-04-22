from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


def _safe_float(value: Any, default: float = 0.0) -> float:
    try:
        return float(value)
    except Exception:
        return default


def bbox_iou(box_a: list[int] | tuple[int, int, int, int], box_b: list[int] | tuple[int, int, int, int]) -> float:
    ax1, ay1, ax2, ay2 = map(float, box_a)
    bx1, by1, bx2, by2 = map(float, box_b)
    inter_x1 = max(ax1, bx1)
    inter_y1 = max(ay1, by1)
    inter_x2 = min(ax2, bx2)
    inter_y2 = min(ay2, by2)
    inter_w = max(0.0, inter_x2 - inter_x1)
    inter_h = max(0.0, inter_y2 - inter_y1)
    inter = inter_w * inter_h
    if inter <= 0.0:
        return 0.0
    area_a = max(0.0, ax2 - ax1) * max(0.0, ay2 - ay1)
    area_b = max(0.0, bx2 - bx1) * max(0.0, by2 - by1)
    union = area_a + area_b - inter
    if union <= 0.0:
        return 0.0
    return inter / union


@dataclass
class TrackState:
    track_id: int
    first_frame: int
    last_frame: int
    last_bbox: list[int]
    hits: int = 0
    missed: int = 0
    best_confidence: float = 0.0
    best_candidate: dict[str, Any] = field(default_factory=dict)
    plate_votes: dict[str, int] = field(default_factory=dict)
    plate_conf_sum: dict[str, float] = field(default_factory=dict)

    def update(self, frame_index: int, candidate: dict[str, Any]) -> None:
        plate = str(candidate.get("plate_number", "")).strip()
        confidence = _safe_float(candidate.get("confidence", 0.0), 0.0)
        self.last_frame = frame_index
        self.last_bbox = list(candidate.get("bbox", self.last_bbox))
        self.hits += 1
        self.missed = 0
        if plate:
            self.plate_votes[plate] = self.plate_votes.get(plate, 0) + 1
            self.plate_conf_sum[plate] = self.plate_conf_sum.get(plate, 0.0) + confidence
        if confidence >= self.best_confidence:
            self.best_confidence = confidence
            self.best_candidate = dict(candidate)

    def top_plate(self) -> str:
        if not self.plate_votes:
            return ""
        best_plate = ""
        best_key = (-1, -1.0)
        for plate, votes in self.plate_votes.items():
            mean_conf = self.plate_conf_sum.get(plate, 0.0) / max(1, votes)
            key = (votes, mean_conf)
            if key > best_key:
                best_key = key
                best_plate = plate
        return best_plate

    def top_votes(self) -> int:
        plate = self.top_plate()
        return int(self.plate_votes.get(plate, 0)) if plate else 0

    def top_mean_confidence(self) -> float:
        plate = self.top_plate()
        votes = self.plate_votes.get(plate, 0)
        if not plate or votes <= 0:
            return 0.0
        return self.plate_conf_sum.get(plate, 0.0) / votes

    def vote_ratio(self) -> float:
        if self.hits <= 0:
            return 0.0
        return self.top_votes() / self.hits

    def summary(self) -> dict[str, Any]:
        top_plate = self.top_plate()
        summary_votes = [
            {"plate_number": plate, "votes": int(votes), "mean_confidence": round(self.plate_conf_sum.get(plate, 0.0) / max(1, votes), 4)}
            for plate, votes in sorted(
                self.plate_votes.items(),
                key=lambda item: (item[1], self.plate_conf_sum.get(item[0], 0.0)),
                reverse=True,
            )
        ]
        return {
            "track_id": self.track_id,
            "plate_number": top_plate,
            "support_frames": self.top_votes(),
            "track_hits": self.hits,
            "vote_ratio": round(self.vote_ratio(), 4),
            "mean_confidence": round(self.top_mean_confidence(), 4),
            "best_confidence": round(self.best_confidence, 4),
            "first_frame": self.first_frame,
            "last_frame": self.last_frame,
            "best_bbox": list(self.best_candidate.get("bbox", self.last_bbox)),
            "plate_votes": summary_votes,
        }


class PlateVideoTracker:
    def __init__(self, iou_threshold: float = 0.3, max_missed: int = 3) -> None:
        self.iou_threshold = max(0.05, min(float(iou_threshold), 0.95))
        self.max_missed = max(0, int(max_missed))
        self._next_track_id = 1
        self._active: dict[int, TrackState] = {}
        self._finished: list[TrackState] = []

    def _create_track(self, frame_index: int, candidate: dict[str, Any]) -> TrackState:
        track = TrackState(
            track_id=self._next_track_id,
            first_frame=frame_index,
            last_frame=frame_index,
            last_bbox=list(candidate.get("bbox", [0, 0, 0, 0])),
        )
        self._next_track_id += 1
        track.update(frame_index, candidate)
        return track

    def update(self, frame_index: int, candidates: list[dict[str, Any]]) -> list[dict[str, Any]]:
        candidates = [dict(item) for item in candidates]
        matches: list[tuple[float, int, int]] = []
        active_items = list(self._active.items())
        for track_id, track in active_items:
            for det_index, candidate in enumerate(candidates):
                bbox = candidate.get("bbox")
                if not bbox:
                    continue
                iou = bbox_iou(track.last_bbox, bbox)
                if iou >= self.iou_threshold:
                    matches.append((iou, track_id, det_index))
        matches.sort(reverse=True)

        assigned_tracks: set[int] = set()
        assigned_dets: set[int] = set()
        assignments: dict[int, int] = {}
        for _, track_id, det_index in matches:
            if track_id in assigned_tracks or det_index in assigned_dets:
                continue
            assigned_tracks.add(track_id)
            assigned_dets.add(det_index)
            assignments[det_index] = track_id

        for det_index, candidate in enumerate(candidates):
            if det_index in assignments:
                track = self._active[assignments[det_index]]
                track.update(frame_index, candidate)
                candidate["track_id"] = track.track_id
                continue
            track = self._create_track(frame_index, candidate)
            self._active[track.track_id] = track
            candidate["track_id"] = track.track_id

        stale_tracks: list[int] = []
        for track_id, track in self._active.items():
            if track_id in assigned_tracks:
                continue
            if any(candidate.get("track_id") == track_id for candidate in candidates):
                continue
            track.missed += 1
            if track.missed > self.max_missed:
                stale_tracks.append(track_id)

        for track_id in stale_tracks:
            self._finished.append(self._active.pop(track_id))

        return candidates

    def finalize(self) -> dict[str, Any]:
        all_tracks = self._finished + list(self._active.values())
        self._finished = all_tracks
        self._active = {}
        summaries = [track.summary() for track in all_tracks]
        summaries.sort(
            key=lambda item: (
                int(item.get("support_frames", 0)),
                float(item.get("vote_ratio", 0.0)),
                float(item.get("mean_confidence", 0.0)),
                float(item.get("best_confidence", 0.0)),
                int(item.get("track_hits", 0)),
            ),
            reverse=True,
        )
        winner = summaries[0] if summaries else None
        return {
            "winner": winner,
            "tracks": summaries,
        }
