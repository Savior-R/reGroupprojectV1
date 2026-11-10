"""Start / result / fail callbacks to the Java internal API (06 §5.3)."""

from __future__ import annotations

from typing import Any

import httpx

from app.schemas.evaluation import EvaluationResult, JobMessage
from app.worker.config import WorkerSettings

START_PATH = "/internal/evaluations/{evaluation_id}/start"
RESULT_PATH = "/internal/evaluations/{evaluation_id}/result"
FAIL_PATH = "/internal/evaluations/{evaluation_id}/fail"


class CallbackError(Exception):
    """The callback did not reach Java; the message must not be acknowledged."""


class CallbackAbandon(Exception):
    """The attempt is stale or the evaluation is terminal; acknowledge and stop."""


class ResultCallback:
    def __init__(
        self,
        *,
        base_url: str,
        service_token: str,
        timeout_s: float = 15.0,
        http: httpx.AsyncClient | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._token = service_token
        self._timeout = timeout_s
        self._http = http
        self._owns_http = http is None

    @classmethod
    def from_settings(
        cls, settings: WorkerSettings, http: httpx.AsyncClient | None = None
    ) -> "ResultCallback":
        return cls(
            base_url=settings.java_base_url,
            service_token=settings.service_token,
            http=http,
        )

    async def aclose(self) -> None:
        if self._owns_http and self._http is not None:
            await self._http.aclose()
            self._http = None

    async def start(self, message: JobMessage) -> None:
        await self._post(
            START_PATH.format(evaluation_id=message.evaluation_id),
            message,
            {"job_id": message.job_id, "attempt": message.attempt},
        )

    async def result(self, message: JobMessage, result: EvaluationResult) -> None:
        payload: dict[str, Any] = {
            "job_id": message.job_id,
            "attempt": message.attempt,
            **result.model_dump(mode="json"),
        }
        await self._post(
            RESULT_PATH.format(evaluation_id=message.evaluation_id), message, payload
        )

    async def fail(
        self,
        message: JobMessage,
        *,
        error_code: str,
        error_message: str,
        partial_reasons: list[str] | None = None,
    ) -> None:
        payload = {
            "job_id": message.job_id,
            "attempt": message.attempt,
            "error_code": error_code,
            "error_message": error_message[:500],
            "partial_reasons": partial_reasons or [],
        }
        await self._post(
            FAIL_PATH.format(evaluation_id=message.evaluation_id), message, payload
        )

    async def _post(
        self, path: str, message: JobMessage, payload: dict[str, Any]
    ) -> None:
        client = self._ensure_http()
        headers = {
            "Idempotency-Key": f"{message.job_id}:{message.attempt}",
        }
        if self._token:
            headers["X-Service-Token"] = self._token
        try:
            response = await client.post(
                self._base_url + path,
                json=payload,
                headers=headers,
                timeout=self._timeout,
            )
        except httpx.HTTPError as exc:
            raise CallbackError(f"callback failed: {type(exc).__name__}") from exc

        if response.status_code == 409:
            raise CallbackAbandon("stale attempt or terminal evaluation")
        if response.status_code >= 400:
            raise CallbackError(f"callback returned {response.status_code}")

    def _ensure_http(self) -> httpx.AsyncClient:
        if self._http is None:
            self._http = httpx.AsyncClient()
            self._owns_http = True
        return self._http
