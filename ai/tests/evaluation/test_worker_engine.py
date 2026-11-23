from __future__ import annotations

import asyncio

import pytest

from app.schemas.evaluation import (
    DimensionResult,
    EvaluationResult,
    JobMessage,
)
from app.providers.base import ProviderError
from app.worker.callback import CallbackAbandon, CallbackError
from app.worker.engine import DONE, STALE, EvaluationEngine
from app.worker.input_client import InputClientError
from tests.evaluation import helpers

MESSAGE = JobMessage(
    job_id="44444444-4444-4444-4444-444444444444",
    evaluation_id="11111111-1111-1111-1111-111111111111",
    source_type="task_submission",
    source_id="33333333-3333-3333-3333-333333333333",
    session_id="22222222-2222-2222-2222-222222222222",
    attempt=1,
)


def _result(*, dimensions: list[DimensionResult] | None = None) -> EvaluationResult:
    return EvaluationResult(
        status="completed" if dimensions else "failed",
        model_version="mock",
        prompt_version="m4-eval-v1",
        rubric_version="m4-rubric-v1",
        dimensions=dimensions or [],
        partial_reasons=[],
    )


def _dimension() -> DimensionResult:
    return DimensionResult(
        dimension="content",
        score=82.0,
        sub_scores={
            "accuracy": 82.0,
            "logic": 80.0,
            "key_points": 84.0,
            "terminology": 86.0,
            "completeness": 78.0,
        },
        sub_confidences={"accuracy": 0.8},
        confidence=0.8,
    )


class FakeInputs:
    def __init__(self, *, fail: bool = False) -> None:
        self.fail = fail

    async def manifest(self, evaluation_id: str):
        if self.fail:
            raise InputClientError("manifest unavailable", status_code=500)
        return helpers.manifest()

    async def texts(self, evaluation_id: str):
        return helpers.texts()

    async def frames(self, evaluation_id: str):
        return helpers.frames()

    async def events(self, evaluation_id: str):
        return helpers.events()


class FakeCallback:
    def __init__(self) -> None:
        self.started = 0
        self.results: list[EvaluationResult] = []
        self.failures: list[tuple[str, str, list[str]]] = []
        self.abandon_start = False
        self.abandon_result = False
        self.error_result = False

    async def start(self, message: JobMessage) -> None:
        if self.abandon_start:
            raise CallbackAbandon("stale")
        self.started += 1

    async def result(self, message: JobMessage, result: EvaluationResult) -> None:
        if self.abandon_result:
            raise CallbackAbandon("terminal")
        if self.error_result:
            raise CallbackError("callback failed")
        self.results.append(result)

    async def fail(
        self,
        message: JobMessage,
        *,
        error_code: str,
        error_message: str,
        partial_reasons=None,
    ) -> None:
        self.failures.append((error_code, error_message, partial_reasons or []))


class FakeCapability:
    def __init__(
        self,
        *,
        result: EvaluationResult | None = None,
        delay: float = 0.0,
        error: Exception | None = None,
    ) -> None:
        self.result = result or _result(dimensions=[_dimension()])
        self.delay = delay
        self.error = error
        self.context = None

    async def evaluate(self, context):
        if self.delay:
            await asyncio.sleep(self.delay)
        if self.error:
            raise self.error
        self.context = context
        return self.result


class FakeMedia:
    def __init__(self) -> None:
        self.url = "unset"

    async def audio_features(self, url):
        self.url = url
        return helpers.audio()


def _engine(
    *, inputs=None, callback=None, capability=None, timeout_s: float = 90.0
) -> EvaluationEngine:
    return EvaluationEngine(
        inputs=inputs or FakeInputs(),
        callback=callback or FakeCallback(),
        capability=capability or FakeCapability(),
        media=FakeMedia(),
        job_timeout_s=timeout_s,
    )


async def test_success_calls_start_then_result_and_acks() -> None:
    callback = FakeCallback()
    engine = _engine(callback=callback)

    outcome = await engine.run(MESSAGE)

    assert outcome == DONE
    assert callback.started == 1
    assert len(callback.results) == 1
    assert callback.results[0].dimensions[0].dimension == "content"
    assert callback.failures == []


async def test_stale_start_abandons_without_reading_input() -> None:
    callback = FakeCallback()
    callback.abandon_start = True
    capability = FakeCapability()
    engine = _engine(callback=callback, capability=capability)

    assert await engine.run(MESSAGE) == STALE
    assert capability.context is None
    assert callback.results == []


async def test_input_failure_reports_fail_callback() -> None:
    callback = FakeCallback()
    engine = _engine(inputs=FakeInputs(fail=True), callback=callback)

    assert await engine.run(MESSAGE) == DONE
    assert callback.results == []
    assert callback.failures[0][0] == "INPUT_FETCH_FAILED"


async def test_job_timeout_reports_ai_timeout() -> None:
    callback = FakeCallback()
    engine = _engine(
        callback=callback,
        capability=FakeCapability(delay=0.5),
        timeout_s=0.01,
    )

    assert await engine.run(MESSAGE) == DONE
    assert callback.failures[0][0] == "AI_TIMEOUT"


async def test_no_dimensions_reports_failure_not_result() -> None:
    callback = FakeCallback()
    engine = _engine(callback=callback, capability=FakeCapability(result=_result()))

    assert await engine.run(MESSAGE) == DONE
    assert callback.results == []
    assert callback.failures[0][0] == "AI_OUTPUT_INVALID"


async def test_result_callback_failure_propagates_without_ack() -> None:
    callback = FakeCallback()
    callback.error_result = True
    engine = _engine(callback=callback)

    with pytest.raises(CallbackError):
        await engine.run(MESSAGE)


async def test_terminal_result_is_reported_as_stale() -> None:
    callback = FakeCallback()
    callback.abandon_result = True
    engine = _engine(callback=callback)

    assert await engine.run(MESSAGE) == STALE
    assert callback.failures == []


class MismatchedInputs(FakeInputs):
    async def manifest(self, evaluation_id: str):
        return helpers.manifest().model_copy(
            update={"evaluation_id": "99999999-9999-9999-9999-999999999999"}
        )


async def test_manifest_evaluation_mismatch_reports_fail() -> None:
    callback = FakeCallback()
    engine = _engine(inputs=MismatchedInputs(), callback=callback)

    assert await engine.run(MESSAGE) == DONE
    assert callback.results == []
    assert callback.failures[0][0] == "INPUT_FETCH_FAILED"


async def test_provider_error_maps_to_service_unavailable() -> None:
    callback = FakeCallback()
    engine = _engine(
        callback=callback,
        capability=FakeCapability(
            error=ProviderError("UPSTREAM_429", "rate limited", retryable=True)
        ),
    )

    assert await engine.run(MESSAGE) == DONE
    assert callback.failures[0][0] == "AI_SERVICE_UNAVAILABLE"
    assert callback.failures[0][1] == "rate limited"


async def test_unexpected_capability_failure_is_reported_as_internal_error() -> None:
    callback = FakeCallback()
    engine = _engine(
        callback=callback,
        capability=FakeCapability(error=ValueError("broken payload")),
    )

    assert await engine.run(MESSAGE) == DONE
    assert callback.failures[0][0] == "AI_INTERNAL_ERROR"
    assert callback.failures[0][1] == "ValueError"


async def test_dimension_timeout_without_results_reports_ai_timeout() -> None:
    callback = FakeCallback()
    timed_out = EvaluationResult(
        status="failed",
        model_version="mock",
        prompt_version="m4-eval-v1",
        rubric_version="m4-rubric-v1",
        dimensions=[],
        partial_reasons=["DIMENSION_TIMEOUT"],
    )
    engine = _engine(callback=callback, capability=FakeCapability(result=timed_out))

    assert await engine.run(MESSAGE) == DONE
    assert callback.failures[0][0] == "AI_TIMEOUT"
    assert callback.failures[0][2] == ["DIMENSION_TIMEOUT"]
