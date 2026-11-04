from __future__ import annotations

import asyncio
import os
import wave
from collections.abc import AsyncIterator

import pytest

from app.config import Settings
from app.container import AppContainer, build_container
from app.providers.base import ASRConfig
from app.schemas.classroom import (
    ClassroomAnswerRequest,
    ClassroomClassifyRequest,
    ClassroomQuestionRequest,
)
from app.schemas.ocr import OCRRequest
from tests.fixtures import (
    asr_sample_path,
    load_classroom_requests,
    load_expected,
    ocr_image_base64,
)


@pytest.fixture(scope="module")
def live_container() -> AppContainer:
    if os.getenv("AI_LIVE") != "1":
        pytest.skip("live cloud smoke; set AI_LIVE=1 to run")

    settings = Settings()
    if settings.ai_mock:
        pytest.skip("AI_MOCK=true; set AI_MOCK=false for live smoke")

    missing = [
        name
        for name, value in (
            ("DEEPSEEK_API_KEY", settings.deepseek_api_key),
            ("OCR_API_KEY", settings.ocr_api_key),
            ("ASR_API_KEY", settings.asr_api_key),
        )
        if not value
    ]
    if missing:
        pytest.skip("missing live provider keys: " + ", ".join(missing))

    return build_container(settings)


async def test_live_classroom_requests(live_container: AppContainer) -> None:
    requests = load_classroom_requests()

    question = await live_container.classroom.question(
        ClassroomQuestionRequest(**requests["question"])
    )
    assert question.text.strip()
    assert question.model_version
    assert question.prompt_version
    assert 0.0 <= question.confidence <= 1.0

    answer = await live_container.classroom.answer(
        ClassroomAnswerRequest(**requests["answer"])
    )
    assert answer.text.strip()
    assert answer.quality in {"correct", "partial", "incorrect"}

    classify = await live_container.classroom.classify(
        ClassroomClassifyRequest(**requests["classify"])
    )
    assert classify.label in {"question", "not_question"}


async def test_live_ocr_fixture(live_container: AppContainer) -> None:
    expected = load_expected()

    response = await live_container.ocr.recognize(
        OCRRequest(image_base64=ocr_image_base64())
    )

    assert expected["ocr_text_contains"].lower() in response.text.lower()
    assert response.model_version


async def test_live_asr_fixture(live_container: AppContainer) -> None:
    expected = load_expected()
    path = asr_sample_path()

    async def audio() -> AsyncIterator[bytes]:
        with wave.open(str(path), "rb") as wav:
            frames_per_chunk = int(wav.getframerate() * 0.1)
            while True:
                data = wav.readframes(frames_per_chunk)
                if not data:
                    break
                yield data
                await asyncio.sleep(0.02)

    async def collect_final_text() -> str:
        finals: list[str] = []
        config = ASRConfig(sample_rate=16000, format="pcm", language="zh")
        async for chunk in live_container.asr.stream(audio(), config):
            if chunk.is_final:
                finals.append(chunk.text)
        return "".join(finals)

    final_text = await asyncio.wait_for(collect_final_text(), timeout=60)
    assert final_text
    assert expected["asr_final_text_contains"] in final_text
