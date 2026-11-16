"""Redis Streams consumer for ``evaluation:jobs`` (06 §5.1).

The consumer acknowledges only after both the evaluation and the Java callback succeed. A failed
callback leaves the message pending so ``XAUTOCLAIM`` can reclaim it after the idle threshold.
"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Any

from pydantic import ValidationError

from app.schemas.evaluation import JobMessage
from app.worker.callback import CallbackError
from app.worker.config import WorkerSettings
from app.worker.engine import DONE, STALE, EvaluationEngine

try:  # optional extra: the worker image installs ``redis``; the web image does not.
    import redis.asyncio as redis
    from redis.exceptions import RedisError
except ImportError:  # pragma: no cover - exercised only when the extra is absent
    redis = None

    class RedisError(Exception):
        pass


logger = logging.getLogger(__name__)


class EvalWorker:
    def __init__(self, settings: WorkerSettings, engine: EvaluationEngine) -> None:
        self._settings = settings
        self._engine = engine
        self._client: Any = None
        self._semaphore = asyncio.Semaphore(max(1, settings.worker_concurrency))
        self._tasks: set[asyncio.Task[None]] = set()

    async def run(self, stop: asyncio.Event | None = None) -> None:
        if redis is None:
            raise RuntimeError(
                "redis package is not installed; add 'redis>=5' to the worker environment"
            )
        stop = stop or asyncio.Event()
        settings = self._settings
        self._client = redis.from_url(settings.redis_url, decode_responses=True)
        try:
            await self._client.ping()
            await self._ensure_group()
            logger.info(
                "eval worker started consumer=%s stream=%s group=%s",
                settings.consumer(),
                settings.stream,
                settings.consumer_group,
            )
            last_reclaim = 0.0
            while not stop.is_set():
                now = time.monotonic()
                if (now - last_reclaim) * 1000 >= settings.reclaim_interval_ms:
                    last_reclaim = now
                    await self._reclaim()
                await self._read_once()
                self._prune_tasks()
        finally:
            await self._drain()
            await self._client.aclose()
            self._client = None
            logger.info("eval worker stopped")

    async def _ensure_group(self) -> None:
        try:
            await self._client.xgroup_create(
                self._settings.stream,
                self._settings.consumer_group,
                id="0",
                mkstream=True,
            )
            logger.info("created consumer group %s", self._settings.consumer_group)
        except RedisError as exc:
            if "BUSYGROUP" not in str(exc):
                raise

    async def _read_once(self) -> None:
        settings = self._settings
        try:
            response = await self._client.xreadgroup(
                settings.consumer_group,
                settings.consumer(),
                {settings.stream: ">"},
                count=settings.batch_size,
                block=settings.block_ms,
            )
        except asyncio.CancelledError:
            raise
        except RedisError as exc:
            logger.warning("xreadgroup failed error=%s", type(exc).__name__)
            await asyncio.sleep(1.0)
            return
        for _, entries in response or []:
            for entry_id, fields in entries:
                self._schedule(entry_id, fields, claimed=False)

    async def _reclaim(self) -> None:
        settings = self._settings
        cursor = "0-0"
        try:
            while True:
                cursor, entries, _ = await self._client.xautoclaim(
                    settings.stream,
                    settings.consumer_group,
                    settings.consumer(),
                    settings.reclaim_idle_ms,
                    start_id=cursor,
                    count=settings.batch_size,
                )
                for entry_id, fields in entries:
                    logger.info("reclaimed pending evaluation message id=%s", entry_id)
                    self._schedule(entry_id, fields, claimed=True)
                if cursor in (None, "0-0", "0"):
                    break
        except asyncio.CancelledError:
            raise
        except RedisError as exc:
            logger.warning("xautoclaim failed error=%s", type(exc).__name__)

    def _schedule(self, entry_id: str, fields: dict[str, Any], *, claimed: bool) -> None:
        task = asyncio.create_task(self._handle(entry_id, fields, claimed=claimed))
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    async def _handle(self, entry_id: str, fields: dict[str, Any], *, claimed: bool) -> None:
        try:
            message = JobMessage.model_validate(_decode(fields))
        except ValidationError as exc:
            logger.error("dropping invalid evaluation message id=%s error=%s", entry_id, exc.errors())
            await self._ack(entry_id)
            return

        async with self._semaphore:
            try:
                outcome = await self._engine.run(message)
            except CallbackError as exc:
                logger.warning(
                    "callback failed; leaving message pending id=%s evaluation_id=%s error=%s",
                    entry_id,
                    message.evaluation_id,
                    exc,
                )
                return
            except Exception as exc:  # never acknowledge an unknown failure
                logger.exception(
                    "unhandled evaluation error id=%s evaluation_id=%s error=%s",
                    entry_id,
                    message.evaluation_id,
                    type(exc).__name__,
                )
                return

        if outcome in {DONE, STALE}:
            await self._ack(entry_id)
        else:
            logger.warning(
                "unexpected outcome id=%s outcome=%s claimed=%s",
                entry_id,
                outcome,
                claimed,
            )

    async def _ack(self, entry_id: str) -> None:
        try:
            await self._client.xack(
                self._settings.stream, self._settings.consumer_group, entry_id
            )
        except RedisError as exc:
            logger.warning("xack failed id=%s error=%s", entry_id, type(exc).__name__)

    def _prune_tasks(self) -> None:
        if len(self._tasks) > self._settings.batch_size * 4:
            logger.warning("evaluation worker backlog tasks=%s", len(self._tasks))

    async def _drain(self) -> None:
        if not self._tasks:
            return
        pending = list(self._tasks)
        try:
            await asyncio.wait_for(
                asyncio.gather(*pending, return_exceptions=True), timeout=30.0
            )
        except asyncio.TimeoutError:
            for task in pending:
                task.cancel()
            await asyncio.gather(*pending, return_exceptions=True)


def _decode(fields: dict[str, Any]) -> dict[str, Any]:
    decoded: dict[str, Any] = {}
    for key, value in fields.items():
        if isinstance(key, bytes):
            key = key.decode("utf-8", errors="replace")
        if isinstance(value, bytes):
            value = value.decode("utf-8", errors="replace")
        decoded[key] = value
    return decoded
