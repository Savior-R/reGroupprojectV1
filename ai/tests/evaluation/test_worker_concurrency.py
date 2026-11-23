"""M4-T10 budget smoke with the deterministic mock provider.

This is a CI regression guard, not real-model performance evidence: it proves the worker can
run three evaluations concurrently inside the documented 90 s job budget and that token usage
is reported without a total score.
"""

from __future__ import annotations

import asyncio
import time
import uuid

from app.capabilities.evaluation import EvaluationCapability
from app.schemas.evaluation import EvaluationResult, JobMessage
from app.worker.engine import DONE, EvaluationEngine
from app.worker.mock_provider import EvaluationMockProvider
from tests.evaluation import helpers


class FakeInputs:
    async def manifest(self, evaluation_id: str):
        return helpers.manifest().model_copy(update={"evaluation_id": evaluation_id})

    async def texts(self, evaluation_id: str):
        return helpers.texts()

    async def frames(self, evaluation_id: str):
        return helpers.frames()

    async def events(self, evaluation_id: str):
        return helpers.events()


class FakeCallback:
    def __init__(self) -> None:
        self.results: list[EvaluationResult] = []
        self.failures: list[tuple[str, str, list[str]]] = []

    async def start(self, message: JobMessage) -> None:
        return None

    async def result(self, message: JobMessage, result: EvaluationResult) -> None:
        self.results.append(result)

    async def fail(self, message: JobMessage, *, error_code, error_message, partial_reasons=None):
        self.failures.append((error_code, error_message, partial_reasons or []))


class FakeMedia:
    async def audio_features(self, url):
        return helpers.audio()


def _message() -> JobMessage:
    return JobMessage(
        job_id=str(uuid.uuid4()),
        evaluation_id=str(uuid.uuid4()),
        source_type="task_submission",
        source_id=str(uuid.uuid4()),
        session_id=str(uuid.uuid4()),
        attempt=1,
    )


async def test_three_concurrent_mock_evaluations_finish_inside_the_90s_budget() -> None:
    callback = FakeCallback()
    engine = EvaluationEngine(
        inputs=FakeInputs(),
        callback=callback,
        capability=EvaluationCapability(llm=EvaluationMockProvider(), model="mock"),
        media=FakeMedia(),
        job_timeout_s=90.0,
    )
    messages = [_message() for _ in range(3)]

    started = time.perf_counter()
    outcomes = await asyncio.wait_for(
        asyncio.gather(*(engine.run(message) for message in messages)), timeout=90.0
    )
    elapsed = time.perf_counter() - started

    assert outcomes == [DONE, DONE, DONE]
    assert callback.failures == []
    assert len(callback.results) == 3
    assert elapsed < 90.0
    for result in callback.results:
        assert result.status == "completed"
        assert len(result.dimensions) == 5
        assert "total_score" not in result.model_dump()
        total_tokens = result.token_usage["prompt"] + result.token_usage["completion"]
        assert result.token_usage["prompt"] > 0
        assert result.token_usage["completion"] > 0
        assert total_tokens < 20_000
