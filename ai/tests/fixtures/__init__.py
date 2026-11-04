from __future__ import annotations

import base64
import copy
import json
from pathlib import Path
from typing import Any

FIXTURES_DIR = Path(__file__).resolve().parent


def load_classroom_requests() -> dict[str, dict[str, Any]]:
    data = json.loads(
        (FIXTURES_DIR / "classroom_requests.json").read_text(encoding="utf-8")
    )
    context = data["context"]
    return {
        "question": {"context": copy.deepcopy(context), **data["question"]},
        "answer": {"context": copy.deepcopy(context), **data["answer"]},
        "classify": data["classify"],
    }


def load_expected() -> dict[str, Any]:
    return json.loads((FIXTURES_DIR / "expected.json").read_text(encoding="utf-8"))


def ocr_image_base64() -> str:
    return base64.b64encode(
        (FIXTURES_DIR / "images" / "ocr_sample.png").read_bytes()
    ).decode("ascii")


def asr_sample_path() -> Path:
    return FIXTURES_DIR / "audio" / "asr_sample.wav"
