from __future__ import annotations

import json
from dataclasses import asdict

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from app.api.deps import verify_websocket_token
from app.providers.base import ASRConfig, ProviderError

router = APIRouter(prefix="/internal/v1", tags=["asr"])


@router.websocket("/asr/stream")
async def asr_stream(websocket: WebSocket, session_id: str | None = None) -> None:
    container = websocket.scope["app"].state.container
    verify_websocket_token(websocket, container.settings)
    await websocket.accept()

    config = ASRConfig(
        sample_rate=_int_param(websocket, "sample_rate", 16000),
        format=websocket.query_params.get("format", "pcm"),
        language=websocket.query_params.get("language", "zh"),
        model=websocket.query_params.get("model", container.settings.asr_model),
    )

    async def audio():
        while True:
            message = await websocket.receive()
            if message["type"] == "websocket.disconnect":
                return
            chunk = message.get("bytes")
            if chunk:
                yield chunk
                continue
            text = message.get("text")
            if not text:
                continue
            try:
                command = json.loads(text)
            except json.JSONDecodeError:
                continue
            if command.get("type") == "finish":
                return

    try:
        async for chunk in container.asr.stream(audio(), config):
            await websocket.send_json({"type": "chunk", **asdict(chunk)})
        await websocket.send_json({"type": "done"})
        await websocket.close()
    except WebSocketDisconnect:
        return
    except ProviderError as exc:
        await websocket.send_json(
            {"type": "error", "code": exc.code, "message": exc.message}
        )
        await websocket.close(code=1011)


def _int_param(websocket: WebSocket, name: str, default: int) -> int:
    raw = websocket.query_params.get(name)
    if raw is None:
        return default
    try:
        return int(raw)
    except ValueError:
        return default
