import json

import httpx
import pytest

from app.providers.ocr_cloud import OCRCloudProvider


@pytest.mark.asyncio
async def test_recognize_posts_image_url_and_extracts_text():
    seen: dict[str, object] = {}

    async def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["authorization"] = request.headers.get("authorization")
        seen["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "model": "qwen3.5-ocr",
                "choices": [{"message": {"content": "同学们看这道题"}}],
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        provider = OCRCloudProvider(
            api_key="test-key",
            endpoint="https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            model="qwen3.5-ocr",
            client=client,
        )
        result = await provider.recognize(image_url="https://example.com/frame.png")

    assert seen["url"] == "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
    assert seen["authorization"] == "Bearer test-key"
    body = seen["body"]
    assert body["model"] == "qwen3.5-ocr"
    content = body["messages"][1]["content"]
    assert content[0]["image_url"]["url"] == "https://example.com/frame.png"

    assert result.text == "同学们看这道题"
    assert result.model_version == "qwen3.5-ocr"
    assert result.boxes == []
    assert result.latency_ms is not None


@pytest.mark.asyncio
async def test_recognize_accepts_base64_image():
    seen: dict[str, object] = {}

    async def handler(request: httpx.Request) -> httpx.Response:
        seen["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={"model": "qwen3.5-ocr", "choices": [{"message": {"content": "ok"}}]},
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        provider = OCRCloudProvider(api_key="test-key", client=client)
        await provider.recognize(image_base64="aGVsbG8=")

    content = seen["body"]["messages"][1]["content"]
    assert content[0]["image_url"]["url"] == "data:image/jpeg;base64,aGVsbG8="


@pytest.mark.asyncio
async def test_recognize_requires_an_image():
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(200))) as client:
        provider = OCRCloudProvider(api_key="test-key", client=client)
        with pytest.raises(ValueError, match="image_url or image_base64"):
            await provider.recognize()
