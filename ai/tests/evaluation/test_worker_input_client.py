from __future__ import annotations

import json
from typing import Any, Callable

import httpx
import pytest

from app.worker.input_client import EvaluationInputClient, InputClientError
from tests.evaluation import helpers

EVALUATION_ID = "11111111-1111-1111-1111-111111111111"
BASE_URL = "http://java.test"


def _client(
    handler: Callable[[httpx.Request], httpx.Response],
    **kwargs: Any,
) -> EvaluationInputClient:
    http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return EvaluationInputClient(
        base_url=BASE_URL,
        service_token="service-token",
        http=http,
        **kwargs,
    )


def _page(items: list[dict[str, Any]], size: int) -> dict[str, Any]:
    return {"items": items, "total": len(items), "page": 0, "size": size}


def _text(item_id: str, at_ms: int) -> dict[str, Any]:
    return {
        "id": item_id,
        "speaker_type": "teacher",
        "text": f"第 {item_id} 段",
        "at_ms": at_ms,
        "start_ms": at_ms,
        "end_ms": at_ms + 1000,
    }


def _frame(item_id: str, ts: int) -> dict[str, Any]:
    return {
        "id": item_id,
        "timestamp_ms": ts,
        "text": f"frame {item_id}",
        "status": "processed",
    }


def _event(item_id: str, seq: int) -> dict[str, Any]:
    return {
        "id": item_id,
        "seq": seq,
        "type": "teacher.ask_class",
        "timestamp_ms": seq * 1000,
        "actor": "teacher",
        "payload": {},
    }


async def test_manifest_uses_service_token_and_parses_snake_case() -> None:
    requests: list[httpx.Request] = []
    payload = helpers.manifest().model_dump(mode="json")

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json=payload)

    client = _client(handler)

    manifest = await client.manifest(EVALUATION_ID)

    assert manifest.evaluation_id == EVALUATION_ID
    assert manifest.mode == "free"
    assert manifest.weights["content"] == 30
    assert requests[0].url.path == (
        f"/internal/evaluations/{EVALUATION_ID}/input/manifest"
    )
    assert requests[0].headers["X-Service-Token"] == "service-token"


async def test_texts_follow_after_id_until_a_short_page() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        after_id = request.url.params.get("after_id")
        if after_id is None:
            return httpx.Response(200, json=_page(
                [_text("t1", 0), _text("t2", 1000)], 2))
        return httpx.Response(200, json=_page([_text("t3", 2000)], 2))

    client = _client(handler, text_page_size=2)

    items = await client.texts(EVALUATION_ID)

    assert [item.id for item in items] == ["t1", "t2", "t3"]
    assert len(requests) == 2
    assert requests[0].url.params.get("after_id") is None
    assert requests[1].url.params.get("after_id") == "t2"
    assert requests[1].url.params.get("limit") == "2"


async def test_texts_stop_at_the_maximum_even_if_pages_keep_coming() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        index = len(requests)
        return httpx.Response(200, json=_page([_text(f"t{index}", index * 1000)], 1))

    client = _client(handler, text_page_size=1, max_texts=3)

    items = await client.texts(EVALUATION_ID)

    assert [item.id for item in items] == ["t1", "t2", "t3"]
    assert len(requests) == 3


async def test_frames_use_timestamp_and_id_cursor() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.url.params.get("after_ts") is None:
            return httpx.Response(200, json=_page(
                [_frame("f1", 1000), _frame("f2", 1000)], 2))
        return httpx.Response(200, json=_page([_frame("f3", 2000)], 2))

    client = _client(handler, frame_page_size=2)

    items = await client.frames(EVALUATION_ID)

    assert [item.id for item in items] == ["f1", "f2", "f3"]
    assert requests[1].url.params.get("after_ts") == "1000"
    assert requests[1].url.params.get("after_id") == "f2"


async def test_events_follow_after_seq() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.url.params.get("after_seq") is None:
            return httpx.Response(200, json=_page(
                [_event("e1", 1), _event("e2", 2)], 2))
        return httpx.Response(200, json=_page([_event("e3", 3)], 2))

    client = _client(handler, event_page_size=2)

    items = await client.events(EVALUATION_ID)

    assert [item.seq for item in items] == [1, 2, 3]
    assert requests[1].url.params.get("after_seq") == "2"


async def test_non_200_is_an_input_error_with_status_code() -> None:
    client = _client(lambda request: httpx.Response(503, json={"error": "down"}))

    with pytest.raises(InputClientError) as raised:
        await client.texts(EVALUATION_ID)

    assert raised.value.status_code == 503


async def test_non_json_body_is_an_input_error() -> None:
    client = _client(lambda request: httpx.Response(200, text="not json"))

    with pytest.raises(InputClientError, match="not JSON"):
        await client.manifest(EVALUATION_ID)


async def test_unexpected_top_level_shape_is_an_input_error() -> None:
    client = _client(
        lambda request: httpx.Response(
            200, content=json.dumps([1, 2, 3]), headers={"content-type": "application/json"}
        )
    )

    with pytest.raises(InputClientError, match="unexpected shape"):
        await client.manifest(EVALUATION_ID)


async def test_network_failure_is_an_input_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    client = _client(handler)

    with pytest.raises(InputClientError):
        await client.manifest(EVALUATION_ID)
