from __future__ import annotations

import json
from typing import Any, Callable

import httpx
import pytest

from app.schemas.evaluation import DimensionResult, EvaluationResult, JobMessage
from app.worker.callback import CallbackAbandon, CallbackError, ResultCallback

BASE_URL = "http://java.test"
MESSAGE = JobMessage(
    job_id="44444444-4444-4444-4444-444444444444",
    evaluation_id="11111111-1111-1111-1111-111111111111",
    source_type="task_submission",
    source_id="33333333-3333-3333-3333-333333333333",
    session_id="22222222-2222-2222-2222-222222222222",
    attempt=2,
)


def _callback(
    handler: Callable[[httpx.Request], httpx.Response],
    *,
    token: str = "service-token",
) -> ResultCallback:
    http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return ResultCallback(
        base_url=BASE_URL,
        service_token=token,
        http=http,
    )


def _recording() -> tuple[list[httpx.Request], Callable[[], ResultCallback]]:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json={"evaluation_id": MESSAGE.evaluation_id})

    return requests, lambda: _callback(handler)


def _payload(request: httpx.Request) -> dict[str, Any]:
    return json.loads(request.content.decode("utf-8"))


async def test_start_posts_job_and_attempt_with_idempotency_key() -> None:
    requests, build = _recording()

    await build().start(MESSAGE)

    request = requests[0]
    assert request.url.path == f"/internal/evaluations/{MESSAGE.evaluation_id}/start"
    assert request.headers["Idempotency-Key"] == f"{MESSAGE.job_id}:2"
    assert request.headers["X-Service-Token"] == "service-token"
    assert _payload(request) == {"job_id": MESSAGE.job_id, "attempt": 2}


async def test_result_posts_dimensions_without_a_total() -> None:
    requests, build = _recording()
    result = EvaluationResult(
        status="completed",
        model_version="mock",
        prompt_version="m4-eval-v1",
        rubric_version="m4-rubric-v1",
        dimensions=[
            DimensionResult(dimension="content", score=82.0, confidence=0.8)
        ],
        warnings=["EMPTY_EVENTS"],
        token_usage={"total_tokens": 780},
    )

    await build().result(MESSAGE, result)

    body = _payload(requests[0])
    assert "total_score" not in body
    assert body["job_id"] == MESSAGE.job_id
    assert body["attempt"] == 2
    assert body["status"] == "completed"
    assert body["dimensions"][0]["dimension"] == "content"
    assert body["warnings"] == ["EMPTY_EVENTS"]
    assert body["token_usage"] == {"total_tokens": 780}


async def test_fail_truncates_the_error_message_and_keeps_reasons() -> None:
    requests, build = _recording()

    await build().fail(
        MESSAGE,
        error_code="AI_TIMEOUT",
        error_message="x" * 800,
        partial_reasons=["DIMENSION_TIMEOUT"],
    )

    body = _payload(requests[0])
    assert body["error_code"] == "AI_TIMEOUT"
    assert len(body["error_message"]) == 500
    assert body["partial_reasons"] == ["DIMENSION_TIMEOUT"]


async def test_409_is_abandoned_not_retried() -> None:
    callback = _callback(lambda request: httpx.Response(409, json={"error": "stale"}))

    with pytest.raises(CallbackAbandon):
        await callback.start(MESSAGE)


@pytest.mark.parametrize("status", [400, 404, 500, 502, 503])
async def test_other_errors_raise_callback_error(status: int) -> None:
    callback = _callback(lambda request: httpx.Response(status, json={"error": "x"}))

    with pytest.raises(CallbackError):
        await callback.start(MESSAGE)


async def test_transport_failure_raises_callback_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    callback = _callback(handler)

    with pytest.raises(CallbackError):
        await callback.start(MESSAGE)


async def test_empty_token_does_not_send_the_header() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json={})

    await _callback(handler, token="").start(MESSAGE)

    assert "X-Service-Token" not in requests[0].headers
