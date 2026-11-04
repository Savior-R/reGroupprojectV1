from __future__ import annotations

from fastapi import HTTPException, Request, WebSocket, WebSocketException

from app.config import Settings


def _expected_token(settings: Settings) -> str:
    return settings.service_token


async def verify_service_token(request: Request) -> None:
    settings = request.app.state.container.settings
    expected = _expected_token(settings)
    if not expected:
        return

    token = request.headers.get("x-service-token")
    if not token:
        raise HTTPException(status_code=401, detail="missing service token")
    if token != expected:
        raise HTTPException(status_code=403, detail="invalid service token")


def verify_websocket_token(websocket: WebSocket, settings: Settings) -> None:
    expected = _expected_token(settings)
    if not expected:
        return

    token = websocket.headers.get("x-service-token")
    if not token or token != expected:
        raise WebSocketException(code=1008, reason="invalid service token")
