"""Single-job evaluation pipeline: start → manifest → inputs → evaluate → result/fail."""

from __future__ import annotations

import asyncio
import logging

from app.capabilities.evaluation import EvaluationCapability, EvaluationContext
from app.capabilities.media import MediaCapability
from app.providers.base import ProviderError
from app.schemas.evaluation import JobMessage, Manifest
from app.worker.callback import CallbackAbandon, CallbackError, ResultCallback
from app.worker.config import WorkerSettings
from app.worker.input_client import EvaluationInputClient, InputClientError

logger = logging.getLogger(__name__)

DONE = "done"
STALE = "stale"


class EvaluationEngine:
    def __init__(
        self,
        *,
        inputs: EvaluationInputClient,
        callback: ResultCallback,
        capability: EvaluationCapability,
        media: MediaCapability,
        job_timeout_s: float = 90.0,
    ) -> None:
        self._inputs = inputs
        self._callback = callback
        self._capability = capability
        self._media = media
        self._job_timeout_s = job_timeout_s

    @classmethod
    def from_settings(
        cls,
        settings: WorkerSettings,
        *,
        capability: EvaluationCapability,
        media: MediaCapability,
        inputs: EvaluationInputClient | None = None,
        callback: ResultCallback | None = None,
    ) -> "EvaluationEngine":
        return cls(
            inputs=inputs or EvaluationInputClient.from_settings(settings),
            callback=callback or ResultCallback.from_settings(settings),
            capability=capability,
            media=media,
            job_timeout_s=settings.job_timeout_s,
        )

    async def run(self, message: JobMessage) -> str:
        logger.info(
            "evaluation job start evaluation_id=%s job_id=%s attempt=%s",
            message.evaluation_id,
            message.job_id,
            message.attempt,
        )
        try:
            await self._callback.start(message)
        except CallbackAbandon:
            logger.info(
                "evaluation job abandoned evaluation_id=%s attempt=%s",
                message.evaluation_id,
                message.attempt,
            )
            return STALE
        except CallbackError as exc:
            logger.warning(
                "start callback failed evaluation_id=%s error=%s",
                message.evaluation_id,
                exc,
            )
            raise

        try:
            manifest = await self._inputs.manifest(message.evaluation_id)
            if manifest.evaluation_id != message.evaluation_id:
                raise InputClientError("manifest evaluation_id mismatch")
            texts, frames, events = await asyncio.gather(
                self._inputs.texts(message.evaluation_id),
                self._inputs.frames(message.evaluation_id),
                self._inputs.events(message.evaluation_id),
            )
            audio = await self._media.audio_features(self._audio_url(manifest))
            context = EvaluationContext(
                manifest=manifest,
                texts=texts,
                frames=frames,
                events=events,
                audio=audio,
            )
            result = await asyncio.wait_for(
                self._capability.evaluate(context), timeout=self._job_timeout_s
            )
        except CallbackAbandon:
            raise
        except InputClientError as exc:
            return await self._fail(message, "INPUT_FETCH_FAILED", str(exc))
        except asyncio.TimeoutError:
            return await self._fail(message, "AI_TIMEOUT", "evaluation job exceeded 90s")
        except ProviderError as exc:
            logger.warning(
                "evaluation provider failure evaluation_id=%s code=%s",
                message.evaluation_id,
                exc.code,
            )
            return await self._fail(message, "AI_SERVICE_UNAVAILABLE", exc.message)
        except Exception as exc:  # defensive: never lose the terminal callback
            logger.exception(
                "evaluation job failed evaluation_id=%s error=%s",
                message.evaluation_id,
                type(exc).__name__,
            )
            return await self._fail(message, "AI_INTERNAL_ERROR", type(exc).__name__)

        if not result.dimensions:
            error_code = (
                "AI_TIMEOUT"
                if "DIMENSION_TIMEOUT" in result.partial_reasons
                else "AI_OUTPUT_INVALID"
            )
            return await self._fail(
                message,
                error_code,
                "no dimension could be evaluated",
                result.partial_reasons,
            )

        try:
            await self._callback.result(message, result)
        except CallbackAbandon:
            logger.info(
                "result ignored evaluation_id=%s attempt=%s", message.evaluation_id, message.attempt
            )
            return STALE
        logger.info(
            "evaluation job done evaluation_id=%s status=%s dimensions=%s",
            message.evaluation_id,
            result.status,
            len(result.dimensions),
        )
        return DONE

    async def _fail(
        self,
        message: JobMessage,
        error_code: str,
        error_message: str,
        partial_reasons: list[str] | None = None,
    ) -> str:
        try:
            await self._callback.fail(
                message,
                error_code=error_code,
                error_message=error_message,
                partial_reasons=partial_reasons,
            )
        except CallbackAbandon:
            return STALE
        logger.info(
            "evaluation job reported failure evaluation_id=%s code=%s",
            message.evaluation_id,
            error_code,
        )
        return DONE

    @staticmethod
    def _audio_url(manifest: Manifest) -> str | None:
        for recording in manifest.recordings:
            if recording.stream_type == "audio" or (recording.mime or "").startswith("audio/"):
                return recording.url
        return None
