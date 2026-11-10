"""Read-only client for the Java internal evaluation input API (06 §9.3)."""

from __future__ import annotations

from typing import Any

import httpx

from app.schemas.evaluation import EventItem, FrameItem, Manifest, TextItem
from app.worker.config import WorkerSettings

MANIFEST_PATH = "/internal/evaluations/{evaluation_id}/input/manifest"
TEXTS_PATH = "/internal/evaluations/{evaluation_id}/input/texts"
FRAMES_PATH = "/internal/evaluations/{evaluation_id}/input/frames"
EVENTS_PATH = "/internal/evaluations/{evaluation_id}/input/events"


class InputClientError(Exception):
    """A manifest or input page could not be read; the job may be retried as a whole."""

    def __init__(self, message: str, *, status_code: int | None = None) -> None:
        super().__init__(message)
        self.status_code = status_code


class EvaluationInputClient:
    def __init__(
        self,
        *,
        base_url: str,
        service_token: str,
        timeout_s: float = 30.0,
        http: httpx.AsyncClient | None = None,
        text_page_size: int = 500,
        frame_page_size: int = 200,
        event_page_size: int = 500,
        max_texts: int = 20_000,
        max_frames: int = 10_000,
        max_events: int = 20_000,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._token = service_token
        self._timeout = timeout_s
        self._http = http
        self._owns_http = http is None
        self._text_page_size = text_page_size
        self._frame_page_size = frame_page_size
        self._event_page_size = event_page_size
        self._max_texts = max_texts
        self._max_frames = max_frames
        self._max_events = max_events

    @classmethod
    def from_settings(
        cls, settings: WorkerSettings, http: httpx.AsyncClient | None = None
    ) -> "EvaluationInputClient":
        return cls(
            base_url=settings.java_base_url,
            service_token=settings.service_token,
            http=http,
            text_page_size=settings.text_page_size,
            frame_page_size=settings.frame_page_size,
            event_page_size=settings.event_page_size,
            max_texts=settings.max_texts,
            max_frames=settings.max_frames,
            max_events=settings.max_events,
        )

    async def aclose(self) -> None:
        if self._owns_http and self._http is not None:
            await self._http.aclose()
            self._http = None

    async def manifest(self, evaluation_id: str) -> Manifest:
        payload = await self._get(MANIFEST_PATH.format(evaluation_id=evaluation_id))
        return Manifest.model_validate(payload)

    async def texts(self, evaluation_id: str) -> list[TextItem]:
        items: list[TextItem] = []
        after_id: str | None = None
        while len(items) < self._max_texts:
            params: dict[str, Any] = {"limit": self._text_page_size}
            if after_id:
                params["after_id"] = after_id
            page = await self._get(
                TEXTS_PATH.format(evaluation_id=evaluation_id), params=params
            )
            rows = page.get("items") or []
            if not rows:
                break
            items.extend(TextItem.model_validate(row) for row in rows)
            next_cursor = rows[-1].get("id")
            if not next_cursor or next_cursor == after_id or len(rows) < self._text_page_size:
                break
            after_id = next_cursor
        return items[: self._max_texts]

    async def frames(self, evaluation_id: str) -> list[FrameItem]:
        items: list[FrameItem] = []
        after_ts: int | None = None
        after_id: str | None = None
        while len(items) < self._max_frames:
            params: dict[str, Any] = {"limit": self._frame_page_size}
            if after_ts is not None:
                params["after_ts"] = after_ts
            if after_id:
                params["after_id"] = after_id
            page = await self._get(
                FRAMES_PATH.format(evaluation_id=evaluation_id), params=params
            )
            rows = page.get("items") or []
            if not rows:
                break
            items.extend(FrameItem.model_validate(row) for row in rows)
            next_ts = rows[-1].get("timestamp_ms")
            next_id = rows[-1].get("id")
            if next_ts is None or not next_id:
                break
            if next_ts == after_ts and next_id == after_id:
                break
            after_ts, after_id = next_ts, next_id
            if len(rows) < self._frame_page_size:
                break
        return items[: self._max_frames]

    async def events(self, evaluation_id: str) -> list[EventItem]:
        items: list[EventItem] = []
        after_seq: int | None = None
        while len(items) < self._max_events:
            params: dict[str, Any] = {"limit": self._event_page_size}
            if after_seq is not None:
                params["after_seq"] = after_seq
            page = await self._get(
                EVENTS_PATH.format(evaluation_id=evaluation_id), params=params
            )
            rows = page.get("items") or []
            if not rows:
                break
            items.extend(EventItem.model_validate(row) for row in rows)
            next_seq = rows[-1].get("seq")
            if next_seq is None or next_seq == after_seq:
                break
            after_seq = next_seq
            if len(rows) < self._event_page_size:
                break
        return items[: self._max_events]

    async def _get(
        self, path: str, params: dict[str, Any] | None = None
    ) -> dict[str, Any]:
        client = self._ensure_http()
        try:
            response = await client.get(
                self._base_url + path,
                params=params,
                headers=self._headers(),
                timeout=self._timeout,
            )
        except httpx.HTTPError as exc:
            raise InputClientError(f"input request failed: {type(exc).__name__}") from exc
        if response.status_code != 200:
            raise InputClientError(
                f"input request returned {response.status_code}",
                status_code=response.status_code,
            )
        try:
            payload = response.json()
        except ValueError as exc:
            raise InputClientError("input response is not JSON") from exc
        if not isinstance(payload, dict):
            raise InputClientError("input response has an unexpected shape")
        return payload

    def _headers(self) -> dict[str, str]:
        if not self._token:
            return {}
        return {"X-Service-Token": self._token}

    def _ensure_http(self) -> httpx.AsyncClient:
        if self._http is None:
            self._http = httpx.AsyncClient()
            self._owns_http = True
        return self._http
