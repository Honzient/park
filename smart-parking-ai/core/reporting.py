from __future__ import annotations

from pathlib import Path
from typing import Any


def to_jsonable(value: Any) -> Any:
    if value is None:
        return None
    if isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, dict):
        return {str(key): to_jsonable(nested) for key, nested in value.items()}
    if isinstance(value, (list, tuple, set)):
        return [to_jsonable(item) for item in value]
    if hasattr(value, "item"):
        try:
            return value.item()
        except Exception:
            pass
    if hasattr(value, "tolist"):
        try:
            return value.tolist()
        except Exception:
            pass
    return str(value)


def extract_metric_bundle(metrics_obj: Any) -> dict[str, Any]:
    if metrics_obj is None:
        return {}

    result: dict[str, Any] = {}

    results_dict = getattr(metrics_obj, "results_dict", None)
    if isinstance(results_dict, dict):
        result["results_dict"] = to_jsonable(results_dict)

    fitness = getattr(metrics_obj, "fitness", None)
    if fitness is not None:
        result["fitness"] = to_jsonable(fitness)

    speed = getattr(metrics_obj, "speed", None)
    if speed is not None:
        result["speed"] = to_jsonable(speed)

    box = getattr(metrics_obj, "box", None)
    if box is not None:
        for attr in ("map", "map50", "map75", "mp", "mr"):
            if hasattr(box, attr):
                result[f"box_{attr}"] = to_jsonable(getattr(box, attr))

    return result
