import asyncio
import json

import pytest

from app.providers.asr_cloud import ASRCloudProvider
from app.providers.base import ASRConfig, ProviderError


class FakeWebSocket:
    def __init__(self, incoming: list[dict]) -> None:
        self._incoming: asyncio.Queue[dict] = asyncio.Queue()
        for item in incoming:
            self._incoming.put_nowait(item)
        self.sent: list[dict | bytes] = []

    async def send(self, message: dict | bytes) -> None:
        self.sent.append(message)

    def __aiter__(self) -> "FakeWebSocket":
        return self

    async def __anext__(self) -> str:
        item = await self._incoming.get()
        if item is None:
            raise StopAsyncIteration
        return json.dumps(item)


class FakeConnect:
    def __init__(self, websocket: FakeWebSocket) -> None:
        self.websocket = websocket

    async def __aenter__(self) -> FakeWebSocket:
        return self.websocket

    async def __aexit__(self, *args: object) -> None:
        return None


def event(name: str, payload: dict | None = None) -> dict:
    return {"header": {"event": name, "task_id": "task-1"}, "payload": payload or {}}


async def audio_stream():
    yield b"chunk-1"
    yield b"chunk-2"


@pytest.mark.asyncio
async def test_stream_sends_run_task_and_yields_partial_then_final():
    websocket = FakeWebSocket(
        [
            event("task-started"),
            event(
                "result-generated",
                {
                    "output": {
                        "sentence": {
                            "text": "同学",
                            "sentence_end": False,
                            "begin_time": 0,
                            "end_time": 120,
                        }
                    }
                },
            ),
            event(
                "result-generated",
                {
                    "output": {
                        "sentence": {
                            "text": "同学们好",
                            "sentence_end": True,
                            "begin_time": 0,
                            "end_time": 480,
                        }
                    }
                },
            ),
            event("task-finished"),
        ]
    )

    provider = ASRCloudProvider(
        api_key="test-key",
        endpoint="wss://asr.example/stream",
        model="paraformer-realtime-v2",
        connect=lambda *args, **kwargs: FakeConnect(websocket),
    )
    chunks = [
        chunk
        async for chunk in provider.stream(audio_stream(), ASRConfig(sample_rate=16000))
    ]

    assert [chunk.text for chunk in chunks] == ["同学", "同学们好"]
    assert [chunk.is_final for chunk in chunks] == [False, True]
    assert chunks[0].begin_ms == 0
    assert chunks[1].end_ms == 480

    run_task = json.loads(websocket.sent[0])
    assert run_task["header"]["action"] == "run-task"
    assert run_task["payload"]["model"] == "paraformer-realtime-v2"
    assert run_task["payload"]["parameters"]["sample_rate"] == 16000
    assert run_task["payload"]["parameters"]["format"] == "pcm"
    assert b"chunk-1" in websocket.sent
    assert b"chunk-2" in websocket.sent
    finish_task = json.loads(websocket.sent[-1])
    assert finish_task["header"]["action"] == "finish-task"


@pytest.mark.asyncio
async def test_stream_raises_provider_error_on_task_failed():
    websocket = FakeWebSocket(
        [
            event("task-started"),
            event("task-failed", {"error_code": "InvalidParameter", "error_message": "bad audio"}),
        ]
    )
    provider = ASRCloudProvider(
        api_key="test-key",
        endpoint="wss://asr.example/stream",
        connect=lambda *args, **kwargs: FakeConnect(websocket),
    )

    with pytest.raises(ProviderError) as raised:
        async for _ in provider.stream(audio_stream(), ASRConfig()):
            pass

    assert raised.value.code == "ASR_TASK_FAILED"
    assert raised.value.retryable is False
