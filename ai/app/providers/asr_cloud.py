from __future__ import annotations

import asyncio
import json
import uuid
from collections.abc import AsyncIterator, Callable
from typing import Any

import websockets

from app.providers.base import ASRChunk, ASRConfig, ProviderError


class ASRCloudProvider:
    """Aliyun Paraformer real-time WebSocket adapter.

    The provider only translates the vendor protocol. Session lifecycle,
    persistence and retry policy remain in Java / the capability layer.
    """

    def __init__(
        self,
        *,
        api_key: str,
        endpoint: str = "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
        model: str = "paraformer-realtime-v2",
        workspace_id: str | None = None,
        connect: Callable[..., Any] | None = None,
    ) -> None:
        if not api_key:
            raise ValueError("ASR api_key is required")
        self._api_key = api_key
        self._endpoint = endpoint
        self._model = model
        self._workspace_id = workspace_id
        self._connect = connect or websockets.connect

    async def stream(
        self,
        audio: AsyncIterator[bytes],
        config: ASRConfig,
    ) -> AsyncIterator[ASRChunk]:
        task_id = str(uuid.uuid4())
        headers = {"Authorization": f"Bearer {self._api_key}"}
        if self._workspace_id:
            headers["X-DashScope-WorkSpace"] = self._workspace_id

        async with self._connect(self._endpoint, additional_headers=headers) as websocket:
            await websocket.send(json.dumps(self._run_task(task_id, config)))

            await self._wait_for_started(websocket)
            sender = asyncio.create_task(self._send_audio(websocket, audio, task_id))
            try:
                async for raw in websocket:
                    if not isinstance(raw, str):
                        continue
                    message = json.loads(raw)
                    event = self._event_name(message)
                    if event == "result-generated":
                        chunk = self._parse_sentence(message, config)
                        if chunk is not None:
                            yield chunk
                    elif event == "task-finished":
                        break
                    elif event == "task-failed":
                        raise self._task_failed(message)
            finally:
                await sender

    async def _wait_for_started(self, websocket: Any) -> None:
        async for raw in websocket:
            if not isinstance(raw, str):
                continue
            message = json.loads(raw)
            event = self._event_name(message)
            if event == "task-started":
                return
            if event == "task-failed":
                raise self._task_failed(message)
        raise ProviderError(
            "ASR_CONNECTION_CLOSED",
            "ASR connection closed before task-started",
            retryable=True,
        )

    async def _send_audio(
        self,
        websocket: Any,
        audio: AsyncIterator[bytes],
        task_id: str,
    ) -> None:
        async for chunk in audio:
            if chunk:
                await websocket.send(chunk)
        await websocket.send(
            json.dumps(
                {
                    "header": {
                        "action": "finish-task",
                        "task_id": task_id,
                        "streaming": "duplex",
                    },
                    "payload": {"input": {}},
                }
            )
        )

    def _run_task(self, task_id: str, config: ASRConfig) -> dict[str, Any]:
        return {
            "header": {
                "action": "run-task",
                "task_id": task_id,
                "streaming": "duplex",
            },
            "payload": {
                "task_group": "audio",
                "task": "asr",
                "function": "recognition",
                "model": config.model or self._model,
                "parameters": {
                    "format": config.format,
                    "sample_rate": config.sample_rate,
                    "language_hints": [config.language],
                    "max_sentence_silence": config.max_sentence_silence_ms,
                },
                "input": {},
            },
        }

    @staticmethod
    def _event_name(message: dict[str, Any]) -> str:
        return str(message.get("header", {}).get("event", ""))

    @staticmethod
    def _parse_sentence(message: dict[str, Any], config: ASRConfig) -> ASRChunk | None:
        sentence = message.get("payload", {}).get("output", {}).get("sentence", {})
        text = str(sentence.get("text", "")).strip()
        if not text:
            return None
        return ASRChunk(
            text=text,
            is_final=bool(sentence.get("sentence_end", False)),
            begin_ms=_optional_int(sentence.get("begin_time")),
            end_ms=_optional_int(sentence.get("end_time")),
            confidence=_optional_float(sentence.get("confidence")),
        )

    @staticmethod
    def _task_failed(message: dict[str, Any]) -> ProviderError:
        header = message.get("header", {})
        payload = message.get("payload", {})
        vendor_code = header.get("error_code") or payload.get("error_code")
        detail = (
            header.get("error_message")
            or payload.get("error_message")
            or "Paraformer task failed"
        )
        message_text = f"{vendor_code}: {detail}" if vendor_code else str(detail)
        return ProviderError("ASR_TASK_FAILED", message_text, retryable=False)


def _optional_int(value: Any) -> int | None:
    return int(value) if value is not None else None


def _optional_float(value: Any) -> float | None:
    return float(value) if value is not None else None
