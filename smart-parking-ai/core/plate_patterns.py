from __future__ import annotations

import re


CHINA_PLATE_PATTERN = re.compile(
    r"([京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼使领]"
    r"[A-HJ-NP-Z][A-HJ-NP-Z0-9]{4,5}[A-HJ-NP-Z0-9挂学警港澳])"
)

GENERIC_PLATE_PATTERN = re.compile(r"([\u4e00-\u9fff][A-HJ-NP-Z][A-HJ-NP-Z0-9]{5,6})")


def normalize_plate_text(text: str) -> str:
    if not text:
        return ""
    normalized = re.sub(r"[^0-9A-Z\u4e00-\u9fa5]", "", text.upper())
    if not normalized:
        return ""
    matched = CHINA_PLATE_PATTERN.search(normalized)
    if matched:
        return matched.group(1)
    generic = GENERIC_PLATE_PATTERN.search(normalized)
    if generic:
        return generic.group(1)
    if 6 <= len(normalized) <= 10:
        return normalized
    return ""
