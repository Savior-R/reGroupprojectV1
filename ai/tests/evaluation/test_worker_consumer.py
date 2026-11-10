from __future__ import annotations

from app.schemas.evaluation import JobMessage
from app.worker.callback import CallbackError
from app.worker.config import WorkerSettings
from app.worker.consumer import EvalWorker, _decode
from app.worker.engine import DONE, STALE

FIELDS = {
    "job_id": "44444444-4444-4444-4444-444444444444",
    "evaluation_id": "11111111-1111-1111-1111-111111111111",
    "source_type": "task_submission",
    "source_id": "33333333-3333-3333-3333-333333333333",
    "session_id": "22222222-2222-2222-2222-222222222222",
    "attempt": "1",
}


class FakeEngine:
    def __init__(self, outcome: str = DONE, error: Exception | None = None) -> None:
        self.outcome = outcome
        self.error = error
        self.messages: list[JobMessage] = []

    async def run(self, message: JobMessage) -> str:
        self.messages.append(message)
        if self.error:
            raise self.error
        return self.outcome


class FakeRedis:
    def __init__(self) -> None:
        self.acked: list[str] = []

    async def xack(self, stream: str, group: str, entry_id: str) -> int:
        self.acked.append(entry_id)
        return 1


def _worker(engine: FakeEngine) -> tuple[EvalWorker, FakeRedis]:
    settings = WorkerSettings(redis_url="redis://localhost:6379/0")
    worker = EvalWorker(settings, engine)  # type: ignore[arg-type]
    client = FakeRedis()
    worker._client = client  # type: ignore[assignment]
    return worker, client


async def test_done_acknowledges_the_entry() -> None:
    engine = FakeEngine(DONE)
    worker, client = _worker(engine)

    await worker._handle("1-0", FIELDS, claimed=False)

    assert client.acked == ["1-0"]
    assert engine.messages[0].attempt == 1


async def test_stale_attempt_is_acknowledged() -> None:
    worker, client = _worker(FakeEngine(STALE))

    await worker._handle("2-0", FIELDS, claimed=True)

    assert client.acked == ["2-0"]


async def test_callback_failure_leaves_message_pending() -> None:
    worker, client = _worker(FakeEngine(error=CallbackError("java down")))

    await worker._handle("3-0", FIELDS, claimed=False)

    assert client.acked == []


async def test_invalid_message_is_acknowledged() -> None:
    worker, client = _worker(FakeEngine())

    await worker._handle("4-0", {"attempt": "1"}, claimed=False)

    assert client.acked == ["4-0"]


async def test_bytes_fields_are_decoded() -> None:
    decoded = _decode({b"job_id": b"abc", "attempt": b"1"})

    assert decoded == {"job_id": "abc", "attempt": "1"}
