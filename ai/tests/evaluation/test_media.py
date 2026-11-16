from __future__ import annotations

import io
import math
import struct
import wave

from app.capabilities.media import MediaCapability, analyze_wav
from app.schemas.evaluation import FrameItem


def _wav(*, silent_seconds: float, tone_seconds: float, rate: int = 16_000) -> bytes:
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(rate)
        silence = b"\x00\x00" * int(rate * silent_seconds)
        samples = b"".join(
            struct.pack("<h", int(12_000 * math.sin(2 * math.pi * 440 * index / rate)))
            for index in range(int(rate * tone_seconds))
        )
        handle.writeframes(silence + samples)
    return buffer.getvalue()


def test_analyze_wav_reports_silence_ratios() -> None:
    features = analyze_wav(_wav(silent_seconds=0.8, tone_seconds=0.2))

    assert features is not None
    assert features.pause_ratio == 0.8
    # long_pause_ratio is the longest continuous silent run (0.8 s of 1 s).
    assert features.long_pause_ratio == 0.8
    assert features.volume_low_ratio == 0.8


def test_analyze_wav_rejects_non_wav_input() -> None:
    assert analyze_wav(b"not a wave file") is None


async def test_audio_features_returns_none_without_url() -> None:
    capability = MediaCapability()

    assert await capability.audio_features(None) is None


async def test_audio_features_are_deterministic_for_mock_urls() -> None:
    capability = MediaCapability()

    first = await capability.audio_features("mock://audio")
    second = await capability.audio_features("mock://audio")

    assert first is not None and second is not None
    assert first == second


async def test_select_keyframes_prefers_slide_changes_and_caps_at_four() -> None:
    frames = [
        FrameItem(
            id=f"f{index}",
            timestamp_ms=index * 1000,
            frame_type="slide_change" if index % 2 == 0 else "normal",
            object_key=f"frames/{index}.jpg",
        )
        for index in range(10)
    ]

    selected = await MediaCapability().select_keyframes(frames)

    assert len(selected) == 4
    assert all(frame.frame_type == "slide_change" for frame in selected)
    assert [frame.timestamp_ms for frame in selected] == sorted(
        frame.timestamp_ms for frame in selected
    )
