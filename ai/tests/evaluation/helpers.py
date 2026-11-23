from __future__ import annotations

from app.schemas.evaluation import (
    AudioFeatures,
    EventItem,
    FrameItem,
    Manifest,
    ManifestCounts,
    ManifestSession,
    TextItem,
)

WEIGHTS = {
    "free": {
        "content": 30,
        "expression": 20,
        "interaction": 20,
        "board": 15,
        "time": 15,
    },
    "scenario": {
        "content": 30,
        "expression": 20,
        "interaction": 20,
        "board": 15,
        "time": 15,
    },
    "interview": {"content": 40, "expression": 30, "time": 30},
}


def manifest(mode: str = "free", *, weights: dict[str, int] | None = None) -> Manifest:
    config: dict[str, object] = {
        "topic": "分数的初步认识",
        "reference_material": "参考材料：认识几分之一。",
        "duration_recommended_seconds": 900,
        "duration_max_seconds": 1200,
    }
    if mode == "interview":
        config["answer_points"] = ["认识几分之一", "能比较同分母分数"]
    return Manifest(
        evaluation_id="11111111-1111-1111-1111-111111111111",
        session_id="22222222-2222-2222-2222-222222222222",
        source_type="task_submission",
        source_id="33333333-3333-3333-3333-333333333333",
        mode=mode,  # type: ignore[arg-type]
        session=ManifestSession(
            session_type="task",
            duration_ms=900_000,
            started_at="2026-11-10T08:00:00Z",
            ended_at="2026-11-10T08:15:00Z",
        ),
        config=config,
        recordings=[],
        counts=ManifestCounts(texts=4, frames=2, events=3),
        weights=weights or WEIGHTS[mode],
        flags={},
    )


def texts() -> list[TextItem]:
    return [
        TextItem(
            id="t1",
            speaker_type="teacher",
            text="同学们，今天我们学习分数的初步认识。",
            at_ms=0,
            start_ms=0,
            end_ms=20_000,
        ),
        TextItem(
            id="t2",
            speaker_type="teacher",
            text="把一个饼平均分成两份，每份就是它的二分之一。",
            at_ms=120_000,
            start_ms=120_000,
            end_ms=150_000,
        ),
        TextItem(
            id="t3",
            speaker_type="student",
            text="老师，为什么要平均分？",
            at_ms=200_000,
            start_ms=200_000,
            end_ms=205_000,
        ),
        TextItem(
            id="t4",
            speaker_type="teacher",
            text="这个问题很好，因为不平均分每份就不一样大。",
            at_ms=260_000,
            start_ms=260_000,
            end_ms=280_000,
        ),
    ]


def frames() -> list[FrameItem]:
    return [
        FrameItem(
            id="f1",
            timestamp_ms=15_000,
            text="分数的初步认识",
            confidence=0.95,
            frame_type="slide_change",
            content_hash="h1",
        ),
        FrameItem(
            id="f2",
            timestamp_ms=125_000,
            text="1/2 = 把一个整体平均分成 2 份",
            confidence=0.93,
            frame_type="slide_change",
            content_hash="h2",
        ),
    ]


def events() -> list[EventItem]:
    return [
        EventItem(
            id="e1",
            seq=1,
            type="classroom.question_asked",
            timestamp_ms=195_000,
            actor="teacher",
        ),
        EventItem(
            id="e2",
            seq=2,
            type="student.answer",
            timestamp_ms=255_000,
            actor="student",
        ),
        EventItem(
            id="e3",
            seq=3,
            type="student.hand_raised",
            timestamp_ms=250_000,
            actor="student",
        ),
    ]


def audio() -> AudioFeatures:
    return AudioFeatures(
        pause_ratio=0.12,
        long_pause_ratio=0.04,
        volume_low_ratio=0.03,
    )
