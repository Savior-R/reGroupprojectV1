"""Entrypoint: ``python -m app.worker.main``.

Runs as a separate process from the FastAPI web service. It has no database access and only
talks to Redis and the Java internal API.
"""

from __future__ import annotations

import asyncio
import logging
import os
import signal
from collections.abc import AsyncIterator
from typing import Any

import httpx

from app.capabilities.evaluation import EvaluationCapability
from app.capabilities.media import MediaCapability
from app.providers.base import LLMChunk, LLMProvider, LLMResponse
from app.providers.deepseek import DeepSeekProvider
from app.worker.callback import ResultCallback
from app.worker.config import WorkerSettings
from app.worker.consumer import EvalWorker
from app.worker.engine import EvaluationEngine
from app.worker.input_client import EvaluationInputClient
from app.worker.mock_provider import EvaluationMockProvider

logger = logging.getLogger(__name__)


class _LimitedLLM:
    """Caps concurrent provider calls across all evaluation dimensions."""

    def __init__(self, provider: LLMProvider, limit: int) -> None:
        self._provider = provider
        self._semaphore = asyncio.Semaphore(max(1, limit))

    async def chat(
        self,
        messages: list[dict[str, str]],
        model: str,
        timeout_s: float,
        *,
        response_format: dict[str, Any] | None = None,
        temperature: float | None = None,
        thinking: bool | None = None,
    ) -> LLMResponse:
        async with self._semaphore:
            return await self._provider.chat(
                messages,
                model,
                timeout_s,
                response_format=response_format,
                temperature=temperature,
                thinking=thinking,
            )

    async def stream(
        self,
        messages: list[dict[str, str]],
        model: str,
        timeout_s: float,
        *,
        temperature: float | None = None,
        thinking: bool | None = None,
    ) -> AsyncIterator[LLMChunk]:
        async with self._semaphore:
            async for chunk in self._provider.stream(
                messages, model, timeout_s, temperature=temperature, thinking=thinking
            ):
                yield chunk


def _configure_logging() -> None:
    logging.basicConfig(
        level=os.environ.get("LOG_LEVEL", "INFO").upper(),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )


def _build_provider(settings: WorkerSettings) -> tuple[LLMProvider, str]:
    if settings.ai_mock:
        return EvaluationMockProvider(), "mock"
    if not settings.deepseek_api_key:
        raise RuntimeError("DEEPSEEK_API_KEY is required when AI_MOCK is not true")
    provider = DeepSeekProvider(
        api_key=settings.deepseek_api_key,
        base_url=settings.deepseek_base_url,
        model=settings.deepseek_model,
    )
    return provider, settings.deepseek_model


async def main() -> int:
    _configure_logging()
    settings = WorkerSettings()
    if not settings.service_token:
        logger.warning("SERVICE_TOKEN is empty; Java may reject internal callbacks")

    try:
        provider, model = _build_provider(settings)
    except RuntimeError as exc:
        logger.error("invalid worker configuration: %s", exc)
        return 2

    http = httpx.AsyncClient()
    try:
        capability = EvaluationCapability(
            llm=_LimitedLLM(provider, settings.llm_concurrency),
            model=model,
            timeout_s=settings.llm_timeout_s,
            dimension_timeout_s=settings.dimension_timeout_s,
        )
        engine = EvaluationEngine.from_settings(
            settings,
            capability=capability,
            media=MediaCapability(http=http),
            inputs=EvaluationInputClient.from_settings(settings, http=http),
            callback=ResultCallback.from_settings(settings, http=http),
        )
        worker = EvalWorker(settings, engine)
        stop = asyncio.Event()
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            try:
                loop.add_signal_handler(sig, stop.set)
            except (NotImplementedError, RuntimeError):
                # Windows event loops do not implement signal handlers.
                pass
        try:
            await worker.run(stop)
        except KeyboardInterrupt:
            stop.set()
        return 0
    finally:
        await http.aclose()


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
