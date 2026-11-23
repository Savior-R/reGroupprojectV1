"""Cross-language contract fixtures for the Java ↔ Python boundary (06 §5.1, §9.3).

The JSON files are copied field-for-field from the Java DTOs (snake_case, Redis string
fields, ISO-8601 instants). These tests fail as soon as either side renames a field or changes
a nested shape.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import httpx
import pytest

from app.schemas.evaluation import (
    EvaluationResult,
    EventItem,
    FrameItem,
    JobMessage,
    Manifest,
    TextItem,
)
from app.worker.consumer import _decode
from app.worker.input_client import EvaluationInputClient

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "contract"
EVALUATION_ID = "11111111-1111-1111-1111-111111111111"


def _load(name: str) -> dict[str, Any]:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def test_manifest_fixture_matches_the_java_manifest_dto() -> None:
    payload = _load("manifest.json")

    assert set(payload) == {
        "evaluation_id",
        "session_id",
        "source_type",
        "source_id",
        "mode",
        "session",
        "config",
        "recordings",
        "counts",
        "weights",
        "rubric_version",
        "flags",
    }
    assert set(payload["session"]) == {
        "session_type",
        "status",
        "duration_ms",
        "stream_offsets",
        "started_at",
        "ended_at",
    }
    assert set(payload["recordings"][0]) == {
        "stream_id",
        "stream_type",
        "url",
        "duration_ms",
        "offset_ms",
        "mime",
    }
    assert set(payload["counts"]) == {"texts", "frames", "events"}

    manifest = Manifest.model_validate(payload)

    assert manifest.evaluation_id == EVALUATION_ID
    assert manifest.mode == "free"
    assert manifest.weights["content"] == 30
    assert manifest.counts.texts == 4
    assert manifest.recordings[0].stream_type == "audio"
    assert manifest.flags["answer_points_complete"] is True


@pytest.mark.parametrize(
    ("file_name", "model"),
    [
        ("input_text_page.json", TextItem),
        ("input_frame_page.json", FrameItem),
        ("input_event_page.json", EventItem),
    ],
)
def test_input_page_fixtures_validate_with_the_worker_models(
    file_name: str, model: type
) -> None:
    page = _load(file_name)

    assert set(page) == {"items", "total", "page", "size"}
    assert page["total"] == len(page["items"])
    for row in page["items"]:
        model.model_validate(row)


async def test_input_client_reads_fixtures_through_cursor_paging() -> None:
    pages = {
        "texts": _load("input_text_page.json")["items"],
        "frames": _load("input_frame_page.json")["items"],
        "events": _load("input_event_page.json")["items"],
    }

    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path.endswith("/input/manifest"):
            return httpx.Response(200, json=_load("manifest.json"))
        kind = path.rsplit("/", 1)[-1]
        rows = pages[kind]
        limit = int(request.url.params.get("limit", "500"))
        if kind == "texts":
            after_id = request.url.params.get("after_id")
            start = (
                next(
                    (index + 1 for index, row in enumerate(rows)
                     if row["id"] == after_id),
                    0,
                )
                if after_id
                else 0
            )
        elif kind == "events":
            after_seq = request.url.params.get("after_seq")
            start = (
                next(
                    (index + 1 for index, row in enumerate(rows)
                     if str(row["seq"]) == after_seq),
                    0,
                )
                if after_seq
                else 0
            )
        else:
            after_ts = request.url.params.get("after_ts")
            after_id = request.url.params.get("after_id")
            start = 0
            if after_ts is not None:
                for index, row in enumerate(rows):
                    if row["timestamp_ms"] > int(after_ts):
                        start = index
                        break
                    if row["timestamp_ms"] == int(after_ts) and row["id"] > after_id:
                        start = index
                        break
                else:
                    start = len(rows)
        sliced = rows[start : start + limit]
        return httpx.Response(
            200,
            json={"items": sliced, "total": len(rows), "page": 0, "size": limit},
        )

    http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    client = EvaluationInputClient(
        base_url="http://java.test",
        service_token="service-token",
        http=http,
        text_page_size=2,
        frame_page_size=1,
        event_page_size=2,
    )

    manifest = await client.manifest(EVALUATION_ID)
    texts = await client.texts(EVALUATION_ID)
    frames = await client.frames(EVALUATION_ID)
    events = await client.events(EVALUATION_ID)

    assert len(texts) == manifest.counts.texts == 4
    assert len(frames) == manifest.counts.frames == 2
    assert len(events) == manifest.counts.events == 3
    assert [text.at_ms for text in texts] == sorted(text.at_ms for text in texts)
    assert [frame.timestamp_ms for frame in frames] == [15000, 125000]
    assert [event.seq for event in events] == [1, 2, 3]


def test_redis_queue_fixture_parses_as_a_worker_job_message() -> None:
    payload = _load("queue_message.json")
    assert set(payload) == {
        "job_id",
        "evaluation_id",
        "source_type",
        "source_id",
        "session_id",
        "attempt",
        "published_at",
    }
    assert isinstance(payload["attempt"], str)  # Java writes every hash field as a string

    message = JobMessage.model_validate(payload)
    # Redis may hand the worker bytes instead of str; _decode normalizes before validation.
    decoded = _decode(
        {key.encode(): str(value).encode() for key, value in payload.items()}
    )
    assert JobMessage.model_validate(decoded) == message
    assert message.attempt == 2


def test_result_callback_fixture_matches_the_worker_result_model() -> None:
    payload = _load("result_callback.json")
    allowed = {
        "status",
        "model_version",
        "prompt_version",
        "rubric_version",
        "dimensions",
        "issues",
        "warnings",
        "partial_reasons",
        "input_flags",
        "token_usage",
    }
    assert set(payload) - {"job_id", "attempt"} == allowed

    result = EvaluationResult.model_validate(
        {key: value for key, value in payload.items() if key in allowed}
    )

    assert result.status == "completed"
    assert {dimension.dimension for dimension in result.dimensions} == {
        "content",
        "expression",
    }
    assert len([issue for issue in result.issues if issue.is_suggestion]) <= 5
    assert result.token_usage["prompt"] == 12000


def test_fail_callback_fixture_matches_the_java_fail_dto() -> None:
    payload = _load("fail_callback.json")

    assert set(payload) == {
        "job_id",
        "attempt",
        "error_code",
        "error_message",
        "partial_reasons",
    }
    assert payload["attempt"] >= 1
    assert payload["partial_reasons"] == ["DIMENSION_TIMEOUT"]
