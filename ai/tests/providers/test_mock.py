import json

import pytest

from app.providers.base import ASRConfig
from app.providers.mock import MockLLMProvider, MockProvider


@pytest.mark.asyncio
async def test_mock_llm_returns_question_json():
    provider = MockProvider()
    result = await provider.chat(
        [{"role": "system", "content": "生成虚拟学生提问"}, {"role": "user", "content": "课堂"}],
        "deepseek-flash",
        5.0,
    )
    payload = json.loads(result.text)
    assert payload["text"]
    assert payload["context_sufficient"] is True
    assert result.model_version == "mock"


@pytest.mark.asyncio
async def test_mock_llm_chat_accepts_thinking_parameter():
    provider = MockProvider()
    result = await provider.chat(
        [{"role": "system", "content": "生成虚拟学生提问"}, {"role": "user", "content": "课堂"}],
        "deepseek-flash",
        5.0,
        thinking=False,
    )
    assert json.loads(result.text)["text"]


@pytest.mark.asyncio
async def test_mock_llm_stream_yields_one_final_chunk():
    provider = MockLLMProvider()
    chunks = [
        chunk
        async for chunk in provider.stream(
            [{"role": "user", "content": "hi"}],
            "deepseek-flash",
            5.0,
            thinking=False,
        )
    ]
    assert len(chunks) == 1
    assert chunks[0].is_final is True
    assert chunks[0].model_version == "mock"


@pytest.mark.asyncio
async def test_mock_ocr_and_asr_are_deterministic():
    provider = MockProvider()
    ocr = await provider.recognize(image_url="https://example.com/a.png")
    assert ocr.text == "mock ocr text"
    assert ocr.model_version == "mock"

    async def audio():
        yield b"chunk"

    chunks = [chunk async for chunk in provider.stream(audio(), ASRConfig())]
    assert [chunk.text for chunk in chunks] == ["mock partial", "mock final"]
    assert chunks[-1].is_final is True
