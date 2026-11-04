import json

import httpx
import pytest

from app.providers.base import ProviderError
from app.providers.deepseek import DeepSeekProvider


@pytest.mark.asyncio
async def test_chat_posts_openai_compatible_request_and_parses_response():
    seen: dict[str, object] = {}

    async def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["authorization"] = request.headers.get("authorization")
        seen["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "model": "deepseek-flash",
                "choices": [{"message": {"content": '{"text":"hello"}'}}],
                "usage": {"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18},
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        provider = DeepSeekProvider(
            api_key="test-key",
            base_url="https://api.deepseek.com",
            model="deepseek-flash",
            client=client,
        )
        result = await provider.chat(
            messages=[{"role": "user", "content": "hello"}],
            model="deepseek-flash",
            timeout_s=5.0,
            response_format={"type": "json_object"},
        )

    assert seen["url"] == "https://api.deepseek.com/chat/completions"
    assert seen["authorization"] == "Bearer test-key"
    body = seen["body"]
    assert body["model"] == "deepseek-flash"
    assert body["messages"] == [{"role": "user", "content": "hello"}]
    assert body["response_format"] == {"type": "json_object"}
    assert body["stream"] is False
    assert "thinking" not in body

    assert result.text == '{"text":"hello"}'
    assert result.model_version == "deepseek-flash"
    assert result.usage is not None
    assert result.usage.total_tokens == 18
    assert result.latency_ms is not None


@pytest.mark.asyncio
async def test_chat_maps_rate_limit_to_retryable_provider_error():
    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(429, json={"error": {"message": "slow down"}})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        provider = DeepSeekProvider(
            api_key="test-key",
            base_url="https://api.deepseek.com",
            model="deepseek-flash",
            client=client,
        )
        with pytest.raises(ProviderError) as raised:
            await provider.chat(
                messages=[{"role": "user", "content": "hello"}],
                model="deepseek-flash",
                timeout_s=5.0,
            )

    assert raised.value.code == "RATE_LIMITED"
    assert raised.value.retryable is True
    assert raised.value.status_code == 429


@pytest.mark.asyncio
async def test_stream_parses_sse_deltas():
    body = (
        'data: {"model":"deepseek-flash","choices":[{"delta":{"content":"你"}}]}\n\n'
        'data: {"model":"deepseek-flash","choices":[{"delta":{"content":"好"}}]}\n\n'
        "data: [DONE]\n\n"
    )

    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            headers={"content-type": "text/event-stream"},
            content=body.encode(),
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        provider = DeepSeekProvider(
            api_key="test-key",
            base_url="https://api.deepseek.com",
            model="deepseek-flash",
            client=client,
        )
        chunks = [
            chunk
            async for chunk in provider.stream(
                [{"role": "user", "content": "hello"}],
                "deepseek-flash",
                5.0,
            )
        ]

    assert [chunk.text for chunk in chunks] == ["你", "好"]
    assert all(chunk.model_version == "deepseek-flash" for chunk in chunks)


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("thinking", "expected"),
    [(False, "disabled"), (True, "enabled")],
)
async def test_chat_sends_thinking_toggle_when_requested(thinking, expected):
    seen: dict[str, object] = {}

    async def handler(request: httpx.Request) -> httpx.Response:
        seen["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "model": "deepseek-flash",
                "choices": [{"message": {"content": "{}"}}],
                "usage": {},
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        provider = DeepSeekProvider(
            api_key="test-key",
            base_url="https://api.deepseek.com",
            model="deepseek-flash",
            client=client,
        )
        await provider.chat(
            messages=[{"role": "user", "content": "hello"}],
            model="deepseek-flash",
            timeout_s=5.0,
            thinking=thinking,
        )

    body = seen["body"]
    assert body["thinking"] == {"type": expected}
