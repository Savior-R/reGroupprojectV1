"""Audio and keyframe helpers for M4 evaluation (06 §6.2, §6.4)."""

from __future__ import annotations

import io
import math
import struct
import wave
from typing import Iterable

import httpx

from app.schemas.evaluation import AudioFeatures, FrameItem

WINDOW_MS = 200
SILENCE_DBFS = -40.0
LONG_PAUSE_MS = 3000
MAX_KEYFRAMES = 4


class MediaCapability:
    """Local media analysis. Never sends raw audio or video to the LLM."""

    def __init__(self, *, http: httpx.AsyncClient | None = None) -> None:
        self._http = http

    async def audio_features(self, audio_url: str | None) -> AudioFeatures | None:
        if not audio_url or audio_url.startswith("mock://"):
            return self._mock_features() if audio_url else None
        try:
            if self._http is not None:
                response = await self._http.get(audio_url, timeout=30.0)
            else:
                async with httpx.AsyncClient() as client:
                    response = await client.get(audio_url, timeout=30.0)
            response.raise_for_status()
        except Exception:
            return None
        return analyze_wav(response.content)

    async def select_keyframes(self, frames: Iterable[FrameItem]) -> list[FrameItem]:
        candidates = [
            frame
            for frame in frames
            if frame.object_key
            and frame.frame_type in {"slide_change", "final", "chart", "low_confidence"}
        ]
        if not candidates:
            candidates = [frame for frame in frames if frame.object_key]
        candidates.sort(key=lambda frame: frame.timestamp_ms)
        if len(candidates) <= MAX_KEYFRAMES:
            return candidates
        step = (len(candidates) - 1) / (MAX_KEYFRAMES - 1)
        return [candidates[round(index * step)] for index in range(MAX_KEYFRAMES)]

    @staticmethod
    def _mock_features() -> AudioFeatures:
        return AudioFeatures(
            pause_ratio=0.11,
            long_pause_ratio=0.03,
            volume_low_ratio=0.02,
        )


def analyze_wav(data: bytes) -> AudioFeatures | None:
    """Computes silence ratios from a PCM WAV file; other containers return None."""
    try:
        with wave.open(io.BytesIO(data), "rb") as handle:
            if handle.getsampwidth() != 2:
                return None
            sample_rate = handle.getframerate()
            channels = handle.getnchannels()
            raw = handle.readframes(handle.getnframes())
    except (wave.Error, EOFError, struct.error):
        return None

    if not raw or sample_rate <= 0 or channels <= 0:
        return None

    samples = struct.unpack("<" + "h" * (len(raw) // 2), raw)
    window_samples = max(1, int(sample_rate * WINDOW_MS / 1000)) * channels
    threshold = 32768 * (10 ** (SILENCE_DBFS / 20))

    windows: list[bool] = []
    for start in range(0, len(samples), window_samples):
        chunk = samples[start : start + window_samples]
        if not chunk:
            continue
        rms = math.sqrt(sum(value * value for value in chunk) / len(chunk))
        windows.append(rms < threshold)
    if not windows:
        return None

    total_ms = len(windows) * WINDOW_MS
    silent = sum(1 for silent_window in windows if silent_window)
    longest = 0
    current = 0
    for silent_window in windows:
        current = current + 1 if silent_window else 0
        longest = max(longest, current)

    return AudioFeatures(
        pause_ratio=round(silent / len(windows), 2),
        long_pause_ratio=round(
            min(1.0, (longest * WINDOW_MS) / max(total_ms, 1)), 2
        ),
        volume_low_ratio=round(silent / len(windows), 2),
    )
