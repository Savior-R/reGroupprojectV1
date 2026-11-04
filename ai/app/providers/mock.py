from __future__ import annotations

import json
from collections.abc import AsyncIterator
from typing import Any

from app.providers.base import ASRChunk, ASRConfig, LLMChunk, LLMResponse, OCRResult, Usage


class MockProvider:
    """Deterministic provider for CI, local development and demos."""

    async def chat(
        self,
        messages: list[dict[str, str]],
        model: str,
        timeout_s: float,
        *,
        response_format: dict[str, Any] | None = None,
        temperature: float | None = None,
        thinking: bool | None = None,
    ) -> LLMResponse:
        system = messages[0].get("content", "") if messages else ""
        if "判断教师话语" in system:
            payload = {"label": "question", "confidence": 0.9}
        elif "回答" in system:
            payload = {"text": "这是模拟的学生回答。", "quality": "partial", "confidence": 0.8}
        else:
            payload = {
                "text": "老师，为什么要这样计算？",
                "context_sufficient": True,
                "input_question_complete": True,
                "output_complete": True,
                "missing": [],
                "confidence": 0.8,
            }
        return LLMResponse(
            text=json.dumps(payload, ensure_ascii=False),
            model_version="mock",
            usage=Usage(),
            latency_ms=0,
        )

    async def recognize(
        self,
        image_url: str | None = None,
        image_base64: str | None = None,
        model: str | None = None,
        timeout_s: float = 15.0,
    ) -> OCRResult:
        return OCRResult(text="mock ocr text", model_version="mock", latency_ms=0)

    async def stream(
        self,
        audio: AsyncIterator[bytes],
        config: ASRConfig,
    ) -> AsyncIterator[ASRChunk]:
        async for _ in audio:
            pass
        yield ASRChunk(text="mock partial", is_final=False, begin_ms=0, end_ms=100)
        yield ASRChunk(text="mock final", is_final=True, begin_ms=0, end_ms=300)


class MockLLMProvider(MockProvider):
    """LLM mock separated because LLM and ASR both expose ``stream``."""

    async def stream(
        self,
        messages: list[dict[str, str]],
        model: str,
        timeout_s: float,
        *,
        temperature: float | None = None,
        thinking: bool | None = None,
    ) -> AsyncIterator[LLMChunk]:
        response = await self.chat(
            messages,
            model,
            timeout_s,
            temperature=temperature,
            thinking=thinking,
        )
        yield LLMChunk(text=response.text, model_version="mock", is_final=True)
