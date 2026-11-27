"""Text normalization shared by training and inference.

The Java ``LinearTeacherIntentClassifier`` mirrors these rules exactly:
- replace known student names with the token ``某同学`` (longest name first),
- drop whitespace and punctuation, keeping Unicode letters, digits and ``_``.
"""

from __future__ import annotations

import re
from collections.abc import Iterable

_NON_WORD = re.compile(r"[^\w]+", re.UNICODE)
STUDENT_TOKEN = "某同学"


def normalize(text: str) -> str:
    return _NON_WORD.sub("", text or "")


def mask_student_names(text: str, names: Iterable[str]) -> str:
    masked = text or ""
    unique = sorted({name for name in names if name}, key=len, reverse=True)
    for name in unique:
        masked = masked.replace(name, STUDENT_TOKEN)
    return masked


def prepare(text: str, names: Iterable[str] = ()) -> str:
    return normalize(mask_student_names(text, names))
